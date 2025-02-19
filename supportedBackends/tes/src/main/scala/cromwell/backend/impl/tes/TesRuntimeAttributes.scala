package cromwell.backend.impl.tes

import cats.data.Validated
import cats.syntax.validated._
import com.typesafe.config.Config
import common.validation.ErrorOr.ErrorOr
import cromwell.backend.google.pipelines.common.DisksValidation
import cromwell.backend.google.pipelines.common.io.{
  PipelinesApiAttachedDisk,
  PipelinesApiEmptyMountedDisk,
  PipelinesApiWorkingDisk
}
import cromwell.backend.standard.StandardValidatedRuntimeAttributesBuilder
import cromwell.backend.validation._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import wdl4s.parser.MemoryUnit
import wom.RuntimeAttributesKeys
import wom.format.MemorySize
import wom.types.{WomIntegerType, WomStringType}
import wom.values._

import java.util.regex.Pattern

case class TesRuntimeAttributes(continueOnReturnCode: ContinueOnReturnCode,
                                dockerImage: String,
                                dockerWorkingDir: Option[String],
                                failOnStderr: Boolean,
                                cpu: Option[Int Refined Positive],
                                memory: Option[MemorySize],
                                disk: Option[MemorySize],
                                preemptible: Boolean,
                                localizedSasEnvVar: Option[String],
                                backendParameters: Map[String, Option[String]]
)

object TesRuntimeAttributes {
  val DockerWorkingDirKey = "dockerWorkingDir"
  val DiskSizeKey = "disk"
  val PreemptibleKey = "preemptible"
  val LocalizedSasKey = "azureSasEnvironmentVariable"

  private def cpuValidation(runtimeConfig: Option[Config]): OptionalRuntimeAttributesValidation[Int Refined Positive] =
    CpuValidation.optional

  private def failOnStderrValidation(runtimeConfig: Option[Config]) = FailOnStderrValidation.default(runtimeConfig)

  private def continueOnReturnCodeValidation(runtimeConfig: Option[Config]) =
    ContinueOnReturnCodeValidation.default(runtimeConfig)

  private def diskSizeValidation(runtimeConfig: Option[Config]): OptionalRuntimeAttributesValidation[MemorySize] =
    MemoryValidation.optional(DiskSizeKey)

  private def diskSizeCompatValidation(
    runtimeConfig: Option[Config]
  ): OptionalRuntimeAttributesValidation[Seq[PipelinesApiAttachedDisk]] =
    DisksValidation.optional

  private def memoryValidation(runtimeConfig: Option[Config]): OptionalRuntimeAttributesValidation[MemorySize] =
    MemoryValidation.optional(RuntimeAttributesKeys.MemoryKey)

  private val dockerValidation: RuntimeAttributesValidation[String] = DockerValidation.instance

  private val dockerWorkingDirValidation: OptionalRuntimeAttributesValidation[String] =
    DockerWorkingDirValidation.optional
  private def preemptibleValidation(runtimeConfig: Option[Config]) = PreemptibleValidation.default(runtimeConfig)
  private def localizedSasValidation: OptionalRuntimeAttributesValidation[String] = LocalizedSasValidation.optional

  def runtimeAttributesBuilder(backendRuntimeConfig: Option[Config]): StandardValidatedRuntimeAttributesBuilder =
    // !! NOTE !! If new validated attributes are added to TesRuntimeAttributes, be sure to include
    // their validations here so that they will be handled correctly with backendParameters.
    // Location 2 of 2
    StandardValidatedRuntimeAttributesBuilder
      .default(backendRuntimeConfig)
      .withValidation(
        cpuValidation(backendRuntimeConfig),
        memoryValidation(backendRuntimeConfig),
        diskSizeValidation(backendRuntimeConfig),
        diskSizeCompatValidation(backendRuntimeConfig),
        dockerValidation,
        dockerWorkingDirValidation,
        preemptibleValidation(backendRuntimeConfig),
        localizedSasValidation
      )

  def makeBackendParameters(runtimeAttributes: Map[String, WomValue],
                            keysToExclude: Set[String],
                            config: TesConfiguration
  ): Map[String, Option[String]] =
    if (config.useBackendParameters)
      runtimeAttributes.view
        .filterKeys(k => !keysToExclude.contains(k))
        .flatMap(_ match {
          case (key, WomString(s)) => Option((key, Option(s)))
          case (key, WomOptionalValue(WomStringType, Some(WomString(optS)))) => Option((key, Option(optS)))
          case (key, WomOptionalValue(WomStringType, None)) => Option((key, None))
          case _ => None
        })
        .toMap
    else
      Map.empty

  private def detectDiskFormat(backendRuntimeConfig: Option[Config],
                               validatedRuntimeAttributes: ValidatedRuntimeAttributes
  ): Option[MemorySize] = {

    def adaptPapiDisks(disks: Seq[PipelinesApiAttachedDisk]): MemorySize =
      disks match {
        case disk :: Nil if disk.isInstanceOf[PipelinesApiWorkingDisk] =>
          MemorySize(disk.sizeGb.toDouble, MemoryUnit.GB)
        case _ :: _ =>
          // When a user specifies only a custom disk, we add the default disk in the background, so we technically have multiple disks.
          // But we don't want to confuse the user with `multiple disks` message when they only put one.
          if (disks.exists(_.isInstanceOf[PipelinesApiEmptyMountedDisk]))
            throw new IllegalArgumentException("Disks with custom mount points are not supported by this backend")
          else
            // Multiple `local-disk` is not legal, but possible and should be detected
            throw new IllegalArgumentException("Expecting exactly one disk definition on this backend, found multiple")
      }

    val maybeTesDisk: Option[MemorySize] =
      RuntimeAttributesValidation.extractOption(diskSizeValidation(backendRuntimeConfig).key,
                                                validatedRuntimeAttributes
      )
    val maybePapiDisk: Option[Seq[PipelinesApiAttachedDisk]] =
      RuntimeAttributesValidation.extractOption(diskSizeCompatValidation(backendRuntimeConfig).key,
                                                validatedRuntimeAttributes
      )

    (maybeTesDisk, maybePapiDisk) match {
      case (Some(tesDisk: MemorySize), _) =>
        Option(
          tesDisk
        ) // If WDLs are in circulation with both `disk` and `disks`, pick the one intended for this backend
      case (None, Some(papiDisks: Seq[PipelinesApiAttachedDisk])) =>
        Option(adaptPapiDisks(papiDisks))
      case _ =>
        None
    }
  }

  def apply(validatedRuntimeAttributes: ValidatedRuntimeAttributes,
            rawRuntimeAttributes: Map[String, WomValue],
            config: TesConfiguration
  ): TesRuntimeAttributes = {
    val backendRuntimeConfig = config.runtimeConfig
    val docker: String = RuntimeAttributesValidation.extract(dockerValidation, validatedRuntimeAttributes)
    val dockerWorkingDir: Option[String] =
      RuntimeAttributesValidation.extractOption(dockerWorkingDirValidation.key, validatedRuntimeAttributes)
    val cpu: Option[Int Refined Positive] =
      RuntimeAttributesValidation.extractOption(cpuValidation(backendRuntimeConfig).key, validatedRuntimeAttributes)
    val memory: Option[MemorySize] =
      RuntimeAttributesValidation.extractOption(memoryValidation(backendRuntimeConfig).key, validatedRuntimeAttributes)
    val disk: Option[MemorySize] = detectDiskFormat(backendRuntimeConfig, validatedRuntimeAttributes)
    val failOnStderr: Boolean =
      RuntimeAttributesValidation.extract(failOnStderrValidation(backendRuntimeConfig), validatedRuntimeAttributes)
    val continueOnReturnCode: ContinueOnReturnCode =
      RuntimeAttributesValidation.extract(continueOnReturnCodeValidation(backendRuntimeConfig),
                                          validatedRuntimeAttributes
      )
    val preemptible: Boolean =
      RuntimeAttributesValidation.extract(preemptibleValidation(backendRuntimeConfig), validatedRuntimeAttributes)
    val localizedSas: Option[String] =
      RuntimeAttributesValidation.extractOption(localizedSasValidation.key, validatedRuntimeAttributes)

    // !! NOTE !! If new validated attributes are added to TesRuntimeAttributes, be sure to include
    // their validations here so that they will be handled correctly with backendParameters.
    // Location 1 of 2
    val validations = Set(
      dockerValidation,
      dockerWorkingDirValidation,
      cpuValidation(backendRuntimeConfig),
      memoryValidation(backendRuntimeConfig),
      diskSizeValidation(backendRuntimeConfig),
      diskSizeCompatValidation(backendRuntimeConfig),
      failOnStderrValidation(backendRuntimeConfig),
      continueOnReturnCodeValidation(backendRuntimeConfig),
      preemptibleValidation(backendRuntimeConfig),
      localizedSasValidation
    )

    // BT-458 any strings included in runtime attributes that aren't otherwise used should be
    // passed through to the TES server as part of backend_parameters
    val keysToExclude = validations map { _.key }
    val backendParameters = makeBackendParameters(rawRuntimeAttributes, keysToExclude, config)

    new TesRuntimeAttributes(
      continueOnReturnCode,
      docker,
      dockerWorkingDir,
      failOnStderr,
      cpu,
      memory,
      disk,
      preemptible,
      localizedSas,
      backendParameters
    )
  }
}

object DockerWorkingDirValidation {
  lazy val instance: RuntimeAttributesValidation[String] = new DockerWorkingDirValidation
  lazy val optional: OptionalRuntimeAttributesValidation[String] = instance.optional
}

class DockerWorkingDirValidation extends StringRuntimeAttributesValidation(TesRuntimeAttributes.DockerWorkingDirKey) {
  // NOTE: Docker's current test specs don't like WdlInteger, etc. auto converted to WdlString.
  override protected def validateValue: PartialFunction[WomValue, ErrorOr[String]] = { case WomString(value) =>
    value.validNel
  }
}

/**
  * Validates the "preemptible" runtime attribute as a Boolean or a String 'true' or 'false', returning the value as a
  * `Boolean`.
  *
  * `instance` returns an validation that errors when no attribute is specified.
  *
  * `configDefaultWdlValue` returns the value of the attribute as specified by the
  * reference.conf file, coerced into a WomValue.
  *
  * `default` a validation with the default value specified by the reference.conf file.
  */

object PreemptibleValidation {
  lazy val instance: RuntimeAttributesValidation[Boolean] = new PreemptibleValidation
  def default(runtimeConfig: Option[Config]): RuntimeAttributesValidation[Boolean] =
    instance.withDefault(configDefaultWdlValue(runtimeConfig) getOrElse WomBoolean(false))
  def configDefaultWdlValue(runtimeConfig: Option[Config]): Option[WomValue] =
    instance.configDefaultWomValue(runtimeConfig)
}

class PreemptibleValidation extends BooleanRuntimeAttributesValidation(TesRuntimeAttributes.PreemptibleKey) {
  override def usedInCallCaching: Boolean = false

  override protected def validateExpression: PartialFunction[WomValue, Boolean] = {
    case womBoolValue if womType.coerceRawValue(womBoolValue).isSuccess => true
    case womIntValue if WomIntegerType.coerceRawValue(womIntValue).isSuccess => true
  }

  override protected def validateValue: PartialFunction[WomValue, ErrorOr[Boolean]] = {
    case value if womType.coerceRawValue(value).isSuccess =>
      validateCoercedValue(womType.coerceRawValue(value).get.asInstanceOf[WomBoolean])
    // The TES spec requires a boolean preemptible value, but many WDLs written originally
    // for other backends use an integer. Interpret integers > 0 as true, others as false.
    case value if WomIntegerType.coerceRawValue(value).isSuccess =>
      validateCoercedValue(WomBoolean(WomIntegerType.coerceRawValue(value).get.asInstanceOf[WomInteger].value > 0))
    case value if womType.coerceRawValue(value.valueString).isSuccess =>
      /*
      NOTE: This case statement handles WdlString("true") coercing to WdlBoolean(true).
      For some reason "true" as String is coercable... but not the WdlString.
       */
      validateCoercedValue(womType.coerceRawValue(value.valueString).get.asInstanceOf[WomBoolean])
  }

  override protected def missingValueMessage: String =
    s"Expecting $key runtime attribute to be an Integer, Boolean, or a String with values of 'true' or 'false'"
}

object LocalizedSasValidation {
  lazy val instance: RuntimeAttributesValidation[String] = new LocalizedSasValidation
  lazy val optional: OptionalRuntimeAttributesValidation[String] = instance.optional
}

class LocalizedSasValidation extends StringRuntimeAttributesValidation(TesRuntimeAttributes.LocalizedSasKey) {
  private def isValidBashVariableName(str: String): Boolean = {
    // require string be only letters, numbers, and underscores
    val pattern = Pattern.compile("^[a-zA-Z0-9_]+$", Pattern.CASE_INSENSITIVE)
    val matcher = pattern.matcher(str)
    matcher.find
  }

  override protected def invalidValueMessage(value: WomValue): String =
    s"Invalid Runtime Attribute value for ${TesRuntimeAttributes.LocalizedSasKey}. Value must be a string containing only letters, numbers, and underscores."

  override protected def validateValue: PartialFunction[WomValue, ErrorOr[String]] = { case WomString(value) =>
    if (isValidBashVariableName(value)) value.validNel else Validated.invalidNel(invalidValueMessage(WomString(value)))
  }
}