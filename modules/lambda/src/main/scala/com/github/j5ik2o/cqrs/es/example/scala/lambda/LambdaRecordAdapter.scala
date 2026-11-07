package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{ AttributeValue => LambdaAttributeValue }
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.{ AttributeValue => SdkAttributeValue }
import software.amazon.awssdk.services.dynamodb.model.{ Record => SdkRecord }
import software.amazon.awssdk.services.dynamodb.model.{ StreamRecord => SdkStreamRecord }

import scala.jdk.CollectionConverters._

/** Converts a Lambda DynamoDB Streams record into the SDK v2 record that [[HeadStreamDecoder]] reads.
  *
  * The head item uses string, number, binary, list and map attributes only. Any other shape is rejected instead of
  * being silently dropped, because a record that cannot be decoded must become a recovery task rather than disappear.
  */
object LambdaRecordAdapter {

  def toSdkRecord(record: DynamodbEvent.DynamodbStreamRecord): SdkRecord = {
    val builder = SdkRecord.builder().eventName(record.getEventName)
    Option(record.getDynamodb).foreach { streamRecord =>
      val streamBuilder = SdkStreamRecord.builder()
      Option(streamRecord.getNewImage).foreach { image =>
        streamBuilder.newImage(
          image.asScala.map { case (key, value) => key -> toSdkAttributeValue(value) }.toMap.asJava
        )
      }
      builder.dynamodb(streamBuilder.build())
    }
    builder.build()
  }

  private def toSdkAttributeValue(value: LambdaAttributeValue): SdkAttributeValue = {
    val builder = SdkAttributeValue.builder()
    if (value.getS != null) {
      builder.s(value.getS)
    } else if (value.getN != null) {
      builder.n(value.getN)
    } else if (value.getB != null) {
      builder.b(SdkBytes.fromByteBuffer(value.getB))
    } else if (value.getL != null) {
      builder.l(value.getL.asScala.map(toSdkAttributeValue).asJava)
    } else if (value.getM != null) {
      builder.m(value.getM.asScala.map { case (key, item) => key -> toSdkAttributeValue(item) }.asJava)
    } else {
      throw new IllegalArgumentException("unsupported DynamoDB attribute value in a head stream record")
    }
    builder.build()
  }
}
