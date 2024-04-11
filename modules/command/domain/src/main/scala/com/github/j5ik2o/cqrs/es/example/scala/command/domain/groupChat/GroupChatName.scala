package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

final case class GroupChatName(private val value: String) {
  require(value.nonEmpty && value.length <= 64, "value.length must be between 1 and 64")

  def asString: String = value
}
