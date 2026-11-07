package com.github.j5ik2o.cqrs.es.example.scala.rmu

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** The 24 hour stream retention check that makes the reader run a full resync. */
object ResyncTrigger {

  def shouldResync(lastRecordTime: Option[Instant], now: Instant, retention: FiniteDuration): Boolean =
    lastRecordTime.exists(recordTime => recordTime.isBefore(now.minusMillis(retention.toMillis)))
}
