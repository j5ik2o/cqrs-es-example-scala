package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreFactory
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.JdbcReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.rmu.DynamoDbHeadStreamRunner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.EventStoreJournalEventReader
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdater
import org.slf4j.LoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import zio.Task
import zio.ZIO
import zio.ZIOAppDefault

import java.net.URI

object ReadModelUpdaterApp extends ZIOAppDefault {

  private val logger = LoggerFactory.getLogger(getClass)

  override def run: Task[Unit] =
    for {
      config <- ZIO.fromEither(RmuConfig.fromEnv()).mapError(message => new IllegalArgumentException(message))
      _ = logger.info(
        "starting read model updater: headTable={}, maxItemCount={}, pollIntervalMillis={}",
        config.dynamoDb.headTableName,
        config.maxItemCount.toString,
        config.pollIntervalMillis.toString
      )
      clients <- ZIO.attempt(buildClients(config))
      (dynamoDbClient, streamsClient) = clients
      store <- ZIO.attempt(EventStoreFactory.dynamoDb(config.dynamoDb))
      dao = new JdbcReadModelDao(config.readModel)
      updater = new ReadModelUpdater(dao, new EventStoreJournalEventReader(store))
      runner = new DynamoDbHeadStreamRunner(
        dynamoDbClient = dynamoDbClient,
        streamsClient = streamsClient,
        headTableName = config.dynamoDb.headTableName,
        updater = updater,
        maxItemCount = config.maxItemCount,
        pollIntervalMillis = config.pollIntervalMillis
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
