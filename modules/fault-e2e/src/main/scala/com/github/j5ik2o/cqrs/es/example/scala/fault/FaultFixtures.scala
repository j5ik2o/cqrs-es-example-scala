package com.github.j5ik2o.cqrs.es.example.scala.fault

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatName
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreGroupChatRepository
import com.github.j5ik2o.cqrs.es.example.scala.rmu.EventStoreJournalEventReader
import com.github.j5ik2o.cqrs.es.example.scala.rmu.HeadEventRecord

import java.time.Instant

/** One group chat and its current domain state. */
final case class ChatHandle(id: GroupChatId, state: GroupChat) {
  def aggregateId: String = id.asString
}

/** Writes real events through the real event store (journal, snapshot and head) and reads them back from the journal.
  *
  * The runner never fabricates a head transition for the happy path; every seq_nr comes from the same write path the
  * Write API uses.
  */
final class FaultFixtures(
    repository: EventStoreGroupChatRepository,
    journal: EventStoreJournalEventReader,
    owner: UserAccountId,
    member: UserAccountId
) {

  def createChat(name: String): ChatHandle = {
    val id             = GroupChatId.newId
    val occurredAt     = Instant.now()
    val (state, event) = GroupChat.create(id, GroupChatName(name), owner, occurredAt)
    repository.persistEventAndSnapshot(id, event, state, occurredAt).get
    ChatHandle(id, state)
  }

  def rename(handle: ChatHandle, name: String): ChatHandle = {
    val occurredAt     = Instant.now()
    val (state, event) = handle.state.rename(GroupChatName(name), owner, occurredAt).toOption.get
    repository.persistEvent(handle.id, event, state.seqNr, occurredAt).get
    ChatHandle(handle.id, state)
  }

  def addMember(handle: ChatHandle): ChatHandle = {
    val occurredAt     = Instant.now()
    val (state, event) = handle.state.addMember(member, MemberRole.Member, owner, occurredAt).toOption.get
    repository.persistEvent(handle.id, event, state.seqNr, occurredAt).get
    ChatHandle(handle.id, state)
  }

  def journalRecord(aggregateId: String, seqNr: Long): HeadEventRecord =
    journal
      .eventsSince(aggregateId, seqNr)
      .get
      .find(_.seqNr == seqNr)
      .getOrElse(
        throw new IllegalStateException(s"the journal for $aggregateId has no event at seq_nr $seqNr")
      )
}
