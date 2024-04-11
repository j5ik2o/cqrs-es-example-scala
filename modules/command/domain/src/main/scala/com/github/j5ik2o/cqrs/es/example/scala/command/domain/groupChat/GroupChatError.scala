package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

enum GroupChatError {
  case AlreadyDeletedError(id: GroupChatId)
  case AlreadyExistsNameError(id: GroupChatId)
  case AlreadyExistsMemberError(id: GroupChatId)
  case AlreadyMemberError(id: GroupChatId)
  case NotAdministratorError(id: GroupChatId)
  case NotMemberError(id: GroupChatId)
  case MessageNotFoundError(id: GroupChatId, messageId: MessageId)
}
