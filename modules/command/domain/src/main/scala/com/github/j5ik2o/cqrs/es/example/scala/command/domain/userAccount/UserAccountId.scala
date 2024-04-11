package com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount

import wvlet.airframe.ulid.ULID

object UserAccountId {
  final val Prefix: String   = "UserAccount"
  def newId(): UserAccountId = UserAccountId(ULID.newULID)
}

final case class UserAccountId(private val value: ULID) {
  def asString: String = s"${UserAccountId.Prefix}-${value.toString}"
  def toULID: ULID     = value
}
