package com.github.j5ik2o.cqrs.es.example.scala.rmu

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class PagedHeadScannerSpec extends AnyFreeSpec with Matchers {

  private val key1 = Map("aid" -> AttributeValue.fromS("GroupChat-1"))
  private val key2 = Map("aid" -> AttributeValue.fromS("GroupChat-2"))

  private final class FakePageSource(pages: Vector[HeadPage]) extends HeadPageSource {
    val requests: mutable.Buffer[Option[Map[String, AttributeValue]]] = mutable.Buffer.empty
    private var index                                                 = 0

    override def scanPage(exclusiveStartKey: Option[Map[String, AttributeValue]]): Try[HeadPage] =
      Try {
        requests += exclusiveStartKey
        val page = pages(index)
        index += 1
        page
      }
  }

  "the paged head scanner" - {
    "walks every page with the last evaluated key" in {
      val heads = Vector(
        Head("GroupChat-1", "GroupChat", 1L),
        Head("GroupChat-2", "GroupChat", 2L),
        Head("GroupChat-3", "GroupChat", 3L)
      )
      val source = new FakePageSource(
        Vector(
          HeadPage(Vector(heads(0)), Some(key1)),
          HeadPage(Vector(heads(1)), Some(key2)),
          HeadPage(Vector(heads(2)), None)
        )
      )
      val visited = mutable.Buffer.empty[Head]

      new PagedHeadScanner(source).scanHeads(head => visited += head) shouldBe Success(())

      visited.toVector shouldBe heads
      source.requests.toVector shouldBe Vector(None, Some(key1), Some(key2))
    }

    "propagates a scan failure" in {
      val source = new HeadPageSource {
        override def scanPage(exclusiveStartKey: Option[Map[String, AttributeValue]]): Try[HeadPage] =
          Failure(new IllegalStateException("scan failed"))
      }

      new PagedHeadScanner(source).scanHeads(_ => ()).isFailure shouldBe true
    }
  }
}
