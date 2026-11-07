package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS

class BackoffSpec extends AnyFreeSpec with Matchers {

  private def millis(value: Long): FiniteDuration = FiniteDuration(value, MILLISECONDS)

  "the poll backoff" - {
    "grows exponentially to the cap and resets" in {
      val backoff = new Backoff(millis(100), millis(500), identity)

      backoff.next() shouldBe millis(100)
      backoff.next() shouldBe millis(200)
      backoff.next() shouldBe millis(400)
      backoff.next() shouldBe millis(500)
      backoff.next() shouldBe millis(500)

      backoff.reset()
      backoff.next() shouldBe millis(100)
    }

    "uses a 500 ms start when the initial delay is zero" in {
      val backoff = new Backoff(millis(0), millis(500), identity)

      backoff.next() shouldBe millis(500)
    }
  }
}
