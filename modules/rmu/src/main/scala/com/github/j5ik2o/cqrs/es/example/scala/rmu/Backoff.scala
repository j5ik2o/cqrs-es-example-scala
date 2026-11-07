package com.github.j5ik2o.cqrs.es.example.scala.rmu

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.SECONDS

/** The stream reader's idle and failure delay. The delay grows exponentially to a cap and applies full jitter, so a
  * restarted reader does not hammer the stream or the database in lock step with another replica.
  */
final class Backoff(
    initial: FiniteDuration,
    max: FiniteDuration,
    jitter: FiniteDuration => FiniteDuration = RetryPolicy.fullJitter
) {

  private val start: FiniteDuration =
    if (initial == null || initial.length <= 0) FiniteDuration(500, MILLISECONDS) else initial
  private val limit: FiniteDuration =
    if (max == null || max.length <= 0 || max < start) start.max(FiniteDuration(30, SECONDS)) else max
  private var attempt = 0

  def next(): FiniteDuration = synchronized {
    var delay = start
    var index = 0
    while (index < attempt && delay < limit) {
      delay = delay * 2
      index += 1
    }
    if (delay > limit) {
      delay = limit
    }
    attempt += 1
    jitter(delay)
  }

  def reset(): Unit = synchronized {
    attempt = 0
  }
}
