package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

import java.time.Instant
import scala.util.Try

final case class GroupChatRecord(
    id: String,
    name: String,
    ownerId: String,
    createdAt: Instant,
    updatedAt: Instant
)

final case class MemberRecord(
    id: String,
    groupChatId: String,
    userAccountId: String,
    role: String,
    createdAt: Instant,
    updatedAt: Instant
)

final case class MessageRecord(
    id: String,
    groupChatId: String,
    userAccountId: String,
    text: String,
    createdAt: Instant,
    updatedAt: Instant
)

enum ProjectionResult {
  case Applied
  case Duplicate
}

/** Raised when a projection would skip a sequence number. The Read Model Updater fills the hole from the journal before
  * it retries, so this exception means the journal itself is incomplete.
  */
final class ReadModelGapException(val aggregateId: String, val expectedSeqNr: Long, val actualSeqNr: Long)
    extends RuntimeException(
      s"read model projection gap for $aggregateId: expected sequence $expectedSeqNr, got $actualSeqNr"
    )

/** Read side of the MySQL read model plus the idempotent projection operations used by the Read Model Updater.
  *
  * Every projection operation is monotonic per aggregate. It applies the change and advances `read_model_positions` in
  * one transaction. A sequence number that was already applied is reported as [[ProjectionResult.Duplicate]] and does
  * not change any row. A sequence number that skips ahead fails with [[ReadModelGapException]].
  */
trait ReadModelDao {

  def findGroupChat(groupChatId: String, userAccountId: String): Try[Option[GroupChatRecord]]

  def findGroupChats(userAccountId: String): Try[Vector[GroupChatRecord]]

  def findMember(groupChatId: String, userAccountId: String): Try[Option[MemberRecord]]

  def findMembers(groupChatId: String, userAccountId: String): Try[Vector[MemberRecord]]

  def findMessage(messageId: String, userAccountId: String): Try[Option[MessageRecord]]

  def findMessages(groupChatId: String, userAccountId: String): Try[Vector[MessageRecord]]

  def lastProcessedSeqNr(aggregateId: String): Try[Long]

  def insertGroupChat(
      aggregateId: String,
      name: String,
      administrator: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]

  def deleteGroupChat(aggregateId: String, occurredAt: Instant, seqNr: Long): Try[ProjectionResult]

  def updateGroupChatName(
      aggregateId: String,
      name: String,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]

  def insertMember(
      aggregateId: String,
      member: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]

  def deleteMember(
      aggregateId: String,
      userAccountId: UserAccountId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]

  def insertMessage(
      aggregateId: String,
      message: Message,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]

  def updateMessage(
      aggregateId: String,
      messageId: MessageId,
      body: MessageBody,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]

  def deleteMessage(
      aggregateId: String,
      messageId: MessageId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult]
}
