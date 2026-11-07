package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.github.j5ik2o.cqrs.es.example.scala.rmu.RmuConfig

/** Configuration of the Lambda handlers.
  *
  * It reuses [[RmuConfig]] for the event store, read model and retry settings, and adds the SQS settings of the
  * recovery notifier. The SQS client is configured separately so a custom DynamoDB endpoint is not sent SQS requests.
  */
final case class LambdaRmuConfig(
    rmu: RmuConfig,
    recoveryQueueUrl: Option[String],
    sqsRegion: String,
    sqsEndpointUrl: Option[String]
)

object LambdaRmuConfig {

  def fromEnv(env: String => Option[String] = sys.env.get): Either[String, LambdaRmuConfig] =
    RmuConfig.fromEnv(env).map { rmu =>
      LambdaRmuConfig(
        rmu = rmu,
        recoveryQueueUrl = nonBlank(env("RECOVERY_QUEUE_URL")),
        sqsRegion = nonBlank(env("RECOVERY_SQS_REGION")).getOrElse(rmu.dynamoDb.region),
        sqsEndpointUrl = nonBlank(env("RECOVERY_SQS_ENDPOINT_URL")).orElse(nonBlank(env("AWS_ENDPOINT_URL")))
      )
    }

  private def nonBlank(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)
}
