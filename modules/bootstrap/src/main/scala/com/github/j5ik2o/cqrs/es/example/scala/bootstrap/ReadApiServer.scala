package com.github.j5ik2o.cqrs.es.example.scala.bootstrap

import caliban.CalibanError
import caliban.interop.tapir.HttpInterpreter
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.graphql.ReadApi
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.JdbcReadModelDao
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.slf4j.LoggerFactory
import zio.Exit
import zio.Task
import zio.ZIO
import zio.ZIOAppDefault
import zio.interop.catz._

object ReadApiServer extends ZIOAppDefault {

  private val logger = LoggerFactory.getLogger(getClass)

  override def run: Task[Unit] =
    for {
      config <- ZIO.fromEither(ReadApiConfig.fromEnv()).mapError(message => new IllegalArgumentException(message))
      _ = logger.info(
        "starting read api server: host={}, port={}",
        config.host,
        config.port.toString
      )
      dao = new JdbcReadModelDao(config.readModel)
      interpreter <- ZIO.attempt {
        ReadApi(dao).interpreter match {
          case Exit.Success(value) => value
          case Exit.Failure(cause) =>
            throw new IllegalStateException(s"failed to build the GraphQL schema: ${cause.prettyPrint}")
        }
      }
      httpApp = Router(
        "/graphql" -> caliban.Http4sAdapter.makeHttpService[Any, CalibanError](
          HttpInterpreter(interpreter)
        )
      ).orNotFound
      host <- ZIO.attempt(
        Host
          .fromString(config.host).getOrElse(
            throw new IllegalArgumentException(s"invalid host: ${config.host}")
          )
      )
      port <- ZIO.attempt(
        Port
          .fromInt(config.port).getOrElse(
            throw new IllegalArgumentException(s"invalid port: ${config.port}")
          )
      )
      _ <- EmberServerBuilder
        .default[Task]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .use { _ =>
          ZIO.succeed(
            logger.info("read api server is ready at http://{}:{}/graphql", config.host, config.port.toString)
          ) *> ZIO.never
        }
    } yield ()
}
