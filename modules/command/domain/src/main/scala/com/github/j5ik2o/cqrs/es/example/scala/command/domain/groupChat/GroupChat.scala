package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.event.store.adapter.java.Aggregate

import java.time.Instant

final case class GroupChat(private val id: GroupChatId,
                           private val deleted: Boolean,
                           private val name: GroupChatName,
                           private val members: Members,
                           private val messages: Messages,
                           private val seqNrCounter: Long,
                           private val version: Long,
                           lastUpdatedAt: Instant) extends Aggregate[GroupChat, GroupChatId] {

  override def getId: GroupChatId = id
  override def getSequenceNumber: Long = seqNrCounter
  override def getVersion: Long = version
  override def withVersion(version: Long): GroupChat = copy(version = version)

  def delete(executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else {
      Right(copy(deleted = true, lastUpdatedAt = Instant.now().nn))
    }
  }

  def rename(name: GroupChatName, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else {
      Right(copy(name = name, lastUpdatedAt = Instant.now().nn))
    }
  }

  def addMember(member: Member,  executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isMember(member.userAccountId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else if (members.isMember(executorId)) {
      Left(GroupChatError.AlreadyMemberError(id))
    } else {
      Right(copy(members = members.add(member),  lastUpdatedAt = Instant.now().nn))
    }
  }

  def removeMember(userAccountId: UserAccountId, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else if (!members.isMember(executorId)) {
      Left(GroupChatError.NotMemberError(id))
    } else {
      Right(copy(members = members.removeByUserAccountId(userAccountId),  lastUpdatedAt = Instant.now().nn))
    }
  }

  def postMessage(message: Message, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(GroupChatError.NotMemberError(id))
    } else {
      Right(copy(messages = messages.add(message),  lastUpdatedAt = Instant.now().nn))
    }
  }

  def deleteMessage(messageId: MessageId, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(GroupChatError.NotMemberError(id))
    } else if (!messages.existsById(messageId)) {
      Left(GroupChatError.MessageNotFoundError(id, messageId))
    } else {
      Right(copy(messages = messages.removeById(messageId),  lastUpdatedAt = Instant.now().nn))
    }
  }

  // def applyEvent(event: GroupChatEvent): GroupChat =
}
