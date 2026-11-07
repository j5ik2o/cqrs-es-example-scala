package com.github.j5ik2o.cqrs.es.example.scala.rmu

import java.time.Clock
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.NANOSECONDS
import scala.concurrent.duration.SECONDS
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Bounds how long one record may be retried before it is parked.
  *
  * `maxElapsed` is the wall-clock budget, so a long queue of records cannot push a single invocation past the caller's
  * deadline. The delay grows exponentially and is capped, then reduced by full jitter so replicas do not retry in lock
  * step.
  */
final class RetryPolicy(
    val maxAttempts: Int,
    val maxElapsed: FiniteDuration,
    val initialDelay: FiniteDuration,
    val maxDelay: FiniteDuration,
    sleeper: Sleeper = Sleeper.system,
    jitter: FiniteDuration => FiniteDuration = RetryPolicy.fullJitter,
    clock: Clock = Clock.systemUTC()
) {

  private val attempts: Int = Math.max(1, maxAttempts)
  private val elapsed: FiniteDuration =
    if (maxElapsed == null || maxElapsed.length <= 0) RetryPolicy.zero else maxElapsed
  private val initial: FiniteDuration =
    if (initialDelay == null || initialDelay.length <= 0) RetryPolicy.zero else initialDelay
  private val capped: FiniteDuration =
    if (maxDelay == null || maxDelay.length <= 0) RetryPolicy.zero else maxDelay

  def run[A](operation: => Try[A]): Try[A] = {
    val startedAt              = clock.instant()
    var lastFailure: Throwable = null
    var attempt                = 1
    while (attempt <= attempts) {
      operation match {
        case success: Success[A] =>
          return success
        case Failure(failure) =>
          lastFailure = failure
          if (!FailureClassifier.isRetryable(failure)) {
            return Failure(failure)
          }
          if (attempt == attempts) {
            return Failure(new RetryExhaustedException(attempt, failure))
          }
          val delay = delayFor(attempt)
          val elapsedSinceStart = FiniteDuration(
            Math.max(0L, java.time.Duration.between(startedAt, clock.instant()).toMillis),
            MILLISECONDS
          )
          if (elapsed.length > 0 && elapsedSinceStart + delay > elapsed) {
            return Failure(
              new RetryExhaustedException(
                attempt,
                new RetryExhaustedException.ElapsedBudgetExceeded(elapsedSinceStart, failure)
              )
            )
          }
          sleeper.sleep(delay)
          attempt += 1
      }
    }
    Failure(new RetryExhaustedException(attempts, lastFailure))
  }

  /** Returns the jittered delay before the next attempt. `attempt` starts at 1. */
  def delayFor(attempt: Int): FiniteDuration = {
    val normalized = Math.max(1, attempt)
    if (initial.length == 0) {
      RetryPolicy.zero
    } else {
      var value = initial
      var index = 1
      while (index < normalized && (capped.length == 0 || value < capped)) {
        value = value * 2
        index += 1
      }
      if (capped.length > 0 && value > capped) {
        value = capped
      }
      jitter(value)
    }
  }
}

object RetryPolicy {

  final val zero: FiniteDuration = FiniteDuration(0, NANOSECONDS)

  final val DefaultMaxAttempts  = 5
  final val DefaultMaxElapsed   = FiniteDuration(30, SECONDS)
  final val DefaultInitialDelay = FiniteDuration(200, MILLISECONDS)
  final val DefaultMaxDelay     = FiniteDuration(5, SECONDS)

  def defaults: RetryPolicy =
    new RetryPolicy(DefaultMaxAttempts, DefaultMaxElapsed, DefaultInitialDelay, DefaultMaxDelay)

  /** Used when the outer delivery mechanism (Lambda or the stream reader) retries instead. */
  def noRetry: RetryPolicy =
    new RetryPolicy(1, zero, zero, zero, _ => (), identity)

  /** Full jitter: a uniform delay between zero and the capped exponential delay. */
  def fullJitter(delay: FiniteDuration): FiniteDuration =
    if (delay == null || delay.length <= 0) {
      zero
    } else {
      val upper = delay.toNanos
      FiniteDuration(ThreadLocalRandom.current().nextLong(upper + 1L), NANOSECONDS)
    }
}
