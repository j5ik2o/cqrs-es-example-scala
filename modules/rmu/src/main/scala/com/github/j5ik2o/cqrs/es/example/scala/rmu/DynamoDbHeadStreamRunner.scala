package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.Shard
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success

/** Polls the head table's DynamoDB Stream and forwards each head transition to [[ReadModelUpdater]].
  *
  * Shard iterators are kept across polls. A new shard starts at `TRIM_HORIZON`, so a restarted updater replays what is
  * still inside the 24 hour stream retention and the position table discards the duplicates. An expired iterator is
  * dropped and recreated; the replay is safe for the same reason.
  */
final class DynamoDbHeadStreamRunner(
    dynamoDbClient: DynamoDbClient,
    streamsClient: DynamoDbStreamsClient,
    headTableName: String,
    updater: ReadModelUpdater,
    maxItemCount: Int,
    pollIntervalMillis: Long
) {

  private val logger    = LoggerFactory.getLogger(getClass)
  private val iterators = mutable.Map.empty[String, String]

  @volatile private var running = true

  def stop(): Unit = running = false

  def run(): Unit =
    while (running) {
      try {
        pollOnce()
      } catch {
        case _: ExpiredIteratorException =>
          logger.warn("the shard iterator expired, restarting the shards from TRIM_HORIZON")
          iterators.clear()
        case _: TrimmedDataAccessException =>
          logger.warn("the stream records were trimmed, restarting the shards from TRIM_HORIZON")
          iterators.clear()
        case error: Throwable =>
          logger.warn("polling the head stream failed; the next poll retries", error)
      }
      if (running) Thread.sleep(pollIntervalMillis)
    }

  private def pollOnce(): Unit = {
    val streamArn   = describeStreamArn()
    val pageSize    = Math.max(1, Math.min(maxItemCount, 1000))
    var nextShardId = Option.empty[String]
    var continue    = true
    while (continue) {
      val requestBuilder = DescribeStreamRequest.builder().streamArn(streamArn)
      nextShardId.foreach(shardId => requestBuilder.exclusiveStartShardId(shardId))
      val description = streamsClient.describeStream(requestBuilder.build()).streamDescription()
      description.shards().asScala.foreach(shard => pollShard(streamArn, shard, pageSize))
      nextShardId = Option(description.lastEvaluatedShardId())
      continue = nextShardId.isDefined
    }
  }

  private def pollShard(streamArn: String, shard: Shard, pageSize: Int): Unit = {
    val shardId  = shard.shardId()
    val iterator = iterators.getOrElse(shardId, initialIterator(streamArn, shardId))
    val response = streamsClient.getRecords(
      GetRecordsRequest.builder().shardIterator(iterator).limit(pageSize).build()
    )
    response.records().asScala.foreach(processRecord)
    Option(response.nextShardIterator()) match {
      case Some(next) => iterators.update(shardId, next)
      case None       => iterators.remove(shardId)
    }
  }

  private def processRecord(record: Record): Unit =
    HeadStreamDecoder.decode(record, GroupChatSerializers.eventSerializer).foreach { transition =>
      updater.applyTransition(transition) match {
        case Success(_) =>
          logger.debug("projected head transition: aggregateId={}, seqNr={}", transition.aggregateId, transition.seqNr)
        case Failure(error) =>
          throw error
      }
    }

  private def initialIterator(streamArn: String, shardId: String): String = {
    val response = streamsClient.getShardIterator(
      GetShardIteratorRequest
        .builder()
        .streamArn(streamArn)
        .shardId(shardId)
        .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
        .build()
    )
    Option(response.shardIterator()).getOrElse(
      throw new IllegalStateException(s"no shard iterator was returned for shard $shardId")
    )
  }

  private def describeStreamArn(): String = {
    val table = dynamoDbClient.describeTable(builder => builder.tableName(headTableName)).table()
    Option(table.latestStreamArn())
      .filter(_.nonEmpty).getOrElse(
        throw new IllegalStateException(s"the head table $headTableName has no stream")
      )
  }
}
