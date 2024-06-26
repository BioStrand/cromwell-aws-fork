package cromwell.backend.google.pipelines.v2beta.api

import java.util

import com.google.api.services.lifesciences.v2beta.model.Operation
import com.typesafe.scalalogging.StrictLogging
import common.assertion.CromwellTimeoutSpec
import cromwell.backend.google.pipelines.v2beta.api.Deserialization._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

class DeserializationSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers with StrictLogging {
  behavior of "Deserialization"

  it should "deserialize events from operation metadata" in {
    val operation = new Operation()
    val metadataMap = Map[String, AnyRef](
      "events" -> new util.ArrayList(
        List[java.util.Map[String, Object]](
          Map[String, AnyRef](
            "description" -> "event 1 description",
            "timestamp" -> "2018-04-20T14:38:25+00:00",
            "workerAssigned" -> Map[String, AnyRef](
              "zone" -> "event 1 Zone",
              "instance" -> "event 1 Instance"
            ).asJava
          ).asJava,
          Map[String, AnyRef](
            "description" -> "event 2 description",
            "timestamp" -> "2018-04-20T14:39:25+00:00",
            "containerStarted" -> Map[String, AnyRef](
              "actionId" -> Integer.valueOf(18),
              "ipAddress" -> "86.127.54.8",
              "portMappings" -> Map(
                "8000" -> Integer.valueOf(8008)
              ).asJava
            ).asJava,
          ).asJava
        ).asJava
      )
    ).asJava

    operation.setMetadata(metadataMap)
    val deserializedEvents = operation.events.valueOr(errors => fail(errors.toList.mkString(", ")))

    val event1 = deserializedEvents.head
    event1.getDescription shouldBe "event 1 description"
    event1.getTimestamp shouldBe "2018-04-20T14:38:25+00:00"
    // Event1 details are of type WorkerAssignedEvent, so it should not be defined for something else
    event1.getContainerStarted shouldBe null

    val event1Details = event1.getWorkerAssigned
    event1Details should not be null
    event1Details.getInstance shouldBe "event 1 Instance"
    event1Details.getZone shouldBe "event 1 Zone"

    val event2 = deserializedEvents(1)
    event2.getDescription shouldBe "event 2 description"
    event2.getTimestamp shouldBe "2018-04-20T14:39:25+00:00"

    val event2Details = event2.getContainerStarted
    event2Details should not be null
    event2Details.getActionId shouldBe 18
    event2Details.getIpAddress shouldBe "86.127.54.8"
    event2Details.getPortMappings.size() shouldBe 1
    event2Details.getPortMappings.get("8000") shouldBe 8008
  }

  it should "deserialize pipeline from operation metadata" in {
    val operation = new Operation()

    val metadataMap = Map[String, AnyRef](
      "pipeline" -> Map[String, AnyRef](
        "actions" -> List[java.util.Map[String, Object]](
          Map[String, Object](
            "containerName" -> "actionName",
            "imageUri" -> "ubuntu:latest",
            "commands" -> List[String]("echo", "hello").asJava
          ).asJava
        ).asJava,
        "resources" -> Map[String, Object](
          "projectId" -> "project",
          "virtualMachine" -> Map[String, Any](
            "machineType" -> "custom-1-1024",
            "preemptible" -> false
          ).asJava
        ).asJava
      ).asJava
    ).asJava

    operation.setMetadata(metadataMap)
    val deserializedPipeline = operation.pipeline.get.get
    val action = deserializedPipeline.getActions.get(0)
    action.getCommands.asScala shouldBe List("echo", "hello")
    action.getImageUri shouldBe "ubuntu:latest"
    action.getContainerName shouldBe "actionName"
    val virtualMachine = deserializedPipeline.getResources.getVirtualMachine
    virtualMachine.getMachineType shouldBe "custom-1-1024"
    virtualMachine.getPreemptible shouldBe false
  }

  // https://github.com/broadinstitute/cromwell/issues/4772
  it should "deserialize pipeline from operation metadata without preemptible" in {
    val operation = new Operation()

    val metadataMap = Map[String, AnyRef](
      "pipeline" -> Map[String, AnyRef](
        "actions" -> List[java.util.Map[String, Object]](
          Map[String, Object](
            "containerName" -> "actionName",
            "imageUri" -> "ubuntu:latest",
            "commands" -> List[String]("echo", "hello").asJava
          ).asJava
        ).asJava,
        "resources" -> Map[String, Object](
          "projectId" -> "project",
          "virtualMachine" -> Map(
            "machineType" -> "custom-1-1024",
          ).asJava
        ).asJava
      ).asJava
    ).asJava

    operation.setMetadata(metadataMap)
    val deserializedPipeline = operation.pipeline.get.get
    val action = deserializedPipeline.getActions.get(0)
    action.getCommands.asScala shouldBe List("echo", "hello")
    action.getImageUri shouldBe "ubuntu:latest"
    action.getContainerName shouldBe "actionName"
    val virtualMachine = deserializedPipeline.getResources.getVirtualMachine
    virtualMachine.getMachineType shouldBe "custom-1-1024"
    virtualMachine.getPreemptible shouldBe null
  }

  it should "be able to say if the operation has started" in {
    val operation = new Operation()

    def makeMetadata(details: Map[String, Object]) = Map[String, AnyRef](
      "events" -> new util.ArrayList(
        List[java.util.Map[String, Object]](
          (Map[String, AnyRef](
            "description" -> "event 1 description",
            "timestamp" -> "2018-04-20T14:38:25+00:00"
          ) ++ details).asJava
        ).asJava
      )
    ).asJava

    val metadataMapStarted = makeMetadata(Map[String, Object](
      "workerAssigned" -> Map(
        "zone" -> "event 1 Zone",
        "instance" -> "event 1 Instance"
      ).asJava))
    val metadataMapNotStarted = makeMetadata(Map.empty)
    val metadataMapNotStarted2 = makeMetadata(Map[String, Object](
      "containerStarted" -> Map().asJava
    ))

    operation.setMetadata(metadataMapStarted)
    operation.hasStarted shouldBe true
    operation.setMetadata(metadataMapNotStarted)
    operation.hasStarted shouldBe false
    operation.setMetadata(metadataMapNotStarted2)
    operation.hasStarted shouldBe false
  }

  it should "deserialize big decimals correctly" in {
    val valueMap = Map[String, Object](
      "integerValue" -> BigDecimal(5),
      "doubleValue" -> BigDecimal.decimal(6D),
      "floatValue" -> BigDecimal.decimal(7F),
      "longValue" -> BigDecimal.decimal(8L)
    ).asJava

    val deserialized = Deserialization.deserializeTo[DeserializationTestClass](valueMap)
    deserialized match {
      case Success(deserializedSuccess) =>
        deserializedSuccess.integerValue shouldBe 5
        deserializedSuccess.doubleValue shouldBe 6D
        deserializedSuccess.floatValue shouldBe 7F
        deserializedSuccess.longValue shouldBe 8L
      case Failure(f) =>
        fail("Bad deserialization", f)
    }
  }

}
