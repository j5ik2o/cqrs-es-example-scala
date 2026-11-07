package com.github.j5ik2o.cqrs.es.example.scala.rmu

import scala.concurrent.duration.FiniteDuration

/** Sleeps between retry attempts. Tests inject a sleeper that records the requested delays instead of waiting. */
trait Sleeper {
  def sleep(delay: FiniteDuration): Unit
}

object Sleeper {

  val system: Sleeper = delay =>
    try Thread.sleep(Math.max(0L, delay.toMillis))
    catch {
      case error: InterruptedException =>
        Thread.currentThread().interrupt()
        throw new RetryInterruptedException(error)
    }
}
