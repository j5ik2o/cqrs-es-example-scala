package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Members
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Messages
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax._

import java.time.Instant
import scala.util.Try

object GroupChatJsonCodecs {

  given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)

  given Decoder[Instant] = Decoder.decodeString.emap { value =>
    Try(Instant.parse(value)).toEither.left.map(error => s"invalid instant: ${error.getMessage}")
  }

  given Encoder[GroupChatId] = Encoder.encodeString.contramap(_.value)
  given Decoder[GroupChatId] = Decoder.decodeString.emap(GroupChatId.fromValue)

  given Encoder[MemberId] = Encoder.encodeString.contramap(_.value)
  given Decoder[MemberId] = Decoder.decodeString.emap(MemberId.fromString)

  given Encoder[MessageId] = Encoder.encodeString.contramap(_.value)
  given Decoder[MessageId] = Decoder.decodeString.emap(MessageId.fromString)

  given Encoder[UserAccountId] = Encoder.encodeString.contramap(_.value)
  given Decoder[UserAccountId] = Decoder.decodeString.emap(UserAccountId.fromValue)

  given Encoder[GroupChatName] = Encoder.encodeString.contramap(_.value)
  given Decoder[GroupChatName] = Decoder.decodeString.emap(GroupChatName.fromString)

  given Encoder[MessageBody] = Encoder.encodeString.contramap(_.value)
  given Decoder[MessageBody] = Decoder.decodeString.emap(MessageBody.fromString)

  given Encoder[MemberRole] = Encoder.encodeString.contramap(_.asString)
  given Decoder[MemberRole] = Decoder.decodeString.emap(MemberRole.fromString)

  given Encoder[Member] = Encoder.instance { member =>
    Json.obj(
      "id"            -> member.id.asJson,
      "userAccountId" -> member.userAccountId.asJson,
      "role"          -> member.role.asJson
    )
  }

  given Decoder[Member] = Decoder.instance { cursor =>
    for {
      id <- cursor.downField("id").as[MemberId]
      userAccountId <- cursor.downField("userAccountId").as[UserAccountId]
      role <- cursor.downField("role").as[MemberRole]
    } yield Member(id, userAccountId, role)
  }

  given Encoder[Members] = Encoder.instance(members => Json.obj("values" -> members.values.asJson))
  given Decoder[Members] = Decoder.instance { cursor =>
    cursor.downField("values").as[Vector[Member]].map(Members.apply)
  }

  given Encoder[Message] = Encoder.instance { message =>
    Json.obj(
      "id"       -> message.id.asJson,
      "body"     -> message.body.asJson,
      "senderId" -> message.senderId.asJson
    )
  }

  given Decoder[Message] = Decoder.instance { cursor =>
    for {
      id <- cursor.downField("id").as[MessageId]
      body <- cursor.downField("body").as[MessageBody]
      senderId <- cursor.downField("senderId").as[UserAccountId]
    } yield Message(id, body, senderId)
  }

  given Encoder[Messages] = Encoder.instance(messages => Json.obj("values" -> messages.values.asJson))
  given Decoder[Messages] = Decoder.instance { cursor =>
    cursor.downField("values").as[Vector[Message]].map(Messages.apply)
  }

  given Encoder[GroupChat] = Encoder.instance { groupChat =>
    Json.obj(
      "id"            -> groupChat.id.asJson,
      "deleted"       -> Json.fromBoolean(groupChat.deleted),
      "name"          -> groupChat.name.asJson,
      "members"       -> groupChat.members.asJson,
      "messages"      -> groupChat.messages.asJson,
      "seqNr"         -> Json.fromLong(groupChat.seqNr),
      "lastUpdatedAt" -> groupChat.lastUpdatedAt.asJson
    )
  }

  given Decoder[GroupChat] = Decoder.instance { cursor =>
    for {
      id <- cursor.downField("id").as[GroupChatId]
      deleted <- cursor.downField("deleted").as[Boolean]
      name <- cursor.downField("name").as[GroupChatName]
      members <- cursor.downField("members").as[Members]
      messages <- cursor.downField("messages").as[Messages]
      seqNr <- cursor.downField("seqNr").as[Long]
      lastUpdatedAt <- cursor.downField("lastUpdatedAt").as[Instant]
    } yield GroupChat(id, deleted, name, members, messages, seqNr, lastUpdatedAt)
  }

  given Encoder[GroupChatEvent] = Encoder.instance {
    case event: GroupChatEvent.Created =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "name"       -> event.name.asJson,
        "owner"      -> event.owner.asJson,
        "executorId" -> event.executorId.asJson
      )
    case event: GroupChatEvent.Deleted =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "executorId" -> event.executorId.asJson
      )
    case event: GroupChatEvent.Renamed =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "name"       -> event.name.asJson,
        "executorId" -> event.executorId.asJson
      )
    case event: GroupChatEvent.MemberAdded =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "member"     -> event.member.asJson,
        "executorId" -> event.executorId.asJson
      )
    case event: GroupChatEvent.MemberRemoved =>
      Json.obj(
        "type"          -> Json.fromString(event.eventType),
        "userAccountId" -> event.userAccountId.asJson,
        "executorId"    -> event.executorId.asJson
      )
    case event: GroupChatEvent.MessagePosted =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "message"    -> event.message.asJson,
        "executorId" -> event.executorId.asJson
      )
    case event: GroupChatEvent.MessageEdited =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "messageId"  -> event.messageId.asJson,
        "body"       -> event.body.asJson,
        "executorId" -> event.executorId.asJson
      )
    case event: GroupChatEvent.MessageDeleted =>
      Json.obj(
        "type"       -> Json.fromString(event.eventType),
        "messageId"  -> event.messageId.asJson,
        "executorId" -> event.executorId.asJson
      )
  }

  given Decoder[GroupChatEvent] = Decoder.instance { cursor =>
    cursor.downField("type").as[String].flatMap {
      case GroupChatEvent.CreatedType =>
        for {
          name <- cursor.downField("name").as[GroupChatName]
          owner <- cursor.downField("owner").as[Member]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.Created(name, owner, executorId)
      case GroupChatEvent.DeletedType =>
        cursor.downField("executorId").as[UserAccountId].map(GroupChatEvent.Deleted.apply)
      case GroupChatEvent.RenamedType =>
        for {
          name <- cursor.downField("name").as[GroupChatName]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.Renamed(name, executorId)
      case GroupChatEvent.MemberAddedType =>
        for {
          member <- cursor.downField("member").as[Member]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.MemberAdded(member, executorId)
      case GroupChatEvent.MemberRemovedType =>
        for {
          userAccountId <- cursor.downField("userAccountId").as[UserAccountId]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.MemberRemoved(userAccountId, executorId)
      case GroupChatEvent.MessagePostedType =>
        for {
          message <- cursor.downField("message").as[Message]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.MessagePosted(message, executorId)
      case GroupChatEvent.MessageEditedType =>
        for {
          messageId <- cursor.downField("messageId").as[MessageId]
          body <- cursor.downField("body").as[MessageBody]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.MessageEdited(messageId, body, executorId)
      case GroupChatEvent.MessageDeletedType =>
        for {
          messageId <- cursor.downField("messageId").as[MessageId]
          executorId <- cursor.downField("executorId").as[UserAccountId]
        } yield GroupChatEvent.MessageDeleted(messageId, executorId)
      case other =>
        Left(io.circe.DecodingFailure(s"unknown group chat event type: $other", cursor.history))
    }
  }
}
