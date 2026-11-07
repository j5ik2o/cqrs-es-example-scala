package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.UlidSupport

object MemberId {

  def newId: MemberId = MemberId(UlidSupport.newUlid())

  def fromString(value: String): Either[String, MemberId] =
    UlidSupport.parse(value, "Member").map(MemberId.apply)
}

final case class MemberId(value: String) {
  def asString: String = value
}
