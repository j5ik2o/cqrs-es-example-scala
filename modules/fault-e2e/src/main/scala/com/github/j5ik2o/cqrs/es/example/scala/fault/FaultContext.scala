package com.github.j5ik2o.cqrs.es.example.scala.fault

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreFactory
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreGroupChatRepository
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.JdbcReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig
import com.github.j5ik2o.cqrs.es.example.scala.rmu.DynamoDbHeadScanner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.EventStoreJournalEventReader
import com.github.j5ik2o.cqrs.es.example.scala.rmu.JdbcRecoveryTaskStore
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RmuConfig
import com.github.j5ik2o.event.store.adapter.scala.EventStore
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient

import java.net.URI
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.SECONDS
import scala.util.Try

/** Everything the fault scenarios share: the real DynamoDB event store, the real MySQL read model and the deadlines. */
final class FaultContext(
    val config: RmuConfig,
    val dynamoDbClient: DynamoDbClient,
    val streamsClient: DynamoDbStreamsClient,
    val store: EventStore[GroupChatEvent, GroupChat],
    val timeout: FiniteDuration,
    val pollInterval: FiniteDuration
) {

  val readModel: ReadModelDatabaseConfig        = config.readModel
  val repository: EventStoreGroupChatRepository = EventStoreGroupChatRepository(store)
  val dao: JdbcReadModelDao                     = new JdbcReadModelDao(readModel)
  val journal: EventStoreJournalEventReader     = new EventStoreJournalEventReader(store)
  val headScanner: DynamoDbHeadScanner          = new DynamoDbHeadScanner(dynamoDbClient, config.dynamoDb.headTableName)
  val taskStore: JdbcRecoveryTaskStore          = new JdbcRecoveryTaskStore(readModel)
  val fixtures: FaultFixtures =
    new FaultFixtures(repository, journal, FaultContext.Owner, FaultContext.Member)

  /** Waits until LocalStack, the tables and the migrated MySQL schema answer. The conditions are read-only. */
  def awaitReady(): Unit = {
    E2eSupport.awaitUntil("the DynamoDB head table stream", timeout, pollInterval)(
      Try(
        dynamoDbClient
          .describeTable(builder => builder.tableName(config.dynamoDb.headTableName))
          .table()
          .latestStreamArn()
      ).toOption.exists(arn => arn != null && arn.nonEmpty),
      "the head table or its stream is not ready"
    )
    E2eSupport.awaitUntil("the MySQL read model schema", timeout, pollInterval)(
      dao.lastProcessedSeqNr(FaultContext.ReadyAggregateId).isSuccess && taskStore.listPending().isSuccess,
      "the read model database is not answering"
    )
    E2eSupport.awaitUntil("the DynamoDB store configuration item", timeout, pollInterval)(
      headScanner.storeId().isSuccess,
      "the __config__ item is missing or has no store_id"
    )
  }

  def close(): Unit = {
    Try(streamsClient.close())
    Try(dynamoDbClient.close())
    ()
  }
}

object FaultContext {

  val Owner: UserAccountId  = UserAccountId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get
  val Member: UserAccountId = UserAccountId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAW").toOption.get

  /** A read-only probe. It never creates a position row. */
  val ReadyAggregateId: String = "__fault_e2e_ready__"

  def fromEnv(): FaultContext = {
    val config = RmuConfig.fromEnv().fold(message => throw new IllegalArgumentException(message), identity)
    val region = Region.of(config.dynamoDb.region)

    val dynamoDbBuilder = DynamoDbClient.builder().region(region)
    val streamsBuilder  = DynamoDbStreamsClient.builder().region(region)
    config.dynamoDb.endpointUrl.foreach { endpoint =>
      val uri = URI.create(endpoint)
      dynamoDbBuilder.endpointOverride(uri)
      streamsBuilder.endpointOverride(uri)
    }

    val dynamoDbClient = dynamoDbBuilder.build()
    val streamsClient  = streamsBuilder.build()
    val store          = EventStoreFactory.dynamoDb(config.dynamoDb)
    val timeout        = FiniteDuration(envLong("FAULT_E2E_SCENARIO_TIMEOUT_SECONDS", 120L), SECONDS)
    val pollInterval   = FiniteDuration(envLong("FAULT_E2E_POLL_INTERVAL_MILLIS", 200L), MILLISECONDS)

    new FaultContext(config, dynamoDbClient, streamsClient, store, timeout, pollInterval)
  }

  private def envLong(name: String, default: Long): Long =
    sys.env.get(name).flatMap(_.toLongOption).filter(_ > 0L).getOrElse(default)
}
