package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.event.store.adapter.java.AggregateId
import wvlet.airframe.ulid.ULID

object GroupChatId {
  final val Prefix: String = "GroupChat"
  def newId: GroupChatId   = GroupChatId(ULID.newULID)
}

final case class GroupChatId(private val value: ULID) extends AggregateId {
  def asString: String = s"${GroupChatId.Prefix}-${value.toString}"
  def toULID: ULID     = value

  override def getTypeName: String = GroupChatId.Prefix
  override def getValue: String    = value.toString
}
