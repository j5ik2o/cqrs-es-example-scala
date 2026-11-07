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
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.jdk.CollectionConverters._
import scala.util.Success
import scala.util.Try

class ReadModelUpdaterRecoverySpec extends AnyFreeSpec with Matchers {

  private final class FakeJournal extends JournalEventReader {

    private var events = Map.empty[String, Vector[HeadEventRecord]]

    def store(aggregateId: String, records: HeadEventRecord*): Unit =
      events = events.updated(aggregateId, records.toVector)

    override def eventsSince(aggregateId: String, seqNr: Long): Try[Vector[HeadEventRecord]] =
      Try(events.getOrElse(aggregateId, Vector.empty).filter(_.seqNr >= seqNr).sortBy(_.seqNr))
  }

  private implicit val executionContext: ExecutionContext = ExecutionContext.global

  private val storeIdProvider = new StoreIdProvider {
    override def storeId(): Try[String] = Success("store-1")
  }

  private val baseTime = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner       = userAccountId('V')
  private val member      = userAccountId('W')
  private val groupChatId = GroupChatId.newId
  private val aggregateId = groupChatId.asString
  private val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)
  private val addedMember = Member(MemberId.newId, member, MemberRole.Member)

  private def record(seqNr: Long, event: GroupChatEvent): HeadEventRecord =
    HeadEventRecord(seqNr, baseTime.plusSeconds(seqNr), event.manifest, event)

  private def transition(seqNr: Long, event: GroupChatEvent): HeadTransition =
    HeadTransition(aggregateId, seqNr, Vector(record(seqNr, event)))

  private def created: GroupChatEvent = GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner)

  private def retrying(attempts: Int): RetryPolicy =
    new RetryPolicy(
      maxAttempts = attempts,
      maxElapsed = FiniteDuration(0, MILLISECONDS),
      initialDelay = FiniteDuration(0, MILLISECONDS),
      maxDelay = FiniteDuration(0, MILLISECONDS),
      sleeper = _ => (),
      jitter = identity
    )

  private def newRecovery(
      dao: ReadModelDao,
      journal: FakeJournal,
      attempts: Int
  ): (InMemoryRecoveryTaskStore, RecoveryService, ReadModelUpdater) = {
    val store   = new InMemoryRecoveryTaskStore
    val applier = new ReadModelEventApplier(dao, journal)
    val service = new RecoveryService(store, storeIdProvider, applier)
    val updater = new ReadModelUpdater(applier, Some(service), retrying(attempts))
    (store, service, updater)
  }

  "the recovering read model updater" - {
    "parks an event after retry exhaustion and replays it from the journal" in {
      val delegate = new InMemoryReadModelDao
      val failing  = new FailingReadModelDao(delegate)
      val journal  = new FakeJournal
      journal.store(
        aggregateId,
        record(1L, created),
        record(2L, GroupChatEvent.MemberAdded(addedMember, owner)),
        record(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))
      )
      val (store, service, updater) = newRecovery(failing, journal, attempts = 2)
      failing.failOnSeqNr = Some(3L)

      updater.applyTransition(transition(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))) shouldBe
      Success(RecordOutcome.RecordedFailure)

      delegate.lastProcessedSeqNr(aggregateId).get shouldBe 2L
      store.all should have size 1
      store.all.head.seqNr shouldBe 3L
      store.all.head.failureKind shouldBe FailureKind.Unknown

      failing.failOnSeqNr = None
      service.recoverPending() shouldBe Success(1)

      delegate.lastProcessedSeqNr(aggregateId).get shouldBe 3L
      delegate.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"
      store.all.head.status shouldBe RecoveryTask.StatusCompleted
    }

    "parks a journal gap and replays it once the journal is complete" in {
      val dao                       = new InMemoryReadModelDao
      val journal                   = new FakeJournal
      val (store, service, updater) = newRecovery(dao, journal, attempts = 2)

      updater.applyTransition(transition(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))) shouldBe
      Success(RecordOutcome.RecordedFailure)

      store.all should have size 1
      store.all.head.failureKind shouldBe FailureKind.JournalGap
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 0L

      journal.store(
        aggregateId,
        record(1L, created),
        record(2L, GroupChatEvent.MemberAdded(addedMember, owner)),
        record(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))
      )
      service.recoverPending() shouldBe Success(1)

      dao.lastProcessedSeqNr(aggregateId).get shouldBe 3L
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"
      store.all.head.status shouldBe RecoveryTask.StatusCompleted
    }

    "returns a failure when the recovery task itself cannot be stored" in {
      val delegate = new InMemoryReadModelDao
      val failing  = new FailingReadModelDao(delegate)
      val journal  = new FakeJournal
      journal.store(
        aggregateId,
        record(1L, created),
        record(2L, GroupChatEvent.MemberAdded(addedMember, owner)),
        record(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))
      )
      val (store, _, updater) = newRecovery(failing, journal, attempts = 2)
      failing.failOnSeqNr = Some(3L)
      store.failOnRecord = true

      val result = updater.applyTransition(transition(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner)))

      result.isFailure shouldBe true
      delegate.lastProcessedSeqNr(aggregateId).get shouldBe 2L
      store.all shouldBe empty
    }

    "does not park a duplicate or an older delivery" in {
      val dao                 = new InMemoryReadModelDao
      val journal             = new FakeJournal
      val (store, _, updater) = newRecovery(dao, journal, attempts = 2)

      updater.applyTransition(transition(1L, created)) shouldBe Success(RecordOutcome.Applied)
      updater.applyTransition(transition(1L, created)) shouldBe Success(RecordOutcome.Applied)
      updater.applyTransition(transition(2L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))) shouldBe
      Success(RecordOutcome.Applied)
      updater.applyTransition(transition(1L, created)) shouldBe Success(RecordOutcome.Applied)

      dao.lastProcessedSeqNr(aggregateId).get shouldBe 2L
      store.all shouldBe empty
    }

    "parks a record whose payload cannot be decoded using its identifiers" in {
      val dao                       = new InMemoryReadModelDao
      val journal                   = new FakeJournal
      val (store, service, updater) = newRecovery(dao, journal, attempts = 2)
      val image = Map(
        "aid"    -> AttributeValue.fromS(aggregateId),
        "seq_nr" -> AttributeValue.fromN("1")
      ).asJava
      val damaged = Record
        .builder()
        .eventName(OperationType.INSERT)
        .dynamodb(StreamRecord.builder().newImage(image).build())
        .build()

      updater.applyRecord(damaged, GroupChatSerializers.eventSerializer) shouldBe
      Success(RecordOutcome.RecordedFailure)

      store.all should have size 1
      store.all.head.aggregateId shouldBe aggregateId
      store.all.head.seqNr shouldBe 1L
      store.all.head.failureKind shouldBe FailureKind.EventPayload

      journal.store(aggregateId, record(1L, created))
      service.recoverPending() shouldBe Success(1)
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
    }

    "applies one event once when two readers race" in {
      val dao     = new InMemoryReadModelDao
      val journal = new FakeJournal
      val applier = new ReadModelEventApplier(dao, journal)
      val event   = created
      val results = Await.result(
        Future.sequence(
          Vector.fill(2)(Future(applier.applyHeadEvent(aggregateId, record(1L, event)).get))
        ),
        10.seconds
      )

      results.count(_ == ProjectionResult.Applied) shouldBe 1
      results.count(_ == ProjectionResult.Duplicate) shouldBe 1
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
      dao.findMembers(aggregateId, owner.asString).get should have size 1
    }
  }
}
