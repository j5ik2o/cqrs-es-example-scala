package com.github.j5ik2o.cqrs.es.example.scala.command.processor

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatError
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatRepository

import java.time.Clock
import java.time.Instant
import scala.util.Try

enum CommandError {

  case DomainError(cause: GroupChatError)
  case SystemError(cause: Throwable)

  def message: String = this match {
    case DomainError(cause) =>
      cause.message
    case SystemError(cause) =>
      Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getSimpleName)
  }
}

sealed trait SnapshotPolicy {

  def shouldSnapshot(seqNr: Long): Boolean
}

object SnapshotPolicy {

  case object Never extends SnapshotPolicy {
    override def shouldSnapshot(seqNr: Long): Boolean = false
  }

  case object Always extends SnapshotPolicy {
    override def shouldSnapshot(seqNr: Long): Boolean = true
  }

  final case class Every(interval: Long) extends SnapshotPolicy {
    require(interval > 0, "interval must be positive")

    override def shouldSnapshot(seqNr: Long): Boolean = seqNr % interval == 0L
  }
}

final class GroupChatCommandProcessor(
    repository: GroupChatRepository,
    clock: Clock = Clock.systemUTC(),
    snapshotPolicy: SnapshotPolicy = SnapshotPolicy.Every(10)
) {

  def createGroupChat(name: GroupChatName, executorId: UserAccountId): Either[CommandError, GroupChatId] = {
    val id             = GroupChatId.newId
    val occurredAt     = Instant.now(clock)
    val (state, event) = GroupChat.create(id, name, executorId, occurredAt)
    persist(id, state, event, occurredAt, snapshot = true).map(_ => id)
  }

  def deleteGroupChat(id: GroupChatId, executorId: UserAccountId): Either[CommandError, GroupChatId] =
    update(id) { (state, occurredAt) =>
      state.delete(executorId, occurredAt)
    }.map(_ => id)

  def renameGroupChat(
      id: GroupChatId,
      name: GroupChatName,
      executorId: UserAccountId
  ): Either[CommandError, GroupChatId] =
    update(id) { (state, occurredAt) =>
      state.rename(name, executorId, occurredAt)
    }.map(_ => id)

  def addMember(
      id: GroupChatId,
      userAccountId: UserAccountId,
      role: MemberRole,
      executorId: UserAccountId
  ): Either[CommandError, GroupChatId] =
    update(id) { (state, occurredAt) =>
      state.addMember(userAccountId, role, executorId, occurredAt)
    }.map(_ => id)

  def removeMember(
      id: GroupChatId,
      userAccountId: UserAccountId,
      executorId: UserAccountId
  ): Either[CommandError, GroupChatId] =
    update(id) { (state, occurredAt) =>
      state.removeMember(userAccountId, executorId, occurredAt)
    }.map(_ => id)

  def postMessage(
      id: GroupChatId,
      body: MessageBody,
      executorId: UserAccountId
  ): Either[CommandError, (GroupChatId, MessageId)] =
    update(id) { (state, occurredAt) =>
      state.postMessage(body, executorId, occurredAt)
    }.flatMap {
      case (_, event: GroupChatEvent.MessagePosted) => Right((id, event.message.id))
      case (_, other) =>
        Left(CommandError.SystemError(new IllegalStateException(s"unexpected event: ${other.eventType}")))
    }

  def editMessage(
      id: GroupChatId,
      messageId: MessageId,
      body: MessageBody,
      executorId: UserAccountId
  ): Either[CommandError, GroupChatId] =
    update(id) { (state, occurredAt) =>
      state.editMessage(messageId, body, executorId, occurredAt)
    }.map(_ => id)

  def deleteMessage(
      id: GroupChatId,
      messageId: MessageId,
      executorId: UserAccountId
  ): Either[CommandError, GroupChatId] =
    update(id) { (state, occurredAt) =>
      state.deleteMessage(messageId, executorId, occurredAt)
    }.map(_ => id)

  private def update(
      id: GroupChatId
  )(
      operation: (GroupChat, Instant) => Either[GroupChatError, (GroupChat, GroupChatEvent)]
  ): Either[CommandError, (GroupChat, GroupChatEvent)] =
    load(id).flatMap { state =>
      val occurredAt = Instant.now(clock)
      operation(state, occurredAt).left.map(CommandError.DomainError.apply).flatMap { case (newState, event) =>
        persist(id, newState, event, occurredAt, snapshotPolicy.shouldSnapshot(newState.seqNr)).map(_ =>
          (newState, event)
        )
      }
    }

  private def load(id: GroupChatId): Either[CommandError, GroupChat] =
    fromTry(repository.findById(id)).flatMap {
      case Some(state) => Right(state)
      case None        => Left(CommandError.DomainError(GroupChatError.GroupChatNotFoundError(id)))
    }

  private def persist(
      id: GroupChatId,
      state: GroupChat,
      event: GroupChatEvent,
      occurredAt: Instant,
      snapshot: Boolean
  ): Either[CommandError, Unit] =
    fromTry {
      if (snapshot) repository.persistEventAndSnapshot(id, event, state, occurredAt)
      else repository.persistEvent(id, event, state.seqNr, occurredAt)
    }

  private def fromTry[A](value: => Try[A]): Either[CommandError, A] =
    value.toEither.left.map(CommandError.SystemError.apply)
}
