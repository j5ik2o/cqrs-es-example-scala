package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class DynamoDbHeadPageSourceSpec extends AnyFreeSpec with Matchers {

  private val client = DynamoDbClient
    .builder()
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
    .build()

  "the DynamoDB head page source" - {
    "scans consistently, excludes the configuration item and projects the head fields" in {
      val request = new DynamoDbHeadPageSource(client, "head").scanRequest(None)

      request.tableName() shouldBe "head"
      request.consistentRead() shouldBe true
      request.filterExpression() shouldBe "aid <> :config"
      request.expressionAttributeValues().get(":config").s() shouldBe HeadStreamDecoder.ConfigAid
      request.projectionExpression() shouldBe "aid, seq_nr, type_name"
      request.limit() shouldBe null
      request.exclusiveStartKey().isEmpty shouldBe true
    }

    "sets the page limit and the exclusive start key" in {
      val startKey = Map("aid" -> AttributeValue.fromS("GroupChat-1"))
      val request  = new DynamoDbHeadPageSource(client, "head", Some(2)).scanRequest(Some(startKey))

      request.limit().intValue() shouldBe 2
      request.exclusiveStartKey().get("aid").s() shouldBe "GroupChat-1"
    }
  }
}
