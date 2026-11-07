package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig

final case class ReadApiConfig(host: String, port: Int, readModel: ReadModelDatabaseConfig)

object ReadApiConfig {

  final val DefaultHost             = "0.0.0.0"
  final val DefaultPort             = 48082
  final val DefaultDatabaseUrl      = ReadModelDatabaseConfig.DefaultUrl
  final val DefaultDatabaseUser     = ReadModelDatabaseConfig.DefaultUser
  final val DefaultDatabasePassword = ReadModelDatabaseConfig.DefaultPassword

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
    Right(ReadModelDatabaseConfig.fromEnv(env))

  private[bootstrap] def parsePort(value: String): Either[String, Int] =
    value.toIntOption.filter(port => port > 0 && port <= 65535).toRight(s"invalid port: $value")
}
