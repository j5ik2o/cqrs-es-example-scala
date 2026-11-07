package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

final case class Message(id: MessageId, body: MessageBody, senderId: UserAccountId)

object Message {

  def newMessage(body: MessageBody, senderId: UserAccountId): Message =
    Message(MessageId.newId, body, senderId)
}
