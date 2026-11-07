package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

import java.time.Instant

final case class GroupChat(
    id: GroupChatId,
    deleted: Boolean,
    name: GroupChatName,
    members: Members,
    messages: Messages,
    seqNr: Long,
    lastUpdatedAt: Instant
) {

  import GroupChatError.*

  def delete(
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(NotAdministratorError(id, executorId))
    } else {
      val nextSeqNr = seqNr + 1
      Right(
        (
          copy(deleted = true, seqNr = nextSeqNr, lastUpdatedAt = occurredAt),
          GroupChatEvent.Deleted(executorId)
        )
      )
    }
  }

  def rename(
      newName: GroupChatName,
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(NotAdministratorError(id, executorId))
    } else if (name == newName) {
      Left(AlreadyRenamedError(id, newName))
    } else {
      val nextSeqNr = seqNr + 1
      Right(
        (
          copy(name = newName, seqNr = nextSeqNr, lastUpdatedAt = occurredAt),
          GroupChatEvent.Renamed(newName, executorId)
        )
      )
    }
  }

  def addMember(
      userAccountId: UserAccountId,
      role: MemberRole,
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(NotAdministratorError(id, executorId))
    } else if (members.isMember(userAccountId)) {
      Left(AlreadyMemberError(id, userAccountId))
    } else {
      val member    = Member.newMember(userAccountId, role)
      val nextSeqNr = seqNr + 1
      Right(
        (
          copy(members = members.add(member), seqNr = nextSeqNr, lastUpdatedAt = occurredAt),
          GroupChatEvent.MemberAdded(member, executorId)
        )
      )
    }
  }

  def removeMember(
      userAccountId: UserAccountId,
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(NotAdministratorError(id, executorId))
    } else if (!members.isMember(userAccountId)) {
      Left(NotMemberError(id, userAccountId))
    } else {
      val nextSeqNr = seqNr + 1
      Right(
        (
          copy(
            members = members.removeByUserAccountId(userAccountId),
            seqNr = nextSeqNr,
            lastUpdatedAt = occurredAt
          ),
          GroupChatEvent.MemberRemoved(userAccountId, executorId)
        )
      )
    }
  }

  def postMessage(
      body: MessageBody,
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(NotMemberError(id, executorId))
    } else {
      val message   = Message.newMessage(body, executorId)
      val nextSeqNr = seqNr + 1
      Right(
        (
          copy(messages = messages.add(message), seqNr = nextSeqNr, lastUpdatedAt = occurredAt),
          GroupChatEvent.MessagePosted(message, executorId)
        )
      )
    }
  }

  def editMessage(
      messageId: MessageId,
      body: MessageBody,
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(NotMemberError(id, executorId))
    } else {
      messages.findById(messageId) match {
        case None =>
          Left(MessageNotFoundError(id, messageId))
        case Some(message) if message.senderId != executorId =>
          Left(NotMessageSenderError(id, messageId, executorId))
        case Some(_) =>
          val nextSeqNr = seqNr + 1
          Right(
            (
              copy(messages = messages.editBody(messageId, body), seqNr = nextSeqNr, lastUpdatedAt = occurredAt),
              GroupChatEvent.MessageEdited(messageId, body, executorId)
            )
          )
      }
    }
  }

  def deleteMessage(
      messageId: MessageId,
      executorId: UserAccountId,
      occurredAt: Instant
  ): Either[GroupChatError, (GroupChat, GroupChatEvent)] = {
    if (deleted) {
      Left(AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(NotMemberError(id, executorId))
    } else {
      messages.findById(messageId) match {
        case None =>
          Left(MessageNotFoundError(id, messageId))
        case Some(message) if message.senderId != executorId =>
          Left(NotMessageSenderError(id, messageId, executorId))
        case Some(_) =>
          val nextSeqNr = seqNr + 1
          Right(
            (
              copy(messages = messages.removeById(messageId), seqNr = nextSeqNr, lastUpdatedAt = occurredAt),
              GroupChatEvent.MessageDeleted(messageId, executorId)
            )
          )
      }
    }
  }

  def applyEvent(event: GroupChatEvent, eventSeqNr: Long, occurredAt: Instant): GroupChat =
    event match {
      case _: GroupChatEvent.Created =>
        throw new IllegalStateException("a created event can only be applied to an empty aggregate")
      case GroupChatEvent.Deleted(_) =>
        copy(deleted = true, seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
      case GroupChatEvent.Renamed(newName, _) =>
        copy(name = newName, seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
      case GroupChatEvent.MemberAdded(member, _) =>
        copy(members = members.add(member), seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
      case GroupChatEvent.MemberRemoved(userAccountId, _) =>
        copy(members = members.removeByUserAccountId(userAccountId), seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
      case GroupChatEvent.MessagePosted(message, _) =>
        copy(messages = messages.add(message), seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
      case GroupChatEvent.MessageEdited(messageId, body, _) =>
        copy(messages = messages.editBody(messageId, body), seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
      case GroupChatEvent.MessageDeleted(messageId, _) =>
        copy(messages = messages.removeById(messageId), seqNr = eventSeqNr, lastUpdatedAt = occurredAt)
    }
}

object GroupChat {

  final val SnapshotManifest: String = "group-chat@1"

  def create(
      id: GroupChatId,
      name: GroupChatName,
      executorId: UserAccountId,
      occurredAt: Instant
  ): (GroupChat, GroupChatEvent.Created) = {
    val owner = Member.newMember(executorId, MemberRole.Admin)
    val state = GroupChat(
      id = id,
      deleted = false,
      name = name,
      members = Members(Vector(owner)),
      messages = Messages.empty,
      seqNr = 1L,
      lastUpdatedAt = occurredAt
    )
    (state, GroupChatEvent.Created(name, owner, executorId))
  }

  def applyEvent(
      current: Option[GroupChat],
      id: GroupChatId,
      event: GroupChatEvent,
      eventSeqNr: Long,
      occurredAt: Instant
  ): GroupChat =
    current match {
      case Some(state) =>
        state.applyEvent(event, eventSeqNr, occurredAt)
      case None =>
        event match {
          case GroupChatEvent.Created(name, owner, _) =>
            GroupChat(
              id = id,
              deleted = false,
              name = name,
              members = Members(Vector(owner)),
              messages = Messages.empty,
              seqNr = eventSeqNr,
              lastUpdatedAt = occurredAt
            )
          case other =>
            throw new IllegalStateException(s"the first event must be created: ${other.eventType}")
        }
    }

  def replay(
      id: GroupChatId,
      snapshot: Option[GroupChat],
      events: Seq[(GroupChatEvent, Long, Instant)]
  ): Option[GroupChat] =
    events.foldLeft(snapshot) { (current, entry) =>
      val (event, seqNr, occurredAt) = entry
      Some(applyEvent(current, id, event, seqNr, occurredAt))
    }
}
