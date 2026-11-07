package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.graphql

import caliban.CalibanError
import caliban.GraphQLInterpreter
import caliban.GraphQLResponse
import caliban.ResponseValue
import caliban.Value.StringValue
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository.EventStoreGroupChatRepository
import com.github.j5ik2o.cqrs.es.example.scala.command.processor.GroupChatCommandProcessor
import com.github.j5ik2o.cqrs.es.example.scala.command.processor.SnapshotPolicy
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import zio.Exit
import zio.Runtime
import zio.Unsafe

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WriteApiSpec extends AnyFreeSpec with Matchers {

  private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner    = userAccountId('V')
  private val member   = userAccountId('W')
  private val outsider = userAccountId('X')

  private def newApi(): caliban.GraphQL[Any] = {
    val repository = EventStoreGroupChatRepository.memory()
    val processor  = new GroupChatCommandProcessor(repository, clock, SnapshotPolicy.Always)
    WriteApi(processor)
  }

  private def newInterpreter(): GraphQLInterpreter[Any, CalibanError] =
    newApi().interpreter match {
      case Exit.Success(interpreter) => interpreter
      case Exit.Failure(cause)       => fail(s"failed to build the interpreter: ${cause.prettyPrint}")
    }

  private def execute(
      interpreter: GraphQLInterpreter[Any, CalibanError],
      query: String
  ): GraphQLResponse[CalibanError] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(interpreter.execute(query)).getOrThrowFiberFailure()
    }

  private def field(value: ResponseValue, name: String): ResponseValue =
    value match {
      case ResponseValue.ObjectValue(fields) =>
        fields
          .collectFirst { case (`name`, fieldValue) => fieldValue }
          .getOrElse(fail(s"missing field '$name' in $value"))
      case other =>
        fail(s"expected an object but got: $other")
    }

  private def stringField(value: ResponseValue, name: String): String =
    field(value, name) match {
      case StringValue(stringValue) => stringValue
      case other                    => fail(s"expected a string at '$name' but got: $other")
    }

  "the write api schema" - {
    "exposes the eight mutations and healthCheck" in {
      val sdl = newApi().render

      Seq(
        "createGroupChat",
        "deleteGroupChat",
        "renameGroupChat",
        "addMember",
        "removeMember",
        "postMessage",
        "editMessage",
        "deleteMessage"
      ).foreach { operation =>
        sdl should include(operation)
      }
      sdl should include("healthCheck: String!")
      sdl should include("input: CreateGroupChatInput!")
      sdl should include("input: PostMessageInput!")
      sdl should include("enum Role")
      sdl should include("MEMBER")
      sdl should include("ADMIN")
    }
  }

  "the write api" - {
    "answers healthCheck" in {
      val response = execute(newInterpreter(), "{ healthCheck }")

      response.errors shouldBe empty
      stringField(response.data, "healthCheck") shouldBe "OK"
    }

    "creates a group chat and runs the member and message mutations" in {
      val interpreter = newInterpreter()

      val created = execute(
        interpreter,
        s"""mutation {
           |  createGroupChat(input: { name: "team", executorId: "${owner.asString}" }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      created.errors shouldBe empty
      val groupChatId = stringField(field(created.data, "createGroupChat"), "groupChatId")

      val added = execute(
        interpreter,
        s"""mutation {
           |  addMember(input: {
           |    groupChatId: "$groupChatId"
           |    userAccountId: "${member.asString}"
           |    role: MEMBER
           |    executorId: "${owner.asString}"
           |  }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      added.errors shouldBe empty
      stringField(field(added.data, "addMember"), "groupChatId") shouldBe groupChatId

      val posted = execute(
        interpreter,
        s"""mutation {
           |  postMessage(input: {
           |    groupChatId: "$groupChatId"
           |    content: "hello"
           |    executorId: "${member.asString}"
           |  }) {
           |    groupChatId
           |    messageId
           |  }
           |}""".stripMargin
      )
      posted.errors shouldBe empty
      val messageId = stringField(field(posted.data, "postMessage"), "messageId")

      val edited = execute(
        interpreter,
        s"""mutation {
           |  editMessage(input: {
           |    groupChatId: "$groupChatId"
           |    messageId: "$messageId"
           |    content: "edited"
           |    executorId: "${member.asString}"
           |  }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      edited.errors shouldBe empty

      val deletedMessage = execute(
        interpreter,
        s"""mutation {
           |  deleteMessage(input: {
           |    groupChatId: "$groupChatId"
           |    messageId: "$messageId"
           |    executorId: "${member.asString}"
           |  }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      deletedMessage.errors shouldBe empty

      val removed = execute(
        interpreter,
        s"""mutation {
           |  removeMember(input: {
           |    groupChatId: "$groupChatId"
           |    userAccountId: "${member.asString}"
           |    executorId: "${owner.asString}"
           |  }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      removed.errors shouldBe empty

      val renamed = execute(
        interpreter,
        s"""mutation {
           |  renameGroupChat(input: {
           |    groupChatId: "$groupChatId"
           |    name: "renamed"
           |    executorId: "${owner.asString}"
           |  }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      renamed.errors shouldBe empty

      val deleted = execute(
        interpreter,
        s"""mutation {
           |  deleteGroupChat(input: {
           |    groupChatId: "$groupChatId"
           |    executorId: "${owner.asString}"
           |  }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      deleted.errors shouldBe empty
    }

    "returns a GraphQL error for a domain violation" in {
      val interpreter = newInterpreter()

      val created = execute(
        interpreter,
        s"""mutation {
           |  createGroupChat(input: { name: "team", executorId: "${owner.asString}" }) {
           |    groupChatId
           |  }
           |}""".stripMargin
      )
      val groupChatId = stringField(field(created.data, "createGroupChat"), "groupChatId")

      val response = execute(
        interpreter,
        s"""mutation {
           |  postMessage(input: {
           |    groupChatId: "$groupChatId"
           |    content: "hello"
           |    executorId: "${outsider.asString}"
           |  }) {
           |    groupChatId
           |    messageId
           |  }
           |}""".stripMargin
      )

      response.errors.map(_.getMessage).mkString(" ") should include("not a member")
    }
  }
}
