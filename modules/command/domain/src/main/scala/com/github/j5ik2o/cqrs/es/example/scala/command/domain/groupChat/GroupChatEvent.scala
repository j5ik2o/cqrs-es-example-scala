package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.event.store.adapter.java.Event
import wvlet.airframe.ulid.ULID

import java.time.Instant

sealed trait GroupChatEvent extends Event[GroupChatId]{
  def id: ULID
  def aggregateId: GroupChatId
  def sequenceNumber: Long
  def occurredAt: Instant

  override def getId: String = id.toString
  override def getAggregateId: GroupChatId = aggregateId
  override def getSequenceNumber: Long = sequenceNumber
  override def getOccurredAt: Instant = occurredAt
  override def isCreated: Boolean = false
}

object GroupChatEvent {
  final case class Created(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, name: String, occurredAt: Instant) extends GroupChatEvent {
    override def isCreated: Boolean = true
  }
  final case class Deleted(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, occurredAt: Instant) extends GroupChatEvent
  final case class Renamed(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, name: String, occurredAt: Instant) extends GroupChatEvent
  final case class MemberAdded(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, member: Member, occurredAt: Instant) extends GroupChatEvent
  final case class MemberRemoved(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, userAccountId: UserAccountId, occurredAt: Instant) extends GroupChatEvent
  final case class MessagePosted(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, message: Message, occurredAt: Instant) extends GroupChatEvent
  final case class MessageDeleted(id: ULID, aggregateId: GroupChatId, sequenceNumber: Long, messageId: MessageId, occurredAt: Instant) extends GroupChatEvent
}
