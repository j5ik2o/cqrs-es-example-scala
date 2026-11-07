package com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.UlidSupport

object UserAccountId {

  final val Prefix: String = "UserAccount"

  def newId: UserAccountId = UserAccountId(UlidSupport.newUlid())

  def fromString(value: String): Either[String, UserAccountId] =
    UlidSupport.parse(value, Prefix).map(UserAccountId.apply)

  def fromValue(value: String): Either[String, UserAccountId] =
    UlidSupport.parse(value, Prefix).map(UserAccountId.apply)
}

final case class UserAccountId(value: String) {
  def asString: String = s"${UserAccountId.Prefix}-$value"
}
