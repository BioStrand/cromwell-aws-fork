package cromwell

import java.net.URL
import java.nio.file.InvalidPathException

import better.files.File
import cats.syntax.apply._
import cats.syntax.validated._
import common.validation.ErrorOr.ErrorOr
import common.validation.Validation._
import cromwell.CommandLineArguments._
import cromwell.CromwellApp.Command
import cromwell.core.WorkflowOptions
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.webservice.PartialWorkflowSources
import org.slf4j.Logger

import scala.util.{Failure, Success}

object CommandLineArguments {
  val DefaultCromwellHost = new URL("http://localhost:8000")
  case class ValidSubmission(workflowSource: Option[String],
                             workflowUrl: Option[String],
                             workflowRoot: Option[String],
                             worflowInputs: String,
                             workflowOptions: WorkflowOptions,
                             workflowLabels: String,
                             dependencies: Option[File])

  case class WorkflowSourceOrUrl(source: Option[String], url: Option[String])
}

case class CommandLineArguments(command: Option[Command] = None,
                                workflowSource: Option[String] = None,
                                workflowRoot: Option[String] = None,
                                workflowInputs: Option[Path] = None,
                                workflowOptions: Option[Path] = None,
                                workflowType: Option[String] = None,
                                workflowTypeVersion: Option[String] = None,
                                workflowLabels: Option[Path] = None,
                                imports: Option[Path] = None,
                                metadataOutput: Option[Path] = None,
                                host: URL = CommandLineArguments.DefaultCromwellHost
                               ) {
  def validateSubmission(logger: Logger): ErrorOr[ValidSubmission] = {

    def getWorkflowSourceFromPath(workflowPath: Path): ErrorOr[WorkflowSourceOrUrl] = {
      WorkflowSourceOrUrl(None, Option(workflowPath.pathAsString)).validNel
    }

    val workflowSourceAndUrl: ErrorOr[WorkflowSourceOrUrl] = DefaultPathBuilder.build(workflowSource.get) match {
      case Success(workflowPath) => {
        if (!workflowPath.exists) s"Workflow source path does not exist: $workflowPath".invalidNel
        else if(!workflowPath.isReadable) s"Workflow source path is not readable: $workflowPath".invalidNel
        else getWorkflowSourceFromPath(workflowPath)
      }
      case Failure(e: InvalidPathException) => s"Invalid file path. Error: ${e.getMessage}".invalidNel
      case Failure(_) => PartialWorkflowSources.validateWorkflowUrl(workflowSource.get).map(validUrl => WorkflowSourceOrUrl(None, Option(validUrl)))
    }

    val inputsJson: ErrorOr[String] = readOptionContent("Workflow inputs", workflowInputs)

    import common.validation.ErrorOr.ShortCircuitingFlatMap
    val optionsJson = readOptionContent("Workflow options", workflowOptions).flatMap { WorkflowOptions.fromJsonString(_).toErrorOr }

    val labelsJson = readOptionContent("Workflow labels", workflowLabels)

    val workflowImports: Option[File] = imports.map(p => File(p.pathAsString))

    (workflowSourceAndUrl, inputsJson, optionsJson, labelsJson) mapN {
      case (srcOrUrl, i, o, l) =>
        ValidSubmission(srcOrUrl.source, srcOrUrl.url, workflowRoot, i, o, l, workflowImports)
    }
  }

  /** Read the path to a string. */
  private def readContent(inputDescription: String, path: Path): ErrorOr[String] = {
    if (!path.exists) {
      s"$inputDescription does not exist: $path".invalidNel
    } else if (!path.isReadable) {
      s"$inputDescription is not readable: $path".invalidNel
    } else path.contentAsString.validNel
  }

  /** Read the path to a string, unless the path is None, in which case returns "{}". */
  private def readOptionContent(inputDescription: String, pathOption: Option[Path]): ErrorOr[String] = {
    pathOption match {
      case Some(path) => readContent(inputDescription, path)
      case None => "{}".validNel
    }
  }
}
