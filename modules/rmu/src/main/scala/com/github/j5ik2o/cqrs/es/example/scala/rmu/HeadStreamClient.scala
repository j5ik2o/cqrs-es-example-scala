package com.github.j5ik2o.cqrs.es.example.scala.rmu

import scala.util.Try

/** One page of shard identifiers of a stream. */
final case class ShardPage(shardIds: Vector[String], lastEvaluatedShardId: Option[String])

/** One page of stream records. DynamoDB batch item failures are identified by the stream sequence number. */
final case class StreamRecordPage(
    records: Vector[software.amazon.awssdk.services.dynamodb.model.Record],
    sequenceNumbers: Vector[String],
    nextShardIterator: Option[String]
)

/** The stream operations that the poller needs. Tests replace this with a fake. */
trait HeadStreamClient {

  def streamArn(headTableName: String): Try[String]

  def describeShards(streamArn: String, exclusiveStartShardId: Option[String]): Try[ShardPage]

  def getRecords(streamArn: String, shardIterator: String, limit: Int): Try[StreamRecordPage]

  def shardIteratorAtTrimHorizon(streamArn: String, shardId: String): Try[String]

  def shardIteratorAfterSequenceNumber(streamArn: String, shardId: String, sequenceNumber: String): Try[String]
}
