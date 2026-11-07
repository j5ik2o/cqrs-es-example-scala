package com.github.j5ik2o.cqrs.es.example.scala.rmu

import java.time.Instant
import scala.collection.mutable
import scala.util.Try

/** Process-local recovery task store used by the tests. Recording is idempotent on the same key as the MySQL table. */
final class InMemoryRecoveryTaskStore extends RecoveryTaskStore {

  @volatile var failOnRecord: Boolean = false

  private val tasks = mutable.LinkedHashMap.empty[(String, String, String, Long), RecoveryTask]
  private var tick  = 0L

  override def record(task: RecoveryTask): Try[Unit] =
    Try(synchronized {
      if (failOnRecord) {
        throw new IllegalStateException("injected recovery task store failure")
      }
      val key = keyOf(task)
      tasks.get(key) match {
        case Some(existing) =>
          tasks.update(
            key,
            existing.copy(
              projectionVersion = task.projectionVersion,
              failureKind = task.failureKind,
              errorMessage = task.errorMessage,
              status = task.status,
              updatedAt = task.updatedAt
            )
          )
        case None =>
          tasks.update(key, task)
      }
    })

  override def listPending(): Try[Vector[RecoveryTask]] =
    Try(synchronized {
      tasks.valuesIterator
        .filter(_.status == RecoveryTask.StatusPending)
        .toVector
        .sortBy(task => (task.updatedAt.toEpochMilli, task.aggregateId, task.seqNr))
    })

  override def markCompleted(
      storeId: String,
      projectionName: String,
      aggregateId: String,
      seqNr: Long
  ): Try[Unit] =
    Try(synchronized {
      val key = (storeId, projectionName, aggregateId, seqNr)
      tasks.get(key).foreach(task => tasks.update(key, task.copy(status = RecoveryTask.StatusCompleted)))
    })

  override def markFailed(task: RecoveryTask, failure: Throwable): Try[Unit] =
    Try(synchronized {
      tick += 1L
      val key = keyOf(task)
      tasks.get(key).foreach { existing =>
        tasks.update(
          key,
          existing.copy(
            errorMessage = Option(failure).flatMap(value => Option(value.getMessage)).getOrElse("failed"),
            updatedAt = Instant.now().plusMillis(tick)
          )
        )
      }
    })

  def all: Vector[RecoveryTask] = synchronized(tasks.values.toVector)

  private def keyOf(task: RecoveryTask): (String, String, String, Long) =
    (task.storeId, task.projectionName, task.aggregateId, task.seqNr)
}
