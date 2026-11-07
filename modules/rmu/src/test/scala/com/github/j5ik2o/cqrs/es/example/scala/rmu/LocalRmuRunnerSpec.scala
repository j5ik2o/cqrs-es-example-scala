package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.InMemoryReadModelDao
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.HOURS
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.NANOSECONDS
import scala.concurrent.duration.SECONDS
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class LocalRmuRunnerSpec extends AnyFreeSpec with Matchers {

  private val baseTime = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner       = userAccountId('V')
  private val member      = userAccountId('W')
  private val groupChatId = GroupChatId.newId
  private val aggregateId = groupChatId.asString
  private val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)
  private val addedMember = Member(MemberId.newId, member, MemberRole.Member)

  private def journalRecord(seqNr: Long, event: GroupChatEvent): HeadEventRecord =
    HeadEventRecord(seqNr, baseTime.plusSeconds(seqNr), event.manifest, event)

  private def epochNanos(instant: Instant): Long =
    instant.getEpochSecond * 1_000_000_000L + instant.getNano

  /** Builds the head table item image that the event store writes, so the test exercises the real stream decoder. */
  private def headStreamRecord(seqNr: Long, event: GroupChatEvent): Record = {
    val occurredAt = baseTime.plusSeconds(seqNr)
    val eventImage = Map(
      "seq_nr"      -> AttributeValue.fromN(seqNr.toString),
      "occurred_at" -> AttributeValue.fromN(epochNanos(occurredAt).toString),
      "manifest"    -> AttributeValue.fromS(event.manifest),
      "payload" -> AttributeValue.fromB(
        SdkBytes.fromByteArray(GroupChatSerializers.eventSerializer.serialize(event))
      )
    ).asJava
    val headImage = Map(
      "aid"       -> AttributeValue.fromS(aggregateId),
      "seq_nr"    -> AttributeValue.fromN(seqNr.toString),
      "type_name" -> AttributeValue.fromS("GroupChat"),
      "events"    -> AttributeValue.fromL(java.util.List.of(AttributeValue.fromM(eventImage)))
    ).asJava
    Record
      .builder()
      .eventName(OperationType.INSERT)
      .dynamodb(StreamRecord.builder().newImage(headImage).build())
      .build()
  }

  private def streamPage(records: Vector[Record], nextShardIterator: Option[String]): StreamRecordPage =
    StreamRecordPage(
      records = records,
      sequenceNumbers = records.map(_.dynamodb().sequenceNumber()),
      nextShardIterator = nextShardIterator
    )

  /** The first poll reads one empty page and keeps the shard open. Later polls serve whatever the test enqueues; with
    * nothing queued they return the same page again, so the poll loop keeps running instead of completing the shard.
    */
  private final class ScriptedStreamClient(
      firstPage: StreamRecordPage,
      trimmedAfterFirstPage: Boolean = false
  ) extends HeadStreamClient {

    private val lock  = new Object
    private val pages = mutable.Queue.empty[() => Try[StreamRecordPage]]
    private var polls = 0

    pages.enqueue(() => Success(firstPage))

    def enqueue(page: => StreamRecordPage): Unit = lock.synchronized {
      pages.enqueue(() => Success(page))
    }

    def pollsAfterFirst: Int = lock.synchronized(polls - 1)

    override def streamArn(headTableName: String): Try[String] = Success("arn:stream")

    override def describeShards(streamArn: String, exclusiveStartShardId: Option[String]): Try[ShardPage] =
      Success(ShardPage(Vector("s1"), None))

    override def shardIteratorAtTrimHorizon(streamArn: String, shardId: String): Try[String] =
      Success(s"trim-$shardId")

    override def shardIteratorAfterSequenceNumber(
        streamArn: String,
        shardId: String,
        sequenceNumber: String
    ): Try[String] = Success(s"after-$sequenceNumber")

    override def getRecords(streamArn: String, shardIterator: String, limit: Int): Try[StreamRecordPage] =
      lock.synchronized {
        polls += 1
        if (polls == 1) {
          pages.dequeue()()
        } else if (trimmedAfterFirstPage) {
          Failure(TrimmedDataAccessException.builder().message("trimmed").build())
        } else if (pages.nonEmpty) {
          pages.dequeue()()
        } else {
          Success(StreamRecordPage(Vector.empty, Vector.empty, Some(shardIterator)))
        }
      }
  }

  private final class FakeJournal(events: Vector[HeadEventRecord]) extends JournalEventReader {

    override def eventsSince(aggregateId: String, seqNr: Long): Try[Vector[HeadEventRecord]] =
      Try(synchronized(events.filter(_.seqNr >= seqNr).sortBy(_.seqNr)))
  }

  private val storeIdProvider = new StoreIdProvider {
    override def storeId(): Try[String] = Success("store-1")
  }

  private def noSleep: Sleeper = _ => ()

  private def fastBackoff: Backoff =
    new Backoff(FiniteDuration(1, MILLISECONDS), FiniteDuration(2, MILLISECONDS), _ => FiniteDuration(0, NANOSECONDS))

  private final case class Wiring(
      dao: InMemoryReadModelDao,
      poller: HeadStreamPoller,
      applier: ReadModelEventApplier,
      updater: ReadModelUpdater
  )

  private def wiring(client: HeadStreamClient): Wiring = {
    val dao = new InMemoryReadModelDao
    val journal = new FakeJournal(
      Vector(
        journalRecord(1L, GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner)),
        journalRecord(2L, GroupChatEvent.MemberAdded(addedMember, owner)),
        journalRecord(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))
      )
    )
    val applier = new ReadModelEventApplier(dao, journal)
    val updater = new ReadModelUpdater(applier, None, RetryPolicy.noRetry)
    val poller = new HeadStreamPoller(
      client = client,
      handler = record => updater.applyRecord(record, GroupChatSerializers.eventSerializer).map(_ => ()),
      maxItemCount = 10
    )
    Wiring(dao, poller, applier, updater)
  }

  private def newRunner(wiring: Wiring, scanner: HeadScanner): LocalRmuRunner = {
    val recovery = new RecoveryService(new InMemoryRecoveryTaskStore, storeIdProvider, wiring.applier)
    new LocalRmuRunner(
      settings = RunnerSettings(
        headTableName = "head",
        recoveryInterval = FiniteDuration(1, HOURS)
      ),
      poller = wiring.poller,
      resyncer = new Resyncer(wiring.applier, scanner, Some(recovery)),
      recoveryService = recovery,
      retryPolicy = RetryPolicy.noRetry,
      pollBackoff = fastBackoff,
      sleeper = noSleep
    )
  }

  /** A scan that first applies a stale head and then waits until an update delivered by the stream has been applied. If
    * polling and the scan did not run in parallel, the wait times out and the test fails.
    */
  private final class UpdateDuringScanScanner(
      subscriptionResumed: () => Boolean,
      injectUpdate: () => Unit,
      currentPosition: () => Long,
      scanFinished: CountDownLatch
  ) extends HeadScanner {

    @volatile var sawSubscriptionFirst = false
    @volatile var sawUpdateDuringScan  = false

    override def scanHeads(visitor: Head => Unit): Try[Unit] =
      try {
        Try {
          sawSubscriptionFirst = subscriptionResumed()
          if (!sawSubscriptionFirst) {
            throw new IllegalStateException("the scan started before the stream subscription resumed")
          }
          visitor(Head(aggregateId, "GroupChat", 1L))
          injectUpdate()
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
          while (currentPosition() < 3L && System.nanoTime() < deadline) {
            Thread.sleep(1L)
          }
          sawUpdateDuringScan = currentPosition() >= 3L
        }
      } finally {
        scanFinished.countDown()
      }
  }

  /** Blocks inside the scan so the test can observe whether a second scan starts concurrently. */
  private final class BlockingScanner(scanStarted: CountDownLatch, releaseScan: CountDownLatch) extends HeadScanner {

    private var concurrent  = 0
    private var maxSeen     = 0
    private var invocations = 0

    def maxConcurrent: Int = synchronized(maxSeen)
    def scans: Int         = synchronized(invocations)

    override def scanHeads(visitor: Head => Unit): Try[Unit] =
      Try {
        synchronized {
          concurrent += 1
          invocations += 1
          if (concurrent > maxSeen) {
            maxSeen = concurrent
          }
        }
        try {
          scanStarted.countDown()
          awaitWithin(releaseScan, FiniteDuration(30, SECONDS))
        } finally {
          synchronized(concurrent -= 1)
        }
      }
  }

  private def awaitWithin(latch: CountDownLatch, timeout: FiniteDuration): Boolean = {
    val deadline = System.nanoTime() + timeout.toNanos
    while (latch.getCount > 0L && System.nanoTime() < deadline) {
      Thread.sleep(1L)
    }
    latch.getCount == 0L
  }

  private def startRunner(runner: LocalRmuRunner): Thread = {
    val thread = new Thread(() => runner.run(), "test-read-model-updater")
    thread.start()
    thread
  }

  "the local read model updater runner" - {
    "resumes the stream before scanning and keeps an update that arrives during the scan" in {
      val client   = new ScriptedStreamClient(streamPage(Vector.empty, Some("s1-p2")))
      val parts    = wiring(client)
      val finished = new CountDownLatch(1)
      val scanner = new UpdateDuringScanScanner(
        subscriptionResumed = () => parts.poller.lastPollTime.isDefined,
        injectUpdate = () =>
          client.enqueue(
            streamPage(Vector(headStreamRecord(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))), None)
          ),
        currentPosition = () => parts.dao.lastProcessedSeqNr(aggregateId).get,
        scanFinished = finished
      )
      val runner = newRunner(parts, scanner)
      val thread = startRunner(runner)

      try {
        awaitWithin(finished, FiniteDuration(10, SECONDS)) shouldBe true
        scanner.sawSubscriptionFirst shouldBe true
        scanner.sawUpdateDuringScan shouldBe true
        parts.dao.lastProcessedSeqNr(aggregateId).get shouldBe 3L
        parts.dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"
        parts.dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) should
        contain theSameElementsAs Seq(owner.asString, member.asString)
      } finally {
        runner.stop()
        thread.join(5000L)
      }
    }

    "runs one reconciliation at a time while the stream reports trimmed records" in {
      val client  = new ScriptedStreamClient(streamPage(Vector.empty, Some("s1-p2")), trimmedAfterFirstPage = true)
      val parts   = wiring(client)
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val scanner = new BlockingScanner(started, release)
      val runner  = newRunner(parts, scanner)
      val thread  = startRunner(runner)

      try {
        awaitWithin(started, FiniteDuration(10, SECONDS)) shouldBe true
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (client.pollsAfterFirst < 1 && System.nanoTime() < deadline) {
          Thread.sleep(5L)
        }
        client.pollsAfterFirst should be >= 1
        scanner.maxConcurrent shouldBe 1
        scanner.scans shouldBe 1
      } finally {
        release.countDown()
        runner.stop()
        thread.join(5000L)
      }
    }

    "stops the reconciliation worker when the runner is stopped" in {
      val client  = new ScriptedStreamClient(streamPage(Vector.empty, Some("s1-p2")))
      val parts   = wiring(client)
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val runner  = newRunner(parts, new BlockingScanner(started, release))
      val thread  = startRunner(runner)

      awaitWithin(started, FiniteDuration(10, SECONDS)) shouldBe true
      runner.stop()
      thread.join(5000L)

      thread.isAlive shouldBe false
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (runner.resyncWorkerAlive && System.nanoTime() < deadline) {
        Thread.sleep(10L)
      }
      runner.resyncWorkerAlive shouldBe false
    }
  }
}
