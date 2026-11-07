package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RetryPolicy
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Checks the values operators rely on in the CloudFormation deployment asset. */
class RmuDeploymentTemplateSpec extends AnyFreeSpec with Matchers {

  private val ReportBatchItemFailures = "ReportBatchItemFailures"

  private def template: Json =
    parse(
      new String(Files.readAllBytes(repositoryFile("deploy/rmu-deployment.template.json")), StandardCharsets.UTF_8)
    ).toOption.get

  private def parameters: io.circe.ACursor = template.hcursor.downField("Parameters")
  private def resource(logicalId: String): io.circe.ACursor =
    template.hcursor.downField("Resources").downField(logicalId).downField("Properties")

  private def defaultValue(name: String): Long =
    parameters.downField(name).downField("Default").as[Long].toOption.get

  "RmuDeploymentTemplate" - {
    "configures the head stream event source mapping" in {
      val mapping = resource("RmuEventSourceMapping")

      mapping.downField("BisectBatchOnFunctionError").as[Boolean].toOption shouldBe Some(true)
      mapping.downField("FunctionResponseTypes").as[List[String]].toOption.get should contain(
        ReportBatchItemFailures
      )
      mapping.downField("StartingPosition").as[String].toOption shouldBe Some("TRIM_HORIZON")
      mapping.downField("BatchSize").downField("Ref").as[String].toOption shouldBe Some("RmuBatchSize")
      mapping.downField("MaximumRetryAttempts").downField("Ref").as[String].toOption shouldBe Some(
        "MaximumRetryAttempts"
      )
      mapping.downField("MaximumRecordAgeInSeconds").downField("Ref").as[String].toOption shouldBe Some(
        "MaximumRecordAgeInSeconds"
      )
      mapping
        .downField("DestinationConfig")
        .downField("OnFailure")
        .downField("Destination")
        .downField("Fn::GetAtt")
        .as[List[String]]
        .toOption
        .get shouldBe List("OnFailureQueue", "Arn")
    }

    "sets a function timeout that exceeds the retry budget" in {
      resource("RmuFunction").downField("Timeout").downField("Ref").as[String].toOption shouldBe Some(
        "RmuTimeoutSeconds"
      )
      val timeoutSeconds     = defaultValue("RmuTimeoutSeconds")
      val retryElapsedMillis = defaultValue("RetryMaxElapsedMillis")

      timeoutSeconds should be > retryElapsedMillis / 1000
      timeoutSeconds should be <= 900L
    }

    "keeps the recovery queue visibility timeout above the worker timeout" in {
      defaultValue("RecoveryQueueVisibilityTimeoutSeconds") should be >= defaultValue(
        "RecoveryWorkerTimeoutSeconds"
      )
    }

    "parks exhausted records and recovery messages in dead letter queues" in {
      resource("OnFailureQueue")
        .downField("RedrivePolicy")
        .downField("deadLetterTargetArn")
        .downField("Fn::GetAtt")
        .as[List[String]]
        .toOption
        .get shouldBe List("OnFailureDlq", "Arn")
      resource("RecoveryQueue")
        .downField("RedrivePolicy")
        .downField("deadLetterTargetArn")
        .downField("Fn::GetAtt")
        .as[List[String]]
        .toOption
        .get shouldBe List("RecoveryDlq", "Arn")
    }

    "wires the recovery queue to the notifier and the worker" in {
      resource("RmuFunction")
        .downField("Environment")
        .downField("Variables")
        .downField("RECOVERY_QUEUE_URL")
        .downField("Ref")
        .as[String]
        .toOption shouldBe Some("RecoveryQueue")
      resource("RecoveryWorkerEventSourceMapping")
        .downField("FunctionResponseTypes")
        .as[List[String]]
        .toOption
        .get shouldBe List(ReportBatchItemFailures)
      resource("RecoveryWorkerEventSourceMapping").downField("BatchSize").as[Int].toOption shouldBe Some(1)
    }

    "names handlers that exist" in {
      val readModelUpdater = resource("RmuFunction").downField("Handler").as[String].toOption.get
      readModelUpdater shouldBe s"${classOf[LambdaReadModelUpdater].getName}::handleRequest"
      classOf[LambdaReadModelUpdater].getMethod(
        "handleRequest",
        classOf[DynamodbEvent],
        classOf[Context]
      )

      val recoveryWorker = resource("RecoveryWorkerFunction").downField("Handler").as[String].toOption.get
      recoveryWorker shouldBe s"${classOf[LambdaRecoveryWorker].getName}::handleRequest"
      classOf[LambdaRecoveryWorker].getMethod("handleRequest", classOf[SQSEvent], classOf[Context])

      val resyncWorker = resource("ResyncWorkerFunction").downField("Handler").as[String].toOption.get
      resyncWorker shouldBe s"${classOf[LambdaResyncWorker].getName}::handleRequest"
      classOf[LambdaResyncWorker].getMethod("handleRequest", classOf[Object], classOf[Context])
    }

    "keeps the environment retry defaults in step with the runtime policy" in {
      defaultValue("RetryMaxAttempts") shouldBe RetryPolicy.DefaultMaxAttempts.toLong
      defaultValue("RetryMaxElapsedMillis") shouldBe RetryPolicy.DefaultMaxElapsed.toMillis
      defaultValue("RetryInitialDelayMillis") shouldBe RetryPolicy.DefaultInitialDelay.toMillis
      defaultValue("RetryMaxDelayMillis") shouldBe RetryPolicy.DefaultMaxDelay.toMillis
    }

    "schedules periodic recovery and resync" in {
      resource("RecoveryWorkerSchedule")
        .downField("Targets")
        .downArray
        .downField("Arn")
        .downField("Fn::GetAtt")
        .downArray
        .as[String]
        .toOption shouldBe Some("RecoveryWorkerFunction")
      resource("ResyncWorkerSchedule")
        .downField("Targets")
        .downArray
        .downField("Arn")
        .downField("Fn::GetAtt")
        .downArray
        .as[String]
        .toOption shouldBe Some("ResyncWorkerFunction")
      resource("RecoveryWorkerSchedule").downField("State").as[String].toOption shouldBe Some("ENABLED")
      resource("ResyncWorkerSchedule").downField("State").as[String].toOption shouldBe Some("ENABLED")
    }

    "keeps the recovery task budget positive" in {
      parameters.downField("RecoveryMaxTasksPerRun").downField("MinValue").as[Int].toOption.get should be >= 1
    }
  }

  private def repositoryFile(relative: String): Path = {
    var current = Paths.get("").toAbsolutePath
    while (current != null && !Files.isRegularFile(current.resolve("build.sbt"))) {
      current = current.getParent
    }
    if (current == null) {
      throw new IllegalStateException(
        s"repository root not found from ${Paths.get("").toAbsolutePath}"
      )
    }
    current.resolve(relative)
  }
}
