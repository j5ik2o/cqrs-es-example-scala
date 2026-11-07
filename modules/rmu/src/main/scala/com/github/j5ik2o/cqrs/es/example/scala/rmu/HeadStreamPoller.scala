package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException

import java.time.Clock
import java.time.Instant
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Drives every shard of the head table's stream.
  *
  * The first iterator starts at `TRIM_HORIZON` so a restart replays the retained records; the read model positions drop
  * what was already applied. An expired iterator is refreshed from the last processed sequence number, which is not
  * data loss. A trimmed shard raises [[StreamTrimmedException]] so the caller runs a full resync (DY-15).
  */
final class HeadStreamPoller(
    client: HeadStreamClient,
    handler: Record => Try[Unit],
    maxItemCount: Int,
    maxPagesPerShard: Int = HeadStreamPoller.DefaultMaxPagesPerShard,
    clock: Clock = Clock.systemUTC()
) {

  private val logger              = LoggerFactory.getLogger(getClass)
  private val iterators           = mutable.Map.empty[String, String]
  private val lastSequenceNumbers = mutable.Map.empty[String, String]
  private val completedShards     = mutable.Set.empty[String]

  @volatile private var lastRecord: Option[Instant] = None

  /** The time the last record was read, or None when no record has been read yet. */
  def lastRecordTime: Option[Instant] = lastRecord

  /** Polls each open shard once and returns the number of records handed to the handler. The caller owns the idle or
    * failure backoff between calls.
    */
  def pollOnce(headTableName: String): Try[Int] = synchronized {
    Try {
      val streamArn = client.streamArn(headTableName).get
      val pageSize  = Math.max(1, Math.min(maxItemCount, 1000))
      var processed = 0

      var nextShardId    = Option.empty[String]
      var continueShards = true
      while (continueShards) {
        val shardPage = client.describeShards(streamArn, nextShardId).get
        shardPage.shardIds.foreach { shardId =>
          if (!completedShards.contains(shardId)) {
            var iterator = Option(initialIterator(streamArn, shardId))
            var loops    = 0
            val maxLoops = Math.max(1, maxPagesPerShard) * 2 + 1
            while (iterator.isDefined && loops < maxLoops) {
              loops += 1
              val current = iterator.get
              client.getRecords(streamArn, current, pageSize) match {
                case Failure(error: ExpiredIteratorException) =>
                  logger.warn(
                    s"stream iterator expired; resuming after the last processed sequence number: shardId=$shardId, sequenceNumber=${lastSequenceNumbers
                        .get(shardId)}"
                  )
                  iterator = Some(nextIteratorAfterLastSequence(streamArn, shardId))
                case Failure(error: TrimmedDataAccessException) =>
                  throw new StreamTrimmedException(shardId, error)
                case Failure(error) =>
                  throw error
                case Success(page) =>
                  page.records.zip(page.sequenceNumbers).foreach { case (record, sequenceNumber) =>
                    handler(record).get
                    lastSequenceNumbers.update(shardId, sequenceNumber)
                    processed += 1
                  }
                  if (page.records.nonEmpty) {
                    lastRecord = Some(Instant.now(clock))
                  }
                  page.nextShardIterator match {
                    case Some(next) =>
                      iterators.update(shardId, next)
                      // A busy shard must not starve the other shards or the periodic recovery run. The iterator is
                      // saved, so the next poll continues from here.
                      iterator = if (page.records.isEmpty) None else Some(next)
                    case None =>
                      iterators.remove(shardId)
                      completedShards.add(shardId)
                      iterator = None
                  }
              }
            }
          }
        }
        nextShardId = shardPage.lastEvaluatedShardId
        continueShards = nextShardId.isDefined
      }
      processed
    }
  }

  /** Clears the iterator state before a full resync. */
  def reset(): Unit = synchronized {
    iterators.clear()
    lastSequenceNumbers.clear()
    completedShards.clear()
    lastRecord = None
  }

  private def initialIterator(streamArn: String, shardId: String): String =
    iterators.get(shardId).getOrElse {
      val iterator = client.shardIteratorAtTrimHorizon(streamArn, shardId) match {
        case Success(value)                             => value
        case Failure(error: TrimmedDataAccessException) => throw new StreamTrimmedException(shardId, error)
        case Failure(error)                             => throw error
      }
      iterators.update(shardId, iterator)
      iterator
    }

  private def nextIteratorAfterLastSequence(streamArn: String, shardId: String): String = {
    val iteratorTry = lastSequenceNumbers.get(shardId) match {
      case Some(sequenceNumber) => client.shardIteratorAfterSequenceNumber(streamArn, shardId, sequenceNumber)
      case None                 => client.shardIteratorAtTrimHorizon(streamArn, shardId)
    }
    iteratorTry match {
      case Success(value)                             => value
      case Failure(error: TrimmedDataAccessException) => throw new StreamTrimmedException(shardId, error)
      case Failure(error)                             => throw error
    }
  }
}

object HeadStreamPoller {
  final val DefaultMaxPagesPerShard = 10
}
