package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.DynamoDbSettings
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig

final case class RmuConfig(
    dynamoDb: DynamoDbSettings,
    readModel: ReadModelDatabaseConfig,
    maxItemCount: Int,
    retryMaxAttempts: Int,
    retryMaxElapsedMillis: Long,
    retryInitialDelayMillis: Long,
    retryMaxDelayMillis: Long,
    pollBackoffInitialMillis: Long,
    pollBackoffMaxMillis: Long,
    recoveryIntervalMillis: Long,
    recoveryMaxTasksPerRun: Int,
    streamRetentionHours: Long
)

object RmuConfig {

  final val DefaultMaxItemCount             = 100
  final val DefaultRetryMaxAttempts         = 5
  final val DefaultRetryMaxElapsedMillis    = 30000L
  final val DefaultRetryInitialDelayMillis  = 200L
  final val DefaultRetryMaxDelayMillis      = 5000L
  final val DefaultPollBackoffInitialMillis = 500L
  final val DefaultPollBackoffMaxMillis     = 30000L
  final val DefaultRecoveryIntervalMillis   = 60000L
  final val DefaultRecoveryMaxTasksPerRun   = 50
  final val DefaultStreamRetentionHours     = 24L
  final val DefaultRegion                   = "ap-northeast-1"
  final val DefaultJournalTableName         = "cqrs-es-example-journal"
  final val DefaultSnapshotTableName        = "cqrs-es-example-snapshot"
  final val DefaultHeadTableName            = "cqrs-es-example-head"
  final val DefaultSnapshotIndexName        = "snapshot-active-history-index"

  def fromEnv(env: String => Option[String] = sys.env.get): Either[String, RmuConfig] =
    for {
      maxItemCount <- parsePositiveInt("STREAM_MAX_ITEM_COUNT", env, DefaultMaxItemCount)
      retryMaxAttempts <- parsePositiveInt("STREAM_RETRY_MAX_ATTEMPTS", env, DefaultRetryMaxAttempts)
      retryMaxElapsedMillis <- parseNonNegativeLong(
        "STREAM_RETRY_MAX_ELAPSED_MILLIS",
        env,
        DefaultRetryMaxElapsedMillis
      )
      retryInitialDelayMillis <- parseNonNegativeLong(
        "STREAM_RETRY_INITIAL_DELAY_MILLIS",
        env,
        DefaultRetryInitialDelayMillis
      )
      retryMaxDelayMillis <- parseNonNegativeLong(
        "STREAM_RETRY_MAX_DELAY_MILLIS",
        env,
        DefaultRetryMaxDelayMillis
      )
      pollBackoffInitialMillis <- parsePositiveLong(
        "STREAM_POLL_BACKOFF_INITIAL_MILLIS",
        env,
        DefaultPollBackoffInitialMillis
      )
      pollBackoffMaxMillis <- parsePositiveLong(
        "STREAM_POLL_BACKOFF_MAX_MILLIS",
        env,
        DefaultPollBackoffMaxMillis
      )
      recoveryIntervalMillis <- parsePositiveLong(
        "STREAM_RECOVERY_INTERVAL_MILLIS",
        env,
        DefaultRecoveryIntervalMillis
      )
      recoveryMaxTasksPerRun <- parsePositiveInt(
        "STREAM_RECOVERY_MAX_TASKS_PER_RUN",
        env,
        DefaultRecoveryMaxTasksPerRun
      )
      streamRetentionHours <- parsePositiveLong(
        "STREAM_RETENTION_HOURS",
        env,
        DefaultStreamRetentionHours
      )
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
      retryMaxAttempts = retryMaxAttempts,
      retryMaxElapsedMillis = retryMaxElapsedMillis,
      retryInitialDelayMillis = retryInitialDelayMillis,
      retryMaxDelayMillis = retryMaxDelayMillis,
      pollBackoffInitialMillis = pollBackoffInitialMillis,
      pollBackoffMaxMillis = pollBackoffMaxMillis,
      recoveryIntervalMillis = recoveryIntervalMillis,
      recoveryMaxTasksPerRun = recoveryMaxTasksPerRun,
      streamRetentionHours = streamRetentionHours
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

  private def parsePositiveLong(
      name: String,
      env: String => Option[String],
      default: Long
  ): Either[String, Long] =
    env(name) match {
      case None => Right(default)
      case Some(value) =>
        value.toLongOption.filter(_ > 0).toRight(s"invalid $name: $value")
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
