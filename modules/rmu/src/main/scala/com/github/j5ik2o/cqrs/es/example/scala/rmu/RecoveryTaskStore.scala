package com.github.j5ik2o.cqrs.es.example.scala.rmu

import scala.util.Try

/** Persists recovery tasks. Recording is idempotent on the (store, projection, aggregate, seq_nr) key so a redelivered
  * record does not create duplicates.
  */
trait RecoveryTaskStore {

  def record(task: RecoveryTask): Try[Unit]

  def listPending(): Try[Vector[RecoveryTask]]

  def markCompleted(storeId: String, projectionName: String, aggregateId: String, seqNr: Long): Try[Unit]

  /** Records a failed recovery attempt and moves the task behind tasks not yet attempted. */
  def markFailed(task: RecoveryTask, failure: Throwable): Try[Unit]
}
