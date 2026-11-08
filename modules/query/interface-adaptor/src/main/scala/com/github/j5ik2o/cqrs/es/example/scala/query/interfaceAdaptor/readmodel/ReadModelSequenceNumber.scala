package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Enforces the ESA core-contract T-9 upper bound on sequence numbers.
  *
  * T-9 treats a sequence number as a JSON number, and JSON clients that use IEEE-754 doubles cannot represent every
  * integer above 2^53 - 1 exactly. The read model therefore refuses to store a value above that limit instead of
  * accepting a number that some clients would round.
  */
object ReadModelSequenceNumber {

  /** Largest sequence number that every JSON client can round-trip exactly (2^53 - 1). */
  final val MaxSafeValue: Long = 9007199254740991L

  final class OutOfRangeException(val seqNr: Long)
      extends IllegalArgumentException(
        s"sequence number $seqNr exceeds the T-9 safe integer limit $MaxSafeValue"
      )

  /** Returns `seqNr` unchanged when it is within the safe range, otherwise a failure. */
  def validate(seqNr: Long): Try[Long] =
    if (seqNr > MaxSafeValue) Failure(new OutOfRangeException(seqNr))
    else Success(seqNr)
}
