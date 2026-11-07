package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig

final case class ReadApiConfig(host: String, port: Int, readModel: ReadModelDatabaseConfig)

object ReadApiConfig {

  final val DefaultHost        = "0.0.0.0"
  final val DefaultPort        = 48082
  final val DefaultDatabaseUrl = "jdbc:mysql://localhost:3306/cqrs_es_example?useSSL=false&allowPublicKeyRetrieval=true"
  final val DefaultDatabaseUser     = "root"
  final val DefaultDatabasePassword = "password"

  def fromEnv(env: String => Option[String] = sys.env.get): Either[String, ReadApiConfig] =
    for {
      port <- parsePort(env("READ_API_PORT").getOrElse(DefaultPort.toString))
      readModel <- parseReadModel(env)
    } yield ReadApiConfig(
      host = env("READ_API_HOST").getOrElse(DefaultHost),
      port = port,
      readModel = readModel
    )

  private[bootstrap] def parseReadModel(
      env: String => Option[String]
  ): Either[String, ReadModelDatabaseConfig] =
    Right(
      ReadModelDatabaseConfig(
        url = env("READ_MODEL_DATABASE_URL").getOrElse(DefaultDatabaseUrl),
        user = env("READ_MODEL_DATABASE_USER").getOrElse(DefaultDatabaseUser),
        password = env("READ_MODEL_DATABASE_PASSWORD").getOrElse(DefaultDatabasePassword)
      )
    )

  private[bootstrap] def parsePort(value: String): Either[String, Int] =
    value.toIntOption.filter(port => port > 0 && port <= 65535).toRight(s"invalid port: $value")
}
