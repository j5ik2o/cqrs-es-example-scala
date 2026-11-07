package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ReadApiConfigSpec extends AnyFreeSpec with Matchers {

  "ReadApiConfig" - {
    "uses the documented defaults" in {
      val config = ReadApiConfig.fromEnv(_ => None).toOption.get

      config.host shouldBe "0.0.0.0"
      config.port shouldBe 48082
      config.readModel.url should include("jdbc:mysql://localhost:3306/cqrs_es_example")
      config.readModel.user shouldBe "root"
    }

    "reads the read api and database settings from the environment" in {
      val env = Map(
        "READ_API_HOST"                -> "127.0.0.1",
        "READ_API_PORT"                -> "18082",
        "READ_MODEL_DATABASE_URL"      -> "jdbc:mysql://mysql:3306/read_model",
        "READ_MODEL_DATABASE_USER"     -> "app",
        "READ_MODEL_DATABASE_PASSWORD" -> "secret"
      )

      val config = ReadApiConfig.fromEnv(env.get).toOption.get

      config.host shouldBe "127.0.0.1"
      config.port shouldBe 18082
      config.readModel.url shouldBe "jdbc:mysql://mysql:3306/read_model"
      config.readModel.user shouldBe "app"
      config.readModel.password shouldBe "secret"
    }

    "rejects an out-of-range port" in {
      ReadApiConfig
        .fromEnv(key => if (key == "READ_API_PORT") Some("70000") else None).left.toOption.get should include(
        "invalid port"
      )
    }
  }

  "RmuConfig" - {
    "uses the documented defaults" in {
      val config = RmuConfig.fromEnv(_ => None).toOption.get

      config.dynamoDb.headTableName shouldBe "cqrs-es-example-head"
      config.dynamoDb.journalTableName shouldBe "cqrs-es-example-journal"
      config.dynamoDb.endpointUrl shouldBe None
      config.maxItemCount shouldBe 100
      config.retryMaxAttempts shouldBe 5
      config.retryMaxElapsedMillis shouldBe 30000L
      config.pollBackoffInitialMillis shouldBe 500L
      config.pollBackoffMaxMillis shouldBe 30000L
      config.recoveryIntervalMillis shouldBe 60000L
      config.recoveryMaxTasksPerRun shouldBe 50
      config.streamRetentionHours shouldBe 24L
    }

    "reads the stream settings from the environment" in {
      val env = Map(
        "HEAD_TABLE_NAME"                    -> "test-head",
        "AWS_REGION"                         -> "us-east-1",
        "AWS_DYNAMODB_ENDPOINT_URL"          -> "http://localhost:4566",
        "STREAM_MAX_ITEM_COUNT"              -> "25",
        "STREAM_RETRY_MAX_ATTEMPTS"          -> "7",
        "STREAM_RETRY_MAX_ELAPSED_MILLIS"    -> "15000",
        "STREAM_POLL_BACKOFF_INITIAL_MILLIS" -> "250",
        "STREAM_POLL_BACKOFF_MAX_MILLIS"     -> "4000",
        "STREAM_RECOVERY_INTERVAL_MILLIS"    -> "120000",
        "STREAM_RECOVERY_MAX_TASKS_PER_RUN"  -> "10",
        "STREAM_RETENTION_HOURS"             -> "48"
      )

      val config = RmuConfig.fromEnv(env.get).toOption.get

      config.dynamoDb.headTableName shouldBe "test-head"
      config.dynamoDb.region shouldBe "us-east-1"
      config.dynamoDb.endpointUrl shouldBe Some("http://localhost:4566")
      config.maxItemCount shouldBe 25
      config.retryMaxAttempts shouldBe 7
      config.retryMaxElapsedMillis shouldBe 15000L
      config.pollBackoffInitialMillis shouldBe 250L
      config.pollBackoffMaxMillis shouldBe 4000L
      config.recoveryIntervalMillis shouldBe 120000L
      config.recoveryMaxTasksPerRun shouldBe 10
      config.streamRetentionHours shouldBe 48L
    }

    "rejects a non-positive max item count" in {
      RmuConfig
        .fromEnv(key => if (key == "STREAM_MAX_ITEM_COUNT") Some("0") else None)
        .left
        .toOption
        .get should include("STREAM_MAX_ITEM_COUNT")
    }
  }
}
