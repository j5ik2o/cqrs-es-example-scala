package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.event.store.adapter.java.Aggregate

import java.time.Instant

final case class GroupChat(
    private val id: GroupChatId,
    private val deleted: Boolean,
    private val name: GroupChatName,
    private val members: Members,
    private val messages: Messages,
    private val seqNrCounter: Long,
    private val version: Long,
    private val lastUpdatedAt: Instant
) extends Aggregate[GroupChat, GroupChatId] {

  override def getId: GroupChatId                    = id
  override def getSequenceNumber: Long               = seqNrCounter
  override def getVersion: Long                      = version
  override def withVersion(version: Long): GroupChat = copy(version = version)

  def delete(executorId: UserAccountId, version: Long): Either[GroupChatError, GroupChat] = {
    if (this.version != version) {
      Left(GroupChatError.VersionConflictError(id, this.version, version))
    } else if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else {
      Right(copy(deleted = true, version = version + 1, lastUpdatedAt = Instant.now()))
    }
  }

  def rename(name: GroupChatName, executorId: UserAccountId, version: Long): Either[GroupChatError, GroupChat] = {
    if (this.version != version) {
      Left(GroupChatError.VersionConflictError(id, this.version, version))
    } else if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else {
      Right(copy(name = name, version = version + 1, lastUpdatedAt = Instant.now()))
    }
  }

  def addMember(member: Member, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (this.version != version) {
      Left(GroupChatError.VersionConflictError(id, this.version, version))
    } else if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isMember(member.userAccountId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else if (members.isMember(executorId)) {
      Left(GroupChatError.AlreadyMemberError(id))
    } else {
      Right(copy(members = members.add(member), version = version + 1, lastUpdatedAt = Instant.now()))
    }
  }

  def removeMember(userAccountId: UserAccountId, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (this.version != version) {
      Left(GroupChatError.VersionConflictError(id, this.version, version))
    } else if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isAdministrator(executorId)) {
      Left(GroupChatError.NotAdministratorError(id))
    } else if (!members.isMember(executorId)) {
      Left(GroupChatError.NotMemberError(id))
    } else {
      Right(
        copy(
          members = members.removeByUserAccountId(userAccountId),
          version = version + 1,
          lastUpdatedAt = Instant.now()
        )
      )
    }
  }

  def postMessage(message: Message, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (this.version != version) {
      Left(GroupChatError.VersionConflictError(id, this.version, version))
    } else if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(GroupChatError.NotMemberError(id))
    } else {
      Right(copy(messages = messages.add(message), version = version + 1, lastUpdatedAt = Instant.now()))
    }
  }

  def deleteMessage(messageId: MessageId, executorId: UserAccountId): Either[GroupChatError, GroupChat] = {
    if (this.version != version) {
      Left(GroupChatError.VersionConflictError(id, this.version, version))
    } else if (deleted) {
      Left(GroupChatError.AlreadyDeletedError(id))
    } else if (!members.isMember(executorId)) {
      Left(GroupChatError.NotMemberError(id))
    } else if (!messages.existsById(messageId)) {
      Left(GroupChatError.MessageNotFoundError(id, messageId))
    } else {
      Right(copy(messages = messages.removeById(messageId), version = version + 1, lastUpdatedAt = Instant.now()))
    }
  }

  // def applyEvent(event: GroupChatEvent): GroupChat =
}
