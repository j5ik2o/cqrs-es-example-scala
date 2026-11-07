package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Raised when a recovery task cannot be replayed or belongs to another projection. */
final class RecoveryException(message: String, cause: Throwable = null) extends ReadModelUpdateException(message, cause)
