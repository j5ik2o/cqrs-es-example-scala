package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class EventStoreGroupChatRepositorySpec extends AnyFreeSpec with Matchers {

  private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner = userAccountId('V')

  private def aggregateId(id: GroupChatId): AggregateId =
    AggregateId.of(GroupChatId.Prefix, id.value)

  "EventStoreGroupChatRepository" - {
    "persists events and snapshots and replays the aggregate" in {
      val store      = EventStoreFactory.memory()
      val repository = EventStoreGroupChatRepository(store)
      val id         = GroupChatId.newId

      val (created, createdEvent) = GroupChat.create(id, GroupChatName("team"), owner, occurredAt)
      repository.persistEventAndSnapshot(id, createdEvent, created, occurredAt).get
      repository.findById(id).get shouldBe Some(created)

      val (renamed, renamedEvent) =
        created.rename(GroupChatName("renamed"), owner, occurredAt.plusSeconds(1)).toOption.get
      repository.persistEvent(id, renamedEvent, renamed.seqNr, occurredAt.plusSeconds(1)).get
      repository.findById(id).get shouldBe Some(renamed)

      val (posted, postedEvent) =
        renamed.postMessage(MessageBody("hello"), owner, occurredAt.plusSeconds(2)).toOption.get
      repository.persistEventAndSnapshot(id, postedEvent, posted, occurredAt.plusSeconds(2)).get
      repository.findById(id).get shouldBe Some(posted)

      val messageId = postedEvent.asInstanceOf[GroupChatEvent.MessagePosted].message.id
      val (edited, editedEvent) =
        posted.editMessage(messageId, MessageBody("edited"), owner, occurredAt.plusSeconds(3)).toOption.get
      repository.persistEvent(id, editedEvent, edited.seqNr, occurredAt.plusSeconds(3)).get

      repository.findById(id).get shouldBe Some(edited)

      val envelopes = store.getEventsByIdSinceSeqNr(aggregateId(id), 1L).get
      envelopes.map(_.seqNr()) shouldBe Seq(1L, 2L, 3L, 4L)
      envelopes.map(_.manifest()) shouldBe Seq(
        "group-chat-created@1",
        "group-chat-renamed@1",
        "group-chat-message-posted@1",
        "group-chat-message-edited@1"
      )

      val latest = store.getLatestSnapshotById(aggregateId(id)).get.get
      latest.headSeqNr shouldBe 4L
      latest.snapshot.map(_.seqNr()) shouldBe Some(3L)
    }

    "returns None for an aggregate that was never persisted" in {
      val repository = EventStoreGroupChatRepository(EventStoreFactory.memory())

      repository.findById(GroupChatId.newId).get shouldBe None
    }

    "rejects a duplicate sequence number" in {
      val repository              = EventStoreGroupChatRepository(EventStoreFactory.memory())
      val id                      = GroupChatId.newId
      val (created, createdEvent) = GroupChat.create(id, GroupChatName("team"), owner, occurredAt)

      repository.persistEvent(id, createdEvent, created.seqNr, occurredAt).get

      repository.persistEvent(id, createdEvent, created.seqNr, occurredAt).isFailure shouldBe true
    }
  }
}
