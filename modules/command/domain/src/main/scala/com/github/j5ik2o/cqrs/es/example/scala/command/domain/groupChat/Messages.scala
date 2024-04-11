package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

final case class Messages(private val values: Vector[Message]) {
  def add(value: Message): Messages            = copy(values = values :+ value)
  def removeById(id: MessageId): Messages      = copy(values = values.filterNot(_.id == id))
  def existsById(id: MessageId): Boolean       = values.exists(_.id == id)
  def findById(id: MessageId): Option[Message] = values.find(_.id == id)

  def size: Int                 = values.size
  def isEmpty: Boolean          = values.isEmpty
  def nonEmpty: Boolean         = values.nonEmpty
  def toVector: Vector[Message] = values
}
