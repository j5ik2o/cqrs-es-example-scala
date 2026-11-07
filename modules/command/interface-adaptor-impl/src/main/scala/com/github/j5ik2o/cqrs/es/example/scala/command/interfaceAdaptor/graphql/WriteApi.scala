package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.graphql

import caliban.CalibanError
import caliban.GraphQL
import caliban.RootResolver
import caliban.graphQL
import caliban.schema.ArgBuilder
import caliban.schema.ArgBuilder.auto._
import caliban.schema.Schema
import caliban.schema.Schema.auto._
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.processor.CommandError
import com.github.j5ik2o.cqrs.es.example.scala.command.processor.GroupChatCommandProcessor
import zio.Task
import zio.UIO
import zio.ZIO

enum Role {
  case MEMBER
  case ADMIN
}

final case class CreateGroupChatInput(name: String, executorId: String)
final case class DeleteGroupChatInput(groupChatId: String, executorId: String)
final case class RenameGroupChatInput(groupChatId: String, name: String, executorId: String)
final case class AddMemberInput(groupChatId: String, userAccountId: String, role: Role, executorId: String)
final case class RemoveMemberInput(groupChatId: String, userAccountId: String, executorId: String)
final case class PostMessageInput(groupChatId: String, content: String, executorId: String)
final case class EditMessageInput(groupChatId: String, messageId: String, content: String, executorId: String)
final case class DeleteMessageInput(groupChatId: String, messageId: String, executorId: String)

final case class CreateGroupChatArgs(input: CreateGroupChatInput)
final case class DeleteGroupChatArgs(input: DeleteGroupChatInput)
final case class RenameGroupChatArgs(input: RenameGroupChatInput)
final case class AddMemberArgs(input: AddMemberInput)
final case class RemoveMemberArgs(input: RemoveMemberInput)
final case class PostMessageArgs(input: PostMessageInput)
final case class EditMessageArgs(input: EditMessageInput)
final case class DeleteMessageArgs(input: DeleteMessageInput)

final case class GroupChatResult(groupChatId: String)
final case class MessageResult(groupChatId: String, messageId: String)

final case class Query(healthCheck: UIO[String])

final case class Mutation(
    createGroupChat: CreateGroupChatArgs => Task[GroupChatResult],
    deleteGroupChat: DeleteGroupChatArgs => Task[GroupChatResult],
    renameGroupChat: RenameGroupChatArgs => Task[GroupChatResult],
    addMember: AddMemberArgs => Task[GroupChatResult],
    removeMember: RemoveMemberArgs => Task[GroupChatResult],
    postMessage: PostMessageArgs => Task[MessageResult],
    editMessage: EditMessageArgs => Task[GroupChatResult],
    deleteMessage: DeleteMessageArgs => Task[GroupChatResult]
)

object WriteApi {
  given Schema[Any, Role]            = Schema.derived
  given Schema[Any, GroupChatResult] = Schema.derived
  given Schema[Any, MessageResult]   = Schema.derived
  given Schema[Any, Query]           = Schema.derived
  given Schema[Any, Mutation]        = Schema.derived

  def apply(processor: GroupChatCommandProcessor): GraphQL[Any] = {
    val query = Query(ZIO.succeed("OK"))
    val mutation = Mutation(
      createGroupChat = args =>
        for {
          name <- parse(GroupChatName.fromString(args.input.name))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          id <- execute(processor.createGroupChat(name, executor))
        } yield GroupChatResult(id.asString),
      deleteGroupChat = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(processor.deleteGroupChat(id, executor))
        } yield GroupChatResult(result.asString),
      renameGroupChat = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          name <- parse(GroupChatName.fromString(args.input.name))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(processor.renameGroupChat(id, name, executor))
        } yield GroupChatResult(result.asString),
      addMember = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          userAccountId <- parse(UserAccountId.fromString(args.input.userAccountId))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(
            processor.addMember(id, userAccountId, toMemberRole(args.input.role), executor)
          )
        } yield GroupChatResult(result.asString),
      removeMember = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          userAccountId <- parse(UserAccountId.fromString(args.input.userAccountId))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(processor.removeMember(id, userAccountId, executor))
        } yield GroupChatResult(result.asString),
      postMessage = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          body <- parse(MessageBody.fromString(args.input.content))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(processor.postMessage(id, body, executor))
        } yield MessageResult(result._1.asString, result._2.asString),
      editMessage = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          messageId <- parse(MessageId.fromString(args.input.messageId))
          body <- parse(MessageBody.fromString(args.input.content))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(processor.editMessage(id, messageId, body, executor))
        } yield GroupChatResult(result.asString),
      deleteMessage = args =>
        for {
          id <- parse(GroupChatId.fromString(args.input.groupChatId))
          messageId <- parse(MessageId.fromString(args.input.messageId))
          executor <- parse(UserAccountId.fromString(args.input.executorId))
          result <- execute(processor.deleteMessage(id, messageId, executor))
        } yield GroupChatResult(result.asString)
    )
    graphQL(RootResolver(query, mutation))
  }

  private def toMemberRole(role: Role): MemberRole = role match {
    case Role.MEMBER => MemberRole.Member
    case Role.ADMIN  => MemberRole.Admin
  }

  private def parse[A](value: Either[String, A]): Task[A] =
    ZIO.fromEither(value).mapError(message => CalibanError.ExecutionError(message))

  private def execute[A](value: Either[CommandError, A]): Task[A] =
    ZIO.fromEither(value).mapError {
      case CommandError.DomainError(cause) =>
        CalibanError.ExecutionError(cause.message)
      case CommandError.SystemError(cause) =>
        CalibanError.ExecutionError(
          Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getSimpleName),
          innerThrowable = Some(cause)
        )
    }
}
