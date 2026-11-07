package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers.eventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.scala.EventStore

import java.time.Instant
import scala.util.Success
import scala.util.Try

final class EventStoreGroupChatRepository(store: EventStore[GroupChatEvent, GroupChat]) extends GroupChatRepository {

  override def findById(id: GroupChatId): Try[Option[GroupChat]] = {
    val aggregateId = toAggregateId(id)
    store.getLatestSnapshotById(aggregateId).flatMap {
      case None =>
        Success(None)
      case Some(read) =>
        val startSeqNr = read.snapshot.map(_.seqNr() + 1L).getOrElse(1L)
        store.getEventsByIdSinceSeqNr(aggregateId, startSeqNr).map { events =>
          events.foldLeft(read.snapshot.map(_.aggregate())) { (current, envelope) =>
            Some(
              GroupChat.applyEvent(
                current,
                id,
                envelope.payload(),
                envelope.seqNr(),
                envelope.occurredAt()
              )
            )
          }
        }
    }
  }

  override def persistEvent(
      id: GroupChatId,
      event: GroupChatEvent,
      seqNr: Long,
      occurredAt: Instant
  ): Try[Unit] =
    store.persistEvent(toEventEnvelope(id, event, seqNr, occurredAt))

  override def persistEventAndSnapshot(
      id: GroupChatId,
      event: GroupChatEvent,
      snapshot: GroupChat,
      occurredAt: Instant
  ): Try[Unit] =
    store.persistEventAndSnapshot(
      toEventEnvelope(id, event, snapshot.seqNr, occurredAt),
      toSnapshotEnvelope(snapshot)
    )

  private def toAggregateId(id: GroupChatId): AggregateId =
    AggregateId.of(GroupChatId.Prefix, id.value)

  private def toEventEnvelope(
      id: GroupChatId,
      event: GroupChatEvent,
      seqNr: Long,
      occurredAt: Instant
  ): EventEnvelope[GroupChatEvent] =
    EventEnvelope
      .builder[GroupChatEvent]()
      .aggregateId(toAggregateId(id))
      .seqNr(seqNr)
      .occurredAt(occurredAt)
      .manifest(event.manifest)
      .payload(event)
      .build()

  private def toSnapshotEnvelope(snapshot: GroupChat): SnapshotEnvelope[GroupChat] =
    SnapshotEnvelope
      .builder[GroupChat]()
      .aggregate(snapshot)
      .seqNr(snapshot.seqNr)
      .manifest(GroupChat.SnapshotManifest)
      .build()
}

object EventStoreGroupChatRepository {

  def apply(store: EventStore[GroupChatEvent, GroupChat]): EventStoreGroupChatRepository =
    new EventStoreGroupChatRepository(store)

  def memory(): EventStoreGroupChatRepository = {
    val store = EventStore.ofMemory[GroupChatEvent, GroupChat](eventStoreConfig).get
    new EventStoreGroupChatRepository(store)
  }
}
