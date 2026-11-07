package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.SECONDS
import scala.util.Failure
import scala.util.Success

class RetryPolicySpec extends AnyFreeSpec with Matchers {

  private final class MutableClock(var current: Instant) extends Clock {
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = current
    def advance(delay: FiniteDuration): Unit   = current = current.plusNanos(delay.toNanos)
  }

  private final class RecordingSleeper(clock: MutableClock) extends Sleeper {
    val delays: mutable.Buffer[FiniteDuration] = mutable.Buffer.empty
    override def sleep(delay: FiniteDuration): Unit = {
      delays += delay
      clock.advance(delay)
    }
  }

  private def millis(value: Long): FiniteDuration = FiniteDuration(value, MILLISECONDS)

  "the retry policy" - {
    "retries a retryable failure and succeeds" in {
      val clock   = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
      val sleeper = new RecordingSleeper(clock)
      val policy = new RetryPolicy(
        maxAttempts = 3,
        maxElapsed = FiniteDuration(1, SECONDS),
        initialDelay = millis(10),
        maxDelay = millis(100),
        sleeper = sleeper,
        jitter = identity,
        clock = clock
      )
      var attempts = 0

      val result = policy.run {
        attempts += 1
        if (attempts < 3) Failure(new IllegalStateException("transient")) else Success("ok")
      }

      result shouldBe Success("ok")
      attempts shouldBe 3
      sleeper.delays shouldBe Vector(millis(10), millis(20))
    }

    "stops immediately on a permanent failure" in {
      val policy   = new RetryPolicy(5, millis(0), millis(10), millis(100), _ => (), identity)
      var attempts = 0
      val failure  = new PermanentFailure(FailureKind.EventPayload, "bad payload")

      val result = policy.run {
        attempts += 1
        Failure(failure)
      }

      result shouldBe Failure(failure)
      attempts shouldBe 1
    }

    "fails after the attempt limit" in {
      val policy   = new RetryPolicy(3, millis(0), millis(10), millis(100), _ => (), identity)
      var attempts = 0

      val result = policy.run {
        attempts += 1
        Failure(new IllegalStateException("transient"))
      }

      attempts shouldBe 3
      result.failed.get shouldBe a[RetryExhaustedException]
      result.failed.get.asInstanceOf[RetryExhaustedException].attempts shouldBe 3
    }

    "stops when the elapsed budget would be exceeded" in {
      val clock   = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
      val sleeper = new RecordingSleeper(clock)
      val policy = new RetryPolicy(
        maxAttempts = 10,
        maxElapsed = millis(100),
        initialDelay = millis(200),
        maxDelay = millis(1000),
        sleeper = sleeper,
        jitter = identity,
        clock = clock
      )
      var attempts = 0

      val result = policy.run {
        attempts += 1
        Failure(new IllegalStateException("transient"))
      }

      attempts shouldBe 1
      val exhausted = result.failed.get.asInstanceOf[RetryExhaustedException]
      exhausted.getCause shouldBe a[RetryExhaustedException.ElapsedBudgetExceeded]
      sleeper.delays shouldBe empty
    }

    "caps the exponential delay" in {
      val policy = new RetryPolicy(6, millis(0), millis(10), millis(15), _ => (), identity)

      policy.delayFor(1) shouldBe millis(10)
      policy.delayFor(2) shouldBe millis(15)
      policy.delayFor(3) shouldBe millis(15)
      policy.delayFor(6) shouldBe millis(15)
    }

    "spreads the delay with full jitter inside the capped delay" in {
      val base = millis(100)

      val delays = (1 to 100).map(_ => RetryPolicy.fullJitter(base))

      all(delays) should (be >= millis(0) and be <= base)
      delays.distinct.size should be > 1
    }

    "does not retry when the outer loop owns the retry" in {
      var attempts = 0

      val result = RetryPolicy.noRetry.run {
        attempts += 1
        Failure(new IllegalStateException("transient"))
      }

      attempts shouldBe 1
      result.isFailure shouldBe true
    }
  }
}
