package com.github.j5ik2o.cqrs.es.example.scala.rmu

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ScanRequest

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Scans one page of the head table with strong consistency for DY-15 resynchronisation.
  *
  * The `__config__` item is filtered out of the scan. Heads of aggregate types that this projection does not own are
  * skipped before their optional fields are parsed.
  */
final class DynamoDbHeadPageSource(
    client: DynamoDbClient,
    tableName: String,
    pageLimit: Option[Int] = None
) extends HeadPageSource {

  import DynamoDbHeadPageSource.ConfigAggregateId

  override def scanPage(exclusiveStartKey: Option[Map[String, AttributeValue]]): Try[HeadPage] =
    Try {
      val response = client.scan(scanRequest(exclusiveStartKey))
      val heads = response
        .items()
        .asScala
        .flatMap(item => toHead(item.asScala.toMap))
        .toVector
      HeadPage(heads, Option(response.lastEvaluatedKey()).filter(!_.isEmpty).map(_.asScala.toMap))
    }

  private[rmu] def scanRequest(exclusiveStartKey: Option[Map[String, AttributeValue]]): ScanRequest = {
    val builder = ScanRequest
      .builder()
      .tableName(tableName)
      .consistentRead(true)
      .projectionExpression("aid, seq_nr, type_name")
      .filterExpression("aid <> :config")
      .expressionAttributeValues(Map(":config" -> AttributeValue.fromS(ConfigAggregateId)).asJava)
    pageLimit.foreach(limit => builder.limit(limit))
    exclusiveStartKey.foreach(key => builder.exclusiveStartKey(key.asJava))
    builder.build()
  }

  private def toHead(item: Map[String, AttributeValue]): Option[Head] = {
    val aggregateId = item.get("aid").flatMap(value => Option(value.s())).getOrElse("")
    if (aggregateId.isEmpty || aggregateId == ConfigAggregateId || !AggregateType.isGroupChat(aggregateId)) {
      None
    } else {
      val typeName = requiredString(item, "type_name")
      val seqNr    = requiredLong(item, "seq_nr")
      Some(Head(aggregateId, typeName, seqNr))
    }
  }

  private def requiredString(item: Map[String, AttributeValue], name: String): String =
    item
      .get(name).flatMap(value => Option(value.s())).getOrElse(
        throw new ReadModelUpdateException(s"head item has no $name string")
      )

  private def requiredLong(item: Map[String, AttributeValue], name: String): Long =
    item
      .get(name)
      .flatMap(value => Option(value.n()))
      .map(_.toLong)
      .getOrElse(throw new ReadModelUpdateException(s"head item has no $name number"))
}

object DynamoDbHeadPageSource {
  final val ConfigAggregateId: String = HeadStreamDecoder.ConfigAid
}
