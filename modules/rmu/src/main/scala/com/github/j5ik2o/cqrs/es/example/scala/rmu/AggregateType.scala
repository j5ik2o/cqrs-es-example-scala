package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Identifies the aggregate types that this read model projects. A head of another type is skipped before its fields
  * are parsed so it cannot abort a scan.
  */
object AggregateType {

  private val GroupChatPrefix = "GroupChat-"

  def isGroupChat(aggregateId: String): Boolean =
    aggregateId != null && aggregateId.startsWith(GroupChatPrefix)
}
