package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.UlidSupport

object MessageId {

  def newId: MessageId = MessageId(UlidSupport.newUlid())

  def fromString(value: String): Either[String, MessageId] =
    UlidSupport.parse(value, "Message").map(MessageId.apply)
}

final case class MessageId(value: String) {
  def asString: String = value
}
