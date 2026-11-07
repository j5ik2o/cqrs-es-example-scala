package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

final case class Member(id: MemberId, userAccountId: UserAccountId, role: MemberRole)

object Member {

  def newMember(userAccountId: UserAccountId, role: MemberRole): Member =
    Member(MemberId.newId, userAccountId, role)

  given Ordering[Member] with {
    override def compare(x: Member, y: Member): Int = x.id.value.compareTo(y.id.value)
  }
}
