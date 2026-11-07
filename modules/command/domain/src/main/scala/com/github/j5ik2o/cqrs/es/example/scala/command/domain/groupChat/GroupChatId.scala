package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.UlidSupport

object GroupChatId {

  final val Prefix: String = "GroupChat"

  def newId: GroupChatId = GroupChatId(UlidSupport.newUlid())

  def fromString(value: String): Either[String, GroupChatId] =
    UlidSupport.parse(value, Prefix).map(GroupChatId.apply)

  def fromValue(value: String): Either[String, GroupChatId] =
    UlidSupport.parse(value, Prefix).map(GroupChatId.apply)
}

final case class GroupChatId(value: String) {
  def asString: String = s"${GroupChatId.Prefix}-$value"
}
