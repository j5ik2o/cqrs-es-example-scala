package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.scala.EventStore

import scala.util.Try

/** Reads journal events so the updater can fill a sequence gap that the stream did not deliver. */
trait JournalEventReader {

  def eventsSince(aggregateId: String, seqNr: Long): Try[Vector[HeadEventRecord]]

  /** Reads the events of the inclusive range. Recovery applies a gap in bounded chunks. */
  def eventsBetween(aggregateId: String, fromSeqNr: Long, toSeqNr: Long): Try[Vector[HeadEventRecord]] =
    eventsSince(aggregateId, fromSeqNr).map(
      _.filter(record => record.seqNr >= fromSeqNr && record.seqNr <= toSeqNr).sortBy(_.seqNr)
    )
}

object AggregateIdCodec {

  def split(aid: String): (String, String) = {
    val separator = aid.indexOf('-')
    if (separator <= 0 || separator == aid.length - 1) {
      throw new IllegalArgumentException(s"invalid aggregate id: $aid")
    }
    (aid.substring(0, separator), aid.substring(separator + 1))
  }
}

final class EventStoreJournalEventReader(store: EventStore[GroupChatEvent, GroupChat]) extends JournalEventReader {

  override def eventsSince(aggregateId: String, seqNr: Long): Try[Vector[HeadEventRecord]] =
    Try {
      val (typeName, value) = AggregateIdCodec.split(aggregateId)
      store
        .getEventsByIdSinceSeqNr(AggregateId.of(typeName, value), seqNr)
        .get
        .map { envelope =>
          HeadEventRecord(
            seqNr = envelope.seqNr(),
            occurredAt = envelope.occurredAt(),
            manifest = envelope.manifest(),
            event = envelope.payload()
          )
        }
        .toVector
    }
}
