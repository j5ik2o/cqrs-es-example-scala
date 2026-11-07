package com.github.j5ik2o.cqrs.es.example.scala.command.processor

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatError.GroupChatNotFoundError
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatError.NotAdministratorError
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatError.NotMemberError
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.GroupChatRepository
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import scala.collection.mutable
import scala.util.Try

class GroupChatCommandProcessorSpec extends AnyFreeSpec with Matchers {

  private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
  private val clock        = Clock.fixed(fixedInstant, ZoneOffset.UTC)

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner    = userAccountId('V')
  private val member   = userAccountId('W')
  private val outsider = userAccountId('X')

  private final class InMemoryRepository extends GroupChatRepository {

    private val states = mutable.Map.empty[GroupChatId, GroupChat]

    override def findById(id: GroupChatId): Try[Option[GroupChat]] = Try(states.get(id))

    override def persistEvent(
        id: GroupChatId,
        event: GroupChatEvent,
        seqNr: Long,
        occurredAt: Instant
    ): Try[Unit] = Try {
      states.update(id, GroupChat.applyEvent(states.get(id), id, event, seqNr, occurredAt))
    }

    override def persistEventAndSnapshot(
        id: GroupChatId,
        event: GroupChatEvent,
        snapshot: GroupChat,
        occurredAt: Instant
    ): Try[Unit] = Try {
      states.update(id, snapshot)
    }

    def stateOf(id: GroupChatId): Option[GroupChat] = states.get(id)
  }

  private def newProcessor(): (GroupChatCommandProcessor, InMemoryRepository) = {
    val repository = new InMemoryRepository
    (new GroupChatCommandProcessor(repository, clock, SnapshotPolicy.Always), repository)
  }

  "createGroupChat" - {
    "persists a new group chat whose owner is an administrator" in {
      val (processor, repository) = newProcessor()

      val id = processor.createGroupChat(GroupChatName("team"), owner).toOption.get

      val state = repository.stateOf(id).get
      state.seqNr shouldBe 1L
      state.members.isAdministrator(owner) shouldBe true
      state.lastUpdatedAt shouldBe fixedInstant
    }
  }

  "member commands" - {
    "adds and removes a member" in {
      val (processor, repository) = newProcessor()
      val id                      = processor.createGroupChat(GroupChatName("team"), owner).toOption.get

      processor.addMember(id, member, MemberRole.Member, owner).toOption.get shouldBe id
      repository.stateOf(id).get.members.isMember(member) shouldBe true

      processor.removeMember(id, member, owner).toOption.get shouldBe id
      repository.stateOf(id).get.members.isMember(member) shouldBe false
    }

    "rejects a member addition by a non-administrator" in {
      val (processor, _) = newProcessor()
      val id             = processor.createGroupChat(GroupChatName("team"), owner).toOption.get

      val result = processor.addMember(id, outsider, MemberRole.Member, member)

      result.left.toOption.get shouldBe a[CommandError.DomainError]
      result.left.toOption.get.asInstanceOf[CommandError.DomainError].cause shouldBe
      NotAdministratorError(id, member)
    }
  }

  "message commands" - {
    "posts, edits and deletes a message" in {
      val (processor, repository) = newProcessor()
      val id                      = processor.createGroupChat(GroupChatName("team"), owner).toOption.get

      val (_, messageId) = processor.postMessage(id, MessageBody("hello"), owner).toOption.get
      repository.stateOf(id).get.messages.findById(messageId).map(_.body) shouldBe Some(MessageBody("hello"))

      processor.editMessage(id, messageId, MessageBody("edited"), owner).toOption.get shouldBe id
      repository.stateOf(id).get.messages.findById(messageId).map(_.body) shouldBe Some(MessageBody("edited"))

      processor.deleteMessage(id, messageId, owner).toOption.get shouldBe id
      repository.stateOf(id).get.messages.findById(messageId) shouldBe None
    }

    "rejects a message from a non-member" in {
      val (processor, _) = newProcessor()
      val id             = processor.createGroupChat(GroupChatName("team"), owner).toOption.get

      val result = processor.postMessage(id, MessageBody("hello"), outsider)

      result.left.toOption.get shouldBe
      CommandError.DomainError(NotMemberError(id, outsider))
    }
  }

  "a missing group chat" - {
    "returns a domain error" in {
      val (processor, _) = newProcessor()
      val id             = GroupChatId.newId

      processor.deleteGroupChat(id, owner).left.toOption.get shouldBe
      CommandError.DomainError(GroupChatNotFoundError(id))
    }
  }
}
