package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

/** The local read model updater loop.
  *
  * It follows the head Streams and reconciles every head when it starts, when the stream was trimmed, or when polling
  * has not succeeded for longer than the 24 hour retention window (DY-15). A full reconciliation resumes the stream
  * first and scans the head table in parallel, so an update to a head that was already scanned does not age out of the
  * stream behind a long scan. Pending recovery tasks are replayed at startup and periodically afterwards.
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

  /** Guards the request flags and wakes the reconciliation worker. The worker is the only thread that runs a
    * reconciliation, so two full scans never run at the same time.
    */
  private val resyncMonitor      = new Object
  private var resyncRequested    = false
  private var resyncLabel        = "initial reconciliation"
  private var resyncResetsPoller = false

  @volatile private var resyncThread: Thread = null

  /** Released after the stream has been read successfully once. DY-15 resumes the subscription before the scan so a
    * head updated during the scan is still delivered by the stream (or filled from the journal) instead of expiring
    * behind a scan that takes longer than the retention window.
    */
  private val readsResumed = new CountDownLatch(1)

  def stop(): Unit = {
    running = false
    val thread = resyncThread
    if (thread != null) {
      thread.interrupt()
    }
    resyncMonitor.synchronized(resyncMonitor.notifyAll())
  }

  def run(): Unit = {
    startResyncWorker()
    requestResync("initial reconciliation", resetPoller = false)

    var startupRecoveryPending = true
    var nextRecoveryAt         = clock.instant().plusMillis(settings.recoveryInterval.toMillis)

    while (running) {
      try {
        poller.pollOnce(settings.headTableName) match {
          case Success(processed) =>
            readsResumed.countDown()
            if (processed > 0) pollBackoff.reset() else sleeper.sleep(pollBackoff.next())
          case Failure(error: StreamTrimmedException) =>
            readsResumed.countDown()
            logger.warn(
              s"the reader fell behind the stream retention window; requesting a full resync: shardId=${error.shardId}",
              error
            )
            requestResync("resync after trimmed stream", resetPoller = true)
            sleeper.sleep(pollBackoff.next())
          case Failure(error: RetryInterruptedException) =>
            throw error
          case Failure(error) =>
            logger.warn("polling the head stream failed; the next poll retries", error)
            sleeper.sleep(pollBackoff.next())
        }

        if (ResyncTrigger.shouldResync(poller.lastPollTime, clock.instant(), settings.streamRetention)) {
          logger.warn(
            "the reader was stopped longer than the stream retention window; requesting a full resync"
          )
          requestResync("resync after the retention window", resetPoller = true)
        }

        if (startupRecoveryPending) {
          recoverPending()
          startupRecoveryPending = false
          nextRecoveryAt = clock.instant().plusMillis(settings.recoveryInterval.toMillis)
        }

        if (clock.instant().isAfter(nextRecoveryAt)) {
          recoverPending()
          nextRecoveryAt = clock.instant().plusMillis(settings.recoveryInterval.toMillis)
        }
      } catch {
        case error: RetryInterruptedException =>
          throw error
        case NonFatal(error) =>
          logger.warn("the read model updater loop failed; the next iteration retries", error)
          sleeper.sleep(pollBackoff.next())
      }
    }
  }

  private def startResyncWorker(): Unit = {
    val thread = new Thread(
      () => resyncLoop(),
      "read-model-updater-resync"
    )
    thread.setDaemon(true)
    resyncThread = thread
    thread.start()
  }

  private def requestResync(label: String, resetPoller: Boolean): Unit =
    resyncMonitor.synchronized {
      resyncLabel = label
      resyncResetsPoller = resetPoller
      resyncRequested = true
      resyncMonitor.notifyAll()
    }

  /** The worker keeps one reconciliation in flight. Requests that arrive while it runs set the flag again, so they are
    * queued behind the running scan instead of starting a second one.
    */
  private def resyncLoop(): Unit =
    try {
      readsResumed.await()
      var active = true
      while (active) {
        val request = resyncMonitor.synchronized {
          while (running && !resyncRequested) {
            resyncMonitor.wait()
          }
          if (running) {
            resyncRequested = false
            Some((resyncLabel, resyncResetsPoller))
          } else {
            None
          }
        }
        request match {
          case Some((label, resetPoller)) =>
            if (resync(label)) {
              if (resetPoller) {
                poller.reset()
                pollBackoff.reset()
              }
            } else {
              requestResync(label, resetPoller)
              sleeper.sleep(pollBackoff.next())
            }
          case None =>
            active = false
        }
      }
    } catch {
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
      case _: RetryInterruptedException =>
        ()
      case NonFatal(error) =>
        logger.error("the resync worker stopped after an unexpected failure", error)
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
        // A storage failure leaves the resync incomplete; polling continues and the worker retries the scan.
        logger.error(s"$label did not complete; it will be retried while polling continues", error)
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
