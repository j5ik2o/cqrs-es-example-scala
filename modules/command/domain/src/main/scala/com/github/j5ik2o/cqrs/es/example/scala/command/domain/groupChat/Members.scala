package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

final case class Members(private val values: Vector[Member]) {
  def add(member: Member): Members = Members(values :+ member)
  def removeByUserAccountId(userAccountId: UserAccountId): Members = Members(
    values.filterNot(_.userAccountId == userAccountId)
  )
  def isAdministrator(userAccountId: UserAccountId): Boolean =
    values.exists(_.userAccountId == userAccountId) && values.exists(_.role == MemberRole.Admin)
  def isMember(userAccountId: UserAccountId): Boolean        = values.exists(_.userAccountId == userAccountId)
  def findById(userAccountId: UserAccountId): Option[Member] = values.find(_.userAccountId == userAccountId)

  def size: Int                = values.size
  def isEmpty: Boolean         = values.isEmpty
  def nonEmpty: Boolean        = values.nonEmpty
  def toVector: Vector[Member] = values
}
