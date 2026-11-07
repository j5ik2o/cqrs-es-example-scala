package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Members
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Messages
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.Instant

class GroupChatSerializersSpec extends AnyFreeSpec with Matchers {

  private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner       = userAccountId('V')
  private val member      = userAccountId('W')
  private val groupChatId = GroupChatId.newId
  private val memberId    = MemberId.newId
  private val messageId   = MessageId.newId
  private val memberEntity =
    Member(memberId, member, MemberRole.Member)
  private val messageEntity =
    Message(messageId, MessageBody("hello"), owner)

  private val events: Seq[GroupChatEvent] = Seq(
    GroupChatEvent.Created(GroupChatName("team"), Member(MemberId.newId, owner, MemberRole.Admin), owner),
    GroupChatEvent.Deleted(owner),
    GroupChatEvent.Renamed(GroupChatName("renamed"), owner),
    GroupChatEvent.MemberAdded(memberEntity, owner),
    GroupChatEvent.MemberRemoved(member, owner),
    GroupChatEvent.MessagePosted(messageEntity, owner),
    GroupChatEvent.MessageEdited(messageId, MessageBody("edited"), owner),
    GroupChatEvent.MessageDeleted(messageId, owner)
  )

  private def serializeEvent(event: GroupChatEvent): String =
    new String(GroupChatSerializers.eventSerializer.serialize(event), StandardCharsets.UTF_8)

  "the event serializer" - {
    "round-trips every event type" in {
      events.foreach { event =>
        GroupChatSerializers.eventSerializer.deserialize(
          GroupChatSerializers.eventSerializer.serialize(event)
        ) shouldBe event
      }
    }

    "does not put envelope metadata into the payload" in {
      events.foreach { event =>
        val json = serializeEvent(event)
        json should not include "\"aggregateId\""
        json should not include "\"seqNr\""
        json should not include "\"occurredAt\""
        json should not include "\"manifest\""
      }
    }

    "uses the event type and schema version as the manifest" in {
      events.foreach { event =>
        event.manifest shouldBe s"${event.eventType}@1"
      }
    }
  }

  "the snapshot serializer" - {
    "round-trips the aggregate state" in {
      val snapshot = GroupChat(
        id = groupChatId,
        deleted = false,
        name = GroupChatName("team"),
        members = Members(Vector(memberEntity)),
        messages = Messages(Vector(messageEntity)),
        seqNr = 3L,
        lastUpdatedAt = occurredAt
      )

      GroupChatSerializers.snapshotSerializer.deserialize(
        GroupChatSerializers.snapshotSerializer.serialize(snapshot)
      ) shouldBe snapshot
    }
  }
}
