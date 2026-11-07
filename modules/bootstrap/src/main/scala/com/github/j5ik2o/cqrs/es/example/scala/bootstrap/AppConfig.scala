package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.DynamoDbSettings

enum EventStoreBackend {
  case Memory
  case DynamoDb
}

final case class AppConfig(
    host: String,
    port: Int,
    backend: EventStoreBackend,
    dynamoDb: DynamoDbSettings
)

object AppConfig {

  final val DefaultHost              = "0.0.0.0"
  final val DefaultPort              = 48080
  final val DefaultRegion            = "ap-northeast-1"
  final val DefaultJournalTableName  = "cqrs-es-example-journal"
  final val DefaultSnapshotTableName = "cqrs-es-example-snapshot"
  final val DefaultHeadTableName     = "cqrs-es-example-head"
  final val DefaultSnapshotIndexName = "snapshot-active-history-index"

  def fromEnv(env: String => Option[String] = sys.env.get): Either[String, AppConfig] =
    for {
      port <- parsePort(env("WRITE_API_PORT").getOrElse(DefaultPort.toString))
      backend <- parseBackend(env("EVENT_STORE_BACKEND").getOrElse("dynamodb"))
      endpointUrl = env("AWS_DYNAMODB_ENDPOINT_URL").orElse(env("AWS_ENDPOINT_URL"))
    } yield AppConfig(
      host = env("WRITE_API_HOST").getOrElse(DefaultHost),
      port = port,
      backend = backend,
      dynamoDb = DynamoDbSettings(
        journalTableName = env("JOURNAL_TABLE_NAME").getOrElse(DefaultJournalTableName),
        snapshotTableName = env("SNAPSHOT_TABLE_NAME").getOrElse(DefaultSnapshotTableName),
        headTableName = env("HEAD_TABLE_NAME").getOrElse(DefaultHeadTableName),
        snapshotIndexName = env("SNAPSHOT_ACTIVE_HISTORY_INDEX_NAME").getOrElse(DefaultSnapshotIndexName),
        region = env("AWS_REGION").getOrElse(DefaultRegion),
        endpointUrl = endpointUrl
      )
    )

  private def parsePort(value: String): Either[String, Int] =
    value.toIntOption.filter(port => port > 0 && port <= 65535).toRight(s"invalid port: $value")

  private def parseBackend(value: String): Either[String, EventStoreBackend] =
    value.trim.toLowerCase match {
      case "memory"   => Right(EventStoreBackend.Memory)
      case "dynamodb" => Right(EventStoreBackend.DynamoDb)
      case other      => Left(s"invalid EVENT_STORE_BACKEND: $other")
    }
}
