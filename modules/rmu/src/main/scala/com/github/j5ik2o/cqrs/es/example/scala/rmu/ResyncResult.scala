package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Counts what one full reconciliation saw and did. */
final case class ResyncResult(heads: Int, skipped: Int, advanced: Int, failed: Int)
