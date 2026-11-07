package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdateException
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryException
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryService
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class LambdaRecoveryWorkerSpec extends AnyFreeSpec with Matchers {

  import LambdaEventFixtures._
  import LambdaTestSupport._

  private def service(
      store: RecordingRecoveryTaskStore,
      applier: StubEventApplier
  ): RecoveryService = new RecoveryService(store, storeIdProvider, applier)

  "LambdaRecoveryWorker" - {
    "drains an empty backlog and acknowledges the message" in {
      val worker = new LambdaRecoveryWorker(service(new RecordingRecoveryTaskStore, new StubEventApplier), 50)

      val response = worker.handleRequest(sqsEvent("message-1"), null)

      response.getBatchItemFailures.asScala shouldBe empty
    }

    "redelivers every message when a recovery run fails" in {
      val store = new RecordingRecoveryTaskStore(Vector(recoveryTask(AggregateId, 1L)))
      val applier = new StubEventApplier(
        applyThroughFailures = Map(AggregateId -> new ReadModelUpdateException("database is down"))
      )
      val worker = new LambdaRecoveryWorker(service(store, applier), 50)

      val response = worker.handleRequest(sqsEvent("message-1", "message-2"), null)

      response.getBatchItemFailures.asScala.map(_.getItemIdentifier).toSet shouldBe Set("message-1", "message-2")
    }

    "reports the failure when a manual invocation has no message to redeliver" in {
      val store = new RecordingRecoveryTaskStore(Vector(recoveryTask(AggregateId, 1L)))
      val applier = new StubEventApplier(
        applyThroughFailures = Map(AggregateId -> new ReadModelUpdateException("database is down"))
      )
      val worker = new LambdaRecoveryWorker(service(store, applier), 50)

      intercept[RecoveryException] {
        worker.handleRequest(emptySqsEvent(), null)
      }
    }

    "asks for redelivery when the backlog exceeds the batch cap" in {
      val tasks  = (1 to 11).toVector.map(index => recoveryTask(f"Aggregate-$index", 1L))
      val store  = new RecordingRecoveryTaskStore(tasks)
      val worker = new LambdaRecoveryWorker(service(store, new StubEventApplier), 1)

      val response = worker.handleRequest(sqsEvent("message-1"), null)

      response.getBatchItemFailures.asScala.map(_.getItemIdentifier) shouldBe Seq("message-1")
    }
  }
}
