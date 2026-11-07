package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import com.github.j5ik2o.cqrs.es.example.scala.rmu.PermanentFailure
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdater
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryService
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RetryPolicy
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

class LambdaReadModelUpdaterSpec extends AnyFreeSpec with Matchers {

  import LambdaEventFixtures._
  import LambdaTestSupport._

  private def handler(
      store: RecordingRecoveryTaskStore,
      applier: StubEventApplier = new StubEventApplier()
  ): LambdaReadModelUpdater = {
    val recovery = new RecoveryService(store, storeIdProvider, applier)
    new LambdaReadModelUpdater(
      new ReadModelUpdater(applier, Some(recovery), RetryPolicy.noRetry),
      GroupChatSerializers.eventSerializer
    )
  }

  private def invalidPayload: Array[Byte] = "not-json".getBytes(StandardCharsets.UTF_8)

  "LambdaReadModelUpdater" - {
    "applies a decodable record without redelivery" in {
      val store  = new RecordingRecoveryTaskStore
      val record = streamRecord(headImage(AggregateId, 1L, Seq(eventEntry(1L, validPayload(1L)))), Some("1"))

      val response = handler(store).handleRequest(dynamoDbEvent(record), null)

      response.getBatchItemFailures.asScala shouldBe empty
      store.recorded shouldBe empty
    }

    "records a decode failure as a recovery task instead of redelivering it" in {
      val store  = new RecordingRecoveryTaskStore
      val record = streamRecord(headImage(AggregateId, 1L, Seq(eventEntry(1L, invalidPayload))), Some("1"))

      val response = handler(store).handleRequest(dynamoDbEvent(record), null)

      response.getBatchItemFailures.asScala shouldBe empty
      store.recorded.map(task => (task.aggregateId, task.seqNr)) shouldBe Vector((AggregateId, 1L))
    }

    "redelivers later records of an aggregate whose earlier record was parked" in {
      val store  = new RecordingRecoveryTaskStore
      val first  = streamRecord(headImage(AggregateId, 1L, Seq(eventEntry(1L, invalidPayload))), Some("1"))
      val second = streamRecord(headImage(AggregateId, 2L, Seq(eventEntry(2L, validPayload(2L)))), Some("2"))

      val response = handler(store).handleRequest(dynamoDbEvent(first, second), null)

      response.getBatchItemFailures.asScala.map(_.getItemIdentifier) shouldBe Seq("2")
      store.recorded.map(_.seqNr) shouldBe Vector(1L)
    }

    "fails the whole invocation when a record has no sequence number" in {
      val store = new RecordingRecoveryTaskStore

      val error = intercept[PermanentFailure] {
        handler(store).handleRequest(dynamoDbEvent(recordWithoutStream()), null)
      }
      error.getMessage should include("no sequence number")
    }

    "skips the configuration item" in {
      val store = new RecordingRecoveryTaskStore
      val config = streamRecord(
        Map("aid" -> attributeS("__config__"), "seq_nr" -> attributeN(1L), "events" -> attributeL(Seq.empty)),
        Some("1")
      )

      val response = handler(store).handleRequest(dynamoDbEvent(config), null)

      response.getBatchItemFailures.asScala shouldBe empty
      store.recorded shouldBe empty
    }
  }
}
