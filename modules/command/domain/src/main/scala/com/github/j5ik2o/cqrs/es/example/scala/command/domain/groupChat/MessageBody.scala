package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

final case class MessageBody(value: String) {
  require(value.nonEmpty)
}
