package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.slf4j.LoggerFactory

import java.time.Clock
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

/** The local read model updater loop.
  *
  * It reconciles every head on startup, then follows the head Streams. A trimmed stream, or a reader that has been
  * stopped longer than the 24 hour retention window, triggers another full resync (DY-15). Pending recovery tasks are
  * replayed at startup and periodically afterwards.
  */
final case class RunnerSettings(
    headTableName: String,
    streamRetention: FiniteDuration = FiniteDuration(24, scala.concurrent.duration.HOURS),
    recoveryInterval: FiniteDuration = FiniteDuration(1, scala.concurrent.duration.MINUTES),
    recoveryMaxTasksPerRun: Int = RecoveryService.DefaultMaxTasksPerRun
)

final class LocalRmuRunner(
    settings: RunnerSettings,
    poller: HeadStreamPoller,
    resyncer: Resyncer,
    recoveryService: RecoveryService,
    retryPolicy: RetryPolicy,
    pollBackoff: Backoff,
    sleeper: Sleeper = Sleeper.system,
    clock: Clock = Clock.systemUTC()
) {

  private val logger = LoggerFactory.getLogger(getClass)

  @volatile private var running = true

  def stop(): Unit = running = false

  def run(): Unit = {
    // The first start has no positions at all, and a restart may follow a stop longer than the stream retention
    // window. Reconcile every head before consuming the stream; do not start polling until the baseline scan
    // completes, otherwise an aggregate that was not scanned has no recovery task.
    while (running && !resync("initial reconciliation")) {
      sleeper.sleep(pollBackoff.next())
    }
    recoverPending()
    var nextRecoveryAt = clock.instant().plusMillis(settings.recoveryInterval.toMillis)

    while (running) {
      try {
        var skipPoll = false
        if (ResyncTrigger.shouldResync(poller.lastRecordTime, clock.instant(), settings.streamRetention)) {
          logger.warn(
            "the reader was stopped longer than the stream retention window; running a full resync"
          )
          if (resync("resync after the retention window")) {
            poller.reset()
            pollBackoff.reset()
          } else {
            sleeper.sleep(pollBackoff.next())
            skipPoll = true
          }
        }
        if (!skipPoll) {
          poller.pollOnce(settings.headTableName) match {
            case Success(processed) =>
              if (processed > 0) pollBackoff.reset() else sleeper.sleep(pollBackoff.next())
            case Failure(error: StreamTrimmedException) =>
              logger.warn(
                s"the reader fell behind the stream retention window; running a full resync: shardId=${error.shardId}",
                error
              )
              if (resync("resync after trimmed stream")) {
                poller.reset()
                pollBackoff.reset()
              } else {
                sleeper.sleep(pollBackoff.next())
              }
            case Failure(error: RetryInterruptedException) =>
              throw error
            case Failure(error) =>
              logger.warn("polling the head stream failed; the next poll retries", error)
              sleeper.sleep(pollBackoff.next())
          }
        }
        if (clock.instant().isAfter(nextRecoveryAt)) {
          recoverPending()
          nextRecoveryAt = clock.instant().plusMillis(settings.recoveryInterval.toMillis)
        }
      } catch {
        case error: RetryInterruptedException =>
          throw error
        case error: Throwable =>
          logger.warn("the read model updater loop failed; the next iteration retries", error)
          sleeper.sleep(pollBackoff.next())
      }
    }
  }

  private def resync(label: String): Boolean =
    retryPolicy.run(resyncer.resync()) match {
      case Success(result) =>
        logger.info(
          s"$label finished: heads=${result.heads}, skipped=${result.skipped}, advanced=${result.advanced}, failed=${result.failed}"
        )
        true
      case Failure(error: RetryInterruptedException) =>
        throw error
      case Failure(error) =>
        // Permanent per-aggregate failures were already recorded as recovery tasks and logged inside the resyncer.
        // A storage failure leaves the resync incomplete; the caller keeps retrying it before polling again.
        logger.error(s"$label did not complete", error)
        false
    }

  private def recoverPending(): Unit =
    recoveryService.recoverPending(settings.recoveryMaxTasksPerRun) match {
      case Success(completed) =>
        if (completed > 0) {
          logger.info(s"completed pending recovery tasks: count=$completed")
        }
      case Failure(error: RetryInterruptedException) =>
        throw error
      case Failure(error) =>
        logger.warn("some pending recovery tasks did not complete", error)
    }
}
