package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

final case class GroupChatName(value: String) {
  require(value.nonEmpty && value.length <= 64, "value.length must be between 1 and 64")

  def asString: String = value
}

object GroupChatName {

  def fromString(value: String): Either[String, GroupChatName] =
    if (value.nonEmpty && value.length <= 64) Right(GroupChatName(value))
    else Left("group chat name must be between 1 and 64 characters")
}
