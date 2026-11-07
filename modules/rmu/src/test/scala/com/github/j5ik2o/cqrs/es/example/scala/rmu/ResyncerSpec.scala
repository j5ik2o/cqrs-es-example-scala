package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class ResyncerSpec extends AnyFreeSpec with Matchers {

  private val storeIdProvider = new StoreIdProvider {
    override def storeId(): Try[String] = Success("store-1")
  }

  private final class FakeHeadScanner(heads: Vector[Head]) extends HeadScanner {
    override def scanHeads(visitor: Head => Unit): Try[Unit] = Try(heads.foreach(visitor))
  }

  private final class FakeApplier(
      initialPositions: Map[String, Long],
      failures: Map[String, Throwable]
  ) extends EventApplier {

    private val positions = mutable.Map.from(initialPositions)
    val appliedThrough    = mutable.Buffer.empty[(String, Long)]

    override def position(aggregateId: String): Try[Long] = Try(positions.getOrElse(aggregateId, 0L))

    override def applyHeadEvent(aggregateId: String, record: HeadEventRecord): Try[ProjectionResult] =
      Success(ProjectionResult.Applied)

    override def applyThrough(aggregateId: String, targetSeqNr: Long): Try[Unit] =
      failures.get(aggregateId) match {
        case Some(error) =>
          Failure(error)
        case None =>
          positions.update(aggregateId, targetSeqNr)
          appliedThrough += (aggregateId -> targetSeqNr)
          Success(())
      }
  }

  private def recoveryService(applier: EventApplier, store: InMemoryRecoveryTaskStore): RecoveryService =
    new RecoveryService(store, storeIdProvider, applier)

  "the resyncer" - {
    "fills the lag of every group chat head and skips other aggregate types" in {
      val applier = new FakeApplier(
        initialPositions = Map("GroupChat-A" -> 1L, "GroupChat-B" -> 2L),
        failures = Map.empty
      )
      val scanner = new FakeHeadScanner(
        Vector(
          Head("GroupChat-A", "GroupChat", 3L),
          Head("GroupChat-B", "GroupChat", 2L),
          Head("UserAccount-1", "UserAccount", 9L)
        )
      )
      val resyncer = new Resyncer(applier, scanner, None)

      resyncer.resync() shouldBe Success(ResyncResult(heads = 2, skipped = 1, advanced = 1, failed = 0))
      applier.appliedThrough.toVector shouldBe Vector("GroupChat-A" -> 3L)
    }

    "records a recovery task for a permanent failure and continues scanning" in {
      val gap     = new ReadModelGapException("GroupChat-A", 2L, 3L)
      val applier = new FakeApplier(Map("GroupChat-A" -> 1L, "GroupChat-B" -> 0L), Map("GroupChat-A" -> gap))
      val scanner = new FakeHeadScanner(
        Vector(Head("GroupChat-A", "GroupChat", 3L), Head("GroupChat-B", "GroupChat", 1L))
      )
      val store    = new InMemoryRecoveryTaskStore
      val resyncer = new Resyncer(applier, scanner, Some(recoveryService(applier, store)))

      resyncer.resync() shouldBe Success(ResyncResult(heads = 2, skipped = 0, advanced = 2, failed = 1))
      applier.appliedThrough.toVector shouldBe Vector("GroupChat-B" -> 1L)
      store.all.map(task => (task.aggregateId, task.seqNr, task.failureKind)) shouldBe Vector(
        ("GroupChat-A", 3L, FailureKind.JournalGap)
      )
    }

    "aborts the scan on a storage failure so the caller retries" in {
      val applier = new FakeApplier(
        Map("GroupChat-A" -> 0L, "GroupChat-B" -> 0L),
        Map("GroupChat-A" -> new ReadModelUpdateException("database is down"))
      )
      val scanner = new FakeHeadScanner(
        Vector(Head("GroupChat-A", "GroupChat", 1L), Head("GroupChat-B", "GroupChat", 1L))
      )
      val resyncer = new Resyncer(applier, scanner, None)

      resyncer.resync().isFailure shouldBe true
      applier.appliedThrough.toVector shouldBe empty
    }

    "fails the scan for a recoverable failure when no recovery service is configured" in {
      val applier = new FakeApplier(
        Map("GroupChat-A" -> 0L),
        Map("GroupChat-A" -> new ReadModelGapException("GroupChat-A", 1L, 2L))
      )
      val resyncer = new Resyncer(applier, new FakeHeadScanner(Vector(Head("GroupChat-A", "GroupChat", 2L))), None)

      resyncer.resync().isFailure shouldBe true
    }
  }
}
