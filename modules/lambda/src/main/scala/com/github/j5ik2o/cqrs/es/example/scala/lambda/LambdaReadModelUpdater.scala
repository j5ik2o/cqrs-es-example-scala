package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers
import com.github.j5ik2o.cqrs.es.example.scala.rmu.FailureKind
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadStreamDecoder
import com.github.j5ik2o.cqrs.es.example.scala.rmu.PermanentFailure
import com.github.j5ik2o.cqrs.es.example.scala.rmu.ReadModelUpdater
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecordOutcome
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success

/** The Lambda entry point for the head stream.
  *
  * It returns the records that must be redelivered as a partial batch response. A record whose failure was recorded as
  * a recovery task is not redelivered: the durable task owns it from then on. The remaining records of that aggregate
  * are redelivered because the position rule forbids advancing past an unapplied event. If the record has no sequence
  * number, the whole invocation fails because Lambda cannot checkpoint a partial batch without one.
  */
final class LambdaReadModelUpdater(
    updater: ReadModelUpdater,
    serializer: PayloadSerializer[GroupChatEvent]
) {

  /** Lambda instantiates the handler class with a no-argument constructor. */
  def this() = this(LambdaRmuDependencies.create().updater, GroupChatSerializers.eventSerializer)

  private val logger = LoggerFactory.getLogger(getClass)

  def handleRequest(event: DynamodbEvent, context: Context): StreamsEventResponse = {
    val failures = mutable.ArrayBuffer.empty[StreamsEventResponse.BatchItemFailure]
    val blocked  = mutable.HashSet.empty[String]
    Option(event)
      .flatMap(value => Option(value.getRecords))
      .map(_.asScala)
      .getOrElse(Iterable.empty)
      .foreach { record =>
        val sdkRecord   = LambdaRecordAdapter.toSdkRecord(record)
        val aggregateId = HeadStreamDecoder.fallbackIdentity(sdkRecord).map(_.aggregateId)
        if (aggregateId.exists(blocked.contains)) {
          failures += itemFailure(record)
        } else {
          updater.applyRecord(sdkRecord, serializer) match {
            case Success(RecordOutcome.Applied) =>
              ()
            case Success(RecordOutcome.RecordedFailure) =>
              aggregateId.foreach(blocked.add)
            case Failure(error) =>
              aggregateId.foreach(blocked.add)
              logger.error("failed to apply a head stream record", error)
              failures += itemFailure(record)
          }
        }
      }
    new StreamsEventResponse(failures.asJava)
  }

  private def itemFailure(record: DynamodbEvent.DynamodbStreamRecord): StreamsEventResponse.BatchItemFailure =
    Option(record.getDynamodb)
      .flatMap(value => Option(value.getSequenceNumber))
      .map(new StreamsEventResponse.BatchItemFailure(_))
      .getOrElse(
        throw new PermanentFailure(FailureKind.EventPayload, "head stream record has no sequence number")
      )
}
