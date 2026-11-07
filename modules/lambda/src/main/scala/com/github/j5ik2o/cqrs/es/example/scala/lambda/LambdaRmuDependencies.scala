package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreFactory
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.JdbcReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.rmu.DynamoDbHeadScanner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.EventStoreJournalEventReader
import com.github.j5ik2o.cqrs.es.example.scala.rmu.JdbcRecoveryTaskStore
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelEventApplier
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdater
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryService
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Resyncer
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RetryPolicy
import com.github.j5ik2o.cqrs.es.example.scala.rmu.SqsRecoveryNotifier
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.sqs.SqsClient

import java.net.URI
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS

/** Builds the RMU dependencies once per Lambda container.
  *
  * Lambda instantiates a handler with its no-argument constructor and reuses the instance for later invocations, so the
  * clients and the event store are created on a cold start only.
  */
final case class LambdaRmuDependencies(
    updater: ReadModelUpdater,
    recoveryService: RecoveryService,
    resyncer: Resyncer,
    maxRecoveryTasksPerRun: Int
)

object LambdaRmuDependencies {

  def create(env: String => Option[String] = sys.env.get): LambdaRmuDependencies = {
    val config = LambdaRmuConfig.fromEnv(env).fold(message => throw new IllegalArgumentException(message), identity)
    fromConfig(config)
  }

  def fromConfig(config: LambdaRmuConfig): LambdaRmuDependencies = {
    val rmu            = config.rmu
    val dynamoDbClient = buildDynamoDbClient(rmu.dynamoDb.region, rmu.dynamoDb.endpointUrl)
    val store          = EventStoreFactory.dynamoDb(rmu.dynamoDb)
    val dao            = new JdbcReadModelDao(rmu.readModel)
    val applier        = new ReadModelEventApplier(dao, new EventStoreJournalEventReader(store))
    val scanner        = new DynamoDbHeadScanner(dynamoDbClient, rmu.dynamoDb.headTableName)
    val taskStore      = new JdbcRecoveryTaskStore(rmu.readModel)
    val notifier = config.recoveryQueueUrl.map { queueUrl =>
      SqsRecoveryNotifier(buildSqsClient(config.sqsRegion, config.sqsEndpointUrl), queueUrl)
    }
    val recoveryService = new RecoveryService(taskStore, scanner, applier, notifier)
    val retryPolicy = new RetryPolicy(
      maxAttempts = rmu.retryMaxAttempts,
      maxElapsed = FiniteDuration(rmu.retryMaxElapsedMillis, MILLISECONDS),
      initialDelay = FiniteDuration(rmu.retryInitialDelayMillis, MILLISECONDS),
      maxDelay = FiniteDuration(rmu.retryMaxDelayMillis, MILLISECONDS)
    )
    LambdaRmuDependencies(
      updater = new ReadModelUpdater(applier, Some(recoveryService), retryPolicy),
      recoveryService = recoveryService,
      resyncer = new Resyncer(applier, scanner, Some(recoveryService)),
      maxRecoveryTasksPerRun = rmu.recoveryMaxTasksPerRun
    )
  }

  private def buildDynamoDbClient(region: String, endpointUrl: Option[String]): DynamoDbClient = {
    val builder = DynamoDbClient.builder().region(Region.of(region))
    endpointUrl.foreach(endpoint => builder.endpointOverride(URI.create(endpoint)))
    builder.build()
  }

  private def buildSqsClient(region: String, endpointUrl: Option[String]): SqsClient = {
    val builder = SqsClient.builder().region(Region.of(region))
    endpointUrl.foreach(endpoint => builder.endpointOverride(URI.create(endpoint)))
    builder.build()
  }
}
