package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record

import java.time.Instant
import scala.jdk.CollectionConverters._

/** Decodes a head table stream record into a [[HeadTransition]].
  *
  * The head item carries `aid`, `seq_nr`, `type_name` and `events`. Each event element is a map with `seq_nr`,
  * `occurred_at` (epoch nanoseconds), `manifest` and `payload` (the serialized domain event). The reserved `__config__`
  * item is not an aggregate and is skipped.
  */
object HeadStreamDecoder {

  final val ConfigAid: String = "__config__"

  def decode(record: Record, serializer: PayloadSerializer[GroupChatEvent]): Option[HeadTransition] =
    Option(record.eventName()) match {
      case Some(OperationType.INSERT) | Some(OperationType.MODIFY) =>
        val image = Option(record.dynamodb())
          .flatMap(streamRecord => Option(streamRecord.newImage()))
          .map(_.asScala.toMap)
          .getOrElse(throw new IllegalArgumentException("head stream record has no NewImage"))
        val aid = requiredString(image, "aid")
        if (aid == ConfigAid || !AggregateType.isGroupChat(aid)) {
          None
        } else {
          val seqNr = requiredLong(image, "seq_nr")
          val events = image
            .get("events")
            .map(attribute => attribute.l().asScala.toVector.map(decodeEvent(_, serializer)))
            .getOrElse(missing("events"))
          Some(HeadTransition(aid, seqNr, events))
        }
      case _ =>
        None
    }

  /** Reads only the identifiers of a head record. The updater uses this to park a record whose payload cannot be
    * decoded, so the failure does not stop every later record in the shard.
    */
  def fallbackIdentity(record: Record): Option[HeadRecordIdentity] =
    Option(record) flatMap { value =>
      Option(value.dynamodb())
        .flatMap(streamRecord => Option(streamRecord.newImage()))
        .map(_.asScala.toMap)
        .flatMap { image =>
          for {
            aid <- image.get("aid").flatMap(attribute => Option(attribute.s()))
            seqNr <- image.get("seq_nr").flatMap(attribute => Option(attribute.n())).flatMap(_.toLongOption)
            if aid != ConfigAid && AggregateType.isGroupChat(aid)
          } yield HeadRecordIdentity(aid, seqNr)
        }
    }

  def instantFromEpochNanos(nanos: Long): Instant = {
    val seconds    = Math.floorDiv(nanos, 1_000_000_000L)
    val adjustment = Math.floorMod(nanos, 1_000_000_000L)
    Instant.ofEpochSecond(seconds, adjustment)
  }

  private def decodeEvent(attribute: AttributeValue, serializer: PayloadSerializer[GroupChatEvent]): HeadEventRecord = {
    val eventMap = Option(attribute.m())
      .map(_.asScala.toMap)
      .getOrElse(throw new IllegalArgumentException("head event entry is not a map"))
    HeadEventRecord(
      seqNr = requiredLong(eventMap, "seq_nr"),
      occurredAt = instantFromEpochNanos(requiredLong(eventMap, "occurred_at")),
      manifest = eventMap.get("manifest").flatMap(value => Option(value.s())).getOrElse(""),
      event = serializer.deserialize(requiredBytes(eventMap, "payload"))
    )
  }

  private def requiredString(values: Map[String, AttributeValue], name: String): String =
    values.get(name).flatMap(value => Option(value.s())).getOrElse(missing(name))

  private def requiredLong(values: Map[String, AttributeValue], name: String): Long =
    values
      .get(name)
      .flatMap(value => Option(value.n()))
      .map(_.toLong)
      .getOrElse(missing(name))

  private def requiredBytes(values: Map[String, AttributeValue], name: String): Array[Byte] =
    values
      .get(name)
      .flatMap(value => Option(value.b()))
      .map(_.asByteArray())
      .getOrElse(missing(name))

  private def missing(name: String): Nothing =
    throw new IllegalArgumentException(s"head stream record is missing the attribute '$name'")
}
