package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class GroupChatSpec extends AnyFreeSpec with Matchers {

  private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner         = userAccountId('V')
  private val administrator = userAccountId('W')
  private val member        = userAccountId('X')
  private val outsider      = userAccountId('Y')

  private def newGroupChat(): GroupChat = {
    val (state, _) =
      GroupChat.create(GroupChatId.newId, GroupChatName("team"), owner, occurredAt)
    state
  }

  private def withMember(state: GroupChat, userAccountId: UserAccountId): GroupChat =
    state
      .addMember(userAccountId, MemberRole.Member, owner, occurredAt.plusSeconds(1))
      .toOption
      .get
      ._1

  private def errorOf[A](result: Either[GroupChatError, A]): GroupChatError =
    result.swap.getOrElse(fail("expected an error"))

  "create" - {
    "makes the executor an administrator and starts at sequence number 1" in {
      val (state, event) = GroupChat.create(GroupChatId.newId, GroupChatName("team"), owner, occurredAt)

      state.seqNr shouldBe 1L
      state.deleted shouldBe false
      state.members.size shouldBe 1
      state.members.isAdministrator(owner) shouldBe true
      state.lastUpdatedAt shouldBe occurredAt
      event shouldBe GroupChatEvent.Created(GroupChatName("team"), state.members.toVector.head, owner)
      event.manifest shouldBe "group-chat-created@1"
    }
  }

  "update" - {
    "renames, adds and removes members, and posts, edits and deletes messages in order" in {
      val created = newGroupChat()

      val (renamed, renamedEvent) =
        created.rename(GroupChatName("renamed"), owner, occurredAt.plusSeconds(1)).toOption.get
      renamed.name shouldBe GroupChatName("renamed")
      renamed.seqNr shouldBe 2L
      renamedEvent.manifest shouldBe "group-chat-renamed@1"

      val (withAdmin, _) = renamed
        .addMember(administrator, MemberRole.Admin, owner, occurredAt.plusSeconds(2))
        .toOption
        .get
      withAdmin.members.isAdministrator(administrator) shouldBe true

      val (withMember, _) = withAdmin
        .addMember(member, MemberRole.Member, administrator, occurredAt.plusSeconds(3))
        .toOption
        .get
      withMember.members.isMember(member) shouldBe true

      val (posted, postedEvent) =
        withMember.postMessage(MessageBody("hello"), member, occurredAt.plusSeconds(4)).toOption.get
      val postedMessage = postedEvent.asInstanceOf[GroupChatEvent.MessagePosted].message
      posted.messages.findById(postedMessage.id) shouldBe Some(postedMessage)

      val (edited, editedEvent) = posted
        .editMessage(postedMessage.id, MessageBody("edited"), member, occurredAt.plusSeconds(5))
        .toOption
        .get
      edited.messages.findById(postedMessage.id).map(_.body) shouldBe Some(MessageBody("edited"))
      editedEvent.manifest shouldBe "group-chat-message-edited@1"

      val (deletedMessage, deletedMessageEvent) = edited
        .deleteMessage(postedMessage.id, member, occurredAt.plusSeconds(6))
        .toOption
        .get
      deletedMessage.messages.findById(postedMessage.id) shouldBe None
      deletedMessageEvent.manifest shouldBe "group-chat-message-deleted@1"

      val (removed, removedEvent) = deletedMessage
        .removeMember(member, administrator, occurredAt.plusSeconds(7))
        .toOption
        .get
      removed.members.isMember(member) shouldBe false
      removedEvent.manifest shouldBe "group-chat-member-removed@1"

      val (deleted, deletedEvent) = removed.delete(owner, occurredAt.plusSeconds(8)).toOption.get
      deleted.deleted shouldBe true
      deletedEvent.manifest shouldBe "group-chat-deleted@1"
    }

    "rejects a rename by a non-administrator" in {
      val state = withMember(newGroupChat(), member)

      errorOf(state.rename(GroupChatName("renamed"), member, occurredAt)) shouldBe
      GroupChatError.NotAdministratorError(state.id, member)
    }

    "rejects a rename to the current name" in {
      val state = newGroupChat()

      errorOf(state.rename(state.name, owner, occurredAt)) shouldBe
      GroupChatError.AlreadyRenamedError(state.id, state.name)
    }

    "rejects a duplicate member" in {
      val state = withMember(newGroupChat(), member)

      errorOf(state.addMember(member, MemberRole.Member, owner, occurredAt)) shouldBe
      GroupChatError.AlreadyMemberError(state.id, member)
    }

    "rejects member removal by a non-administrator" in {
      val state = withMember(newGroupChat(), member)

      errorOf(state.removeMember(member, member, occurredAt)) shouldBe
      GroupChatError.NotAdministratorError(state.id, member)
    }

    "rejects removal of a user account that is not a member" in {
      val state = newGroupChat()

      errorOf(state.removeMember(outsider, owner, occurredAt)) shouldBe
      GroupChatError.NotMemberError(state.id, outsider)
    }

    "rejects a message from a non-member" in {
      val state = newGroupChat()

      errorOf(state.postMessage(MessageBody("hello"), outsider, occurredAt)) shouldBe
      GroupChatError.NotMemberError(state.id, outsider)
    }

    "rejects an edit by someone other than the sender" in {
      val state = withMember(newGroupChat(), member)
      val (posted, event) =
        state.postMessage(MessageBody("hello"), owner, occurredAt.plusSeconds(1)).toOption.get
      val messageId = event.asInstanceOf[GroupChatEvent.MessagePosted].message.id

      errorOf(posted.editMessage(messageId, MessageBody("edited"), member, occurredAt)) shouldBe
      GroupChatError.NotMessageSenderError(state.id, messageId, member)
    }

    "rejects editing a message that does not exist" in {
      val state = newGroupChat()
      val id    = MessageId.newId

      errorOf(state.editMessage(id, MessageBody("edited"), owner, occurredAt)) shouldBe
      GroupChatError.MessageNotFoundError(state.id, id)
    }

    "rejects a delete by someone other than the sender" in {
      val state = withMember(newGroupChat(), member)
      val (posted, event) =
        state.postMessage(MessageBody("hello"), owner, occurredAt.plusSeconds(1)).toOption.get
      val messageId = event.asInstanceOf[GroupChatEvent.MessagePosted].message.id

      errorOf(posted.deleteMessage(messageId, member, occurredAt)) shouldBe
      GroupChatError.NotMessageSenderError(state.id, messageId, member)
    }

    "rejects any change after the group chat is deleted" in {
      val deleted = newGroupChat().delete(owner, occurredAt).toOption.get._1

      errorOf(deleted.rename(GroupChatName("renamed"), owner, occurredAt)) shouldBe
      GroupChatError.AlreadyDeletedError(deleted.id)
      errorOf(deleted.postMessage(MessageBody("hello"), owner, occurredAt)) shouldBe
      GroupChatError.AlreadyDeletedError(deleted.id)
    }
  }

  "replay" - {
    "rebuilds the same state from all events" in {
      val id                      = GroupChatId.newId
      val (created, createdEvent) = GroupChat.create(id, GroupChatName("team"), owner, occurredAt)
      val (renamed, renamedEvent) =
        created.rename(GroupChatName("renamed"), owner, occurredAt.plusSeconds(1)).toOption.get
      val (withAdmin, memberAddedEvent) = renamed
        .addMember(administrator, MemberRole.Admin, owner, occurredAt.plusSeconds(2))
        .toOption
        .get
      val (withMember, memberAddedEvent2) = withAdmin
        .addMember(member, MemberRole.Member, administrator, occurredAt.plusSeconds(3))
        .toOption
        .get
      val (posted, messagePostedEvent) =
        withMember.postMessage(MessageBody("hello"), member, occurredAt.plusSeconds(4)).toOption.get
      val messageId = messagePostedEvent.asInstanceOf[GroupChatEvent.MessagePosted].message.id
      val (edited, messageEditedEvent) =
        posted.editMessage(messageId, MessageBody("edited"), member, occurredAt.plusSeconds(5)).toOption.get
      val (removed, memberRemovedEvent) =
        edited.removeMember(administrator, owner, occurredAt.plusSeconds(6)).toOption.get
      val (finalState, deletedEvent) = removed.delete(owner, occurredAt.plusSeconds(7)).toOption.get

      val events = Seq[(GroupChatEvent, Long, Instant)](
        (createdEvent, 1L, occurredAt),
        (renamedEvent, 2L, occurredAt.plusSeconds(1)),
        (memberAddedEvent, 3L, occurredAt.plusSeconds(2)),
        (memberAddedEvent2, 4L, occurredAt.plusSeconds(3)),
        (messagePostedEvent, 5L, occurredAt.plusSeconds(4)),
        (messageEditedEvent, 6L, occurredAt.plusSeconds(5)),
        (memberRemovedEvent, 7L, occurredAt.plusSeconds(6)),
        (deletedEvent, 8L, occurredAt.plusSeconds(7))
      )

      GroupChat.replay(id, None, events) shouldBe Some(finalState)
      GroupChat.replay(id, Some(created), events.drop(1)) shouldBe Some(finalState)
    }
  }
}
