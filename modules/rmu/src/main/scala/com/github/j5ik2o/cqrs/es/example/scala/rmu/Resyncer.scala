package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.slf4j.LoggerFactory

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Implements DY-15's reconciliation: scan every head, compare its seq_nr with the projection position, and fill the
  * lag from the journal with the same position guard as the stream path.
  *
  * A permanent per-aggregate failure is recorded as a recovery task and does not stop the remaining aggregates. A
  * storage failure aborts the scan so the caller can retry it.
  */
final class Resyncer(
    applier: EventApplier,
    scanner: HeadScanner,
    recovery: Option[RecoveryService]
) {

  private val logger = LoggerFactory.getLogger(getClass)

  def resync(): Try[ResyncResult] = {
    var heads    = 0
    var skipped  = 0
    var advanced = 0
    var failed   = 0

    val scanned = scanner.scanHeads { head =>
      if (!AggregateType.isGroupChat(head.aggregateId)) {
        skipped += 1
      } else {
        heads += 1
        val position = applier.position(head.aggregateId).get
        if (head.seqNr > position) {
          advanced += 1
          applier.applyThrough(head.aggregateId, head.seqNr) match {
            case Success(_) =>
              ()
            case Failure(error) if isRecoverable(error) =>
              failed += 1
              recovery match {
                case Some(service) =>
                  service.record(head.aggregateId, head.seqNr, error).get
                  logger.error(
                    s"resync could not apply an aggregate; recorded a recovery task: aid=${head.aggregateId}, seqNr=${head.seqNr}",
                    error
                  )
                case None =>
                  throw error
              }
            case Failure(error) =>
              throw error
          }
        }
      }
    }

    scanned.map(_ => ResyncResult(heads, skipped, advanced, failed))
  }

  private def isRecoverable(failure: Throwable): Boolean =
    FailureClassifier.classify(failure).isDefined || failure.isInstanceOf[RetryExhaustedException]
}
