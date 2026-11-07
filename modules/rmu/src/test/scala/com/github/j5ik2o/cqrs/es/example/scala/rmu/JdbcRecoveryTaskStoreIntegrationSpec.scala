package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant

/** Opt-in check of the MySQL recovery task store. Set `READ_MODEL_INTEGRATION_URL` to run it against a database that
  * already has the migrations applied. Without the variable the suite is canceled, so `sbt test` does not need MySQL.
  */
class JdbcRecoveryTaskStoreIntegrationSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  private val integrationConfig: Option[ReadModelDatabaseConfig] =
    sys.env.get("READ_MODEL_INTEGRATION_URL").map { url =>
      ReadModelDatabaseConfig(
        url = url,
        user = sys.env.getOrElse("READ_MODEL_INTEGRATION_USER", "root"),
        password = sys.env.getOrElse("READ_MODEL_INTEGRATION_PASSWORD", "password")
      )
    }

  private val now = Instant.parse("2026-01-01T00:00:00Z")

  private def requireConfig(): ReadModelDatabaseConfig =
    integrationConfig.getOrElse(cancel("READ_MODEL_INTEGRATION_URL is not set"))

  private def clear(config: ReadModelDatabaseConfig): Unit = {
    val connection = DriverManager.getConnection(config.url, config.user, config.password)
    try {
      val statement = connection.createStatement()
      try statement.executeUpdate("TRUNCATE TABLE read_model_recovery_tasks")
      finally statement.close()
    } finally connection.close()
  }

  override def beforeEach(): Unit = integrationConfig.foreach(clear)

  private def task(aggregateId: String, seqNr: Long, updatedAt: Instant = now): RecoveryTask =
    RecoveryTask(
      storeId = "store-1",
      projectionName = Projection.Name,
      projectionVersion = Projection.Version,
      aggregateId = aggregateId,
      seqNr = seqNr,
      failureKind = FailureKind.EventPayload,
      errorMessage = "failed",
      status = RecoveryTask.StatusPending,
      createdAt = now,
      updatedAt = updatedAt
    )

  "the JDBC recovery task store" - {
    "records a task idempotently and completes it" in {
      val config = requireConfig()
      val store  = new JdbcRecoveryTaskStore(config)

      store.record(task("GroupChat-A", 1L)).get
      store.record(task("GroupChat-A", 1L).copy(errorMessage = "failed again")).get

      val pending = store.listPending().get
      pending should have size 1
      pending.head.errorMessage shouldBe "failed again"

      store.markCompleted("store-1", Projection.Name, "GroupChat-A", 1L).get
      store.listPending().get shouldBe empty
    }

    "moves a failed task behind tasks that have not been attempted" in {
      val config = requireConfig()
      val store  = new JdbcRecoveryTaskStore(config)
      val past   = now.minusSeconds(60)

      store.record(task("GroupChat-A", 1L, past)).get
      store.record(task("GroupChat-B", 1L, past)).get

      store.markFailed(task("GroupChat-A", 1L), new ReadModelUpdateException("database is down")).get

      val pending = store.listPending().get
      pending.map(_.aggregateId) shouldBe Vector("GroupChat-B", "GroupChat-A")
      pending.head.errorMessage shouldBe "failed"
      pending.last.errorMessage should include("database is down")
    }
  }
}
