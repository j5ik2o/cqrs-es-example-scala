package com.github.j5ik2o.cqrs.es.example.scala.fault

import scala.concurrent.duration.FiniteDuration

/** Deadline helpers for the fault-injection e2e.
  *
  * Every wait is a poll with a deadline. A timeout carries the description and the last observed state, so a slow
  * dependency is not reported as a generic failure.
  */
object E2eSupport {

  final class AwaitTimeoutException(message: String) extends RuntimeException(message)

  def awaitUntil(
      description: String,
      timeout: FiniteDuration,
      pollInterval: FiniteDuration
  )(condition: => Boolean, state: => String = ""): Unit = {
    val deadline = System.nanoTime() + timeout.toNanos
    while (!condition) {
      if (System.nanoTime() >= deadline) {
        throw new AwaitTimeoutException(
          s"timed out after ${timeout.toSeconds}s waiting for $description; last state: ${state}"
        )
      }
      Thread.sleep(Math.max(1L, pollInterval.toMillis))
    }
  }

  def check(description: String, condition: => Boolean): Unit =
    if (!condition) {
      throw new AssertionError(description)
    }

  def checkEquals[A](description: String, expected: A)(actual: => A): Unit = {
    val value = actual
    if (value != expected) {
      throw new AssertionError(s"$description: expected $expected but was $value")
    }
  }
}
