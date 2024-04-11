package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import wvlet.airframe.ulid.ULID

final case class MemberId(private val value: ULID) {
  def asString: String = value.toString
  def toULID: ULID = value
}
