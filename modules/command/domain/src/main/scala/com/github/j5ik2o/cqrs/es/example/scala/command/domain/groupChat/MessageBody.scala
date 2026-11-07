package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

final case class MessageBody(value: String) {
  require(value.nonEmpty, "value must not be empty")
}

object MessageBody {

  def fromString(value: String): Either[String, MessageBody] =
    if (value.nonEmpty) Right(MessageBody(value)) else Left("message body must not be empty")
}
