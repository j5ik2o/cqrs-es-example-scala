package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent

import java.time.Instant

/** One event envelope carried by a head transition. The payload has already been deserialized. */
final case class HeadEventRecord(
    seqNr: Long,
    occurredAt: Instant,
    manifest: String,
    event: GroupChatEvent
)

/** A head transition read from the head table's DynamoDB Stream. */
final case class HeadTransition(
    aggregateId: String,
    seqNr: Long,
    events: Vector[HeadEventRecord]
)

/** The identifiers that can be read from a head image even when its event payload cannot be decoded. */
final case class HeadRecordIdentity(aggregateId: String, seqNr: Long)
