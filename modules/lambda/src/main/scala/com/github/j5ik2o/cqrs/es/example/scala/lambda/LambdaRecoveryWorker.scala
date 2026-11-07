package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryException
import com.github.j5ik2o.cqrs.es.example.scala.rmu.RecoveryService
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success

/** Replays durable recovery tasks when the recovery queue wakes it.
  *
  * The SQS message is only a wake-up signal. The worker reads pending tasks from MySQL and replays them from the
  * journal, so a redelivered message is harmless. A backlog larger than the per-run budget is drained in bounded
  * batches. If it is still not empty, every message is returned as a batch item failure so SQS retries and the redrive
  * policy eventually parks a poison message.
  */
final class LambdaRecoveryWorker(
    recoveryService: RecoveryService,
    maxTasksPerRun: Int
) {

  import LambdaRecoveryWorker._

  private def this(dependencies: LambdaRmuDependencies) =
    this(dependencies.recoveryService, dependencies.maxRecoveryTasksPerRun)

  /** Lambda instantiates the handler class with a no-argument constructor. */
  def this() = this(LambdaRmuDependencies.create())

  private val logger = LoggerFactory.getLogger(getClass)

  def handleRequest(event: SQSEvent, context: Context): SQSBatchResponse =
    recoverAllBatches() match {
      case Right(completed) =>
        logger.info("recovery run completed {} pending tasks", Int.box(completed))
        new SQSBatchResponse(new java.util.ArrayList[SQSBatchResponse.BatchItemFailure]())
      case Left(error) =>
        failureResponse(event, error)
    }

  private def recoverAllBatches(): Either[RuntimeException, Int] = {
    var completed                             = 0
    var batch                                 = 0
    var result: Either[RuntimeException, Int] = Right(0)
    var done                                  = false
    while (!done && batch < MaxBatchesPerRun) {
      recoveryService.recoverPending(maxTasksPerRun) match {
        case Success(count) =>
          completed += count
          if (count < maxTasksPerRun) {
            result = Right(completed)
            done = true
          }
        case Failure(error: RuntimeException) =>
          result = Left(error)
          done = true
        case Failure(error) =>
          result = Left(new RecoveryException("recovery run failed", error))
          done = true
      }
      batch += 1
    }
    if (!done) {
      Left(new RecoveryException("recovery run reached the batch cap"))
    } else {
      result
    }
  }

  private def failureResponse(event: SQSEvent, failure: RuntimeException): SQSBatchResponse = {
    val messageIds = Option(event)
      .flatMap(value => Option(value.getRecords))
      .map(_.asScala.toVector)
      .getOrElse(Vector.empty)
      .flatMap(record => Option(record.getMessageId))
    if (messageIds.isEmpty) {
      // A manual invocation has no SQS message to redeliver, so the caller must see the failure instead of a
      // successful response.
      throw failure
    }
    logger.error("recovery run failed; the messages will be redelivered", failure)
    new SQSBatchResponse(messageIds.map(new SQSBatchResponse.BatchItemFailure(_)).asJava)
  }
}

object LambdaRecoveryWorker {

  /** A single message may wake a large backlog. Drain it in bounded batches, then let SQS redeliver if the cap is
    * reached.
    */
  final val MaxBatchesPerRun = 10
}
