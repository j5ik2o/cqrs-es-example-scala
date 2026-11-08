package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

import java.time.Instant
import scala.collection.mutable
import scala.util.Try

/** Process-local read model used by the tests and by local checks without MySQL.
  *
  * It follows the same monotonic projection contract as [[JdbcReadModelDao]].
  */
final class InMemoryReadModelDao extends ReadModelDao {

  private final case class GroupChatRow(
      id: String,
      disabled: Boolean,
      name: String,
      ownerId: String,
      seqNr: Long,
      createdAt: Instant,
      updatedAt: Instant
  )

  private final case class MemberRow(
      id: String,
      groupChatId: String,
      userAccountId: String,
      role: String,
      disabled: Boolean,
      seqNr: Long,
      createdAt: Instant,
      updatedAt: Instant
  )

  private final case class MessageRow(
      id: String,
      disabled: Boolean,
      groupChatId: String,
      userAccountId: String,
      text: String,
      seqNr: Long,
      createdAt: Instant,
      updatedAt: Instant
  )

  private val groupChats = mutable.LinkedHashMap.empty[String, GroupChatRow]
  private val members    = mutable.LinkedHashMap.empty[(String, String), MemberRow]
  private val messages   = mutable.LinkedHashMap.empty[String, MessageRow]
  private val positions  = mutable.LinkedHashMap.empty[String, Long]

  override def findGroupChat(groupChatId: String, userAccountId: String): Try[Option[GroupChatRecord]] =
    Try(synchronized {
      for {
        groupChat <- activeGroupChat(groupChatId)
        _ <- activeMember(groupChatId, userAccountId)
      } yield toRecord(groupChat)
    })

  override def findGroupChats(userAccountId: String): Try[Vector[GroupChatRecord]] =
    Try(synchronized {
      groupChats.valuesIterator
        .filter(groupChat => !groupChat.disabled && activeMember(groupChat.id, userAccountId).isDefined)
        .map(toRecord)
        .toVector
        .sortBy(record => (record.createdAt, record.id))
    })

  override def findMember(groupChatId: String, userAccountId: String): Try[Option[MemberRecord]] =
    Try(synchronized {
      for {
        _ <- activeGroupChat(groupChatId)
        member <- activeMember(groupChatId, userAccountId)
      } yield toRecord(member)
    })

  override def findMembers(groupChatId: String, userAccountId: String): Try[Vector[MemberRecord]] =
    Try(synchronized {
      for {
        _ <- activeGroupChat(groupChatId)
        _ <- activeMember(groupChatId, userAccountId)
      } yield members.valuesIterator
        .filter(member => member.groupChatId == groupChatId && !member.disabled)
        .map(toRecord)
        .toVector
        .sortBy(record => (record.createdAt, record.id))
    }.getOrElse(Vector.empty))

  override def findMessage(messageId: String, userAccountId: String): Try[Option[MessageRecord]] =
    Try(synchronized {
      for {
        message <- activeMessage(messageId)
        _ <- activeGroupChat(message.groupChatId)
        _ <- activeMember(message.groupChatId, userAccountId)
      } yield toRecord(message)
    })

  override def findMessages(groupChatId: String, userAccountId: String): Try[Vector[MessageRecord]] =
    Try(synchronized {
      for {
        _ <- activeGroupChat(groupChatId)
        _ <- activeMember(groupChatId, userAccountId)
      } yield messages.valuesIterator
        .filter(message => message.groupChatId == groupChatId && !message.disabled)
        .map(toRecord)
        .toVector
        .sortBy(record => (record.createdAt, record.id))
    }.getOrElse(Vector.empty))

  override def lastProcessedSeqNr(aggregateId: String): Try[Long] =
    Try(synchronized {
      positions.getOrElse(aggregateId, 0L)
    })

  override def insertGroupChat(
      aggregateId: String,
      name: String,
      administrator: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      val existingCreatedAt = groupChats.get(aggregateId).map(_.createdAt).getOrElse(occurredAt)
      groupChats.update(
        aggregateId,
        GroupChatRow(
          id = aggregateId,
          disabled = false,
          name = name,
          ownerId = administrator.userAccountId.asString,
          seqNr = seqNr,
          createdAt = existingCreatedAt,
          updatedAt = occurredAt
        )
      )
      upsertMember(aggregateId, administrator, occurredAt, seqNr)
    }

  override def deleteGroupChat(aggregateId: String, occurredAt: Instant, seqNr: Long): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      groupChats.get(aggregateId).foreach { row =>
        groupChats.update(aggregateId, row.copy(disabled = true, seqNr = seqNr, updatedAt = occurredAt))
      }
    }

  override def updateGroupChatName(
      aggregateId: String,
      name: String,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      groupChats.get(aggregateId).foreach { row =>
        groupChats.update(aggregateId, row.copy(name = name, seqNr = seqNr, updatedAt = occurredAt))
      }
    }

  override def insertMember(
      aggregateId: String,
      member: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      upsertMember(aggregateId, member, occurredAt, seqNr)
    }

  override def deleteMember(
      aggregateId: String,
      userAccountId: UserAccountId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      val key = aggregateId -> userAccountId.asString
      members.get(key).foreach { row =>
        members.update(key, row.copy(disabled = true, seqNr = seqNr, updatedAt = occurredAt))
      }
    }

  override def insertMessage(
      aggregateId: String,
      message: Message,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      val existingCreatedAt = messages.get(message.id.asString).map(_.createdAt).getOrElse(occurredAt)
      messages.update(
        message.id.asString,
        MessageRow(
          id = message.id.asString,
          disabled = false,
          groupChatId = aggregateId,
          userAccountId = message.senderId.asString,
          text = message.body.value,
          seqNr = seqNr,
          createdAt = existingCreatedAt,
          updatedAt = occurredAt
        )
      )
    }

  override def updateMessage(
      aggregateId: String,
      messageId: MessageId,
      body: MessageBody,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      messages.get(messageId.asString).foreach { row =>
        messages.update(
          messageId.asString,
          row.copy(text = body.value, seqNr = seqNr, updatedAt = occurredAt)
        )
      }
    }

  override def deleteMessage(
      aggregateId: String,
      messageId: MessageId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    project(aggregateId, seqNr) {
      messages.get(messageId.asString).foreach { row =>
        messages.update(messageId.asString, row.copy(disabled = true, seqNr = seqNr, updatedAt = occurredAt))
      }
    }

  private def upsertMember(aggregateId: String, member: Member, occurredAt: Instant, seqNr: Long): Unit = {
    val key               = aggregateId -> member.userAccountId.asString
    val existingCreatedAt = members.get(key).map(_.createdAt).getOrElse(occurredAt)
    members.update(
      key,
      MemberRow(
        id = member.id.asString,
        groupChatId = aggregateId,
        userAccountId = member.userAccountId.asString,
        role = member.role.asString,
        disabled = false,
        seqNr = seqNr,
        createdAt = existingCreatedAt,
        updatedAt = occurredAt
      )
    )
  }

  private def project(aggregateId: String, seqNr: Long)(apply: => Unit): Try[ProjectionResult] =
    ReadModelSequenceNumber.validate(seqNr).flatMap { _ =>
      Try(synchronized {
        val last = positions.getOrElse(aggregateId, 0L)
        if (seqNr <= last) {
          ProjectionResult.Duplicate
        } else if (seqNr != last + 1L) {
          throw new ReadModelGapException(aggregateId, last + 1L, seqNr)
        } else {
          apply
          positions.update(aggregateId, seqNr)
          ProjectionResult.Applied
        }
      })
    }

  private def activeGroupChat(id: String): Option[GroupChatRow] =
    groupChats.get(id).filterNot(_.disabled)

  private def activeMember(groupChatId: String, userAccountId: String): Option[MemberRow] =
    members.get(groupChatId -> userAccountId).filterNot(_.disabled)

  private def activeMessage(id: String): Option[MessageRow] =
    messages.get(id).filterNot(_.disabled)

  private def toRecord(row: GroupChatRow): GroupChatRecord =
    GroupChatRecord(row.id, row.name, row.ownerId, row.createdAt, row.updatedAt)

  private def toRecord(row: MemberRow): MemberRecord =
    MemberRecord(row.id, row.groupChatId, row.userAccountId, row.role, row.createdAt, row.updatedAt)

  private def toRecord(row: MessageRow): MessageRecord =
    MessageRecord(row.id, row.groupChatId, row.userAccountId, row.text, row.createdAt, row.updatedAt)
}
