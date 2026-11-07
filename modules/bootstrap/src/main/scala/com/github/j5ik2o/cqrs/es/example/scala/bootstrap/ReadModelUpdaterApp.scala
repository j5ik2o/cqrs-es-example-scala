package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreFactory
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.JdbcReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Backoff
import com.github.j5ik2o.cqrs.es.example.scala.rmu.DynamoDbHeadScanner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.DynamoDbHeadStreamClient
import com.github.j5ik2o.cqrs.es.example.scala.rmu.EventStoreJournalEventReader
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamPoller
import com.github.j5ik2o.cqrs.es.example.scala.rmu.JdbcRecoveryTaskStore
import com.github.j5ik2o.cqrs.es.example.scala.rmu.LocalRmuRunner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelEventApplier
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdater
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryService
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Resyncer
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RetryPolicy
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RunnerSettings
import org.slf4j.LoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import zio.Task
import zio.ZIO
import zio.ZIOAppDefault

import java.net.URI
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.HOURS
import scala.concurrent.duration.MILLISECONDS

object ReadModelUpdaterApp extends ZIOAppDefault {

  private val logger = LoggerFactory.getLogger(getClass)

  override def run: Task[Unit] =
    for {
      config <- ZIO.fromEither(RmuConfig.fromEnv()).mapError(message => new IllegalArgumentException(message))
      _ = logger.info(
        "starting read model updater: headTable={}, maxItemCount={}, retryMaxAttempts={}, " +
        "pollBackoffInitialMillis={}, pollBackoffMaxMillis={}, recoveryIntervalMillis={}",
        config.dynamoDb.headTableName,
        config.maxItemCount.toString,
        config.retryMaxAttempts.toString,
        config.pollBackoffInitialMillis.toString,
        config.pollBackoffMaxMillis.toString,
        config.recoveryIntervalMillis.toString
      )
      clients <- ZIO.attempt(buildClients(config))
      (dynamoDbClient, streamsClient) = clients
      store <- ZIO.attempt(EventStoreFactory.dynamoDb(config.dynamoDb))
      dao = new JdbcReadModelDao(config.readModel)
      applier = new ReadModelEventApplier(dao, new EventStoreJournalEventReader(store))
      headScanner = new DynamoDbHeadScanner(dynamoDbClient, config.dynamoDb.headTableName)
      taskStore = new JdbcRecoveryTaskStore(config.readModel)
      recoveryService = new RecoveryService(taskStore, headScanner, applier)
      retryPolicy = buildRetryPolicy(config)
      updater = new ReadModelUpdater(applier, Some(recoveryService), retryPolicy)
      streamClient = new DynamoDbHeadStreamClient(dynamoDbClient, streamsClient)
      poller = new HeadStreamPoller(
        client = streamClient,
        handler = record => updater.applyRecord(record, GroupChatSerializers.eventSerializer).map(_ => ()),
        maxItemCount = config.maxItemCount
      )
      resyncer = new Resyncer(applier, headScanner, Some(recoveryService))
      runner = new LocalRmuRunner(
        settings = RunnerSettings(
          headTableName = config.dynamoDb.headTableName,
          streamRetention = FiniteDuration(config.streamRetentionHours, HOURS),
          recoveryInterval = FiniteDuration(config.recoveryIntervalMillis, MILLISECONDS),
          recoveryMaxTasksPerRun = config.recoveryMaxTasksPerRun
        ),
        poller = poller,
        resyncer = resyncer,
        recoveryService = recoveryService,
        retryPolicy = retryPolicy,
        pollBackoff = buildPollBackoff(config)
      )
      _ <- ZIO.attempt {
        Runtime.getRuntime.addShutdownHook(
          new Thread(
            () => {
              runner.stop()
              streamsClient.close()
              dynamoDbClient.close()
            },
            "read-model-updater-shutdown"
          )
        )
        runner.run()
      }
    } yield ()

  private def buildRetryPolicy(config: RmuConfig): RetryPolicy =
    new RetryPolicy(
      maxAttempts = config.retryMaxAttempts,
      maxElapsed = FiniteDuration(config.retryMaxElapsedMillis, MILLISECONDS),
      initialDelay = FiniteDuration(config.retryInitialDelayMillis, MILLISECONDS),
      maxDelay = FiniteDuration(config.retryMaxDelayMillis, MILLISECONDS)
    )

  private def buildPollBackoff(config: RmuConfig): Backoff =
    new Backoff(
      FiniteDuration(config.pollBackoffInitialMillis, MILLISECONDS),
      FiniteDuration(config.pollBackoffMaxMillis, MILLISECONDS)
    )

  private def buildClients(config: RmuConfig): (DynamoDbClient, DynamoDbStreamsClient) = {
    val region          = Region.of(config.dynamoDb.region)
    val dynamoDbBuilder = DynamoDbClient.builder().region(region)
    val streamsBuilder  = DynamoDbStreamsClient.builder().region(region)
    config.dynamoDb.endpointUrl.foreach { endpoint =>
      val uri = URI.create(endpoint)
      dynamoDbBuilder.endpointOverride(uri)
      streamsBuilder.endpointOverride(uri)
    }
    (dynamoDbBuilder.build(), streamsBuilder.build())
  }
}
