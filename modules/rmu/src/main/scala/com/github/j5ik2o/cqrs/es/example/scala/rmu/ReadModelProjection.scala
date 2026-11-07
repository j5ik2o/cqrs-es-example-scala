package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao

import java.time.Instant
import scala.util.Try

/** Maps a domain event onto the read model. Only payload data is used; the timestamps and sequence number come from the
  * envelope.
  */
object ReadModelProjection {

  def apply(
      dao: ReadModelDao,
      aggregateId: String,
      event: GroupChatEvent,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    event match {
      case GroupChatEvent.Created(name, owner, _) =>
        dao.insertGroupChat(aggregateId, name.asString, owner, occurredAt, seqNr)
      case GroupChatEvent.Deleted(_) =>
        dao.deleteGroupChat(aggregateId, occurredAt, seqNr)
      case GroupChatEvent.Renamed(name, _) =>
        dao.updateGroupChatName(aggregateId, name.asString, occurredAt, seqNr)
      case GroupChatEvent.MemberAdded(member, _) =>
        dao.insertMember(aggregateId, member, occurredAt, seqNr)
      case GroupChatEvent.MemberRemoved(userAccountId, _) =>
        dao.deleteMember(aggregateId, userAccountId, occurredAt, seqNr)
      case GroupChatEvent.MessagePosted(message, _) =>
        dao.insertMessage(aggregateId, message, occurredAt, seqNr)
      case GroupChatEvent.MessageEdited(messageId, body, _) =>
        dao.updateMessage(aggregateId, messageId, body, occurredAt, seqNr)
      case GroupChatEvent.MessageDeleted(messageId, _) =>
        dao.deleteMessage(aggregateId, messageId, occurredAt, seqNr)
    }
}
