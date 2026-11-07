package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

enum MemberRole {
  case Admin
  case Member

  def asString: String = this match {
    case Admin  => "ADMIN"
    case Member => "MEMBER"
  }
}

object MemberRole {

  def fromString(value: String): Either[String, MemberRole] =
    value.trim.toUpperCase match {
      case "ADMIN"  => Right(MemberRole.Admin)
      case "MEMBER" => Right(MemberRole.Member)
      case other    => Left(s"invalid member role: $other")
    }
}
