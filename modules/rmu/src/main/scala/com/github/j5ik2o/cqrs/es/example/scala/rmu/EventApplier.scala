package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult

import scala.util.Try

/** Applies events to the read model under the single position rule: only the event at `position + 1` is applied, and
  * the read model change and the position advance commit together.
  *
  * The stream path, the resync path and the recovery replay path all use this interface.
  */
trait EventApplier {

  /** Returns the last applied seq_nr for the aggregate. */
  def position(aggregateId: String): Try[Long]

  /** Applies one head record event. The journal fills a gap below the record's seq_nr. */
  def applyHeadEvent(aggregateId: String, record: HeadEventRecord): Try[ProjectionResult]

  /** Replays the journal from the current position up to `targetSeqNr`. */
  def applyThrough(aggregateId: String, targetSeqNr: Long): Try[Unit]
}
