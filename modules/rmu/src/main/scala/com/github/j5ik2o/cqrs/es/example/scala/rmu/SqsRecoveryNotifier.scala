package com.github.j5ik2o.cqrs.es.example.scala.rmu

import io.circe.Json
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/** Wakes the recovery worker through SQS.
  *
  * The message carries identifiers only. The durable task in MySQL stays the source of truth and the worker re-reads
  * the journal, so a lost or duplicated message changes nothing.
  */
final class SqsRecoveryNotifier(
    send: (String, String) => Unit,
    queueUrl: String
) extends RecoveryNotifier {

  override def notifyTask(task: RecoveryTask): Unit =
    send(queueUrl, SqsRecoveryNotifier.messageBody(task))
}

object SqsRecoveryNotifier {

  def apply(client: SqsClient, queueUrl: String): SqsRecoveryNotifier =
    new SqsRecoveryNotifier(
      (url, body) => {
        client.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(body).build())
        ()
      },
      queueUrl
    )

  /** Builds the wake-up body. It never contains the event payload or the failure message. */
  def messageBody(task: RecoveryTask): String =
    Json
      .obj(
        "storeId"           -> Json.fromString(task.storeId),
        "projectionName"    -> Json.fromString(task.projectionName),
        "projectionVersion" -> Json.fromString(task.projectionVersion),
        "aggregateId"       -> Json.fromString(task.aggregateId),
        "seqNr"             -> Json.fromLong(task.seqNr),
        "failureType"       -> Json.fromString(task.failureKind.asString)
      )
      .noSpaces
}
