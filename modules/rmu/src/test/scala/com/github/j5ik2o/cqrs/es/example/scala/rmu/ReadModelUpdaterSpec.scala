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
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.InMemoryReadModelDao
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.ReadModelGapException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

class ReadModelUpdaterSpec extends AnyFreeSpec with Matchers {

  private final class FakeJournal extends JournalEventReader {

    private var events = Map.empty[String, Vector[HeadEventRecord]]

    def store(aggregateId: String, records: HeadEventRecord*): Unit =
      events = events.updated(aggregateId, records.toVector)

    override def eventsSince(aggregateId: String, seqNr: Long): Try[Vector[HeadEventRecord]] =
      Try(events.getOrElse(aggregateId, Vector.empty).filter(_.seqNr >= seqNr).sortBy(_.seqNr))
  }

  private val baseTime = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner       = userAccountId('V')
  private val member      = userAccountId('W')
  private val other       = userAccountId('X')
  private val groupChatId = GroupChatId.newId
  private val aggregateId = groupChatId.asString
  private val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)
  private val addedMember = Member(MemberId.newId, member, MemberRole.Member)
  private val message     = Message(MessageId.newId, MessageBody("hello"), owner)

  private def record(seqNr: Long, event: GroupChatEvent): HeadEventRecord =
    HeadEventRecord(seqNr, baseTime.plusSeconds(seqNr), event.manifest, event)

  private def transition(seqNr: Long, event: GroupChatEvent): HeadTransition =
    HeadTransition(aggregateId, seqNr, Vector(record(seqNr, event)))

  private def created: GroupChatEvent = GroupChatEvent.Created(GroupChatName("team"), ownerMember, owner)

  private def newUpdater(journal: FakeJournal = new FakeJournal): (InMemoryReadModelDao, ReadModelUpdater) = {
    val dao = new InMemoryReadModelDao
    (dao, new ReadModelUpdater(dao, journal))
  }

  private def run(updater: ReadModelUpdater, transitionValue: HeadTransition): Try[Unit] =
    updater.applyTransition(transitionValue)

  "the read model updater" - {
    "projects a created event" in {
      val (dao, updater) = newUpdater()

      run(updater, transition(1L, created)).isSuccess shouldBe true
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
      val groupChat = dao.findGroupChat(aggregateId, owner.asString).get.get
      groupChat.name shouldBe "team"
      groupChat.ownerId shouldBe owner.asString
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) shouldBe Vector(owner.asString)
    }

    "discards a duplicate delivery" in {
      val (dao, updater)    = newUpdater()
      val createdTransition = transition(1L, created)

      run(updater, createdTransition).isSuccess shouldBe true
      run(updater, createdTransition).isSuccess shouldBe true

      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
      dao.findMembers(aggregateId, owner.asString).get should have size 1
    }

    "fills a gap from the journal before applying the received event" in {
      val journal        = new FakeJournal
      val (dao, updater) = newUpdater(journal)
      run(updater, transition(1L, created)).isSuccess shouldBe true
      journal.store(aggregateId, record(2L, GroupChatEvent.MemberAdded(addedMember, owner)))

      run(updater, transition(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))).isSuccess shouldBe true

      dao.lastProcessedSeqNr(aggregateId).get shouldBe 3L
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) should contain theSameElementsAs Seq(
        owner.asString,
        member.asString
      )
    }

    "fails when the journal cannot fill the gap" in {
      val (dao, updater) = newUpdater()
      run(updater, transition(1L, created)).isSuccess shouldBe true

      val result = run(updater, transition(3L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner)))

      result.failed.get shouldBe a[ReadModelGapException]
      dao.lastProcessedSeqNr(aggregateId).get shouldBe 1L
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "team"
    }

    "projects every event type in sequence" in {
      val (dao, updater) = newUpdater()

      run(updater, transition(1L, created)).isSuccess shouldBe true
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "team"

      run(updater, transition(2L, GroupChatEvent.Renamed(GroupChatName("renamed"), owner))).isSuccess shouldBe true
      dao.findGroupChat(aggregateId, owner.asString).get.get.name shouldBe "renamed"

      run(updater, transition(3L, GroupChatEvent.MemberAdded(addedMember, owner))).isSuccess shouldBe true
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) should contain theSameElementsAs Seq(
        owner.asString,
        member.asString
      )

      run(updater, transition(4L, GroupChatEvent.MessagePosted(message, owner))).isSuccess shouldBe true
      dao.findMessages(aggregateId, member.asString).get.map(_.text) shouldBe Vector("hello")

      run(
        updater,
        transition(5L, GroupChatEvent.MessageEdited(message.id, MessageBody("edited"), owner))
      ).isSuccess shouldBe true
      dao.findMessages(aggregateId, member.asString).get.map(_.text) shouldBe Vector("edited")

      run(updater, transition(6L, GroupChatEvent.MessageDeleted(message.id, owner))).isSuccess shouldBe true
      dao.findMessages(aggregateId, member.asString).get shouldBe empty

      run(updater, transition(7L, GroupChatEvent.MemberRemoved(member, owner))).isSuccess shouldBe true
      dao.findMembers(aggregateId, owner.asString).get.map(_.userAccountId) shouldBe Vector(owner.asString)

      run(updater, transition(8L, GroupChatEvent.Deleted(owner))).isSuccess shouldBe true
      dao.findGroupChats(owner.asString).get shouldBe empty
      dao.findGroupChat(aggregateId, other.asString).get shouldBe empty
    }
  }
}
