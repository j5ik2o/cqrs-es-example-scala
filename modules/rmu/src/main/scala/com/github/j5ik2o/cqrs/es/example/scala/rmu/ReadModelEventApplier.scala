package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException

import java.sql.SQLException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Applies journal and head events in bounded transactions.
  *
  * Only `position + 1` is applied. The DAO locks the position row and commits the read model change with the position
  * advance, so a restart, a retried batch or two concurrent readers cannot skip or apply an event twice. A gap in the
  * journal fails without advancing the position.
  */
final class ReadModelEventApplier(
    dao: ReadModelDao,
    journal: JournalEventReader,
    readAheadEvents: Int = ReadModelEventApplier.DefaultReadAheadEvents,
    conflictRetries: Int = ReadModelEventApplier.DefaultConflictRetries,
    sleeper: Sleeper = Sleeper.system
) extends EventApplier {

  private val readAhead        = Math.max(1, readAheadEvents)
  private val conflictAttempts = Math.max(1, conflictRetries)

  override def position(aggregateId: String): Try[Long] = dao.lastProcessedSeqNr(aggregateId)

  override def applyHeadEvent(aggregateId: String, record: HeadEventRecord): Try[ProjectionResult] =
    position(aggregateId).flatMap { current =>
      if (record.seqNr <= current) {
        Success(ProjectionResult.Duplicate)
      } else if (record.seqNr > current + 1L) {
        // Read and apply the gap in bounded chunks. A head far ahead of the position must not accumulate every missing
        // event in memory before anything is committed.
        applyThrough(aggregateId, record.seqNr - 1L).flatMap(_ => project(aggregateId, record))
      } else {
        project(aggregateId, record)
      }
    }

  override def applyThrough(aggregateId: String, targetSeqNr: Long): Try[Unit] = {
    if (targetSeqNr <= 0L) {
      Success(())
    } else {
      // Apply bounded chunks in a loop. A recursive implementation would grow the stack with the aggregate's history.
      var result: Try[Unit] = Success(())
      var done              = false
      while (!done && result.isSuccess) {
        result = position(aggregateId).flatMap { current =>
          if (current >= targetSeqNr) {
            done = true
            Success(())
          } else {
            val endSeqNr = Math.min(current + readAhead, targetSeqNr)
            journal.eventsBetween(aggregateId, current + 1L, endSeqNr).flatMap { events =>
              Try(requireContiguous(aggregateId, current + 1L, endSeqNr, events)).flatMap { _ =>
                applyEvents(aggregateId, events).flatMap { _ =>
                  position(aggregateId).flatMap { after =>
                    if (after <= current) {
                      Failure(
                        new ReadModelUpdateException(
                          s"aggregate $aggregateId: position stayed at $after while replaying to $targetSeqNr"
                        )
                      )
                    } else {
                      Success(())
                    }
                  }
                }
              }
            }
          }
        }
      }
      result
    }
  }

  private def applyEvents(aggregateId: String, events: Vector[HeadEventRecord]): Try[Unit] =
    events.sortBy(_.seqNr).foldLeft(Try(())) { (accumulator, record) =>
      accumulator.flatMap(_ => project(aggregateId, record).map(_ => ()))
    }

  private def project(aggregateId: String, record: HeadEventRecord): Try[ProjectionResult] = {
    def attempt(remaining: Int): Try[ProjectionResult] = {
      val result = ReadModelProjection(dao, aggregateId, record.event, record.occurredAt, record.seqNr)
      result match {
        case Failure(error) if isTransactionConflict(error) && remaining > 1 =>
          sleeper.sleep(FiniteDuration(5L * (conflictAttempts - remaining + 1), MILLISECONDS))
          attempt(remaining - 1)
        case other =>
          other
      }
    }
    attempt(conflictAttempts)
  }

  private def requireContiguous(
      aggregateId: String,
      fromSeqNr: Long,
      toSeqNr: Long,
      events: Vector[HeadEventRecord]
  ): Unit = {
    var expected = fromSeqNr
    events.foreach { event =>
      if (event.seqNr != expected) {
        throw new ReadModelGapException(aggregateId, expected, event.seqNr)
      }
      expected += 1L
    }
    if (expected != toSeqNr + 1L) {
      throw new ReadModelGapException(aggregateId, expected, toSeqNr)
    }
  }

  /** A deadlock or lock-wait timeout means another transaction held the position row. Retrying the whole transaction is
    * safe because the failed transaction rolled back.
    */
  private def isTransactionConflict(failure: Throwable): Boolean =
    causes(failure).exists {
      case sqlException: SQLException => sqlException.getErrorCode == 1213 || sqlException.getErrorCode == 1205
      case _                          => false
    }

  private def causes(failure: Throwable): Iterator[Throwable] =
    Iterator
      .iterate(failure)(cause => if (cause == null) null else cause.getCause)
      .takeWhile(_ != null)
      .take(32)
}

object ReadModelEventApplier {
  final val DefaultReadAheadEvents = 100
  final val DefaultConflictRetries = 5
}
