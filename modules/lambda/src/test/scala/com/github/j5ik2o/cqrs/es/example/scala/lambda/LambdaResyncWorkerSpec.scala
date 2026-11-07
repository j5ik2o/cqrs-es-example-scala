package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.github.j5ik2o.cqrs.es.example.scala.rmu.Head
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Resyncer
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class LambdaResyncWorkerSpec extends AnyFreeSpec with Matchers {

  import LambdaTestSupport._

  "LambdaResyncWorker" - {
    "reports what one resync saw and did" in {
      val scanner = new StubHeadScanner(
        Vector(
          Head("GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV", "GroupChat", 2L),
          Head("UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV", "UserAccount", 1L)
        )
      )
      val worker = new LambdaResyncWorker(new Resyncer(new StubEventApplier, scanner, None))

      val response = worker.handleRequest(null, null)

      response.get("heads") shouldBe Int.box(1)
      response.get("skipped") shouldBe Int.box(1)
      response.get("advanced") shouldBe Int.box(1)
      response.get("failed") shouldBe Int.box(0)
    }
  }
}
