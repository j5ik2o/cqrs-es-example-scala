package com.github.j5ik2o.cqrs.es.example.scala.rmu

import scala.concurrent.duration.FiniteDuration

/** Signals that the policy stopped retrying. The last failure is the cause. */
final class RetryExhaustedException(val attempts: Int, cause: Throwable)
    extends RuntimeException(s"retry exhausted after $attempts attempts", cause)

object RetryExhaustedException {

  /** Wraps the elapsed-budget check so the caller can distinguish it from the attempt cap. */
  final class ElapsedBudgetExceeded(val elapsed: FiniteDuration, cause: Throwable)
      extends RuntimeException(s"retry elapsed budget exceeded after $elapsed", cause)
}
