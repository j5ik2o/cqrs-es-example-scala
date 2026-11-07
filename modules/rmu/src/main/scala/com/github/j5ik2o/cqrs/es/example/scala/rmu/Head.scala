package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** One entry of the head table after the configuration item is removed. */
final case class Head(aggregateId: String, typeName: String, seqNr: Long)
