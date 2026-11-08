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
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ProjectionResult
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelDatabaseConfig
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelSequenceNumber
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        Seq("read_model_recovery_tasks", "messages", "members", "group_chats", "read_model_positions").foreach {
          table =>
            statement.executeUpdate(s"TRUNCATE TABLE $table")
        }
        statement.executeUpdate("SET FOREIGN_KEY_CHECKS = 1")
      } finally statement.close()
    } finally connection.close()
  }

  override def beforeEach(): Unit = integrationConfig.foreach(clear)

  private def seedPosition(config: ReadModelDatabaseConfig, aggregateId: String, seqNr: Long): Unit = {
    val connection = DriverManager.getConnection(config.url, config.user, config.password)
    try {
      val statement = connection.prepareStatement(
        """INSERT INTO read_model_positions (aggregate_id, last_processed_sequence_number)
          |VALUES (?, ?)
          |ON DUPLICATE KEY UPDATE last_processed_sequence_number = VALUES(last_processed_sequence_number)""".stripMargin
      )
      try {
        statement.setString(1, aggregateId)
        statement.setLong(2, seqNr)
        statement.executeUpdate()
      } finally statement.close()
    } finally connection.close()
  }

  private def sequenceNumbers(
      config: ReadModelDatabaseConfig,
      table: String,
      column: String,
      value: String
  ): Vector[Long] = {
    val connection = DriverManager.getConnection(config.url, config.user, config.password)
    try {
      val statement = connection.prepareStatement(
        s"SELECT last_processed_sequence_number FROM $table WHERE $column = ?"
      )
      try {
        statement.setString(1, value)
        val resultSet = statement.executeQuery()
        try {
          val rows = Vector.newBuilder[Long]
          while (resultSet.next()) rows += resultSet.getLong(1)
          rows.result()
        } finally resultSet.close()
      } finally statement.close()
    } finally connection.close()
  }

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

    "creates the position row once when two readers race" in {
      val config      = requireConfig()
      val dao         = new JdbcReadModelDao(config)
      val applier     = new ReadModelEventApplier(dao, new FakeJournal)
      val aggregateId = GroupChatId.newId.asString
      val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)
      val event       = GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner)
      val headEvent   = record(1L, event)
      val executor    = Executors.newFixedThreadPool(2)
      try {
        val futures = (1 to 2).map { _ =>
          executor.submit(new Callable[ProjectionResult] {
            override def call(): ProjectionResult = applier.applyHeadEvent(aggregateId, headEvent).get
          })
        }
        val results = futures.map(_.get(30L, TimeUnit.SECONDS))

        results.count(_ == ProjectionResult.Applied) shouldBe 1
        results.count(_ == ProjectionResult.Duplicate) shouldBe 1
        dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
        dao.findMembers(aggregateId, owner.asString).get should have size 1
      } finally {
        executor.shutdown()
      }
    }

    "stores 64-bit sequence numbers in all four tables" in {
      val config     = requireConfig()
      val dao        = new JdbcReadModelDao(config)
      val occurredAt = baseTime

      Seq(2147483648L, 9007199254740990L, 9007199254740991L).foreach { seqNr =>
        // group_chats and members are written together by insertGroupChat.
        val groupChatAggregate = s"bigint-group-chat-$seqNr"
        val groupChatAdmin     = Member(MemberId.newId, owner, MemberRole.Admin)
        seedPosition(config, groupChatAggregate, seqNr - 1L)
        val createdGroupChat = dao.insertGroupChat(groupChatAggregate, "team", groupChatAdmin, occurredAt, seqNr)
        createdGroupChat.get shouldBe ProjectionResult.Applied
        dao.lastProcessedSeqNr(groupChatAggregate).get shouldBe seqNr
        sequenceNumbers(config, "group_chats", "id", groupChatAggregate) shouldBe Vector(seqNr)
        sequenceNumbers(config, "members", "group_chat_id", groupChatAggregate) shouldBe Vector(seqNr)

        // A member added later needs a group chat row for the foreign key, so create one at seq 1 first.
        val memberAggregate = s"bigint-member-$seqNr"
        val memberChatAdmin = Member(MemberId.newId, owner, MemberRole.Admin)
        val createdOwner    = dao.insertGroupChat(memberAggregate, "team", memberChatAdmin, occurredAt, 1L)
        createdOwner.get shouldBe ProjectionResult.Applied
        seedPosition(config, memberAggregate, seqNr - 1L)
        val added       = Member(MemberId.newId, member, MemberRole.Member)
        val addedMember = dao.insertMember(memberAggregate, added, occurredAt, seqNr)
        addedMember.get shouldBe ProjectionResult.Applied
        sequenceNumbers(config, "members", "group_chat_id", memberAggregate) should contain(seqNr)

        // Messages also need the group chat row for the foreign key.
        val messageAggregate = s"bigint-message-$seqNr"
        val messageChatAdmin = Member(MemberId.newId, owner, MemberRole.Admin)
        val createdHost      = dao.insertGroupChat(messageAggregate, "team", messageChatAdmin, occurredAt, 1L)
        createdHost.get shouldBe ProjectionResult.Applied
        seedPosition(config, messageAggregate, seqNr - 1L)
        val posted        = Message(MessageId.newId, MessageBody("hello"), owner)
        val postedMessage = dao.insertMessage(messageAggregate, posted, occurredAt, seqNr)
        postedMessage.get shouldBe ProjectionResult.Applied
        sequenceNumbers(config, "messages", "group_chat_id", messageAggregate) shouldBe Vector(seqNr)
        dao.lastProcessedSeqNr(messageAggregate).get shouldBe seqNr
      }
    }

    "rejects a sequence number above the T-9 limit without writing" in {
      val config      = requireConfig()
      val dao         = new JdbcReadModelDao(config)
      val aggregateId = "bigint-overflow"
      seedPosition(config, aggregateId, ReadModelSequenceNumber.MaxSafeValue)

      val result = dao.insertGroupChat(
        aggregateId,
        "team",
        Member(MemberId.newId, owner, MemberRole.Admin),
        baseTime,
        ReadModelSequenceNumber.MaxSafeValue + 1L
      )

      result.failed.get shouldBe a[ReadModelSequenceNumber.OutOfRangeException]
      dao.lastProcessedSeqNr(aggregateId).get shouldBe ReadModelSequenceNumber.MaxSafeValue
      sequenceNumbers(config, "group_chats", "id", aggregateId) shouldBe empty
    }
  }
}
