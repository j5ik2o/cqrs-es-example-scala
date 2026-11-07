package com.github.j5ik2o.cqrs.es.example.scala.rmu

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient

import scala.jdk.CollectionConverters._
import scala.util.Try

/** DynamoDB Streams implementation of [[HeadStreamClient]]. */
final class DynamoDbHeadStreamClient(
    dynamoDbClient: DynamoDbClient,
    streamsClient: DynamoDbStreamsClient
) extends HeadStreamClient {

  override def streamArn(headTableName: String): Try[String] =
    Try {
      val response = dynamoDbClient.describeTable(builder => builder.tableName(headTableName))
      Option(response.table().latestStreamArn())
        .filter(_.nonEmpty)
        .getOrElse(throw new IllegalStateException(s"the head table $headTableName has no stream"))
    }

  override def describeShards(
      streamArn: String,
      exclusiveStartShardId: Option[String]
  ): Try[ShardPage] =
    Try {
      val builder = DescribeStreamRequest.builder().streamArn(streamArn)
      exclusiveStartShardId.foreach(shardId => builder.exclusiveStartShardId(shardId))
      val description = streamsClient.describeStream(builder.build()).streamDescription()
      ShardPage(
        shardIds = description.shards().asScala.map(_.shardId()).toVector,
        lastEvaluatedShardId = Option(description.lastEvaluatedShardId())
      )
    }

  override def getRecords(
      streamArn: String,
      shardIterator: String,
      limit: Int
  ): Try[StreamRecordPage] =
    Try {
      val response = streamsClient.getRecords(
        GetRecordsRequest.builder().shardIterator(shardIterator).limit(limit).build()
      )
      val records = response.records().asScala.toVector
      StreamRecordPage(
        records = records,
        sequenceNumbers = records.map(record => record.dynamodb().sequenceNumber()),
        nextShardIterator = Option(response.nextShardIterator())
      )
    }

  override def shardIteratorAtTrimHorizon(streamArn: String, shardId: String): Try[String] =
    shardIterator(streamArn, shardId, ShardIteratorType.TRIM_HORIZON, None)

  override def shardIteratorAfterSequenceNumber(
      streamArn: String,
      shardId: String,
      sequenceNumber: String
  ): Try[String] =
    shardIterator(streamArn, shardId, ShardIteratorType.AFTER_SEQUENCE_NUMBER, Some(sequenceNumber))

  private def shardIterator(
      streamArn: String,
      shardId: String,
      iteratorType: ShardIteratorType,
      sequenceNumber: Option[String]
  ): Try[String] =
    Try {
      val builder = GetShardIteratorRequest
        .builder()
        .streamArn(streamArn)
        .shardId(shardId)
        .shardIteratorType(iteratorType)
      sequenceNumber.foreach(value => builder.sequenceNumber(value))
      Option(streamsClient.getShardIterator(builder.build()).shardIterator())
        .getOrElse(throw new IllegalStateException(s"no shard iterator was returned for shard $shardId"))
    }
}
