package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

/** The payload of a domain event. Envelope metadata (aggregate id, seq_nr, occurred_at and manifest) is not part of the
  * payload. The manifest is derived from `eventType` and `schemaVersion`.
  */
sealed trait GroupChatEvent {

  def eventType: String

  def schemaVersion: Int = GroupChatEvent.SchemaVersion

  final def manifest: String = s"$eventType@$schemaVersion"
}

object GroupChatEvent {

  final val SchemaVersion: Int = 1

  final val CreatedType        = "group-chat-created"
  final val DeletedType        = "group-chat-deleted"
  final val RenamedType        = "group-chat-renamed"
  final val MemberAddedType    = "group-chat-member-added"
  final val MemberRemovedType  = "group-chat-member-removed"
  final val MessagePostedType  = "group-chat-message-posted"
  final val MessageEditedType  = "group-chat-message-edited"
  final val MessageDeletedType = "group-chat-message-deleted"

  final case class Created(name: GroupChatName, owner: Member, executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = CreatedType
  }

  final case class Deleted(executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = DeletedType
  }

  final case class Renamed(name: GroupChatName, executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = RenamedType
  }

  final case class MemberAdded(member: Member, executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = MemberAddedType
  }

  final case class MemberRemoved(userAccountId: UserAccountId, executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = MemberRemovedType
  }

  final case class MessagePosted(message: Message, executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = MessagePostedType
  }

  final case class MessageEdited(
      messageId: MessageId,
      body: MessageBody,
      executorId: UserAccountId
  ) extends GroupChatEvent {
    override val eventType: String = MessageEditedType
  }

  final case class MessageDeleted(messageId: MessageId, executorId: UserAccountId) extends GroupChatEvent {
    override val eventType: String = MessageDeletedType
  }
}
