package com.github.j5ik2o.cqrs.es.example.scala.rmu

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

/** One page of a head table scan. */
final case class HeadPage(
    heads: Vector[Head],
    lastEvaluatedKey: Option[Map[String, AttributeValue]]
)

/** A page source hides the DynamoDB scan request. The pagination loop is tested with a fake source. */
trait HeadPageSource {
  def scanPage(exclusiveStartKey: Option[Map[String, AttributeValue]]): scala.util.Try[HeadPage]
}
