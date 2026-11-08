package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ReadModelSequenceNumberSpec extends AnyFreeSpec with Matchers {

  private val baseTime = Instant.parse("2026-01-01T00:00:00Z")

  private val owner = UserAccountId
    .fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    .toOption
    .get

  private val ownerMember = Member(MemberId.newId, owner, MemberRole.Admin)

  "the T-9 sequence number guard" - {
    "accepts every value up to and including 2^53 - 1" in {
      Seq(0L, 2147483647L, 2147483648L, 9007199254740990L, 9007199254740991L).foreach { seqNr =>
        ReadModelSequenceNumber.validate(seqNr).get shouldBe seqNr
      }
    }

    "rejects 2^53 and every larger value" in {
      ReadModelSequenceNumber.validate(9007199254740992L).isFailure shouldBe true

      val error = ReadModelSequenceNumber.validate(9007199254740992L).failed.get
      error shouldBe a[ReadModelSequenceNumber.OutOfRangeException]
      error.asInstanceOf[ReadModelSequenceNumber.OutOfRangeException].seqNr shouldBe 9007199254740992L
    }
  }

  "the in-memory read model" - {
    "rejects a sequence number above the T-9 limit before applying the projection" in {
      val dao = new InMemoryReadModelDao

      val result = dao.insertGroupChat("aggregate", "team", ownerMember, baseTime, 9007199254740992L)

      result.failed.get shouldBe a[ReadModelSequenceNumber.OutOfRangeException]
      dao.lastProcessedSeqNr("aggregate").get shouldBe 0L
      dao.findGroupChat("aggregate", owner.asString).get shouldBe None
    }
  }
}
