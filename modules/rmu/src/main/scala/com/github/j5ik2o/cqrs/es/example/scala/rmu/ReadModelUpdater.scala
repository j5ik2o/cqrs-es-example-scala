package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDao
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import software.amazon.awssdk.services.dynamodb.model.Record

import scala.util.Failure
import scala.util.Success
import scala.util.Try

enum RecordOutcome {
  case Applied
  case RecordedFailure
}

/** Applies head transitions to the read model.
  *
  * Every projection is a single database transaction that advances `read_model_positions` together with the read model
  * rows. A sequence number that was already applied reports a duplicate and changes nothing; a sequence number that
  * skips ahead is filled from the journal before it is applied. Duplicates, older deliveries and a restart therefore
  * converge on the same position.
  *
  * A permanent failure, or a retry that exhausted its attempt or elapsed budget, is recorded as a recovery task. A
  * record whose payload cannot be decoded is parked the same way using its identifiers only.
  */
final class ReadModelUpdater(
    applier: EventApplier,
    recovery: Option[RecoveryService],
    retryPolicy: RetryPolicy
) {

  /** Convenience constructor for callers that do not need recovery and let the outer loop retry. */
  def this(dao: ReadModelDao, journal: JournalEventReader) =
    this(new ReadModelEventApplier(dao, journal), None, RetryPolicy.noRetry)

  def applyTransition(transition: HeadTransition): Try[RecordOutcome] = {
    val ordered = transition.events.sortBy(_.seqNr)
    val target  = ordered.lastOption.map(_.seqNr).getOrElse(transition.seqNr)

    var remaining: List[HeadEventRecord] = ordered.toList
    var result: Try[RecordOutcome]       = Success(RecordOutcome.Applied)
    var stop                             = false
    while (!stop && remaining.nonEmpty) {
      val record = remaining.head
      remaining = remaining.tail
      retryPolicy.run(applier.applyHeadEvent(transition.aggregateId, record)) match {
        case Success(_) =>
          ()
        case Failure(error) =>
          recovery match {
            case Some(service) if isRecoverable(error) =>
              // A record can carry several events. Recovery must reach the record's last event, otherwise the
              // events after the failed one wait for another record or a resync.
              result = service
                .record(transition.aggregateId, target, record.seqNr, error)
                .map(_ => RecordOutcome.RecordedFailure)
            case _ =>
              result = Failure(error)
          }
          stop = true
      }
    }
    result
  }

  /** Decodes and applies one stream record. A decode failure is parked as a recovery task when its identifiers can be
    * read, so a damaged payload does not stop every later record in the shard.
    */
  def applyRecord(record: Record, serializer: PayloadSerializer[GroupChatEvent]): Try[RecordOutcome] =
    Try(HeadStreamDecoder.decode(record, serializer)) match {
      case Success(Some(transition)) =>
        applyTransition(transition)
      case Success(None) =>
        Success(RecordOutcome.Applied)
      case Failure(error) =>
        val permanentFailure = error match {
          case permanent: PermanentFailure => permanent
          case other =>
            new PermanentFailure(
              FailureKind.EventPayload,
              Option(other.getMessage).getOrElse("failed to decode a head stream record"),
              other
            )
        }
        HeadStreamDecoder.fallbackIdentity(record) match {
          case Some(identity) if recovery.isDefined =>
            recovery.get
              .record(identity.aggregateId, identity.seqNr, identity.seqNr, permanentFailure)
              .map(_ => RecordOutcome.RecordedFailure)
          case _ =>
            Failure(permanentFailure)
        }
    }

  private def isRecoverable(error: Throwable): Boolean =
    FailureClassifier.classify(error).isDefined || error.isInstanceOf[RetryExhaustedException]
}
