package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

final case class Member(id: MemberId, userAccountId: UserAccountId, role: MemberRole) {}

object Member {

  given Ordering[Member] with {
    override def compare(x: Member, y: Member): Int = x.id.toULID.compareTo(y.id.toULID)
  }

}
