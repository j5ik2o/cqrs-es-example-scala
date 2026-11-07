package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.DynamoDbSettings
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig

final case class RmuConfig(
    dynamoDb: DynamoDbSettings,
    readModel: ReadModelDatabaseConfig,
    maxItemCount: Int,
    pollIntervalMillis: Long
)

object RmuConfig {

  final val DefaultMaxItemCount       = 100
  final val DefaultPollIntervalMillis = 1000L
  final val DefaultRegion             = "ap-northeast-1"
  final val DefaultJournalTableName   = "cqrs-es-example-journal"
  final val DefaultSnapshotTableName  = "cqrs-es-example-snapshot"
  final val DefaultHeadTableName      = "cqrs-es-example-head"
  final val DefaultSnapshotIndexName  = "snapshot-active-history-index"

  def fromEnv(env: String => Option[String] = sys.env.get): Either[String, RmuConfig] =
    for {
      maxItemCount <- parsePositiveInt("STREAM_MAX_ITEM_COUNT", env, DefaultMaxItemCount)
      pollIntervalMillis <- parseNonNegativeLong("STREAM_POLL_INTERVAL_MILLIS", env, DefaultPollIntervalMillis)
      readModel <- ReadApiConfig.parseReadModel(env)
    } yield RmuConfig(
      dynamoDb = DynamoDbSettings(
        journalTableName = env("JOURNAL_TABLE_NAME").getOrElse(DefaultJournalTableName),
        snapshotTableName = env("SNAPSHOT_TABLE_NAME").getOrElse(DefaultSnapshotTableName),
        headTableName = env("HEAD_TABLE_NAME").getOrElse(DefaultHeadTableName),
        snapshotIndexName = env("SNAPSHOT_ACTIVE_HISTORY_INDEX_NAME").getOrElse(DefaultSnapshotIndexName),
        region = env("AWS_REGION").getOrElse(DefaultRegion),
        endpointUrl = env("AWS_DYNAMODB_ENDPOINT_URL").orElse(env("AWS_ENDPOINT_URL"))
      ),
      readModel = readModel,
      maxItemCount = maxItemCount,
      pollIntervalMillis = pollIntervalMillis
    )

  private def parsePositiveInt(
      name: String,
      env: String => Option[String],
      default: Int
  ): Either[String, Int] =
    env(name) match {
      case None => Right(default)
      case Some(value) =>
        value.toIntOption.filter(_ > 0).toRight(s"invalid $name: $value")
    }

  private def parseNonNegativeLong(
      name: String,
      env: String => Option[String],
      default: Long
  ): Either[String, Long] =
    env(name) match {
      case None => Right(default)
      case Some(value) =>
        value.toLongOption.filter(_ >= 0L).toRight(s"invalid $name: $value")
    }
}
