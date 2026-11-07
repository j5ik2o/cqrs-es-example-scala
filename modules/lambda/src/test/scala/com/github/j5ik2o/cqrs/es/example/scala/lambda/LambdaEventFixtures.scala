package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{ AttributeValue => LambdaAttributeValue }
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{ StreamRecord => LambdaStreamRecord }
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatSerializers

import java.nio.ByteBuffer
import scala.jdk.CollectionConverters._

/** Builds the Lambda event objects that the handler tests feed to the entry points. */
object LambdaEventFixtures {

  final val AggregateId = "GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV"

  def validPayload(seqNr: Long): Array[Byte] =
    GroupChatSerializers.eventSerializer.serialize(
      GroupChatEvent.Renamed(
        GroupChatName(s"renamed-$seqNr"),
        UserAccountId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
      )
    )

  def attributeS(value: String): LambdaAttributeValue = {
    val attribute = new LambdaAttributeValue()
    attribute.setS(value)
    attribute
  }

  def attributeN(value: Long): LambdaAttributeValue = {
    val attribute = new LambdaAttributeValue()
    attribute.setN(value.toString)
    attribute
  }

  def attributeB(value: Array[Byte]): LambdaAttributeValue = {
    val attribute = new LambdaAttributeValue()
    attribute.setB(ByteBuffer.wrap(value))
    attribute
  }

  def attributeL(values: Seq[LambdaAttributeValue]): LambdaAttributeValue = {
    val attribute = new LambdaAttributeValue()
    attribute.setL(values.asJava)
    attribute
  }

  def attributeM(values: Map[String, LambdaAttributeValue]): LambdaAttributeValue = {
    val attribute = new LambdaAttributeValue()
    attribute.setM(values.asJava)
    attribute
  }

  def eventEntry(seqNr: Long, payload: Array[Byte]): LambdaAttributeValue =
    attributeM(
      Map(
        "seq_nr"      -> attributeN(seqNr),
        "occurred_at" -> attributeN(0L),
        "manifest"    -> attributeS("group-chat-renamed@1"),
        "payload"     -> attributeB(payload)
      )
    )

  def headImage(
      aggregateId: String,
      headSeqNr: Long,
      events: Seq[LambdaAttributeValue]
  ): Map[String, LambdaAttributeValue] =
    Map(
      "aid"       -> attributeS(aggregateId),
      "seq_nr"    -> attributeN(headSeqNr),
      "type_name" -> attributeS("GroupChat"),
      "events"    -> attributeL(events)
    )

  def streamRecord(
      image: Map[String, LambdaAttributeValue],
      sequenceNumber: Option[String],
      eventName: String = "INSERT"
  ): DynamodbEvent.DynamodbStreamRecord = {
    val streamRecord = new LambdaStreamRecord()
    streamRecord.setNewImage(image.asJava)
    sequenceNumber.foreach(streamRecord.setSequenceNumber)
    val record = new DynamodbEvent.DynamodbStreamRecord()
    record.setEventID(s"event-${sequenceNumber.getOrElse("none")}")
    record.setEventName(eventName)
    record.setDynamodb(streamRecord)
    record
  }

  def recordWithoutStream(): DynamodbEvent.DynamodbStreamRecord = {
    val record = new DynamodbEvent.DynamodbStreamRecord()
    record.setEventID("event-no-stream")
    record.setEventName("INSERT")
    record
  }

  def dynamoDbEvent(records: DynamodbEvent.DynamodbStreamRecord*): DynamodbEvent = {
    val event = new DynamodbEvent()
    event.setRecords(records.asJava)
    event
  }

  def sqsEvent(messageIds: String*): SQSEvent = {
    val event = new SQSEvent()
    event.setRecords(
      messageIds.map { id =>
        val message = new SQSEvent.SQSMessage()
        message.setMessageId(id)
        message
      }.asJava
    )
    event
  }

  def emptySqsEvent(): SQSEvent = new SQSEvent()
}
