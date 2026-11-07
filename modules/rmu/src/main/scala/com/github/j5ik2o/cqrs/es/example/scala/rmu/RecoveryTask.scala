package com.github.j5ik2o.cqrs.es.example.scala.rmu

import java.time.Instant

/** The durable record of one event that could not be applied.
  *
  * It stores identifiers only. The event body is always re-read from the journal, so a task cannot go stale when the
  * payload is repaired.
  */
final case class RecoveryTask(
    storeId: String,
    projectionName: String,
    projectionVersion: String,
    aggregateId: String,
    seqNr: Long,
    failureKind: FailureKind,
    errorMessage: String,
    status: String,
    createdAt: Instant,
    updatedAt: Instant
)

object RecoveryTask {
  final val StatusPending   = "pending"
  final val StatusCompleted = "completed"
}
