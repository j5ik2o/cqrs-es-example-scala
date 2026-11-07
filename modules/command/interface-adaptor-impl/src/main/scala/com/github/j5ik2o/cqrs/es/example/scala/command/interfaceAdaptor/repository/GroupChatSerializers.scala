package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatJsonCodecs.given
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException
import io.circe.parser.decode
import io.circe.syntax._

import java.nio.charset.StandardCharsets

object GroupChatSerializers {

  private val Charset = StandardCharsets.UTF_8

  val eventSerializer: PayloadSerializer[GroupChatEvent] = new PayloadSerializer[GroupChatEvent] {
    override def serialize(value: GroupChatEvent): Array[Byte] =
      value.asJson.noSpaces.getBytes(Charset)

    override def deserialize(bytes: Array[Byte]): GroupChatEvent =
      decode[GroupChatEvent](new String(bytes, Charset)) match {
        case Right(event) => event
        case Left(error) =>
          throw new SerializationException(s"failed to deserialize group chat event: ${error.getMessage}", error)
      }
  }

  val snapshotSerializer: PayloadSerializer[GroupChat] = new PayloadSerializer[GroupChat] {
    override def serialize(value: GroupChat): Array[Byte] =
      value.asJson.noSpaces.getBytes(Charset)

    override def deserialize(bytes: Array[Byte]): GroupChat =
      decode[GroupChat](new String(bytes, Charset)) match {
        case Right(snapshot) => snapshot
        case Left(error) =>
          throw new SerializationException(s"failed to deserialize group chat snapshot: ${error.getMessage}", error)
      }
  }

  val eventStoreConfig: EventStoreConfig[GroupChatEvent, GroupChat] = EventStoreConfig
    .builder[GroupChatEvent, GroupChat]()
    .payloadSerializer(eventSerializer)
    .snapshotSerializer(snapshotSerializer)
    .build()
}
