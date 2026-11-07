package com.github.j5ik2o.cqrs.es.example.scala.fault

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Backoff
import com.github.j5ik2o.cqrs.es.example.scala.rmu.DynamoDbHeadStreamClient
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadScanner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamClient
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamPoller
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadTransition
import com.github.j5ik2o.cqrs.es.example.scala.rmu.LocalRmuRunner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelEventApplier
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdater
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecordOutcome
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryService
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Resyncer
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RetryPolicy
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RunnerSettings

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.SECONDS

/** The five DY-15 scenarios, each run against the real LocalStack tables and the real MySQL read model.
  *
  * The long-running read model updater service is stopped before this runs, so each scenario owns the only reader of
  * the head stream. Every scenario uses fresh aggregate ids, so the position guard, not test cleanup, keeps the runs
  * independent.
  */
final class FaultScenarios(context: FaultContext) {

  import E2eSupport._
  import context.fixtures

  private val maxItemCount = 32

  private final case class RunningUpdater(runner: LocalRmuRunner, thread: Thread)

  private def updateRetry: RetryPolicy =
    new RetryPolicy(
      maxAttempts = 3,
      maxElapsed = FiniteDuration(10, SECONDS),
      initialDelay = FiniteDuration(50, MILLISECONDS),
      maxDelay = FiniteDuration(200, MILLISECONDS),
      jitter = _ => FiniteDuration(50, MILLISECONDS)
    )

  private def pollBackoff: Backoff =
    new Backoff(
      FiniteDuration(100, MILLISECONDS),
      FiniteDuration(500, MILLISECONDS),
      _ => FiniteDuration(100, MILLISECONDS)
    )

  /** Starts the production local runner with a fault-injectable stream client and a scenario-specific scanner. */
  private def startWith[S <: HeadScanner](
      streamClient: HeadStreamClient
  )(makeScanner: HeadStreamPoller => S): (RunningUpdater, S) = {
    val applier  = new ReadModelEventApplier(context.dao, context.journal)
    val recovery = new RecoveryService(context.taskStore, context.headScanner, applier)
    val retry    = updateRetry
    val updater  = new ReadModelUpdater(applier, Some(recovery), retry)
    val poller = new HeadStreamPoller(
      client = streamClient,
      handler = record => updater.applyRecord(record, GroupChatSerializers.eventSerializer).map(_ => ()),
      maxItemCount = maxItemCount
    )
    val scanner  = makeScanner(poller)
    val resyncer = new Resyncer(applier, scanner, Some(recovery))
    val runner = new LocalRmuRunner(
      settings = RunnerSettings(
        headTableName = context.config.dynamoDb.headTableName,
        recoveryInterval = FiniteDuration(1, SECONDS)
      ),
      poller = poller,
      resyncer = resyncer,
      recoveryService = recovery,
      retryPolicy = retry,
      pollBackoff = pollBackoff
    )
    val thread = new Thread(() => runner.run(), "fault-e2e-read-model-updater")
    thread.setDaemon(true)
    thread.start()
    (RunningUpdater(runner, thread), scanner)
  }

  private def stop(running: RunningUpdater): Unit = {
    running.runner.stop()
    running.thread.join(10000L)
    if (running.thread.isAlive) {
      throw new AssertionError("the read model updater did not stop within 10s")
    }
  }

  private def newStreamClient(): InjectableStreamClient =
    new InjectableStreamClient(
      new DynamoDbHeadStreamClient(context.dynamoDbClient, context.streamsClient)
    )

  private def positionOf(aggregateId: String): Long =
    context.dao.lastProcessedSeqNr(aggregateId).get

  /** Scenario 1: the subscription is resumed before the startup scan, an update written during the scan is not lost,
    * and a later head record with a journal gap is filled from the journal.
    */
  def startupScanWithConcurrentUpdate(): Unit = {
    val handleRef     = new AtomicReference(fixtures.createChat("scan-update"))
    val aggregateId   = handleRef.get().aggregateId
    val streamClient  = newStreamClient()
    val scanCompleted = new CountDownLatch(1)
    val (running, scanner) = startWith(streamClient) { poller =>
      new InjectDuringScanScanner(
        delegate = context.headScanner,
        poller = poller,
        targetAggregateId = aggregateId,
        inject = () => handleRef.set(fixtures.rename(handleRef.get(), "scan-update-renamed")),
        scanCompleted = scanCompleted
      )
    }

    try {
      awaitUntil("the initial head scan to complete", context.timeout, context.pollInterval)(
        scanCompleted.getCount == 0L,
        s"scanCompleted=${scanCompleted.getCount}"
      )
      check("the initial scan must start after the head stream subscription resumed", scanner.sawSubscriptionFirst)
      check("the scan must inject an update while it runs", scanner.wasInjected)
      awaitUntil("the update injected during the scan to reach the read model", context.timeout, context.pollInterval)(
        positionOf(aggregateId) == 2L,
        s"position=${positionOf(aggregateId)}"
      )
      checkEquals("the read model name after the scan-time update", "scan-update-renamed")(
        context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
      )
    } finally {
      stop(running)
    }

    // The position is 2, but the journal already has 3 and 4. Applying the seq 4 head record must replay seq 3 from
    // the journal first, under the same position guard.
    val withMember = fixtures.addMember(handleRef.get())
    fixtures.rename(withMember, "scan-update-final")
    val record4 = fixtures.journalRecord(aggregateId, 4L)
    val applier = new ReadModelEventApplier(context.dao, context.journal)

    checkEquals("applying a head record with a journal gap", ProjectionResult.Applied)(
      applier.applyHeadEvent(aggregateId, record4).get
    )
    checkEquals("the position after the journal fill", 4L)(positionOf(aggregateId))
    checkEquals("the read model name after the journal fill", "scan-update-final")(
      context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
    )
    check(
      "the member added at seq 3 must reach the read model",
      context.dao
        .findMembers(aggregateId, FaultContext.Owner.asString).get.exists(
          _.userAccountId == FaultContext.Member.asString
        )
    )
  }

  /** Scenario 2: a trimmed stream stops the poller and runs a full resync, and polling continues afterwards.
    *
    * The stream records are suppressed between the trim and the scan, so the scan has to fill the lag from the journal.
    */
  def trimmedStreamTriggersFullResync(): Unit = {
    val streamClient       = newStreamClient()
    val (running, scanner) = startWith(streamClient)(_ => new RecordingScanner(context.headScanner))

    try {
      awaitUntil("the initial head scan", context.timeout, context.pollInterval)(
        scanner.scans >= 1,
        s"scans=${scanner.scans}"
      )
      val scansBefore = scanner.scans

      // Write the lag while the stream records are hidden, then trim. The scan therefore sees a complete head table
      // no matter when the poller notices the trim.
      streamClient.suppressRecords()
      val handle      = fixtures.createChat("trimmed")
      val withMember  = fixtures.addMember(handle)
      val renamed     = fixtures.rename(withMember, "trimmed-renamed")
      val aggregateId = handle.aggregateId
      streamClient.failNextWithTrimmed()

      awaitUntil("the injected trimmed stream failure", context.timeout, context.pollInterval)(
        streamClient.trimmedFailures >= 1,
        s"trimmedFailures=${streamClient.trimmedFailures}"
      )
      awaitUntil("the full resync after the trimmed stream", context.timeout, context.pollInterval)(
        scanner.scans > scansBefore,
        s"scansBefore=$scansBefore, scans=${scanner.scans}"
      )
      awaitUntil("the resync scan to fill the trimmed lag", context.timeout, context.pollInterval)(
        positionOf(aggregateId) == 3L,
        s"position=${positionOf(aggregateId)}"
      )
      checkEquals("the read model name after the trimmed resync", "trimmed-renamed")(
        context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
      )

      streamClient.resumeRecords()
      fixtures.rename(renamed, "trimmed-final")
      awaitUntil("polling to resume after the resync", context.timeout, context.pollInterval)(
        positionOf(aggregateId) == 4L,
        s"position=${positionOf(aggregateId)}"
      )
      checkEquals("the read model name after polling resumes", "trimmed-final")(
        context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
      )
    } finally {
      streamClient.resumeRecords()
      stop(running)
    }
  }

  /** Scenario 3: an expired iterator resumes after the last processed sequence number without a full resync. */
  def expiredIteratorResumesShard(): Unit = {
    val streamClient       = newStreamClient()
    val (running, scanner) = startWith(streamClient)(_ => new RecordingScanner(context.headScanner))

    try {
      awaitUntil("the initial head scan", context.timeout, context.pollInterval)(
        scanner.scans >= 1,
        s"scans=${scanner.scans}"
      )
      val scansBefore = scanner.scans

      val handle      = fixtures.createChat("expired")
      val withMember  = fixtures.addMember(handle)
      val renamed     = fixtures.rename(withMember, "expired-renamed")
      val aggregateId = handle.aggregateId

      awaitUntil("the two stream updates before the iterator expires", context.timeout, context.pollInterval)(
        positionOf(aggregateId) == 3L,
        s"position=${positionOf(aggregateId)}"
      )

      streamClient.failNextWithExpiredIterator()
      awaitUntil("the injected expired iterator", context.timeout, context.pollInterval)(
        streamClient.expiredFailures >= 1,
        s"expiredFailures=${streamClient.expiredFailures}"
      )
      awaitUntil(
        "the poller to resume after the last processed sequence number",
        context.timeout,
        context.pollInterval
      )(
        streamClient.afterSequenceCalls.nonEmpty,
        "no AFTER_SEQUENCE_NUMBER iterator was requested"
      )

      val finalHandle = fixtures.rename(renamed, "expired-final")
      awaitUntil("the update after the expired iterator", context.timeout, context.pollInterval)(
        positionOf(aggregateId) == finalHandle.state.seqNr,
        s"position=${positionOf(aggregateId)}, expected=${finalHandle.state.seqNr}"
      )
      checkEquals("the read model name after the iterator expires", "expired-final")(
        context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
      )
      checkEquals("an expired iterator must not run a full resync", scansBefore)(scanner.scans)
    } finally {
      stop(running)
    }
  }

  /** Scenario 4: an exhausted retry parks a durable recovery task; replaying it from the journal completes it. */
  def retryExhaustionParksAndRecovers(): Unit = {
    val handle     = fixtures.createChat("retried")
    val withMember = fixtures.addMember(handle)
    fixtures.rename(withMember, "retried-renamed")
    val aggregateId = handle.aggregateId

    val switchable = new SwitchableReadModelDao(context.dao)
    val applier    = new ReadModelEventApplier(switchable, context.journal)
    val recovery   = new RecoveryService(context.taskStore, context.headScanner, applier)
    val retry = new RetryPolicy(
      maxAttempts = 2,
      maxElapsed = FiniteDuration(5, SECONDS),
      initialDelay = FiniteDuration(10, MILLISECONDS),
      maxDelay = FiniteDuration(20, MILLISECONDS),
      jitter = _ => FiniteDuration(0, MILLISECONDS)
    )
    val updater = new ReadModelUpdater(applier, Some(recovery), retry)

    switchable.enableFailure(aggregateId, 3L)
    val record3 = fixtures.journalRecord(aggregateId, 3L)
    checkEquals("the retry exhaustion outcome", RecordOutcome.RecordedFailure)(
      updater.applyTransition(HeadTransition(aggregateId, 3L, Vector(record3))).get
    )

    checkEquals("the position before recovery", 2L)(positionOf(aggregateId))
    val pending = context.taskStore.listPending().get.filter(_.aggregateId == aggregateId)
    checkEquals("the number of pending recovery tasks before recovery", 1)(pending.size)
    checkEquals("the recovery task target sequence number", 3L)(pending.head.seqNr)
    checkEquals("the recovery task status before recovery", "pending")(pending.head.status)

    switchable.disableFailure()
    recovery.recover(pending.head).get

    checkEquals("the position after recovery", 3L)(positionOf(aggregateId))
    checkEquals("the read model name after recovery", "retried-renamed")(
      context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
    )
    check(
      "the recovered member must reach the read model",
      context.dao
        .findMembers(aggregateId, FaultContext.Owner.asString).get.exists(
          _.userAccountId == FaultContext.Member.asString
        )
    )
    check(
      "the recovery task must no longer be pending",
      context.taskStore.listPending().get.forall(_.aggregateId != aggregateId)
    )
    checkEquals("the recovery task status after recovery", "completed")(
      FaultScenarios.recoveryTaskStatus(context.readModel, aggregateId, 3L)
    )
  }

  /** Scenario 5: only `position + 1` is applied, and a projection that fails leaves the position unchanged. */
  def positionGuardRejectsGaps(): Unit = {
    val handle     = fixtures.createChat("guarded")
    val withMember = fixtures.addMember(handle)
    fixtures.rename(withMember, "guarded-renamed")
    val aggregateId = handle.aggregateId

    val applier = new ReadModelEventApplier(context.dao, context.journal)
    val record1 = fixtures.journalRecord(aggregateId, 1L)
    val record2 = fixtures.journalRecord(aggregateId, 2L)
    val record3 = fixtures.journalRecord(aggregateId, 3L)

    checkEquals("applying seq_nr 1", ProjectionResult.Applied)(applier.applyHeadEvent(aggregateId, record1).get)
    checkEquals("the position after seq_nr 1", 1L)(positionOf(aggregateId))

    checkEquals("re-applying seq_nr 1", ProjectionResult.Duplicate)(applier.applyHeadEvent(aggregateId, record1).get)
    checkEquals("the position after a duplicate", 1L)(positionOf(aggregateId))
    checkEquals("the member count after a duplicate", 1)(
      context.dao.findMembers(aggregateId, FaultContext.Owner.asString).get.size
    )

    checkEquals("applying seq_nr 2", ProjectionResult.Applied)(applier.applyHeadEvent(aggregateId, record2).get)
    checkEquals("the position after seq_nr 2", 2L)(positionOf(aggregateId))
    checkEquals("re-applying the older seq_nr 1", ProjectionResult.Duplicate)(
      applier.applyHeadEvent(aggregateId, record1).get
    )
    checkEquals("re-applying seq_nr 2", ProjectionResult.Duplicate)(applier.applyHeadEvent(aggregateId, record2).get)
    checkEquals("the position after older and duplicate deliveries", 2L)(positionOf(aggregateId))

    checkEquals("applying seq_nr 3", ProjectionResult.Applied)(applier.applyHeadEvent(aggregateId, record3).get)
    checkEquals("the position after seq_nr 3", 3L)(positionOf(aggregateId))
    checkEquals("the read model name after seq_nr 3", "guarded-renamed")(
      context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
    )

    // A head that is ahead of the journal must not skip the missing events. seq_nr 5 is not in the journal, so the
    // journal fill for seq_nr 4 rejects the gap and the position stays at 3.
    val future = record3.copy(
      seqNr = 5L,
      event = GroupChatEvent.Renamed(GroupChatName("must-not-apply"), FaultContext.Owner)
    )
    val gap = applier.applyHeadEvent(aggregateId, future)
    check(
      "applying a head ahead of the journal must fail",
      gap.failed.toOption.exists(_.isInstanceOf[ReadModelGapException])
    )
    checkEquals("the position after a rejected gap", 3L)(positionOf(aggregateId))
    checkEquals("the read model name after a rejected gap", "guarded-renamed")(
      context.dao.findGroupChat(aggregateId, FaultContext.Owner.asString).get.get.name
    )

    // The position advance and the read model change commit together. An insert that violates the foreign key must
    // roll the position row back too.
    val orphanAggregateId = GroupChatId.newId.asString
    val orphan            = Message(MessageId.newId, MessageBody("orphan"), FaultContext.Owner)
    check(
      "a projection that violates the foreign key must fail",
      context.dao.insertMessage(orphanAggregateId, orphan, Instant.now(), 1L).isFailure
    )
    checkEquals("the position after a rolled back projection", 0L)(positionOf(orphanAggregateId))
  }
}

object FaultScenarios {

  def recoveryTaskStatus(config: ReadModelDatabaseConfig, aggregateId: String, seqNr: Long): String = {
    import java.sql.DriverManager
    import scala.util.Using

    Using.resource(DriverManager.getConnection(config.url, config.user, config.password)) { connection =>
      Using.resource(
        connection.prepareStatement(
          "SELECT status FROM read_model_recovery_tasks WHERE aggregate_id = ? AND seq_nr = ?"
        )
      ) { statement =>
        statement.setString(1, aggregateId)
        statement.setLong(2, seqNr)
        Using.resource(statement.executeQuery()) { resultSet =>
          if (resultSet.next()) resultSet.getString(1) else "<missing>"
        }
      }
    }
  }
}
