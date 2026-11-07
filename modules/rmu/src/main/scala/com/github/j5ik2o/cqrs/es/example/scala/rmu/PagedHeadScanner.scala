package com.github.j5ik2o.cqrs.es.example.scala.rmu

import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.util.Try

/** Follows `lastEvaluatedKey` until the scan is complete. */
final class PagedHeadScanner(source: HeadPageSource) extends HeadScanner {

  override def scanHeads(visitor: Head => Unit): Try[Unit] =
    Try {
      var exclusiveStartKey = Option.empty[Map[String, AttributeValue]]
      var done              = false
      while (!done) {
        val page = source.scanPage(exclusiveStartKey).get
        page.heads.foreach(visitor)
        exclusiveStartKey = page.lastEvaluatedKey.filter(_.nonEmpty)
        done = exclusiveStartKey.isEmpty
      }
    }
}
