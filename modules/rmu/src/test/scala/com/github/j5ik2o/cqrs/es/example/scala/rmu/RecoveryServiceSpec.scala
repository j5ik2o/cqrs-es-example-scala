package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class RecoveryServiceSpec extends AnyFreeSpec with Matchers {

  private val now = Instant.parse("2026-01-01T00:00:00Z")

  private val storeIdProvider = new StoreIdProvider {
    override def storeId(): Try[String] = Success("store-1")
  }

  private final class FakeApplier(
      initialPositions: Map[String, Long],
      failures: Map[String, Throwable] = Map.empty,
      advances: Boolean = true
  ) extends EventApplier {

    private val positions = mutable.Map.from(initialPositions)

    override def position(aggregateId: String): Try[Long] = Try(positions.getOrElse(aggregateId, 0L))

    override def applyHeadEvent(aggregateId: String, record: HeadEventRecord): Try[ProjectionResult] =
      Success(ProjectionResult.Applied)

    override def applyThrough(aggregateId: String, targetSeqNr: Long): Try[Unit] =
      failures.get(aggregateId) match {
        case Some(error) => Failure(error)
        case None =>
          if (advances) positions.update(aggregateId, targetSeqNr)
          Success(())
      }
  }

  private def recordTask(aggregateId: String, seqNr: Long): RecoveryTask =
    RecoveryTask(
      storeId = "store-1",
      projectionName = Projection.Name,
      projectionVersion = Projection.Version,
      aggregateId = aggregateId,
      seqNr = seqNr,
      failureKind = FailureKind.Unknown,
      errorMessage = "failed",
      status = RecoveryTask.StatusPending,
      createdAt = now,
      updatedAt = now
    )

  "the recovery service" - {
    "records a task idempotently on the task key" in {
      val store   = new InMemoryRecoveryTaskStore
      val applier = new FakeApplier(Map.empty)
      val service = new RecoveryService(store, storeIdProvider, applier)

      service.record("GroupChat-A", 3L, 3L, new PermanentFailure(FailureKind.EventPayload, "bad")).get
      service.record("GroupChat-A", 3L, 2L, new PermanentFailure(FailureKind.EventPayload, "bad again")).get

      store.all should have size 1
      store.all.head.seqNr shouldBe 3L
      store.all.head.errorMessage should include("seq_nr 2")
    }

    "re-reads the position and is safe to run twice" in {
      val store   = new InMemoryRecoveryTaskStore
      val applier = new FakeApplier(Map("GroupChat-A" -> 0L))
      val service = new RecoveryService(store, storeIdProvider, applier)
      service.record("GroupChat-A", 3L, 3L, new PermanentFailure(FailureKind.EventPayload, "bad")).get
      val task = store.listPending().get.head

      service.recover(task) shouldBe Success(())
      applier.position("GroupChat-A") shouldBe Success(3L)
      store.all.head.status shouldBe RecoveryTask.StatusCompleted

      service.recover(task) shouldBe Success(())
      store.all.head.status shouldBe RecoveryTask.StatusCompleted
    }

    "fails when the position does not reach the target" in {
      val store   = new InMemoryRecoveryTaskStore
      val applier = new FakeApplier(Map("GroupChat-A" -> 0L), advances = false)
      val service = new RecoveryService(store, storeIdProvider, applier)
      service.record("GroupChat-A", 3L, 3L, new PermanentFailure(FailureKind.EventPayload, "bad")).get
      val task = store.listPending().get.head

      val result = service.recover(task)

      result.failed.get shouldBe a[RecoveryException]
      store.all.head.status shouldBe RecoveryTask.StatusPending
    }

    "refuses a task from another projection" in {
      val store   = new InMemoryRecoveryTaskStore
      val applier = new FakeApplier(Map("GroupChat-A" -> 0L))
      val service = new RecoveryService(store, storeIdProvider, applier)

      val result = service.recover(recordTask("GroupChat-A", 1L).copy(projectionName = "other"))

      result.failed.get shouldBe a[RecoveryException]
    }

    "replays a bounded batch and leaves a failed task pending" in {
      val store = new InMemoryRecoveryTaskStore
      val applier = new FakeApplier(
        Map("GroupChat-A" -> 0L, "GroupChat-B" -> 0L, "GroupChat-C" -> 0L),
        failures = Map("GroupChat-B" -> new ReadModelUpdateException("database is down"))
      )
      val service = new RecoveryService(store, storeIdProvider, applier)
      service.record("GroupChat-A", 1L, 1L, new PermanentFailure(FailureKind.EventPayload, "bad")).get
      service.record("GroupChat-B", 1L, 1L, new PermanentFailure(FailureKind.EventPayload, "bad")).get
      service.record("GroupChat-C", 1L, 1L, new PermanentFailure(FailureKind.EventPayload, "bad")).get

      service.recoverPending(3).isFailure shouldBe true

      store.all.map(task => task.aggregateId -> task.status).toMap shouldBe Map(
        "GroupChat-A" -> RecoveryTask.StatusCompleted,
        "GroupChat-B" -> RecoveryTask.StatusPending,
        "GroupChat-C" -> RecoveryTask.StatusCompleted
      )
    }

    "skips a task from another projection during a batch" in {
      val store   = new InMemoryRecoveryTaskStore
      val applier = new FakeApplier(Map("GroupChat-A" -> 0L))
      val service = new RecoveryService(store, storeIdProvider, applier)
      store.record(recordTask("GroupChat-X", 1L).copy(projectionName = "other")).get
      service.record("GroupChat-A", 1L, 1L, new PermanentFailure(FailureKind.EventPayload, "bad")).get

      service.recoverPending(10) shouldBe Success(1)

      store.all.find(_.aggregateId == "GroupChat-A").map(_.status) shouldBe Some(RecoveryTask.StatusCompleted)
    }

    "returns a task store write failure" in {
      val store = new InMemoryRecoveryTaskStore
      store.failOnRecord = true
      val applier = new FakeApplier(Map.empty)
      val service = new RecoveryService(store, storeIdProvider, applier)

      service
        .record("GroupChat-A", 1L, 1L, new PermanentFailure(FailureKind.EventPayload, "bad")).isFailure shouldBe true
    }

    "moves a failed task behind tasks that have not been attempted" in {
      val store = new InMemoryRecoveryTaskStore
      store.record(recordTask("GroupChat-A", 1L)).get
      store.record(recordTask("GroupChat-B", 1L)).get

      store.markFailed(recordTask("GroupChat-A", 1L), new ReadModelUpdateException("down")).get

      store.listPending().get.map(_.aggregateId) shouldBe Vector("GroupChat-B", "GroupChat-A")
    }
  }
}
