package com.github.j5ik2o.cqrs.es.example.scala.lambda

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class LambdaRmuConfigSpec extends AnyFreeSpec with Matchers {

  "LambdaRmuConfig" - {
    "uses the documented defaults" in {
      val config = LambdaRmuConfig.fromEnv(_ => None).toOption.get

      config.rmu.dynamoDb.headTableName shouldBe "cqrs-es-example-head"
      config.rmu.retryMaxAttempts shouldBe 5
      config.rmu.recoveryMaxTasksPerRun shouldBe 50
      config.recoveryQueueUrl shouldBe None
      config.sqsRegion shouldBe "ap-northeast-1"
      config.sqsEndpointUrl shouldBe None
    }

    "reads the recovery queue and SQS settings from the environment" in {
      val env = Map(
        "RECOVERY_QUEUE_URL"        -> "https://sqs.ap-northeast-1.amazonaws.com/123/recovery",
        "RECOVERY_SQS_REGION"       -> "us-east-1",
        "RECOVERY_SQS_ENDPOINT_URL" -> "http://localhost:4566"
      )

      val config = LambdaRmuConfig.fromEnv(env.get).toOption.get

      config.recoveryQueueUrl shouldBe Some("https://sqs.ap-northeast-1.amazonaws.com/123/recovery")
      config.sqsRegion shouldBe "us-east-1"
      config.sqsEndpointUrl shouldBe Some("http://localhost:4566")
    }

    "falls back to the shared endpoint for SQS" in {
      val config =
        LambdaRmuConfig.fromEnv(k => if (k == "AWS_ENDPOINT_URL") Some("http://localstack:4566") else None).toOption.get

      config.sqsEndpointUrl shouldBe Some("http://localstack:4566")
    }

    "rejects an invalid retry setting" in {
      val result = LambdaRmuConfig.fromEnv(k => if (k == "STREAM_RETRY_MAX_ATTEMPTS") Some("0") else None)

      result.left.toOption.get should include("STREAM_RETRY_MAX_ATTEMPTS")
    }
  }
}
