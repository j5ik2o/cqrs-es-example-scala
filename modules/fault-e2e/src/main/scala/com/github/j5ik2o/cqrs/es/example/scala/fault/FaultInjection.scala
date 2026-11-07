package com.github.j5ik2o.cqrs.es.example.scala.fault

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.GroupChatRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.MemberRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.MessageRecord
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Head
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadScanner
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamClient
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamPoller
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ShardPage
import com.github.j5ik2o.cqrs.es.example.scala.rmu.StreamRecordPage
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Try

/** Wraps the real DynamoDB Streams client and injects the two stream faults the poller must survive.
  *
  * `TrimmedDataAccessException` makes the poller report a trimmed shard so the runner performs a full resync.
  * `ExpiredIteratorException` makes the poller resume after the last processed sequence number instead of resyncing.
  * `suppressRecords` hides the records of a successful page while keeping its iterator, so the resync scan, rather than
  * the stream, has to apply the lag.
  */
final class InjectableStreamClient(delegate: HeadStreamClient) extends HeadStreamClient {

  private val failTrimmed    = new AtomicBoolean(false)
  private val failExpired    = new AtomicBoolean(false)
  private val suppressing    = new AtomicBoolean(false)
  private val trimmed        = new AtomicInteger(0)
  private val expired        = new AtomicInteger(0)
  private val afterSequences = new ConcurrentLinkedQueue[(String, String)]()

  def failNextWithTrimmed(): Unit = failTrimmed.set(true)

  def failNextWithExpiredIterator(): Unit = failExpired.set(true)

  def suppressRecords(): Unit = suppressing.set(true)

  def resumeRecords(): Unit = suppressing.set(false)

  def trimmedFailures: Int = trimmed.get()

  def expiredFailures: Int = expired.get()

  def afterSequenceCalls: Vector[(String, String)] = afterSequences.iterator().asScala.toVector

  override def streamArn(headTableName: String): Try[String] =
    delegate.streamArn(headTableName)

  override def describeShards(streamArn: String, exclusiveStartShardId: Option[String]): Try[ShardPage] =
    delegate.describeShards(streamArn, exclusiveStartShardId)

  override def getRecords(streamArn: String, shardIterator: String, limit: Int): Try[StreamRecordPage] =
    if (failTrimmed.getAndSet(false)) {
      trimmed.incrementAndGet()
      Failure(TrimmedDataAccessException.builder().message("injected trimmed stream").build())
    } else if (failExpired.getAndSet(false)) {
      expired.incrementAndGet()
      Failure(ExpiredIteratorException.builder().message("injected expired iterator").build())
    } else {
      delegate.getRecords(streamArn, shardIterator, limit).map { page =>
        if (suppressing.get()) {
          StreamRecordPage(Vector.empty, Vector.empty, page.nextShardIterator)
        } else {
          page
        }
      }
    }

  override def shardIteratorAtTrimHorizon(streamArn: String, shardId: String): Try[String] =
    delegate.shardIteratorAtTrimHorizon(streamArn, shardId)

  override def shardIteratorAfterSequenceNumber(
      streamArn: String,
      shardId: String,
      sequenceNumber: String
  ): Try[String] = {
    afterSequences.add((shardId, sequenceNumber))
    delegate.shardIteratorAfterSequenceNumber(streamArn, shardId, sequenceNumber)
  }
}

/** Counts the full head scans so a scenario can prove that a resync ran. */
final class RecordingScanner(delegate: HeadScanner) extends HeadScanner {

  private val invocations = new AtomicInteger(0)

  def scans: Int = invocations.get()

  override def scanHeads(visitor: Head => Unit): Try[Unit] = {
    invocations.incrementAndGet()
    delegate.scanHeads(visitor)
  }
}

/** Injects an update while the startup scan walks the head table.
  *
  * It also records whether the scan started after the head stream subscription resumed. The production runner resumes
  * the subscription before it asks the worker to scan, so a false value means the order regressed.
  */
final class InjectDuringScanScanner(
    delegate: HeadScanner,
    poller: HeadStreamPoller,
    targetAggregateId: String,
    inject: () => Unit,
    scanCompleted: CountDownLatch
) extends HeadScanner {

  private val injected = new AtomicBoolean(false)

  @volatile private var subscriptionFirst = false

  def sawSubscriptionFirst: Boolean = subscriptionFirst

  def wasInjected: Boolean = injected.get()

  override def scanHeads(visitor: Head => Unit): Try[Unit] =
    try {
      subscriptionFirst = poller.lastPollTime.isDefined
      if (!subscriptionFirst) {
        throw new IllegalStateException("the initial scan started before the head stream subscription resumed")
      }
      delegate.scanHeads { head =>
        visitor(head)
        if (head.aggregateId == targetAggregateId && head.seqNr == 1L && injected.compareAndSet(false, true)) {
          inject()
        }
      }
    } finally {
      scanCompleted.countDown()
    }
}

/** Fails the projection of one `(aggregate, seq_nr)` while enabled, and delegates everything else to the real DAO.
  *
  * The failure is a plain runtime exception, which the retry policy treats as retryable. The scenario uses it to
  * exhaust the retry budget and park a durable recovery task.
  */
final class SwitchableReadModelDao(delegate: ReadModelDao) extends ReadModelDao {

  private val failing = new AtomicBoolean(false)

  @volatile private var targetAggregateId = ""

  @volatile private var targetSeqNr = -1L

  def enableFailure(aggregateId: String, seqNr: Long): Unit = {
    targetAggregateId = aggregateId
    targetSeqNr = seqNr
    failing.set(true)
  }

  def disableFailure(): Unit = failing.set(false)

  override def findGroupChat(groupChatId: String, userAccountId: String): Try[Option[GroupChatRecord]] =
    delegate.findGroupChat(groupChatId, userAccountId)

  override def findGroupChats(userAccountId: String): Try[Vector[GroupChatRecord]] =
    delegate.findGroupChats(userAccountId)

  override def findMember(groupChatId: String, userAccountId: String): Try[Option[MemberRecord]] =
    delegate.findMember(groupChatId, userAccountId)

  override def findMembers(groupChatId: String, userAccountId: String): Try[Vector[MemberRecord]] =
    delegate.findMembers(groupChatId, userAccountId)

  override def findMessage(messageId: String, userAccountId: String): Try[Option[MessageRecord]] =
    delegate.findMessage(messageId, userAccountId)

  override def findMessages(groupChatId: String, userAccountId: String): Try[Vector[MessageRecord]] =
    delegate.findMessages(groupChatId, userAccountId)

  override def lastProcessedSeqNr(aggregateId: String): Try[Long] =
    delegate.lastProcessedSeqNr(aggregateId)

  override def insertGroupChat(
      aggregateId: String,
      name: String,
      administrator: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.insertGroupChat(aggregateId, name, administrator, occurredAt, seqNr)
  }

  override def deleteGroupChat(aggregateId: String, occurredAt: Instant, seqNr: Long): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.deleteGroupChat(aggregateId, occurredAt, seqNr)
  }

  override def updateGroupChatName(
      aggregateId: String,
      name: String,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.updateGroupChatName(aggregateId, name, occurredAt, seqNr)
  }

  override def insertMember(
      aggregateId: String,
      member: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.insertMember(aggregateId, member, occurredAt, seqNr)
  }

  override def deleteMember(
      aggregateId: String,
      userAccountId: UserAccountId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.deleteMember(aggregateId, userAccountId, occurredAt, seqNr)
  }

  override def insertMessage(
      aggregateId: String,
      message: Message,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.insertMessage(aggregateId, message, occurredAt, seqNr)
  }

  override def updateMessage(
      aggregateId: String,
      messageId: MessageId,
      body: MessageBody,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.updateMessage(aggregateId, messageId, body, occurredAt, seqNr)
  }

  override def deleteMessage(
      aggregateId: String,
      messageId: MessageId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] = {
    guard(aggregateId, seqNr)
    delegate.deleteMessage(aggregateId, messageId, occurredAt, seqNr)
  }

  private def guard(aggregateId: String, seqNr: Long): Unit =
    if (failing.get() && aggregateId == targetAggregateId && seqNr == targetSeqNr) {
      throw new IllegalStateException(s"injected projection failure: aid=$aggregateId, seq_nr=$seqNr")
    }
}
