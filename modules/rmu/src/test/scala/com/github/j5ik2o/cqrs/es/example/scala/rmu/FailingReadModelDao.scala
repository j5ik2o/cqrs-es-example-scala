package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.GroupChatRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.MemberRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.MessageRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao

import java.time.Instant
import scala.util.Try

/** A read model decorator that fails the projection of one chosen `seq_nr`.
  *
  * It reproduces a failure in the middle of a batch without touching the in-memory read model: the projections before
  * the failing one are committed, the failing one is not, and a retry after clearing the failure continues from it.
  */
final class FailingReadModelDao(delegate: ReadModelDao) extends ReadModelDao {

  @volatile var failOnSeqNr: Option[Long] = None

  override def findGroupChat(groupChatId: String, userAccountId: String): Try[Option[GroupChatRecord]] =
    delegate.findGroupChat(groupChatId, userAccountId)

  override def findGroupChats(userAccountId: String): Try[Vector[GroupChatRecord]] =
    delegate.findGroupChats(userAccountId)

  override def findMember(groupChatId: String, userAccountId: String): Try[Option[MemberRecord]] =
    delegate.findMember(groupChatId, userAccountId)

  override def findMembers(groupChatId: String, userAccountId: String): Try[Vector[MemberRecord]] =
    delegate.findMembers(groupChatId, userAccountId)

  override def findMessage(messageId: String, userAccountId: String): Try[Option[MessageRecord]] =
    delegate.findMessage(messageId, userAccountId)

  override def findMessages(groupChatId: String, userAccountId: String): Try[Vector[MessageRecord]] =
    delegate.findMessages(groupChatId, userAccountId)

  override def lastProcessedSeqNr(aggregateId: String): Try[Long] =
    delegate.lastProcessedSeqNr(aggregateId)

  override def insertGroupChat(
      aggregateId: String,
      name: String,
      administrator: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.insertGroupChat(aggregateId, name, administrator, occurredAt, seqNr)
  }

  override def deleteGroupChat(aggregateId: String, occurredAt: Instant, seqNr: Long): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.deleteGroupChat(aggregateId, occurredAt, seqNr)
  }

  override def updateGroupChatName(
      aggregateId: String,
      name: String,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.updateGroupChatName(aggregateId, name, occurredAt, seqNr)
  }

  override def insertMember(
      aggregateId: String,
      member: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.insertMember(aggregateId, member, occurredAt, seqNr)
  }

  override def deleteMember(
      aggregateId: String,
      userAccountId: UserAccountId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.deleteMember(aggregateId, userAccountId, occurredAt, seqNr)
  }

  override def insertMessage(
      aggregateId: String,
      message: Message,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.insertMessage(aggregateId, message, occurredAt, seqNr)
  }

  override def updateMessage(
      aggregateId: String,
      messageId: MessageId,
      body: MessageBody,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.updateMessage(aggregateId, messageId, body, occurredAt, seqNr)
  }

  override def deleteMessage(
      aggregateId: String,
      messageId: MessageId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(seqNr)
    delegate.deleteMessage(aggregateId, messageId, occurredAt, seqNr)
  }

  private def guard(seqNr: Long): Unit =
    if (failOnSeqNr.contains(seqNr)) {
      throw new IllegalStateException(s"injected projection failure at seq_nr $seqNr")
    }
}
