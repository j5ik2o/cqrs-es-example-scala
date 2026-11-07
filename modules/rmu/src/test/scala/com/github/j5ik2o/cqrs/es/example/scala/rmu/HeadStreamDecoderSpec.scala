package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord

import java.time.Instant
import scala.jdk.CollectionConverters._

class HeadStreamDecoderSpec extends AnyFreeSpec with Matchers {

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner       = userAccountId('V')
  private val groupChatId = "GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV"
  private val occurredAt  = Instant.parse("2026-01-01T00:00:00.123456789Z")
  private val event = GroupChatEvent.Created(
    GroupChatName("team"),
    Member(MemberId.newId, owner, MemberRole.Admin),
    owner
  )

  private def number(value: Long): AttributeValue = AttributeValue.builder().n(value.toString).build()

  private def string(value: String): AttributeValue = AttributeValue.builder().s(value).build()

  private def nanos(instant: Instant): Long =
    instant.getEpochSecond * 1_000_000_000L + instant.getNano

  private def headImage(aid: String, seqNr: Long, event: GroupChatEvent): java.util.Map[String, AttributeValue] = {
    val payload = AttributeValue
      .builder()
      .b(SdkBytes.fromByteArray(GroupChatSerializers.eventSerializer.serialize(event)))
      .build()
    val eventEntry = AttributeValue
      .builder()
      .m(
        Map(
          "seq_nr"      -> number(seqNr),
          "occurred_at" -> number(nanos(occurredAt)),
          "manifest"    -> string(event.manifest),
          "payload"     -> payload
        ).asJava
      )
      .build()
    Map(
      "aid"       -> string(aid),
      "seq_nr"    -> number(seqNr),
      "type_name" -> string("GroupChat"),
      "events"    -> AttributeValue.builder().l(java.util.List.of(eventEntry)).build()
    ).asJava
  }

  private def record(
      eventName: OperationType,
      aid: String = groupChatId,
      seqNr: Long = 1L,
      event: GroupChatEvent = event
  ): Record =
    Record
      .builder()
      .eventName(eventName)
      .dynamodb(StreamRecord.builder().newImage(headImage(aid, seqNr, event)).build())
      .build()

  "the head stream decoder" - {
    "decodes an INSERT into a head transition" in {
      val transition = HeadStreamDecoder
        .decode(record(OperationType.INSERT), GroupChatSerializers.eventSerializer)
        .get

      transition.aggregateId shouldBe groupChatId
      transition.seqNr shouldBe 1L
      transition.events should have size 1
      transition.events.head.seqNr shouldBe 1L
      transition.events.head.occurredAt shouldBe occurredAt
      transition.events.head.manifest shouldBe "group-chat-created@1"
      transition.events.head.event shouldBe event
    }

    "decodes a MODIFY because head updates use it" in {
      val transition = HeadStreamDecoder
        .decode(record(OperationType.MODIFY, seqNr = 2L), GroupChatSerializers.eventSerializer)

      transition.map(_.seqNr) shouldBe Some(2L)
    }

    "skips the configuration item" in {
      HeadStreamDecoder.decode(
        record(OperationType.INSERT, aid = HeadStreamDecoder.ConfigAid),
        GroupChatSerializers.eventSerializer
      ) shouldBe None
    }

    "skips a REMOVE record" in {
      HeadStreamDecoder.decode(record(OperationType.REMOVE), GroupChatSerializers.eventSerializer) shouldBe None
    }

    "skips a head of another aggregate type" in {
      HeadStreamDecoder.decode(
        record(OperationType.INSERT, aid = "UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV"),
        GroupChatSerializers.eventSerializer
      ) shouldBe None
    }

    "reads the fallback identity when the events are missing" in {
      val image = Map(
        "aid"    -> string(groupChatId),
        "seq_nr" -> number(4L)
      ).asJava
      val record = Record
        .builder()
        .eventName(OperationType.INSERT)
        .dynamodb(StreamRecord.builder().newImage(image).build())
        .build()

      HeadStreamDecoder.fallbackIdentity(record) shouldBe Some(HeadRecordIdentity(groupChatId, 4L))
    }

    "does not read a fallback identity for the configuration item" in {
      val image = Map(
        "aid"    -> string(HeadStreamDecoder.ConfigAid),
        "seq_nr" -> number(0L)
      ).asJava
      val record = Record
        .builder()
        .eventName(OperationType.INSERT)
        .dynamodb(StreamRecord.builder().newImage(image).build())
        .build()

      HeadStreamDecoder.fallbackIdentity(record) shouldBe None
    }

    "rejects a record without the events list" in {
      val image = Map("aid" -> string(groupChatId), "seq_nr" -> number(1L)).asJava
      val record = Record
        .builder()
        .eventName(OperationType.INSERT)
        .dynamodb(StreamRecord.builder().newImage(image).build())
        .build()

      val error = intercept[IllegalArgumentException] {
        HeadStreamDecoder.decode(record, GroupChatSerializers.eventSerializer)
      }
      error.getMessage should include("events")
    }
  }
}
