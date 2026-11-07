package com.github.j5ik2o.cqrs.es.example.scala.rmu

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.JdbcReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant
import scala.util.Try

/** Opt-in check of the MySQL implementation. Set `READ_MODEL_INTEGRATION_URL` (and optionally
  * `READ_MODEL_INTEGRATION_USER` / `READ_MODEL_INTEGRATION_PASSWORD`) to run it against a database that already has the
  * migrations applied. Without the variable the suite is canceled, so `sbt test` does not need MySQL.
  */
class JdbcReadModelDaoIntegrationSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  private final class FakeJournal extends JournalEventReader {

    private var events = Map.empty[String, Vector[HeadEventRecord]]

    def store(aggregateId: String, records: HeadEventRecord*): Unit =
      events = events.updated(aggregateId, records.toVector)

    override def eventsSince(aggregateId: String, seqNr: Long): Try[Vector[HeadEventRecord]] =
      Try(events.getOrElse(aggregateId, Vector.empty).filter(_.seqNr >= seqNr).sortBy(_.seqNr))
  }

  private val integrationConfig: Option[ReadModelDatabaseConfig] =
    sys.env.get("READ_MODEL_INTEGRATION_URL").map { url =>
      ReadModelDatabaseConfig(
        url = url,
        user = sys.env.getOrElse("READ_MODEL_INTEGRATION_USER", "root"),
        password = sys.env.getOrElse("READ_MODEL_INTEGRATION_PASSWORD", "password")
      )
    }

  private val baseTime = Instant.parse("2026-01-01T00:00:00Z")

  private def userId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner  = userId('V')
  private val member = userId('W')
  private val other  = userId('X')

  private def requireConfig(): ReadModelDatabaseConfig =
    integrationConfig.getOrElse(cancel("READ_MODEL_INTEGRATION_URL is not set"))

  private def clear(config: ReadModelDatabaseConfig): Unit = {
    val connection = DriverManager.getConnection(config.url, config.user, config.password)
    try {
      val statement = connection.createStatement()
      try {
        statement.executeUpdate("SET FOREIGN_KEY_CHECKS = 0")
        Seq("messages", "members", "group_chats", "read_model_positions").foreach { table =>
          statement.executeUpdate(s"TRUNCATE TABLE $table")
        }
        statement.executeUpdate("SET FOREIGN_KEY_CHECKS = 1")
      } finally statement.close()
    } finally connection.close()
  }

  override def beforeEach(): Unit = integrationConfig.foreach(clear)

  private def record(seqNr: Long, event: GroupChatEvent): HeadEventRecord =
    HeadEventRecord(seqNr, baseTime.plusSeconds(seqNr), event.manifest, event)

  private def transition(aggregateId: String, seqNr: Long, event: GroupChatEvent): HeadTransition =
    HeadTransition(aggregateId, seqNr, Vector(record(seqNr, event)))

  "the JDBC read model" - {
    "projects every event, enforces idempotency and scopes reads" in {
      val config      = requireConfig()
      val dao         = new JdbcReadModelDao(config)
      val journal     = new FakeJournal
      val updater     = new ReadModelUpdater(dao, journal)
      val aggregateId = GroupChatId.newId.asString
      val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)
      val addedMember = Member(MemberId.newId, member, MemberRole.Member)
      val message     = Message(MessageId.newId, MessageBody("hello"), owner)

      def apply(seqNr: Long, event: GroupChatEvent): Unit =
        updater.applyTransition(transition(aggregateId, seqNr, event)).get

      apply(1L, GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner))
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "team"
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) shouldBe Vector(owner.asString)
      dao.findGroupChats(other.asString).get shouldBe empty

      apply(1L, GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner))
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
      dao.findMembers(aggregateId, owner.asString).get should have size 1

      apply(2L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))
      dao.findGroupChat(aggregateId, member.asString).get shouldBe None
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"

      apply(3L, GroupChatEvent.MemberAdded(addedMember, owner))
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) should contain theSameElementsAs Seq(
        owner.asString,
        member.asString
      )

      apply(4L, GroupChatEvent.MessagePosted(message, owner))
      dao.findMessages(aggregateId, member.asString).get.map(_.text) shouldBe Vector("hello")
      dao.findMessages(aggregateId, other.asString).get shouldBe empty

      apply(5L, GroupChatEvent.MessageEdited(message.id, MessageBody("edited"), owner))
      dao.findMessage(message.id.asString, member.asString).get.get.text shouldBe "edited"
      dao.findMessage(message.id.asString, other.asString).get shouldBe None

      apply(6L, GroupChatEvent.MessageDeleted(message.id, owner))
      dao.findMessages(aggregateId, member.asString).get shouldBe empty

      apply(7L, GroupChatEvent.MemberRemoved(member, owner))
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) shouldBe Vector(owner.asString)
      dao.findGroupChat(aggregateId, member.asString).get shouldBe None

      apply(8L, GroupChatEvent.Deleted(owner))
      dao.findGroupChats(owner.asString).get shouldBe empty
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 8L
    }

    "fills a gap from the journal" in {
      val config      = requireConfig()
      val dao         = new JdbcReadModelDao(config)
      val journal     = new FakeJournal
      val updater     = new ReadModelUpdater(dao, journal)
      val aggregateId = GroupChatId.newId.asString
      val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)
      val addedMember = Member(MemberId.newId, member, MemberRole.Member)

      updater
        .applyTransition(transition(aggregateId, 1L, GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner)))
        .get
      journal.store(aggregateId, record(2L, GroupChatEvent.MemberAdded(addedMember, owner)))
      updater
        .applyTransition(transition(aggregateId, 3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner)))
        .get

      dao.lastProcessedSeqNr(aggregateId).get shouldBe 3L
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) should contain theSameElementsAs Seq(
        owner.asString,
        member.asString
      )
    }

    "rolls back the position when the read model write fails in the same transaction" in {
      val config      = requireConfig()
      val dao         = new JdbcReadModelDao(config)
      val aggregateId = GroupChatId.newId.asString
      val orphan      = Message(MessageId.newId, MessageBody("orphan"), owner)

      val result = dao.insertMessage(aggregateId, orphan, baseTime, 1L)

      result.isFailure shouldBe true
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 0L
    }
  }
}
