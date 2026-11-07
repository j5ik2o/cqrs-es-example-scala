package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

enum GroupChatError {

  case GroupChatNotFoundError(id: GroupChatId)
  case AlreadyDeletedError(id: GroupChatId)
  case AlreadyRenamedError(id: GroupChatId, name: GroupChatName)
  case AlreadyMemberError(id: GroupChatId, userAccountId: UserAccountId)
  case NotAdministratorError(id: GroupChatId, userAccountId: UserAccountId)
  case NotMemberError(id: GroupChatId, userAccountId: UserAccountId)
  case MessageNotFoundError(id: GroupChatId, messageId: MessageId)
  case NotMessageSenderError(id: GroupChatId, messageId: MessageId, userAccountId: UserAccountId)

  def message: String = this match {
    case GroupChatNotFoundError(id) =>
      s"group chat not found: ${id.asString}"
    case AlreadyDeletedError(id) =>
      s"group chat is already deleted: ${id.asString}"
    case AlreadyRenamedError(id, name) =>
      s"group chat is already named '${name.asString}': ${id.asString}"
    case AlreadyMemberError(id, userAccountId) =>
      s"user account is already a member: ${userAccountId.asString} (${id.asString})"
    case NotAdministratorError(id, userAccountId) =>
      s"user account is not an administrator: ${userAccountId.asString} (${id.asString})"
    case NotMemberError(id, userAccountId) =>
      s"user account is not a member: ${userAccountId.asString} (${id.asString})"
    case MessageNotFoundError(id, messageId) =>
      s"message not found: ${messageId.asString} (${id.asString})"
    case NotMessageSenderError(id, messageId, userAccountId) =>
      s"user account is not the sender of the message: ${userAccountId.asString} (${messageId.asString}, ${id.asString})"
  }
}
