package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Marks a failure that retrying the same record cannot fix. Only payload, gap and constraint problems are permanent;
  * storage outages stay unclassified so the caller retries them.
  */
final class PermanentFailure(val kind: FailureKind, message: String, cause: Throwable = null)
    extends ReadModelUpdateException(message, cause)
