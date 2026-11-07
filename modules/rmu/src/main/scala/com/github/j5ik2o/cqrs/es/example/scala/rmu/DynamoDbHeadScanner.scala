package com.github.j5ik2o.cqrs.es.example.scala.rmu

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Scans the head table with strong consistency and reads the store identifier from the `__config__` item.
  *
  * The store identifier is needed by a recovery task so a task cannot be replayed against another store's journal.
  */
final class DynamoDbHeadScanner(
    client: DynamoDbClient,
    tableName: String,
    pageLimit: Option[Int] = None
) extends HeadScanner
    with StoreIdProvider {

  import DynamoDbHeadPageSource.ConfigAggregateId

  private val pagedScanner = new PagedHeadScanner(new DynamoDbHeadPageSource(client, tableName, pageLimit))

  @volatile private var cachedStoreId = Option.empty[String]

  override def scanHeads(visitor: Head => Unit): Try[Unit] = pagedScanner.scanHeads(visitor)

  override def storeId(): Try[String] = {
    cachedStoreId match {
      case Some(value) => Try(value)
      case None =>
        Try {
          val response = client.getItem(
            GetItemRequest
              .builder()
              .tableName(tableName)
              .consistentRead(true)
              .key(Map("aid" -> AttributeValue.fromS(ConfigAggregateId)).asJava)
              .build()
          )
          val value = Option(response.item())
            .flatMap(item => Option(item.get("store_id")))
            .flatMap(attribute => Option(attribute.s()))
            .getOrElse(
              throw new ReadModelUpdateException(
                s"head configuration item $ConfigAggregateId has no store_id"
              )
            )
          cachedStoreId = Some(value)
          value
        }
    }
  }
}
