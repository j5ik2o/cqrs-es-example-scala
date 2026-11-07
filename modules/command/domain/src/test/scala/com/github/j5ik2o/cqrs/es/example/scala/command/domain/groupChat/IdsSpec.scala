package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class IdsSpec extends AnyFreeSpec with Matchers {

  private val canonical = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
  private val lowercase = "01arz3ndektsv4rrffq69g5fav"

  "the aggregate id parsers" - {
    "normalize lowercase ULIDs to the canonical uppercase form" in {
      GroupChatId.fromString(lowercase).toOption.get shouldBe GroupChatId(canonical)
      UserAccountId.fromString(lowercase).toOption.get shouldBe UserAccountId(canonical)
      MessageId.fromString(lowercase).toOption.get shouldBe MessageId(canonical)
    }

    "accept a prefixed id regardless of the prefix casing" in {
      GroupChatId.fromString(s"GroupChat-$canonical").toOption.get shouldBe GroupChatId(canonical)
      GroupChatId.fromString(s"groupchat-$lowercase").toOption.get shouldBe GroupChatId(canonical)
      UserAccountId.fromString(s"useraccount-$lowercase").toOption.get shouldBe UserAccountId(canonical)
    }

    "reject values that are not ULIDs" in {
      GroupChatId.fromString("not-a-ulid").isLeft shouldBe true
      UserAccountId.fromString("not-a-ulid").isLeft shouldBe true
      MessageId.fromString("not-a-ulid").isLeft shouldBe true
    }
  }
}
