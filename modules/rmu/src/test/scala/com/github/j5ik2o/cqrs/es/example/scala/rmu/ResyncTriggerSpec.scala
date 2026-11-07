package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.HOURS

class ResyncTriggerSpec extends AnyFreeSpec with Matchers {

  private val now       = Instant.parse("2026-01-02T00:00:00Z")
  private val retention = FiniteDuration(24, HOURS)

  "the resync trigger" - {
    "fires when the last successful poll is older than the retention window" in {
      ResyncTrigger.shouldResync(Some(now.minusSeconds(25L * 60L * 60L)), now, retention) shouldBe true
    }

    "does not fire inside the retention window" in {
      ResyncTrigger.shouldResync(Some(now.minusSeconds(23L * 60L * 60L)), now, retention) shouldBe false
    }

    "does not fire before the first record; the startup scan already covers it" in {
      ResyncTrigger.shouldResync(None, now, retention) shouldBe false
    }
  }
}
