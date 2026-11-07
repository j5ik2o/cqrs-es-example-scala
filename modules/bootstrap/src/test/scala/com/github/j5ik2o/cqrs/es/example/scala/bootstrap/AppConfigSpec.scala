package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyFreeSpec with Matchers {

  "AppConfig" - {
    "uses the documented defaults" in {
      val config = AppConfig.fromEnv(_ => None).toOption.get

      config.host shouldBe "0.0.0.0"
      config.port shouldBe 48080
      config.backend shouldBe EventStoreBackend.DynamoDb
      config.dynamoDb.journalTableName shouldBe "cqrs-es-example-journal"
      config.dynamoDb.snapshotTableName shouldBe "cqrs-es-example-snapshot"
      config.dynamoDb.headTableName shouldBe "cqrs-es-example-head"
      config.dynamoDb.snapshotIndexName shouldBe "snapshot-active-history-index"
      config.dynamoDb.region shouldBe "ap-northeast-1"
      config.dynamoDb.endpointUrl shouldBe None
    }

    "reads the event store settings from the environment" in {
      val env = Map(
        "WRITE_API_HOST"                     -> "127.0.0.1",
        "WRITE_API_PORT"                     -> "12345",
        "EVENT_STORE_BACKEND"                -> "memory",
        "JOURNAL_TABLE_NAME"                 -> "test-journal",
        "SNAPSHOT_TABLE_NAME"                -> "test-snapshot",
        "HEAD_TABLE_NAME"                    -> "test-head",
        "SNAPSHOT_ACTIVE_HISTORY_INDEX_NAME" -> "test-index",
        "AWS_REGION"                         -> "us-east-1",
        "AWS_DYNAMODB_ENDPOINT_URL"          -> "http://localhost:4566"
      )

      val config = AppConfig.fromEnv(env.get).toOption.get

      config.host shouldBe "127.0.0.1"
      config.port shouldBe 12345
      config.backend shouldBe EventStoreBackend.Memory
      config.dynamoDb.journalTableName shouldBe "test-journal"
      config.dynamoDb.snapshotIndexName shouldBe "test-index"
      config.dynamoDb.region shouldBe "us-east-1"
      config.dynamoDb.endpointUrl shouldBe Some("http://localhost:4566")
    }

    "rejects an out-of-range port" in {
      val result = AppConfig.fromEnv(key => if (key == "WRITE_API_PORT") Some("68080") else None)

      result.left.toOption.get should include("invalid port")
    }

    "rejects an unknown backend" in {
      val result = AppConfig.fromEnv(key => if (key == "EVENT_STORE_BACKEND") Some("unknown") else None)

      result.left.toOption.get should include("invalid EVENT_STORE_BACKEND")
    }
  }
}
