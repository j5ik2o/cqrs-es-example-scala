package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException

import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class HeadStreamPollerSpec extends AnyFreeSpec with Matchers {

  private final class FakeStreamClient(
      shards: Vector[String],
      pages: mutable.Map[String, mutable.Queue[Try[StreamRecordPage]]],
      trimHorizonFailure: Option[Throwable] = None
  ) extends HeadStreamClient {

    val afterSequenceCalls: mutable.Buffer[(String, String)] = mutable.Buffer.empty

    override def streamArn(headTableName: String): Try[String] = Success("arn:stream")

    override def describeShards(streamArn: String, exclusiveStartShardId: Option[String]): Try[ShardPage] =
      Success(ShardPage(shards, None))

    override def getRecords(streamArn: String, shardIterator: String, limit: Int): Try[StreamRecordPage] =
      pages.get(shardIterator) match {
        case Some(queue) if queue.nonEmpty => queue.dequeue()
        case _                             => Failure(new IllegalStateException(s"unexpected iterator $shardIterator"))
      }

    override def shardIteratorAtTrimHorizon(streamArn: String, shardId: String): Try[String] =
      trimHorizonFailure.map(Failure(_)).getOrElse(Success(s"trim-$shardId"))

    override def shardIteratorAfterSequenceNumber(
        streamArn: String,
        shardId: String,
        sequenceNumber: String
    ): Try[String] = {
      afterSequenceCalls += (shardId -> sequenceNumber)
      Success(s"after-$sequenceNumber")
    }
  }

  private def record(sequenceNumber: String): Record =
    Record
      .builder()
      .eventID(s"event-$sequenceNumber")
      .dynamodb(StreamRecord.builder().sequenceNumber(sequenceNumber).build())
      .build()

  private def page(
      records: Vector[Record],
      nextShardIterator: Option[String]
  ): StreamRecordPage =
    StreamRecordPage(
      records = records,
      sequenceNumbers = records.map(_.dynamodb().sequenceNumber()),
      nextShardIterator = nextShardIterator
    )

  private def enqueue(
      pages: mutable.Map[String, mutable.Queue[Try[StreamRecordPage]]],
      iterator: String,
      value: StreamRecordPage
  ): Unit =
    pages.getOrElseUpdate(iterator, mutable.Queue.empty).enqueue(Success(value))

  private def enqueueFailure(
      pages: mutable.Map[String, mutable.Queue[Try[StreamRecordPage]]],
      iterator: String,
      failure: Throwable
  ): Unit =
    pages.getOrElseUpdate(iterator, mutable.Queue.empty).enqueue(Failure(failure))

  "the head stream poller" - {
    "polls every page of every shard" in {
      val pages = mutable.Map.empty[String, mutable.Queue[Try[StreamRecordPage]]]
      enqueue(pages, "trim-s1", page(Vector(record("1"), record("2")), Some("s1-p2")))
      enqueue(pages, "s1-p2", page(Vector(record("3")), None))
      enqueue(pages, "trim-s2", page(Vector(record("10")), Some("s2-p2")))
      enqueue(pages, "s2-p2", page(Vector.empty, Some("s2-p3")))
      val client  = new FakeStreamClient(Vector("s1", "s2"), pages)
      val visited = mutable.Buffer.empty[String]
      val poller =
        new HeadStreamPoller(client, value => { visited += value.dynamodb().sequenceNumber(); Success(()) }, 10)

      poller.pollOnce("head") shouldBe Success(4)

      visited.toVector shouldBe Vector("1", "2", "3", "10")
      poller.lastPollTime.isDefined shouldBe true
    }

    "advances the poll time on an empty page so an idle stream is not mistaken for a stopped reader" in {
      val pages = mutable.Map.empty[String, mutable.Queue[Try[StreamRecordPage]]]
      enqueue(pages, "trim-s1", page(Vector.empty, Some("s1-p2")))
      val client = new FakeStreamClient(Vector("s1"), pages)
      val poller = new HeadStreamPoller(client, _ => Success(()), 10)

      poller.pollOnce("head") shouldBe Success(0)
      poller.lastPollTime.isDefined shouldBe true

      poller.reset()
      poller.lastPollTime shouldBe None
    }

    "resumes after the last processed sequence number when the iterator expires" in {
      val pages = mutable.Map.empty[String, mutable.Queue[Try[StreamRecordPage]]]
      enqueue(pages, "trim-s1", page(Vector(record("1")), Some("s1-p2")))
      enqueueFailure(pages, "s1-p2", ExpiredIteratorException.builder().message("expired").build())
      enqueue(pages, "after-1", page(Vector.empty, Some("s1-p3")))
      val client = new FakeStreamClient(Vector("s1"), pages)
      val poller = new HeadStreamPoller(client, _ => Success(()), 10)

      poller.pollOnce("head") shouldBe Success(1)

      client.afterSequenceCalls.toVector shouldBe Vector("s1" -> "1")
    }

    "raises a trimmed stream when the records were trimmed" in {
      val pages = mutable.Map.empty[String, mutable.Queue[Try[StreamRecordPage]]]
      enqueueFailure(pages, "trim-s1", TrimmedDataAccessException.builder().message("trimmed").build())
      val client = new FakeStreamClient(Vector("s1"), pages)
      val poller = new HeadStreamPoller(client, _ => Success(()), 10)

      val result = poller.pollOnce("head")

      result.failed.get shouldBe a[StreamTrimmedException]
      result.failed.get.asInstanceOf[StreamTrimmedException].shardId shouldBe "s1"
    }

    "raises a trimmed stream when the initial iterator was trimmed" in {
      val client = new FakeStreamClient(
        Vector("s1"),
        mutable.Map.empty,
        Some(TrimmedDataAccessException.builder().message("trimmed").build())
      )
      val poller = new HeadStreamPoller(client, _ => Success(()), 10)

      poller.pollOnce("head").failed.get shouldBe a[StreamTrimmedException]
    }

    "does not save the next iterator when the handler fails and replays the page" in {
      val pages = mutable.Map.empty[String, mutable.Queue[Try[StreamRecordPage]]]
      enqueue(pages, "trim-s1", page(Vector(record("1"), record("2")), Some("s1-p2")))
      val client                 = new FakeStreamClient(Vector("s1"), pages)
      val visited                = mutable.Buffer.empty[String]
      var failOn: Option[String] = Some("2")
      val poller = new HeadStreamPoller(
        client,
        value => {
          val sequenceNumber = value.dynamodb().sequenceNumber()
          visited += sequenceNumber
          if (failOn.contains(sequenceNumber)) Failure(new IllegalStateException("injected")) else Success(())
        },
        10
      )

      poller.pollOnce("head").isFailure shouldBe true

      failOn = None
      enqueue(pages, "trim-s1", page(Vector(record("1"), record("2")), None))
      poller.pollOnce("head") shouldBe Success(2)

      visited.toVector shouldBe Vector("1", "2", "1", "2")
    }

    "does not poll a completed shard again" in {
      val pages = mutable.Map.empty[String, mutable.Queue[Try[StreamRecordPage]]]
      enqueue(pages, "trim-s1", page(Vector(record("1")), None))
      val client = new FakeStreamClient(Vector("s1"), pages)
      val poller = new HeadStreamPoller(client, _ => Success(()), 10)

      poller.pollOnce("head") shouldBe Success(1)
      poller.pollOnce("head") shouldBe Success(0)
    }
  }
}
