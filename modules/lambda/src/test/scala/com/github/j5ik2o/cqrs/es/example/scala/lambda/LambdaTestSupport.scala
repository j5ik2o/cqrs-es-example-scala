package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.rmu.EventApplier
import com.github.j5ik2o.cqrs.es.example.scala.rmu.FailureKind
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Head
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadEventRecord
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadScanner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.PermanentFailure
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryTask
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryTaskStore
import com.github.j5ik2o.cqrs.es.example.scala.rmu.StoreIdProvider

import java.time.Instant
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Process-local fakes for the Lambda handler tests. */
object LambdaTestSupport {

  final val StoreId = "store-1"

  val storeIdProvider: StoreIdProvider = new StoreIdProvider {
    override def storeId(): Try[String] = Success(StoreId)
  }

  def recoveryTask(aggregateId: String, seqNr: Long): RecoveryTask = {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    RecoveryTask(
      storeId = StoreId,
      projectionName = com.github.j5ik2o.cqrs.es.example.scala.rmu.Projection.Name,
      projectionVersion = com.github.j5ik2o.cqrs.es.example.scala.rmu.Projection.Version,
      aggregateId = aggregateId,
      seqNr = seqNr,
      failureKind = FailureKind.Unknown,
      errorMessage = "failed",
      status = RecoveryTask.StatusPending,
      createdAt = now,
      updatedAt = now
    )
  }

  final class RecordingRecoveryTaskStore(initial: Vector[RecoveryTask] = Vector.empty) extends RecoveryTaskStore {

    private val tasks = mutable.LinkedHashMap.empty[(String, String, String, Long), RecoveryTask]
    private var tick  = 0L
    initial.foreach(task => tasks.update(keyOf(task), task))

    def recorded: Vector[RecoveryTask] = synchronized(tasks.values.toVector)

    override def record(task: RecoveryTask): Try[Unit] =
      Try(synchronized(tasks.update(keyOf(task), task)))

    override def listPending(): Try[Vector[RecoveryTask]] =
      Try(
        synchronized(
          tasks.valuesIterator
            .filter(_.status == RecoveryTask.StatusPending)
            .toVector
            .sortBy(task => (task.updatedAt.toEpochMilli, task.aggregateId, task.seqNr))
        )
      )

    override def markCompleted(
        storeId: String,
        projectionName: String,
        aggregateId: String,
        seqNr: Long
    ): Try[Unit] =
      Try(
        synchronized {
          val key = (storeId, projectionName, aggregateId, seqNr)
          tasks.get(key).foreach(task => tasks.update(key, task.copy(status = RecoveryTask.StatusCompleted)))
        }
      )

    override def markFailed(task: RecoveryTask, failure: Throwable): Try[Unit] =
      Try(
        synchronized {
          tick += 1L
          val key = keyOf(task)
          tasks.get(key).foreach { existing =>
            tasks.update(
              key,
              existing.copy(
                errorMessage = Option(failure.getMessage).getOrElse("failed"),
                updatedAt = Instant.now().plusMillis(tick)
              )
            )
          }
        }
      )

    private def keyOf(task: RecoveryTask): (String, String, String, Long) =
      (task.storeId, task.projectionName, task.aggregateId, task.seqNr)
  }

  final class StubEventApplier(
      failingSeqNrs: Set[Long] = Set.empty,
      applyThroughFailures: Map[String, Throwable] = Map.empty
  ) extends EventApplier {

    private val positions = mutable.Map.empty[String, Long]

    override def position(aggregateId: String): Try[Long] =
      Try(synchronized(positions.getOrElse(aggregateId, 0L)))

    override def applyHeadEvent(aggregateId: String, record: HeadEventRecord): Try[ProjectionResult] =
      if (failingSeqNrs.contains(record.seqNr)) {
        Failure(new PermanentFailure(FailureKind.EventPayload, s"injected failure at ${record.seqNr}"))
      } else {
        Success(ProjectionResult.Applied)
      }

    override def applyThrough(aggregateId: String, targetSeqNr: Long): Try[Unit] =
      applyThroughFailures.get(aggregateId) match {
        case Some(error) => Failure(error)
        case None =>
          Try(synchronized(positions.update(aggregateId, targetSeqNr)))
      }
  }

  final class StubHeadScanner(heads: Vector[Head]) extends HeadScanner with StoreIdProvider {
    override def scanHeads(visitor: Head => Unit): Try[Unit] = Try(heads.foreach(visitor))
    override def storeId(): Try[String]                      = Success(StoreId)
  }
}
