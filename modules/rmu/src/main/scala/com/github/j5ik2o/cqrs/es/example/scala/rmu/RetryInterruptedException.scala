package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Raised when an interrupt stops a retry loop. It must propagate instead of being retried. */
final class RetryInterruptedException(cause: Throwable)
    extends RuntimeException("the retry loop was interrupted", cause)
