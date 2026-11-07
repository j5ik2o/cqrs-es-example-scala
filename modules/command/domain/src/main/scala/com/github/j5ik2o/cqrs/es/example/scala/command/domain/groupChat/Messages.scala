package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

final case class Messages(values: Vector[Message]) {

  def add(message: Message): Messages = Messages(values :+ message)

  def editBody(messageId: MessageId, body: MessageBody): Messages =
    Messages(values.map(message => if (message.id == messageId) message.copy(body = body) else message))

  def removeById(messageId: MessageId): Messages =
    Messages(values.filterNot(_.id == messageId))

  def findById(messageId: MessageId): Option[Message] =
    values.find(_.id == messageId)

  def existsById(messageId: MessageId): Boolean =
    values.exists(_.id == messageId)

  def size: Int                 = values.size
  def isEmpty: Boolean          = values.isEmpty
  def nonEmpty: Boolean         = values.nonEmpty
  def toVector: Vector[Message] = values
}

object Messages {

  val empty: Messages = Messages(Vector.empty)
}
