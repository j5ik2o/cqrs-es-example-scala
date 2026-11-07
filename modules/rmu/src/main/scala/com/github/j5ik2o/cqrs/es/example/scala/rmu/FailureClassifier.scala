package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException
import software.amazon.awssdk.awscore.exception.AwsServiceException

import java.io.IOException
import java.io.InterruptedIOException
import java.sql.SQLException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** Splits failures into permanent data problems and retryable storage problems.
  *
  * A permanent failure is written to the recovery task store instead of being retried forever. A retryable failure (a
  * database outage, network error or throttling response) is retried with backoff. An unknown failure is treated as
  * retryable a bounded number of times; if it still fails it is parked as a recovery task.
  */
object FailureClassifier {

  private val PermanentMysqlErrorCodes = Set(1062, 1264, 1366, 1406, 1452)

  private val RetryableAwsErrorCodes = Set(
    "ThrottlingException",
    "Throttling",
    "ProvisionedThroughputExceededException",
    "RequestLimitExceeded",
    "InternalServerError",
    "ServiceUnavailable",
    "ExpiredIteratorException",
    "TransactionConflictException",
    "RequestTimeout",
    "RequestTimeoutException"
  )

  /** Returns the failure kind when retrying cannot fix the record. */
  def classify(failure: Throwable): Option[FailureKind] =
    causes(failure).collectFirst {
      case permanent: PermanentFailure => permanent.kind
      case _: ReadModelGapException    => FailureKind.JournalGap
      case sqlException: SQLException if PermanentMysqlErrorCodes.contains(sqlException.getErrorCode) =>
        FailureKind.ReadModelConstraint
    }

  /** Returns whether the same operation may succeed later. */
  def isRetryable(failure: Throwable): Boolean = {
    if (failure == null) {
      false
    } else if (classify(failure).isDefined) {
      false
    } else if (causes(failure).exists(isInterruption)) {
      false
    } else if (causes(failure).exists(_.isInstanceOf[SQLException])) {
      // Permanent codes are already excluded by classify(), so any other SQL state is a storage outage.
      true
    } else if (causes(failure).exists(isRetryableAwsFailure)) {
      true
    } else if (
      causes(failure).exists(cause => cause.isInstanceOf[IOException] || cause.isInstanceOf[TimeoutException])
    ) {
      true
    } else {
      // Unknown storage errors are retried a bounded number of times. A record that still fails is parked as a
      // recovery task by the caller.
      true
    }
  }

  private def isInterruption(cause: Throwable): Boolean =
    cause.isInstanceOf[InterruptedException] ||
    cause.isInstanceOf[InterruptedIOException] ||
    cause.isInstanceOf[CancellationException] ||
    cause.isInstanceOf[RetryInterruptedException]

  private def isRetryableAwsFailure(cause: Throwable): Boolean =
    cause match {
      case sdkException: AwsServiceException =>
        val errorCode = Option(sdkException.awsErrorDetails()).map(_.errorCode()).getOrElse("")
        RetryableAwsErrorCodes.contains(errorCode) ||
        sdkException.statusCode() == 429 ||
        sdkException.statusCode() >= 500
      case _ => false
    }

  private def causes(failure: Throwable): Iterator[Throwable] =
    Iterator
      .iterate(failure)(cause => if (cause == null) null else cause.getCause)
      .takeWhile(_ != null)
      .take(32)
}
