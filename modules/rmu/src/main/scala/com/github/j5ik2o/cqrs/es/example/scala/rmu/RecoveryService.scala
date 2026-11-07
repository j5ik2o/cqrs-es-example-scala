package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.slf4j.LoggerFactory

import java.time.Instant
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Records failed events and replays them from the journal.
  *
  * This is the DLQ half of the RMU contract. The task is durable, the replay is idempotent, and completion is claimed
  * only after the position reaches the task's seq_nr.
  */
final class RecoveryService(
    taskStore: RecoveryTaskStore,
    storeIdProvider: StoreIdProvider,
    applier: EventApplier,
    notifier: Option[RecoveryNotifier] = None
) {

  import RecoveryService.DefaultMaxTasksPerRun

  private val logger = LoggerFactory.getLogger(getClass)

  def record(aggregateId: String, targetSeqNr: Long, failure: Throwable): Try[Unit] =
    record(aggregateId, targetSeqNr, targetSeqNr, failure)

  /** Persists a recovery task. `targetSeqNr` is the position recovery must reach; `failedSeqNr` is the event that
    * failed and is kept in the message for diagnosis. A failure here is returned to the caller on purpose: losing the
    * task would hide a permanently failing event.
    */
  def record(
      aggregateId: String,
      targetSeqNr: Long,
      failedSeqNr: Long,
      failure: Throwable
  ): Try[Unit] =
    storeIdProvider.storeId().flatMap { storeId =>
      val kind = FailureClassifier.classify(failure).getOrElse(FailureKind.Unknown)
      val now  = Instant.now()
      val message =
        s"failed at seq_nr $failedSeqNr: ${Option(failure).flatMap(error => Option(error.getMessage)).getOrElse("")}"
      val task = RecoveryTask(
        storeId = storeId,
        projectionName = Projection.Name,
        projectionVersion = Projection.Version,
        aggregateId = aggregateId,
        seqNr = targetSeqNr,
        failureKind = kind,
        errorMessage = message,
        status = RecoveryTask.StatusPending,
        createdAt = now,
        updatedAt = now
      )
      taskStore.record(task).map { _ =>
        notifier.foreach { value =>
          Try(value.notifyTask(task)).failed.foreach { error =>
            // The durable task already exists. A notification failure only means the worker is not woken
            // immediately; the next recovery run still sees the task.
            logger.warn(s"failed to notify a recovery task: aid=$aggregateId, seqNr=$targetSeqNr", error)
          }
        }
      }
    }

  /** Replays one task. It re-reads the position first, so calling it twice is safe: the second call finds the position
    * at or beyond the task and only marks the task complete.
    */
  def recover(task: RecoveryTask): Try[Unit] =
    for {
      storeId <- storeIdProvider.storeId()
      _ <-
        if (belongsToCurrentProjection(task, storeId)) Success(())
        else
          Failure(
            new RecoveryException(
              s"recovery task belongs to another store or projection: storeId=${task.storeId}, projection=${task.projectionName}@${task.projectionVersion}"
            )
          )
      position <- applier.position(task.aggregateId)
      _ <- if (position < task.seqNr) applier.applyThrough(task.aggregateId, task.seqNr) else Success(())
      confirmed <- applier.position(task.aggregateId)
      _ <-
        if (confirmed < task.seqNr)
          Failure(
            new RecoveryException(
              s"aggregate ${task.aggregateId} stayed at position $confirmed, expected ${task.seqNr}"
            )
          )
        else Success(())
      _ <- taskStore.markCompleted(task.storeId, task.projectionName, task.aggregateId, task.seqNr)
    } yield ()

  /** Replays at most one bounded batch of pending tasks. A task that fails is marked as retried, which moves it behind
    * tasks that have not been attempted yet, so a large backlog does not stop stream polling.
    */
  def recoverPending(maxTasks: Int = DefaultMaxTasksPerRun): Try[Int] =
    storeIdProvider.storeId().flatMap { storeId =>
      taskStore.listPending().map { tasks =>
        var completed: Int                  = 0
        var processed: Int                  = 0
        var firstFailure: Option[Throwable] = None
        var index                           = 0
        while (index < tasks.size && processed < maxTasks) {
          val task = tasks(index)
          index += 1
          if (!belongsToCurrentProjection(task, storeId)) {
            logger.warn(
              s"skipping a recovery task from another store or projection: storeId=${task.storeId}, projection=${task.projectionName}@${task.projectionVersion}"
            )
          } else {
            processed += 1
            recover(task) match {
              case Success(_) =>
                completed += 1
              case Failure(error: RetryInterruptedException) =>
                throw error
              case Failure(error) =>
                taskStore.markFailed(task, error).failed.foreach(markError => error.addSuppressed(markError))
                firstFailure match {
                  case Some(existing) => existing.addSuppressed(error)
                  case None => firstFailure = Some(new RecoveryException("some recovery tasks remain pending", error))
                }
            }
          }
        }
        firstFailure.foreach(error => throw error)
        completed
      }
    }

  def pendingTasks(): Try[Vector[RecoveryTask]] = taskStore.listPending()

  private def belongsToCurrentProjection(task: RecoveryTask, storeId: String): Boolean =
    storeId == task.storeId &&
    Projection.Name == task.projectionName &&
    Projection.Version == task.projectionVersion
}

object RecoveryService {
  final val DefaultMaxTasksPerRun = 50
}
