package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException

import scala.util.Failure
import scala.util.Try

/** Applies head transitions to the read model.
  *
  * Every projection is a single database transaction that advances `read_model_positions` together with the read model
  * rows. A sequence number that was already applied reports a duplicate and changes nothing; a sequence number that
  * skips ahead fails with a gap. The updater then reads the missing events from the journal and retries the projection,
  * so duplicates, older deliveries and a restart all converge on the same position.
  *
  * The events inside one head transition are applied in ascending `seq_nr` because a record's list is not guaranteed to
  * be ordered.
  */
final class ReadModelUpdater(dao: ReadModelDao, journal: JournalEventReader) {

  def applyTransition(transition: HeadTransition): Try[Unit] =
    transition.events.sortBy(_.seqNr).foldLeft(Try(())) { (accumulator, record) =>
      accumulator.flatMap(_ => applyEvent(transition.aggregateId, record))
    }

  def applyEvent(aggregateId: String, record: HeadEventRecord): Try[Unit] =
    project(aggregateId, record).recoverWith { case gap: ReadModelGapException =>
      fillGap(aggregateId, gap.expectedSeqNr, record.seqNr).flatMap(_ => project(aggregateId, record))
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
