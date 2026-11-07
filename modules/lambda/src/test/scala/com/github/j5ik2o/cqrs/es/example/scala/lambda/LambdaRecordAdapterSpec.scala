package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{ AttributeValue => LambdaAttributeValue }
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamDecoder
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.dynamodb.model.OperationType

import scala.jdk.CollectionConverters._

class LambdaRecordAdapterSpec extends AnyFreeSpec with Matchers {

  import LambdaEventFixtures._

  "LambdaRecordAdapter" - {
    "converts the head image and the sequence number" in {
      val image = Map(
        "aid"     -> attributeS(AggregateId),
        "seq_nr"  -> attributeN(3L),
        "text"    -> attributeS("hello"),
        "payload" -> attributeB(Array[Byte](1, 2, 3))
      )

      val record = LambdaRecordAdapter.toSdkRecord(streamRecord(image, Some("42")))

      record.eventName shouldBe OperationType.INSERT
      val converted = record.dynamodb.newImage.asScala
      converted("aid").s shouldBe AggregateId
      converted("seq_nr").n shouldBe "3"
      converted("text").s shouldBe "hello"
      converted("payload").b.asByteArray.toSeq shouldBe Seq[Byte](1, 2, 3)
    }

    "converts nested lists and maps" in {
      val image = Map(
        "events" -> attributeL(
          Seq(
            attributeM(
              Map(
                "seq_nr" -> attributeN(1L),
                "nested" -> attributeL(Seq(attributeS("a"), attributeS("b")))
              )
            )
          )
        )
      )

      val converted = LambdaRecordAdapter.toSdkRecord(streamRecord(image, Some("1"))).dynamodb.newImage

      converted.get("events").l.asScala.head.m.asScala("seq_nr").n shouldBe "1"
      converted
        .get("events")
        .l
        .asScala
        .head
        .m
        .asScala("nested")
        .l
        .asScala
        .map(_.s) shouldBe Seq("a", "b")
    }

    "rejects an attribute shape the head item never uses" in {
      val unsupported = new LambdaAttributeValue()
      unsupported.setSS(java.util.List.of("a", "b"))
      val image = Map("values" -> unsupported)

      val error = intercept[IllegalArgumentException] {
        LambdaRecordAdapter.toSdkRecord(streamRecord(image, Some("1")))
      }
      error.getMessage should include("unsupported")
    }

    "keeps the fallback identity readable" in {
      val record = LambdaRecordAdapter.toSdkRecord(
        streamRecord(headImage(AggregateId, 5L, Seq(eventEntry(5L, validPayload(5L)))), Some("5"))
      )

      HeadStreamDecoder.fallbackIdentity(record).map(_.aggregateId) shouldBe Some(AggregateId)
      HeadStreamDecoder.fallbackIdentity(record).map(_.seqNr) shouldBe Some(5L)
    }
  }
}
