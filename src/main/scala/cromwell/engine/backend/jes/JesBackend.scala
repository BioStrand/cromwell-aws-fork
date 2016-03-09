package cromwell.engine.backend.jes

import java.math.BigInteger
import java.net.SocketTimeoutException
import java.nio.file.{Path, Paths}

import akka.actor.ActorSystem
import com.google.api.client.http.HttpResponseException
import com.google.api.client.util.ExponentialBackOff.Builder
import com.google.api.services.genomics.model.{LocalCopy, PipelineParameter}
import com.typesafe.scalalogging.LazyLogging
import cromwell.engine.ExecutionIndex.IndexEnhancedInt
import cromwell.engine.ExecutionStatus._
import cromwell.engine.backend._
import cromwell.engine.backend.jes.JesBackend._
import cromwell.engine.backend.jes.Run.RunStatus
import cromwell.engine.backend.jes.authentication._
import cromwell.engine.db.DataAccess.{ExecutionKeyToJobKey, globalDataAccess}
import cromwell.engine.db.ExecutionDatabaseKey
import cromwell.engine.db.slick.{Execution, ExecutionInfo}
import cromwell.engine.io.IoInterface
import cromwell.engine.io.gcs._
import cromwell.engine.workflow.{BackendCallKey, WorkflowOptions}
import cromwell.engine.{AbortRegistrationFunction, CallOutput, CallOutputs, HostInputs, _}
import cromwell.logging.WorkflowLogger
import cromwell.util.{AggregatedException, SimpleExponentialBackoff, TryUtil}
import wdl4s.AstTools.EnhancedAstNode
import wdl4s.command.ParameterCommandPart
import wdl4s.expression.NoFunctions
import wdl4s.values._
import wdl4s.{Call, CallInputs, UnsatisfiedInputsException, _}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object JesBackend {
  val ExecParamName = "exec"
  val MonitoringParamName = "monitoring"
  val ExtraConfigParamName = "__extra_config_gcs_path"
  val JesExecScript = "exec.sh"
  val JesMonitoringScript = "monitoring.sh"
  val JesMonitoringLogFile = "monitoring.log"

  // Workflow options keys
  val GcsRootOptionKey = "jes_gcs_root"
  val MonitoringScriptOptionKey = "monitoring_script"
  val GoogleProjectOptionKey = "google_project"
  val AuthFilePathOptionKey = "auth_bucket"
  val WriteToCacheOptionKey = "write_to_cache"
  val ReadFromCacheOptionKey = "read_from_cache"
  val OptionKeys = Set(
    GoogleCloudStorage.RefreshTokenOptionKey, GcsRootOptionKey, MonitoringScriptOptionKey, GoogleProjectOptionKey,
    AuthFilePathOptionKey, WriteToCacheOptionKey, ReadFromCacheOptionKey
  ) ++ WorkflowDescriptor.OptionKeys

  def authGcsCredentialsPath(gcsPath: String): JesInput = JesLiteralInput(ExtraConfigParamName, gcsPath)

  // Only executions in Running state with a recorded operation ID are resumable.
  private val IsResumable: (Execution, Seq[ExecutionInfo]) => Boolean = (e: Execution, eis: Seq[ExecutionInfo]) => {
    e.status.toExecutionStatus == ExecutionStatus.Running &&
      eis.exists(ei => ei.key == JesBackend.InfoKeys.JesRunId && ei.value.isDefined)
  }

  /** In this context transient means that a job is Running but doesn't have a Jes Run ID yet.
    * This could happen if a call starts Running but Cromwell is stopped before the request to JES has been made
    * (or the ID persisted to the DB) .*/
  private val IsTransient: (Execution, Seq[ExecutionInfo]) => Boolean = (e: Execution, eis: Seq[ExecutionInfo]) => {
    e.status.toExecutionStatus == ExecutionStatus.Running &&
      eis.exists(ei => ei.key == JesBackend.InfoKeys.JesRunId && ei.value.isEmpty)
  }

  case class JesJobKey(jesRunId: String) extends JobKey

  private val BuildJobKey: (Execution, Seq[ExecutionInfo]) => JobKey = (e: Execution, eis: Seq[ExecutionInfo]) => {
    JesJobKey(eis.find(_.key == JesBackend.InfoKeys.JesRunId).get.value.get)
  }

  // Decoration around WorkflowDescriptor to generate bucket names and the like
  implicit class JesWorkflowDescriptor(val descriptor: WorkflowDescriptor)
    extends JesBackend(CromwellBackend.backend().actorSystem) {
    def callDir(key: BackendCallKey) = key.callRootPathWithBaseRoot(descriptor, rootPath(descriptor.workflowOptions))
  }

  /**
   * Takes a path in GCS and comes up with a local path which is unique for the given GCS path
   *
   * @param gcsPath The input path
   * @return A path which is unique per input path
   */
  def localFilePathFromCloudStoragePath(gcsPath: GcsPath): Path = {
    Paths.get(gcsPath.bucket).resolve(gcsPath.objectName)
  }

  /**
   * Takes a single WdlValue and maps google cloud storage (GCS) paths into an appropriate local file path.
   * If the input is not a WdlFile, or the WdlFile is not a GCS path, the mapping is a noop.
   *
   * @param wdlValue the value of the input
   * @return a new FQN to WdlValue pair, with WdlFile paths modified if appropriate.
   */
  def gcsPathToLocal(wdlValue: WdlValue): WdlValue = {
    wdlValue match {
      case wdlFile: WdlFile =>
        GcsPath.parse(wdlFile.value) match {
          case Success(gcsPath) => WdlFile(localFilePathFromCloudStoragePath(gcsPath).toString, wdlFile.isGlob)
          case Failure(e) => wdlValue
        }
      case wdlArray: WdlArray => wdlArray map gcsPathToLocal
      case wdlMap: WdlMap => wdlMap map { case (k, v) => gcsPathToLocal(k) -> gcsPathToLocal(v) }
      case _ => wdlValue
    }
  }

  def isFatalJesException(t: Throwable): Boolean = t match {
    case e: HttpResponseException if e.getStatusCode == 403 => true
    case _ => false
  }

  def isTransientJesException(t: Throwable): Boolean = t match {
      // Quota exceeded
    case e: HttpResponseException if e.getStatusCode == 429 => true
    case _ => false
  }

  protected def withRetry[T](f: Option[T] => T, logger: WorkflowLogger, failureMessage: String) = {
    TryUtil.retryBlock(
      fn = f,
      retryLimit = Option(10),
      backoff = SimpleExponentialBackoff(5 seconds, 10 seconds, 1.1D),
      logger = logger,
      failMessage = Option(failureMessage),
      isFatal = isFatalJesException,
      isTransient = isTransientJesException
    )
  }

  sealed trait JesParameter {
    def name: String
    def toGooglePipelineParameter: PipelineParameter
    def toGoogleRunParameter: String
  }

  sealed trait JesInput extends JesParameter

  final case class JesFileInput(name: String, gcs: String, local: Path, mount: JesAttachedDisk) extends JesInput {
    def toGooglePipelineParameter = {
      new PipelineParameter().setName(name).setLocalCopy(
        new LocalCopy().setDisk(mount.name).setPath(local.toString)
      )
    }
    val toGoogleRunParameter: String = gcs
    def containerPath: Path = mount.mountPoint.resolve(local)
  }

  final case class JesLiteralInput(name: String, value: String) extends JesInput {
    def toGooglePipelineParameter = new PipelineParameter().setName(name)
    val toGoogleRunParameter: String = value
  }

  final case class JesFileOutput(name: String, gcs: String, local: Path, mount: JesAttachedDisk) extends JesParameter {
    def toGooglePipelineParameter = {
      new PipelineParameter().setName(name).setLocalCopy(
        new LocalCopy().setDisk(mount.name).setPath(local.toString)
      )
    }
    val toGoogleRunParameter: String = gcs
  }

  implicit class EnhancedExecution(val execution: Execution) extends AnyVal {
    import cromwell.engine.ExecutionIndex._
    def toKey: ExecutionDatabaseKey = ExecutionDatabaseKey(execution.callFqn, execution.index.toIndex, execution.attempt)
    def isScatter: Boolean = execution.callFqn.contains(Scatter.FQNIdentifier)
    def executionStatus: ExecutionStatus = ExecutionStatus.withName(execution.status)
  }

  def callGcsPath(descriptor: WorkflowDescriptor, callKey: BackendCallKey): String = {
    val shardPath = callKey.index map { i => s"/shard-$i" } getOrElse ""
    val workflowPath = workflowGcsPath(descriptor)
    s"$workflowPath/call-${callKey.scope.unqualifiedName}$shardPath"
  }

  def workflowGcsPath(descriptor: WorkflowDescriptor): String = {
    val bucket = descriptor.workflowOptions.getOrElse(GcsRootOptionKey, ProductionJesConfiguration.jesConf.executionBucket).stripSuffix("/")
    s"$bucket/${descriptor.namespace.workflow.unqualifiedName}/${descriptor.id}"
  }

  object InfoKeys {
    val JesRunId = "JES_RUN_ID"
    val JesStatus = "JES_STATUS"
  }

  def jesLogBasename(key: BackendCallKey) = {
    val index = key.index.map(s => s"-$s").getOrElse("")
    s"${key.scope.unqualifiedName}$index"
  }

  def jesLogStdoutFilename(key: BackendCallKey) = s"${jesLogBasename(key)}-stdout.log"
  def jesLogStderrFilename(key: BackendCallKey) = s"${jesLogBasename(key)}-stderr.log"
  def jesLogFilename(key: BackendCallKey) = s"${jesLogBasename(key)}.log"
  def jesReturnCodeFilename(key: BackendCallKey) = s"${jesLogBasename(key)}-rc.txt"
}

/**
 * Representing a running JES execution, instances of this class are never Done and it is never okay to
 * ask them for results.
 */
case class JesPendingExecutionHandle(backendCall: JesBackendCall,
                                     jesOutputs: Seq[JesFileOutput],
                                     run: Run,
                                     previousStatus: Option[RunStatus]) extends ExecutionHandle {
  override val isDone = false
  override val result = NonRetryableExecution(new IllegalStateException("JesPendingExecutionHandle cannot yield a result"))
}

case class JesBackend(actorSystem: ActorSystem)
  extends Backend
  with LazyLogging
  with ProductionJesAuthentication
  with ProductionJesConfiguration {
  type BackendCall = JesBackendCall

  /**
    * Exponential Backoff Builder to be used when polling for call status.
    */
  final private lazy val pollBackoffBuilder = new Builder()
    .setInitialIntervalMillis(20.seconds.toMillis.toInt)
    .setMaxElapsedTimeMillis(Int.MaxValue)
    .setMaxIntervalMillis(10.minutes.toMillis.toInt)
    .setMultiplier(1.1D)

  override def pollBackoff = pollBackoffBuilder.build()

  override def rootPath(options: WorkflowOptions) = options.getOrElse(GcsRootOptionKey, ProductionJesConfiguration.jesConf.executionBucket).stripSuffix("/")

  // FIXME: Add proper validation of jesConf and have it happen up front to provide fail-fast behavior (will do as a separate PR)

  override def adjustInputPaths(backendCall: BackendCall): CallInputs = backendCall.locallyQualifiedInputs mapValues gcsPathToLocal

  override def adjustOutputPaths(call: Call, outputs: CallOutputs): CallOutputs = outputs mapValues {
    case CallOutput(value, hash) => CallOutput(gcsPathToLocal(value), hash)
  }

  private def writeAuthenticationFile(workflow: WorkflowDescriptor) = authenticateAsCromwell { connection =>
    val log = workflowLogger(workflow)

    generateAuthJson(dockerConf, getGcsAuthInformation(workflow)) foreach { content =>

      val path = GcsPath(gcsAuthFilePath(workflow))
      def upload(prev: Option[Unit]) = connection.storage.uploadJson(path, content)

      log.info(s"Creating authentication file for workflow ${workflow.id} at \n ${path.toString}")
      withRetry(upload, log, s"Exception occurred while uploading auth file to $path")
    }
  }

  def getCrc32c(workflow: WorkflowDescriptor, googleCloudStoragePath: GcsPath): String = authenticateAsUser(workflow) {
    _.getCrc32c(googleCloudStoragePath)
  }

  def engineFunctions(ioInterface: IoInterface, workflowContext: WorkflowContext): WorkflowEngineFunctions = {
    new JesWorkflowEngineFunctions(ioInterface, workflowContext)
  }

  /**
   * Get a GcsLocalizing from workflow options if client secrets and refresh token are available.
   */
  def getGcsAuthInformation(workflow: WorkflowDescriptor): Option[JesAuthInformation] = {
    def extractSecrets(userAuthMode: GoogleUserAuthMode) = userAuthMode match {
      case Refresh(secrets) => Option(secrets)
      case _ => None
    }

    for {
      userAuthMode <- googleConf.userAuthMode
      secrets <- extractSecrets(userAuthMode)
      token <- workflow.workflowOptions.get(GoogleCloudStorage.RefreshTokenOptionKey).toOption
    } yield GcsLocalizing(secrets, token)
  }

  /*
   * No need to copy GCS inputs for the workflow we should be able to directly reference them
   * Create an authentication json file containing docker credentials and/or user account information
   */
  override def initializeForWorkflow(workflow: WorkflowDescriptor): Try[HostInputs] = {
    writeAuthenticationFile(workflow)
    Success(workflow.actualInputs)
  }

  override def assertWorkflowOptions(options: WorkflowOptions): Unit = {
    // Warn for unrecognized option keys
    options.toMap.keySet.diff(OptionKeys) match {
      case unknowns if unknowns.nonEmpty => logger.warn(s"Unrecognized workflow option(s): ${unknowns.mkString(", ")}")
      case _ =>
    }

    if (googleConf.userAuthMode.isDefined) {
      Seq(GoogleCloudStorage.RefreshTokenOptionKey) filterNot options.toMap.keySet match {
        case missing if missing.nonEmpty =>
          throw new IllegalArgumentException(s"Missing parameters in workflow options: ${missing.mkString(", ")}")
        case _ =>
      }
    }
  }

  /**
   * Delete authentication file in GCS once workflow is in a terminal state.
   *
   * First queries for the existence of the auth file, then deletes it if it exists.
   * If either of these operations fails, then a Future.failure is returned
   */
  override def cleanUpForWorkflow(workflow: WorkflowDescriptor)(implicit ec: ExecutionContext): Future[Unit] = {
    Future(gcsAuthFilePath(workflow)) map { path =>
      deleteAuthFile(path, workflowLogger(workflow))
      ()
    } recover {
      case e: UnsatisfiedInputsException =>  // No need to fail here, it just means that we didn't have an auth file in the first place so no need to delete it.
    }
  }

  private def deleteAuthFile(authFilePath: String, log: WorkflowLogger): Future[Unit] = authenticateAsCromwell { connection =>
      def gcsCheckAuthFileExists(prior: Option[Boolean]): Boolean = connection.storage.exists(authFilePath)
      def gcsAttemptToDeleteObject(prior: Option[Unit]): Unit = connection.storage.deleteObject(authFilePath)
      withRetry(gcsCheckAuthFileExists, log, s"Failed to query for auth file: $authFilePath") match {
        case Success(exists) if exists =>
          withRetry(gcsAttemptToDeleteObject, log, s"Failed to delete auth file: $authFilePath") match {
            case Success(_) => Future.successful(Unit)
            case Failure(ex) =>
              log.error(s"Could not delete the auth file $authFilePath", ex)
              Future.failed(ex)
          }
        case Failure(ex) =>
          log.error(s"Could not query for the existence of the auth file $authFilePath", ex)
          Future.failed(ex)
        case _ => Future.successful(Unit)
      }
  }

  override def stdoutStderr(backendCall: BackendCall): CallLogs = backendCall.stdoutStderr

  override def bindCall(jobDescriptor: BackendCallJobDescriptor,
                        abortRegistrationFunction: Option[AbortRegistrationFunction]): BackendCall = {
    new JesBackendCall(this, jobDescriptor, abortRegistrationFunction)
  }

  private def executeOrResume(backendCall: BackendCall, runIdForResumption: Option[String])(implicit ec: ExecutionContext): Future[ExecutionHandle] = Future {
    val log = workflowLoggerWithCall(backendCall)
    log.info(s"Call GCS path: ${backendCall.callGcsPath}")
    val monitoringScript: Option[JesInput] = monitoringIO(backendCall)
    val monitoringOutput = monitoringScript map { _ =>
      JesFileOutput(s"$MonitoringParamName-out", backendCall.defaultMonitoringOutputPath, Paths.get(JesMonitoringLogFile), backendCall.workingDisk)
    }

    val jesInputs: Seq[JesInput] = generateJesInputs(backendCall).toSeq ++ monitoringScript :+ backendCall.cmdInput
    val jesOutputs: Seq[JesFileOutput] = generateJesOutputs(backendCall) ++ monitoringOutput

    backendCall.instantiateCommand match {
      case Success(command) => runWithJes(backendCall, command, jesInputs, jesOutputs, runIdForResumption, monitoringScript.isDefined)
      case Failure(ex: SocketTimeoutException) => throw ex // probably a GCS transient issue, throwing will cause it to be retried
      case Failure(ex) => FailedExecutionHandle(ex)
    }
  }

  def execute(backendCall: BackendCall)(implicit ec: ExecutionContext): Future[ExecutionHandle] = executeOrResume(backendCall, runIdForResumption = None)

  def resume(backendCall: BackendCall, jobKey: JobKey)(implicit ec: ExecutionContext): Future[ExecutionHandle] = {
    val runId = Option(jobKey) collect { case jesKey: JesJobKey => jesKey.jesRunId }
    executeOrResume(backendCall, runIdForResumption = runId)
  }

  def useCachedCall(cachedCall: BackendCall, backendCall: BackendCall)(implicit ec: ExecutionContext): Future[ExecutionHandle] = {
    import better.files._

    def renameCallSpecificFiles = {
      val namesBuilder = List(
        JesBackend.jesLogStdoutFilename _,
        JesBackend.jesLogStderrFilename _,
        JesBackend.jesLogFilename _,
        JesBackend.jesReturnCodeFilename _
      )

      /* Paths of the files in the cached call. */
      val cachedPaths = namesBuilder map { _(cachedCall.key) } map backendCall.callGcsPath.resolve
      /* Expected names of the files in the current call.
       * They might be different if the cached call doesn't have the same name / shard
       * as the current call, because this information is part of the filename (TODO should it ?).
       */
      val backendNames = namesBuilder map { _(backendCall.key) }

      (cachedPaths zip backendNames) map {
        case (cachedPath, backendName) => cachedPath.renameTo(backendName)
      }
    }

    def copyingWork(gcs: GoogleCloudStorage): Try[Unit] = for {
        _ <- Try(gcs.copy(cachedCall.callGcsPath.toString, backendCall.callGcsPath.toString))
        _ <- Try(renameCallSpecificFiles)
    } yield ()

    Future {
      val log = workflowLoggerWithCall(backendCall)
      authenticateAsUser(backendCall.workflowDescriptor) { interface =>
        copyingWork(interface) match {
          case Failure(ex) =>
            log.error(s"Exception occurred while attempting to copy outputs from ${cachedCall.callGcsPath} to ${backendCall.callGcsPath}", ex)
            FailedExecutionHandle(ex).future
          case Success(_) => postProcess(backendCall) match {
            case Success(outputs) => backendCall.hash map { h =>
              SuccessfulExecutionHandle(outputs, List.empty[ExecutionEventEntry], backendCall.downloadRcFile.get.stripLineEnd.toInt, h, Option(cachedCall)) }
            case Failure(ex: AggregatedException) if ex.exceptions collectFirst { case s: SocketTimeoutException => s } isDefined =>
              // TODO: What can we return here to retry this operation?
              // TODO: This match clause is similar to handleSuccess(), though it's subtly different for this specific case
              val error = "Socket timeout occurred in evaluating one or more of the output expressions"
              log.error(error, ex)
              FailedExecutionHandle(new Exception(error, ex)).future
            case Failure(ex) => FailedExecutionHandle(ex).future
          }
        }
      }
    } flatten
  }

  /**
    * Turns WdlFiles into relative paths.  These paths are relative to the working disk
    *
    * relativeLocalizationPath("foo/bar.txt") -> "foo/bar.txt"
    * relativeLocalizationPath("gs://some/bucket/foo.txt") -> "some/bucket/foo.txt"
    */
  private def relativeLocalizationPath(file: WdlFile): WdlFile = {
    GcsPath.parse(file.value) match {
      case Success(gcsPath) => WdlFile(gcsPath.bucket + "/" + gcsPath.objectName, file.isGlob)
      case Failure(e) => file
    }
  }

  def generateJesInputs(backendCall: BackendCall): Iterable[JesInput] = {
    /**
      * Commands in WDL tasks can also generate input files.  For example: ./my_exec --file=${write_lines(arr)}
      *
      * write_lines(arr) would produce a string-ified version of the array stored as a GCS path.  The next block of code
      * will go through each ${...} expression within the task's command section and find all write_*() ASTs and
      * evaluate them so the files are written to GCS and the they can be included as inputs to Google's Pipeline object
      */
    val commandExpressions = backendCall.call.task.commandTemplate.collect({
      case x: ParameterCommandPart => x.expression
    })

    val writeFunctionAsts = commandExpressions.map(_.ast).flatMap(x => AstTools.findAsts(x, "FunctionCall")).collect({
      case y if y.getAttribute("name").sourceString.startsWith("write_") => y
    })

    val evaluatedExpressionMap = writeFunctionAsts map { ast =>
      val expression = WdlExpression(ast)
      val value = expression.evaluate(backendCall.lookupFunction(Map.empty), backendCall.callEngineFunctions)
      expression.toWdlString.md5SumShort -> value
    } toMap

    val writeFunctionFiles = evaluatedExpressionMap collect { case (k, v: Success[_]) => k -> v.get } collect { case (k, v: WdlFile) => k -> Seq(v)}

    /** Collect all WdlFiles from inputs to the call */
    val callInputFiles = backendCall.locallyQualifiedInputs mapValues { _.collectAsSeq { case w: WdlFile => w } }

    (callInputFiles ++ writeFunctionFiles) flatMap {
      case (name, files) => jesInputsFromWdlFiles(name, files, files.map(relativeLocalizationPath), backendCall)
    }
  }

  /**
   * Takes two arrays of remote and local WDL File paths and generates the necessary JesInputs.
   */
  private def jesInputsFromWdlFiles(jesNamePrefix: String,
                                    remotePathArray: Seq[WdlFile],
                                    localPathArray: Seq[WdlFile],
                                    backendCall: BackendCall): Iterable[JesInput] = {
    (remotePathArray zip localPathArray zipWithIndex) flatMap {
      case ((remotePath, localPath), index) =>
        Seq(JesFileInput(s"$jesNamePrefix-$index", remotePath.valueString, Paths.get(localPath.valueString), backendCall.workingDisk))
    }
  }

  def generateJesOutputs(backendCall: BackendCall): Seq[JesFileOutput] = {
    val log = workflowLoggerWithCall(backendCall)
    val wdlFileOutputs = backendCall.call.task.outputs flatMap { taskOutput =>
      taskOutput.requiredExpression.evaluateFiles(backendCall.lookupFunction(Map.empty), NoFunctions, taskOutput.wdlType) match {
        case Success(wdlFiles) => wdlFiles map relativeLocalizationPath
        case Failure(ex) =>
          log.warn(s"Could not evaluate $taskOutput: ${ex.getMessage}", ex)
          Seq.empty[WdlFile]
      }
    }

    // Create the mappings. GLOB mappings require special treatment (i.e. stick everything matching the glob in a folder)
    wdlFileOutputs.distinct map { wdlFile =>
      val destination = wdlFile match {
        case WdlSingleFile(filePath) => s"${backendCall.callGcsPath}/$filePath"
        case WdlGlobFile(filePath) => backendCall.globOutputPath(filePath)
      }
      val (relpath, disk) = relativePathAndAttachedDisk(wdlFile.value, backendCall.runtimeAttributes.disks)
      JesFileOutput(makeSafeJesReferenceName(wdlFile.value), destination, relpath, disk)
    }
  }

  /**
    * Given a path (relative or absolute), returns a (Path, JesAttachedDisk) tuple where the Path is
    * relative to the AttachedDisk's mount point
    *
    * @throws Exception if the `path` does not live in one of the supplied `disks`
    */
  private def relativePathAndAttachedDisk(path: String, disks: Seq[JesAttachedDisk]): (Path, JesAttachedDisk) = {
    val absolutePath = Paths.get(path) match {
      case p if !p.isAbsolute => Paths.get(JesWorkingDisk.MountPoint).resolve(p)
      case p => p
    }

    disks.find(d => absolutePath.startsWith(d.mountPoint)) match {
      case Some(disk) => (disk.mountPoint.relativize(absolutePath), disk)
      case None =>
        throw new Exception(s"Absolute path $path doesn't appear to be under any mount points: ${disks.map(_.toString).mkString(", ")}")
    }
  }

  def monitoringIO(backendCall: BackendCall): Option[JesInput] = {
    backendCall.workflowDescriptor.workflowOptions.get(MonitoringScriptOptionKey) map { path =>
      JesFileInput(s"$MonitoringParamName-in", GcsPath(path).toString, Paths.get(JesMonitoringScript), backendCall.workingDisk)
    } toOption
  }

  /**
   * If the desired reference name is too long, we don't want to break JES or risk collisions by arbitrary truncation. So,
   * just use a hash. We only do this when needed to give better traceability in the normal case.
   */
  private def makeSafeJesReferenceName(referenceName: String) = {
    if (referenceName.length <= 127) referenceName else referenceName.md5Sum
  }

  private def uploadCommandScript(backendCall: BackendCall, command: String, withMonitoring: Boolean): Try[Unit] = {
    val monitoring = if (withMonitoring) {
      s"""|touch $JesMonitoringLogFile
          |chmod u+x $JesMonitoringScript
          |./$JesMonitoringScript > $JesMonitoringLogFile &""".stripMargin
    } else ""

    val tmpDir = Paths.get(JesWorkingDisk.MountPoint).resolve("tmp")
    val rcPath = Paths.get(JesWorkingDisk.MountPoint).resolve(JesBackend.jesReturnCodeFilename(backendCall.key))

    val fileContent =
      s"""
         |#!/bin/bash
         |export _JAVA_OPTIONS=-Djava.io.tmpdir=$tmpDir
         |export TMPDIR=$tmpDir
         |cd ${JesWorkingDisk.MountPoint}
         |$monitoring
         |$command
         |echo $$? > $rcPath
       """.stripMargin.trim

    def attemptToUploadObject(priorAttempt: Option[Unit]) = authenticateAsUser(backendCall.workflowDescriptor) { _.uploadObject(backendCall.gcsExecPath, fileContent) }

    val log = workflowLogger(backendCall.workflowDescriptor)
    withRetry(attemptToUploadObject, log, s"${workflowLoggerWithCall(backendCall).tag} Exception occurred while uploading script to ${backendCall.gcsExecPath}")
  }

  private def createJesRun(backendCall: BackendCall, jesParameters: Seq[JesParameter], runIdForResumption: Option[String]): Try[Run] =
    authenticateAsCromwell { connection =>
      def attemptToCreateJesRun(priorAttempt: Option[Run]): Run = Pipeline(
        backendCall.jesCommandLine,
        backendCall.workflowDescriptor,
        backendCall.key,
        backendCall.runtimeAttributes,
        jesParameters,
        backendCall.preemptible,
        googleProject(backendCall.workflowDescriptor),
        connection,
        runIdForResumption
      ).run

      val log = workflowLoggerWithCall(backendCall)
      if (backendCall.preemptible) log.info("Starting call with pre-emptible VM")
      withRetry(attemptToCreateJesRun, log, "Exception occurred while creating JES Run")
    }

  /**
   * Turns a GCS path representing a workflow input into the GCS path where the file would be mirrored to in this workflow:
   * task x {
   *  File x
   *  ...
   *  Output {
   *    File mirror = x
   *  }
   * }
   *
   * This function is more useful in working out the common prefix when the filename is modified somehow
   * in the workflow (e.g. "-new.txt" is appended)
   */
  private def gcsInputToGcsOutput(backendCall: BackendCall, inputValue: WdlValue): WdlValue = {
    // Convert to the local path where the file is localized to in the VM:
    val vmLocalizationPath = gcsPathToLocal(inputValue)

    vmLocalizationPath match {
      // If it's a file, work out where the file would be delocalized to, otherwise no-op:
      case x : WdlFile =>
        val delocalizationPath = s"${backendCall.callGcsPath}/${vmLocalizationPath.valueString}"
        WdlFile(delocalizationPath)
      case a: WdlArray => WdlArray(a.wdlType, a.value map { f => gcsInputToGcsOutput(backendCall, f) })
      case m: WdlMap => WdlMap(m.wdlType, m.value map { case (k, v) => gcsInputToGcsOutput(backendCall, k) -> gcsInputToGcsOutput(backendCall, v) })
      case other => other
    }
  }

  private def customLookupFunction(backendCall: BackendCall, alreadyGeneratedOutputs: Map[String, WdlValue]): String => WdlValue = toBeLookedUp => {
    val originalLookup = backendCall.lookupFunction(alreadyGeneratedOutputs)
    gcsInputToGcsOutput(backendCall, originalLookup(toBeLookedUp))
  }

  def wdlValueToGcsPath(jesOutputs: Seq[JesFileOutput])(value: WdlValue): WdlValue = {
    def toGcsPath(wdlFile: WdlFile) = jesOutputs collectFirst { case o if o.name == makeSafeJesReferenceName(wdlFile.valueString) => WdlFile(o.gcs) } getOrElse value
    value match {
      case wdlArray: WdlArray => wdlArray map wdlValueToGcsPath(jesOutputs)
      case wdlMap: WdlMap => wdlMap map {
        case (k, v) => wdlValueToGcsPath(jesOutputs)(k) -> wdlValueToGcsPath(jesOutputs)(v)
      }
      case file: WdlFile => toGcsPath(file)
      case other => other
    }
  }

  def postProcess(backendCall: BackendCall): Try[CallOutputs] = {
    val outputs = backendCall.call.task.outputs
    val outputFoldingFunction = getOutputFoldingFunction(backendCall)
    val outputMappings = outputs.foldLeft(Seq.empty[AttemptedLookupResult])(outputFoldingFunction).map(_.toPair).toMap
    TryUtil.sequenceMap(outputMappings) map { outputMap =>
      outputMap mapValues { v =>
        CallOutput(v, backendCall.workflowDescriptor.hash(v))
      }
    }
  }

  private def getOutputFoldingFunction(backendCall: BackendCall): (Seq[AttemptedLookupResult], TaskOutput) => Seq[AttemptedLookupResult] = {
    (currentList: Seq[AttemptedLookupResult], taskOutput: TaskOutput) => {
      currentList ++ Seq(AttemptedLookupResult(taskOutput.name, outputLookup(taskOutput, backendCall, currentList)))
    }
  }

  private def outputLookup(taskOutput: TaskOutput, backendCall: BackendCall, currentList: Seq[AttemptedLookupResult]) = for {
  /**
    * This will evaluate the task output expression and coerces it to the task output's type.
    * If the result is a WdlFile, then attempt to find the JesOutput with the same path and
    * return a WdlFile that represents the GCS path and not the local path.  For example,
    *
    * <pre>
    * output {
    *   File x = "out" + ".txt"
    * }
    * </pre>
    *
    * "out" + ".txt" is evaluated to WdlString("out.txt") and then coerced into a WdlFile("out.txt")
    * Then, via wdlFileToGcsPath(), we attempt to find the JesOutput with .name == "out.txt".
    * If it is found, then WdlFile("gs://some_bucket/out.txt") will be returned.
    */
    wdlValue <- taskOutput.requiredExpression.evaluate(customLookupFunction(backendCall, currentList.toLookupMap), backendCall.callEngineFunctions)
    coercedValue <- taskOutput.wdlType.coerceRawValue(wdlValue)
    value = wdlValueToGcsPath(generateJesOutputs(backendCall))(coercedValue)
  } yield value

  def executionResult(status: RunStatus, handle: JesPendingExecutionHandle)(implicit ec: ExecutionContext): Future[ExecutionHandle] = Future {
    val log = workflowLoggerWithCall(handle.backendCall)

    try {
      val backendCall = handle.backendCall
      val outputMappings = postProcess(backendCall)
      lazy val stderrLength: BigInteger = authenticateAsUser(backendCall.workflowDescriptor) { _.objectSize(GcsPath(backendCall.jesStderrGcsPath)) }
      lazy val returnCodeContents = backendCall.downloadRcFile
      lazy val returnCode = returnCodeContents map { _.trim.toInt }
      lazy val continueOnReturnCode = backendCall.runtimeAttributes.continueOnReturnCode

      status match {
        case Run.Success(events) if backendCall.runtimeAttributes.failOnStderr && stderrLength.intValue > 0 =>
          // returnCode will be None if it couldn't be downloaded/parsed, which will yield a null in the DB
          FailedExecutionHandle(new Throwable(s"${log.tag} execution failed: stderr has length $stderrLength"), returnCode.toOption).future
        case Run.Success(events) if returnCodeContents.isFailure =>
          val exception = returnCode.failed.get
          log.warn(s"${log.tag} could not download return code file, retrying: " + exception.getMessage, exception)
          // Return handle to try again.
          handle.future
        case Run.Success(events) if returnCode.isFailure =>
          FailedExecutionHandle(new Throwable(s"${log.tag} execution failed: could not parse return code as integer: " + returnCodeContents.get)).future
        case Run.Success(events) if !continueOnReturnCode.continueFor(returnCode.get) =>
          FailedExecutionHandle(new Throwable(s"${log.tag} execution failed: disallowed command return code: " + returnCode.get), returnCode.toOption).future
        case Run.Success(events) =>
          backendCall.hash map { h => handleSuccess(outputMappings, backendCall.workflowDescriptor, events, returnCode.get, h, handle) }
        case Run.Failed(errorCode, errorMessage, events) => handleFailure(backendCall, errorCode, errorMessage, events, log)
      }
    } catch {
      case e: Exception =>
        log.warn("Caught exception trying to download result, retrying: " + e.getMessage, e)
        // Return the original handle to try again.
        handle.future
    }
  } flatten

  private def runWithJes(backendCall: BackendCall,
                         command: String,
                         jesInputs: Seq[JesInput],
                         jesOutputs: Seq[JesFileOutput],
                         runIdForResumption: Option[String],
                         withMonitoring: Boolean): ExecutionHandle = {
    val log = workflowLoggerWithCall(backendCall)
    val jesParameters = backendCall.standardParameters ++ gcsAuthParameter(backendCall.workflowDescriptor) ++ jesInputs ++ jesOutputs
    log.info(s"`$command`")

    val jesJobSetup = for {
      _ <- uploadCommandScript(backendCall, command, withMonitoring)
      run <- createJesRun(backendCall, jesParameters, runIdForResumption)
    } yield run

    jesJobSetup match {
      case Failure(ex) =>
        log.warn(s"Failed to create a JES run", ex)
        throw ex  // Probably a transient issue, throwing retries it
      case Success(run) => JesPendingExecutionHandle(backendCall, jesOutputs, run, previousStatus = None)
    }
  }

  private def handleSuccess(outputMappings: Try[CallOutputs],
                            workflowDescriptor: WorkflowDescriptor,
                            executionEvents: Seq[ExecutionEventEntry],
                            returnCode: Int,
                            hash: ExecutionHash,
                            executionHandle: ExecutionHandle): ExecutionHandle = {
    outputMappings match {
      case Success(outputs) => SuccessfulExecutionHandle(outputs, executionEvents, returnCode, hash)
      case Failure(ex: AggregatedException) if ex.exceptions collectFirst { case s: SocketTimeoutException => s } isDefined =>
        // Return the execution handle in this case to retry the operation
        executionHandle
      case Failure(ex) => FailedExecutionHandle(ex)
    }
  }

  private def extractErrorCodeFromErrorMessage(errorMessage: String): Int = {
    errorMessage.substring(0, errorMessage.indexOf(':')).toInt
  }

  private def preempted(errorCode: Int, errorMessage: Option[String], backendCall: BackendCall, logger: WorkflowLogger): Boolean = {
    def isPreemptionCode(code: Int) = code == 13 || code == 14

    try {
      errorCode == 10 && errorMessage.isDefined && isPreemptionCode(extractErrorCodeFromErrorMessage(errorMessage.get)) && backendCall.preemptible
    } catch {
      case _: NumberFormatException | _: StringIndexOutOfBoundsException =>
        logger.warn(s"Unable to parse JES error code from error message: ${errorMessage.get}, assuming this was not a preempted VM.")
        false
    }
  }

  private def handleFailure(backendCall: BackendCall, errorCode: Int, errorMessage: Option[String], events: Seq[ExecutionEventEntry], logger: WorkflowLogger) = {
    import lenthall.numeric.IntegerUtil._

    val taskName = s"${backendCall.workflowDescriptor.id}:${backendCall.call.unqualifiedName}"
    val attempt = backendCall.key.attempt

    if (errorMessage.exists(_.contains("Operation canceled at")))  {
      AbortedExecutionHandle.future
    } else if (preempted(errorCode, errorMessage, backendCall, logger)) {
      val preemptedMsg = s"Task $taskName was preempted for the ${attempt.toOrdinal} time."
      val max = backendCall.maxPreemption

      if (attempt < max) {
        val e = new PreemptedException(
          s"""$preemptedMsg The call will be re-started with another pre-emptible VM (max pre-emptible attempts number is $max).
             |Error code $errorCode. Message: $errorMessage""".stripMargin
        )
        RetryableExecutionHandle(e, None, events).future
      } else {
        val e = new PreemptedException(
          s"""$preemptedMsg The maximum number of pre-emptible attempts ($max) has been reached. The call will be restarted with a non-pre-emptible VM.
             |Error code $errorCode. Message: $errorMessage)""".stripMargin)
        RetryableExecutionHandle(e, None, events).future
      }
    } else {
      val e = new Throwable(s"Task ${backendCall.workflowDescriptor.id}:${backendCall.call.unqualifiedName} failed: error code $errorCode. Message: ${errorMessage.getOrElse("null")}")
      FailedExecutionHandle(e, None, events).future
    }
  }

  /**
   * <ul>
   *   <li>Any execution in Failed should fail the restart.</li>
   *   <li>Any execution in Aborted should fail the restart.</li>
   *   <li>Scatters in Starting should fail the restart.</li>
   *   <li>Collectors in Running should be set back to NotStarted.</li>
   *   <li>Calls in Starting should be rolled back to NotStarted.</li>
   *   <li>Calls in Running with no job key should be rolled back to NotStarted.</li>
   * </ul>
   *
   * Calls in Running *with* a job key should be left in Running.  The WorkflowActor is responsible for
   * resuming the CallActors for these calls.
   */
  override def prepareForRestart(restartableWorkflow: WorkflowDescriptor)(implicit ec: ExecutionContext): Future[Unit] = {
    import cromwell.engine.backend.jes.JesBackend.EnhancedExecution

    lazy val tag = s"Workflow ${restartableWorkflow.id.shortString}:"

    def handleExecutionStatuses(executions: Traversable[Execution]): Future[Unit] = {

      def stringifyExecutions(executions: Traversable[Execution]): String = {
        executions.toSeq.sortWith((lt, rt) => lt.callFqn < rt.callFqn || (lt.callFqn == rt.callFqn && lt.index < rt.index)).mkString(" ")
      }

      def isRunningCollector(key: Execution) = key.index.toIndex.isEmpty && key.executionStatus == ExecutionStatus.Running

      val failedOrAbortedExecutions = executions filter { x => x.executionStatus == ExecutionStatus.Aborted || x.executionStatus == ExecutionStatus.Failed }

      if (failedOrAbortedExecutions.nonEmpty) {
        Future.failed(new Throwable(s"$tag Cannot restart, found Failed and/or Aborted executions: " + stringifyExecutions(failedOrAbortedExecutions)))
      } else {
        // Cromwell has execution types: scatter, collector, call.
        val (scatters, collectorsAndCalls) = executions partition { _.isScatter }
        // If a scatter is found in starting state, it's not clear without further database queries whether the call
        // shards have been created or not.  This is an unlikely scenario and could be worked around with further
        // queries or a bracketing transaction, but for now Cromwell just bails out on restarting the workflow.
        val startingScatters = scatters filter { _.executionStatus == ExecutionStatus.Starting }
        if (startingScatters.nonEmpty) {
          Future.failed(new Throwable(s"$tag Cannot restart, found scatters in Starting status: " + stringifyExecutions(startingScatters)))
        } else {
          // Scattered calls have more than one execution with the same FQN.  Find any collectors in these FQN
          // groupings which are in Running state.
          // This is a race condition similar to the "starting scatters" case above, but here the assumption is that
          // it's more likely that collectors can safely be reset to starting.  This may prove not to be the case if
          // entries have been written to the symbol table.
          // Like the starting scatters case, further queries or a bracketing transaction would be a better long term solution.
          val runningCollectors = collectorsAndCalls.groupBy(_.callFqn) collect {
            case (_, xs) if xs.size > 1 => xs filter isRunningCollector } flatten

          for {
            _ <- globalDataAccess.resetTransientExecutions(restartableWorkflow.id, IsTransient)
            _ <- globalDataAccess.setStartingStatus(restartableWorkflow.id, runningCollectors map { _.toKey })
          } yield ()
        }
      }
    }

    for {
      // Find all executions for the specified workflow that are not NotStarted or Done.
      executions <- globalDataAccess.getExecutionsForRestart(restartableWorkflow.id)
      // Examine statuses/types of executions, reset statuses as necessary.
      _ <- handleExecutionStatuses(executions)
    } yield ()
  }

  override def backendType = BackendType.JES

  def gcsAuthFilePath(descriptor: WorkflowDescriptor): String = {
    // If we are going to upload an auth file we need a valid GCS path passed via workflow options.
    val bucket = descriptor.workflowOptions.get(AuthFilePathOptionKey) getOrElse descriptor.workflowRootPath.toString
    s"$bucket/${descriptor.id}_auth.json"
  }

  def googleProject(descriptor: WorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(GoogleProjectOptionKey, jesConf.project)
  }

  // Create an input parameter containing the path to this authentication file, if needed
  def gcsAuthParameter(descriptor: WorkflowDescriptor): Option[JesInput] = {
    if (googleConf.userAuthMode.isDefined || dockerConf.isDefined)
      Option(authGcsCredentialsPath(gcsAuthFilePath(descriptor)))
    else None
  }

  override def findResumableExecutions(id: WorkflowId)(implicit ec: ExecutionContext): Future[Traversable[ExecutionKeyToJobKey]] = {
    globalDataAccess.findResumableExecutions(id, IsResumable, BuildJobKey)
  }

  override def executionInfoKeys: List[String] = List(JesBackend.InfoKeys.JesRunId, JesBackend.InfoKeys.JesStatus)

  override def callEngineFunctions(descriptor: BackendCallJobDescriptor): CallEngineFunctions = {
    val workflowDescriptor = descriptor.workflowDescriptor
    val key = descriptor.key

    lazy val callGcsPath = key.callRootPathWithBaseRoot(workflowDescriptor, rootPath(workflowDescriptor.workflowOptions))

    lazy val jesStdoutGcsPath = callGcsPath.resolve(jesLogStdoutFilename(key)).toString
    lazy val jesStderrGcsPath = callGcsPath.resolve(jesLogStderrFilename(key)).toString

    val callContext = new CallContext(callGcsPath.toString, jesStdoutGcsPath, jesStderrGcsPath)
    new JesCallEngineFunctions(workflowDescriptor.ioManager, callContext)
  }
}
