package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.sql.SQLException

class FailureClassifierSpec extends AnyFreeSpec with Matchers {

  "the failure classifier" - {
    "marks a payload failure permanent" in {
      val failure = new PermanentFailure(FailureKind.EventPayload, "bad payload")

      FailureClassifier.classify(failure) shouldBe Some(FailureKind.EventPayload)
      FailureClassifier.isRetryable(failure) shouldBe false
    }

    "marks a journal gap permanent even when it is wrapped" in {
      val failure = new RuntimeException("wrapper", new ReadModelGapException("GroupChat-1", 2L, 3L))

      FailureClassifier.classify(failure) shouldBe Some(FailureKind.JournalGap)
      FailureClassifier.isRetryable(failure) shouldBe false
    }

    "marks a duplicate key or data constraint permanent" in {
      val failure = new SQLException("duplicate key", "23000", 1062)

      FailureClassifier.classify(failure) shouldBe Some(FailureKind.ReadModelConstraint)
      FailureClassifier.isRetryable(failure) shouldBe false
    }

    "treats other SQL failures as a storage outage" in {
      val failure = new SQLException("lock wait timeout", "HY000", 1205)

      FailureClassifier.classify(failure) shouldBe None
      FailureClassifier.isRetryable(failure) shouldBe true
    }

    "does not retry an interrupt" in {
      val failure = new RuntimeException("wrapped", new InterruptedException("stop"))

      FailureClassifier.classify(failure) shouldBe None
      FailureClassifier.isRetryable(failure) shouldBe false
    }

    "retries an unknown failure a bounded number of times" in {
      val failure = new IllegalStateException("unknown")

      FailureClassifier.classify(failure) shouldBe None
      FailureClassifier.isRetryable(failure) shouldBe true
    }
  }
}
