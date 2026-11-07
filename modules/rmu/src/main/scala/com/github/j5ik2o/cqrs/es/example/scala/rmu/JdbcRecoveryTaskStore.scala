package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import scala.util.Try
import scala.util.Using

/** MySQL implementation of the recovery task store. */
final class JdbcRecoveryTaskStore(config: ReadModelDatabaseConfig) extends RecoveryTaskStore {

  import JdbcRecoveryTaskStore.driverLoaded

  override def record(task: RecoveryTask): Try[Unit] =
    withConnection { connection =>
      execute(
        connection,
        """INSERT INTO read_model_recovery_tasks
          |  (store_id, projection_name, projection_version, aggregate_id, seq_nr,
          |   failure_type, error_message, status, created_at, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          |ON DUPLICATE KEY UPDATE
          |  projection_version = VALUES(projection_version),
          |  failure_type = VALUES(failure_type),
          |  error_message = VALUES(error_message),
          |  status = VALUES(status),
          |  updated_at = VALUES(updated_at)""".stripMargin,
        Seq(
          task.storeId,
          task.projectionName,
          task.projectionVersion,
          task.aggregateId,
          task.seqNr,
          task.failureKind.asString,
          Option(task.errorMessage).getOrElse(""),
          RecoveryTask.StatusPending,
          timestamp(task.createdAt),
          timestamp(task.updatedAt)
        )
      )
      ()
    }

  override def listPending(): Try[Vector[RecoveryTask]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT store_id, projection_name, projection_version, aggregate_id, seq_nr,
          |       failure_type, error_message, status, created_at, updated_at
          |FROM read_model_recovery_tasks
          |WHERE status = 'pending'
          |ORDER BY updated_at, aggregate_id, seq_nr""".stripMargin,
        Seq.empty
      )(toTask)
    }

  override def markCompleted(
      storeId: String,
      projectionName: String,
      aggregateId: String,
      seqNr: Long
  ): Try[Unit] =
    withConnection { connection =>
      execute(
        connection,
        """UPDATE read_model_recovery_tasks
          |SET status = 'completed', updated_at = ?
          |WHERE store_id = ? AND projection_name = ? AND aggregate_id = ? AND seq_nr = ?""".stripMargin,
        Seq(timestamp(Instant.now()), storeId, projectionName, aggregateId, seqNr)
      )
      ()
    }

  override def markFailed(task: RecoveryTask, failure: Throwable): Try[Unit] =
    withConnection { connection =>
      val message = failure match {
        case null  => "recovery attempt failed"
        case error => s"recovery attempt failed: ${Option(error.getMessage).getOrElse(error.getClass.getName)}"
      }
      execute(
        connection,
        """UPDATE read_model_recovery_tasks
          |SET error_message = ?, updated_at = ?
          |WHERE store_id = ? AND projection_name = ? AND aggregate_id = ? AND seq_nr = ?""".stripMargin,
        Seq(
          message,
          timestamp(Instant.now()),
          task.storeId,
          task.projectionName,
          task.aggregateId,
          task.seqNr
        )
      )
      ()
    }

  private def toTask(resultSet: ResultSet): RecoveryTask =
    RecoveryTask(
      storeId = resultSet.getString("store_id"),
      projectionName = resultSet.getString("projection_name"),
      projectionVersion = resultSet.getString("projection_version"),
      aggregateId = resultSet.getString("aggregate_id"),
      seqNr = resultSet.getLong("seq_nr"),
      failureKind = FailureKind.fromString(resultSet.getString("failure_type")),
      errorMessage = resultSet.getString("error_message"),
      status = resultSet.getString("status"),
      createdAt = resultSet.getTimestamp("created_at").toInstant,
      updatedAt = resultSet.getTimestamp("updated_at").toInstant
    )

  private def query[A](
      connection: Connection,
      sql: String,
      parameters: Seq[Any]
  )(mapRow: ResultSet => A): Vector[A] =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      bind(statement, parameters)
      Using.resource(statement.executeQuery()) { resultSet =>
        val rows = Vector.newBuilder[A]
        while (resultSet.next()) {
          rows += mapRow(resultSet)
        }
        rows.result()
      }
    }

  private def execute(connection: Connection, sql: String, parameters: Seq[Any]): Int =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      bind(statement, parameters)
      statement.executeUpdate()
    }

  private def bind(statement: PreparedStatement, parameters: Seq[Any]): Unit =
    parameters.zipWithIndex.foreach { case (parameter, index) =>
      val position = index + 1
      parameter match {
        case value: String    => statement.setString(position, value)
        case value: Long      => statement.setLong(position, value)
        case value: Int       => statement.setInt(position, value)
        case value: Boolean   => statement.setBoolean(position, value)
        case value: Timestamp => statement.setTimestamp(position, value)
        case other =>
          throw new IllegalArgumentException(s"unsupported JDBC parameter type: ${other.getClass.getName}")
      }
    }

  private def withConnection[A](apply: Connection => A): Try[A] =
    Try {
      val _          = driverLoaded
      val connection = DriverManager.getConnection(config.url, config.user, config.password)
      try apply(connection)
      finally connection.close()
    }

  private def timestamp(instant: Instant): Timestamp = Timestamp.from(instant)
}

object JdbcRecoveryTaskStore {
  private lazy val driverLoaded: Boolean =
    Try(Class.forName("com.mysql.cj.jdbc.Driver")).isSuccess
}
