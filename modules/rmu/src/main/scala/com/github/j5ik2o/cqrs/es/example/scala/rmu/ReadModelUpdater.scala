package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Applies head transitions to the read model.
  *
  * The updater discards duplicates by `(aggregate_id, seq_nr)`, which is the position stored in `read_model_positions`.
  * When the next event skips a sequence number, it reads the missing events from the journal and applies them in
  * ascending order first (common contract F-4). The read model operations are monotonic as well, so a duplicate that
  * races with the position read is still a no-op.
  */
final class ReadModelUpdater(dao: ReadModelDao, journal: JournalEventReader) {

  def applyTransition(transition: HeadTransition): Try[Unit] =
    transition.events.sortBy(_.seqNr).foldLeft(Try(())) { (accumulator, record) =>
      accumulator.flatMap(_ => applyEvent(transition.aggregateId, record))
    }

  def applyEvent(aggregateId: String, record: HeadEventRecord): Try[Unit] =
    dao.lastProcessedSeqNr(aggregateId).flatMap { lastProcessed =>
      if (record.seqNr <= lastProcessed) {
        Success(())
      } else if (record.seqNr == lastProcessed + 1L) {
        project(aggregateId, record)
      } else {
        fillGap(aggregateId, lastProcessed + 1L, record.seqNr).flatMap(_ => project(aggregateId, record))
      }
    }

  private def fillGap(aggregateId: String, from: Long, to: Long): Try[Unit] =
    journal.eventsSince(aggregateId, from).flatMap { events =>
      val expected = (from until to).toVector
      val selected = events.filter(record => record.seqNr >= from && record.seqNr < to).sortBy(_.seqNr)
      if (selected.map(_.seqNr) != expected) {
        Failure(new ReadModelGapException(aggregateId, from, selected.headOption.map(_.seqNr).getOrElse(to)))
      } else {
        selected.foldLeft(Try(())) { (accumulator, record) =>
          accumulator.flatMap(_ => project(aggregateId, record))
        }
      }
    }

  private def project(aggregateId: String, record: HeadEventRecord): Try[Unit] =
    ReadModelProjection(dao, aggregateId, record.event, record.occurredAt, record.seqNr).map(_ => ())
}
