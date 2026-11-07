package com.github.j5ik2o.cqrs.es.example.scala.rmu

import io.circe.parser.parse
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

class SqsRecoveryNotifierSpec extends AnyFreeSpec with Matchers {

  private def task: RecoveryTask =
    RecoveryTask(
      storeId = "store-1",
      projectionName = Projection.Name,
      projectionVersion = Projection.Version,
      aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z",
      seqNr = 7L,
      failureKind = FailureKind.JournalGap,
      errorMessage = "journal entry is missing",
      status = RecoveryTask.StatusPending,
      createdAt = Instant.parse("2026-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

  "SqsRecoveryNotifier" - {
    "sends the identifier body to the queue" in {
      val sent     = mutable.ArrayBuffer.empty[(String, String)]
      val notifier = new SqsRecoveryNotifier((url, body) => sent += ((url, body)), "queue-url")

      notifier.notifyTask(task)

      sent should have size 1
      sent.head._1 shouldBe "queue-url"
      val json = parse(sent.head._2).toOption.get
      json.hcursor.get[String]("storeId").toOption shouldBe Some("store-1")
      json.hcursor.get[String]("projectionName").toOption shouldBe Some(Projection.Name)
      json.hcursor.get[String]("projectionVersion").toOption shouldBe Some(Projection.Version)
      json.hcursor.get[String]("aggregateId").toOption shouldBe Some(task.aggregateId)
      json.hcursor.get[Long]("seqNr").toOption shouldBe Some(7L)
      json.hcursor.get[String]("failureType").toOption shouldBe Some("journal_gap")
    }

    "keeps the payload and the failure message out of the message" in {
      val body = SqsRecoveryNotifier.messageBody(task)

      body should not include "payload"
      body should not include "journal entry is missing"
    }
  }
}
