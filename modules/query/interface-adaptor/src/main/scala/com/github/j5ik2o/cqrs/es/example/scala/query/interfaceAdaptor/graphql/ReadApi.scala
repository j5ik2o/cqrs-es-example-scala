package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.graphql

import caliban.CalibanError
import caliban.GraphQL
import caliban.RootResolver
import caliban.Value
import caliban.graphQL
import caliban.schema.ArgBuilder
import caliban.schema.ArgBuilder.auto._
import caliban.schema.Schema
import caliban.schema.Schema.auto._
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.GroupChatRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.MemberRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.MessageRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import zio.Task
import zio.ZIO

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.util.Try

/** A datetime without a time zone. The read model stores UTC values, so the value is rendered in UTC. */
final case class NaiveDateTime(value: LocalDateTime)

object NaiveDateTime {

  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

  def fromInstant(instant: Instant): NaiveDateTime =
    NaiveDateTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

  def render(value: NaiveDateTime): String = value.value.format(formatter)
}

final case class GroupChat(
    id: String,
    name: String,
    ownerId: String,
    createdAt: NaiveDateTime,
    updatedAt: NaiveDateTime
)

final case class Member(
    id: String,
    groupChatId: String,
    userAccountId: String,
    role: String,
    createdAt: NaiveDateTime,
    updatedAt: NaiveDateTime
)

final case class Message(
    id: String,
    groupChatId: String,
    userAccountId: String,
    text: String,
    createdAt: NaiveDateTime,
    updatedAt: NaiveDateTime
)

final case class GetGroupChatArgs(groupChatId: String, userAccountId: String)
final case class GetGroupChatsArgs(userAccountId: String)
final case class GetMemberArgs(groupChatId: String, userAccountId: String)
final case class GetMembersArgs(groupChatId: String, userAccountId: String)
final case class GetMessageArgs(messageId: String, userAccountId: String)
final case class GetMessagesArgs(groupChatId: String, userAccountId: String)

final case class Query(
    getGroupChat: GetGroupChatArgs => Task[Option[GroupChat]],
    getGroupChats: GetGroupChatsArgs => Task[List[GroupChat]],
    getMember: GetMemberArgs => Task[Option[Member]],
    getMembers: GetMembersArgs => Task[List[Member]],
    getMessage: GetMessageArgs => Task[Option[Message]],
    getMessages: GetMessagesArgs => Task[List[Message]]
)

object ReadApi {

  given Schema[Any, NaiveDateTime] =
    Schema.scalarSchema[NaiveDateTime](
      "NaiveDateTime",
      Some("A datetime without a time zone, rendered in UTC."),
      None,
      None,
      value => Value.StringValue(NaiveDateTime.render(value))
    )

  given Schema[Any, GroupChat] = Schema.derived
  given Schema[Any, Member]    = Schema.derived
  given Schema[Any, Message]   = Schema.derived

  given groupChatListSchema: Schema[Any, List[GroupChat]] =
    Schema.listSchema[Any, GroupChat].mapType(_.nonNull)
  given memberListSchema: Schema[Any, List[Member]] =
    Schema.listSchema[Any, Member].mapType(_.nonNull)
  given messageListSchema: Schema[Any, List[Message]] =
    Schema.listSchema[Any, Message].mapType(_.nonNull)

  given Schema[Any, Query] = Schema.derived

  def apply(dao: ReadModelDao): GraphQL[Any] =
    graphQL(
      RootResolver(
        Query(
          getGroupChat = args => optional(dao.findGroupChat(args.groupChatId, args.userAccountId))(toGroupChat),
          getGroupChats = args => list(dao.findGroupChats(args.userAccountId))(toGroupChat),
          getMember = args => optional(dao.findMember(args.groupChatId, args.userAccountId))(toMember),
          getMembers = args => list(dao.findMembers(args.groupChatId, args.userAccountId))(toMember),
          getMessage = args => optional(dao.findMessage(args.messageId, args.userAccountId))(toMessage),
          getMessages = args => list(dao.findMessages(args.groupChatId, args.userAccountId))(toMessage)
        )
      )
    )

  private def toGroupChat(record: GroupChatRecord): GroupChat =
    GroupChat(
      id = record.id,
      name = record.name,
      ownerId = record.ownerId,
      createdAt = NaiveDateTime.fromInstant(record.createdAt),
      updatedAt = NaiveDateTime.fromInstant(record.updatedAt)
    )

  private def toMember(record: MemberRecord): Member =
    Member(
      id = record.id,
      groupChatId = record.groupChatId,
      userAccountId = record.userAccountId,
      role = record.role,
      createdAt = NaiveDateTime.fromInstant(record.createdAt),
      updatedAt = NaiveDateTime.fromInstant(record.updatedAt)
    )

  private def toMessage(record: MessageRecord): Message =
    Message(
      id = record.id,
      groupChatId = record.groupChatId,
      userAccountId = record.userAccountId,
      text = record.text,
      createdAt = NaiveDateTime.fromInstant(record.createdAt),
      updatedAt = NaiveDateTime.fromInstant(record.updatedAt)
    )

  private def optional[A, B](result: Try[Option[A]])(map: A => B): Task[Option[B]] =
    ZIO.fromTry(result).mapError(toExecutionError).flatMap {
      case Some(value) => ZIO.succeed(Some(map(value)))
      case None        => ZIO.fail(CalibanError.ExecutionError("not found"))
    }

  private def list[A, B](result: Try[Vector[A]])(map: A => B): Task[List[B]] =
    ZIO.fromTry(result).mapError(toExecutionError).map(_.map(map).toList)

  private def toExecutionError(error: Throwable): CalibanError =
    CalibanError.ExecutionError(
      Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName),
      innerThrowable = Some(error)
    )
}
