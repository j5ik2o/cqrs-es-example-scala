package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** The failure of an operation that builds or repairs the read model. */
class ReadModelUpdateException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)
