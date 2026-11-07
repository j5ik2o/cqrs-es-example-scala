package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId

import java.time.Instant
import scala.util.Try

trait GroupChatRepository {

  def findById(id: GroupChatId): Try[Option[GroupChat]]

  def persistEvent(
      id: GroupChatId,
      event: GroupChatEvent,
      seqNr: Long,
      occurredAt: Instant
  ): Try[Unit]

  def persistEventAndSnapshot(
      id: GroupChatId,
      event: GroupChatEvent,
      snapshot: GroupChat,
      occurredAt: Instant
  ): Try[Unit]
}
