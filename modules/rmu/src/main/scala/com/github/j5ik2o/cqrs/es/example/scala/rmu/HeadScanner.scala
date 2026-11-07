package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Walks the whole head table. DY-15 requires a strongly consistent read so a resync sees every aggregate that advanced
  * while the reader was stopped.
  */
trait HeadScanner {
  def scanHeads(visitor: Head => Unit): scala.util.Try[Unit]
}
