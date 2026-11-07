package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.graphql

import caliban.CalibanError
import caliban.GraphQLInterpreter
import caliban.GraphQLResponse
import caliban.ResponseValue
import caliban.Value.StringValue
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MemberRole
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId
import com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel.InMemoryReadModelDao
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import zio.Exit
import zio.Runtime
import zio.Unsafe

import java.time.Instant

class ReadApiSpec extends AnyFreeSpec with Matchers {

  private val createdAt = Instant.parse("2026-01-01T00:00:00Z")

  private def userAccountId(last: Char): UserAccountId =
    UserAccountId.fromString(s"01ARZ3NDEKTSV4RRFFQ69G5FA$last").toOption.get

  private val owner    = userAccountId('V')
  private val member   = userAccountId('W')
  private val outsider = userAccountId('X')

  private val groupChatId = GroupChatId.newId
  private val message     = Message(MessageId.newId, MessageBody("hello"), owner)

  private def seededDao(): InMemoryReadModelDao = {
    val dao  = new InMemoryReadModelDao
    val path = groupChatId.asString
    dao.insertGroupChat(path, "team", Member(MemberId.newId, owner, MemberRole.Admin), createdAt, 1L).toOption.get
    dao.insertMember(path, Member(MemberId.newId, member, MemberRole.Member), createdAt.plusSeconds(1), 2L).toOption.get
    dao.insertMessage(path, message, createdAt.plusSeconds(2), 3L).toOption.get
    dao
  }

  private def newApi(dao: InMemoryReadModelDao = seededDao()): caliban.GraphQL[Any] =
    ReadApi(dao)

  private def newInterpreter(dao: InMemoryReadModelDao = seededDao()): GraphQLInterpreter[Any, CalibanError] =
    newApi(dao).interpreter match {
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

  private def listField(value: ResponseValue, name: String): List[ResponseValue] =
    field(value, name) match {
      case ResponseValue.ListValue(values) => values
      case other                           => fail(s"expected a list at '$name' but got: $other")
    }

  "the read api schema" - {
    "exposes the six queries, the three types and the NaiveDateTime scalar" in {
      val sdl = newApi().render

      Seq("getGroupChat", "getGroupChats", "getMember", "getMembers", "getMessage", "getMessages").foreach {
        operation => sdl should include(operation)
      }
      sdl should include("scalar NaiveDateTime")
      sdl should include("type GroupChat")
      sdl should include("type Member")
      sdl should include("type Message")
      sdl should include("createdAt: NaiveDateTime!")
      sdl should include("getGroupChats(userAccountId: String!): [GroupChat!]!")
    }
  }

  "the read api" - {
    "returns the group chats of a member" in {
      val response = execute(
        newInterpreter(),
        s"""{ getGroupChats(userAccountId: "${owner.asString}") { id name ownerId createdAt } }"""
      )

      response.errors shouldBe empty
      val chats = listField(response.data, "getGroupChats")
      chats should have size 1
      stringField(chats.head, "id") shouldBe groupChatId.asString
      stringField(chats.head, "name") shouldBe "team"
      stringField(chats.head, "ownerId") shouldBe owner.asString
      stringField(chats.head, "createdAt") shouldBe "2026-01-01T00:00:00"
    }

    "hides the group chats of an outsider" in {
      val response = execute(
        newInterpreter(),
        s"""{ getGroupChats(userAccountId: "${outsider.asString}") { id } }"""
      )

      response.errors shouldBe empty
      listField(response.data, "getGroupChats") shouldBe empty
    }

    "returns a single group chat for a member" in {
      val response = execute(
        newInterpreter(),
        s"""{ getGroupChat(groupChatId: "${groupChatId.asString}", userAccountId: "${member.asString}") { id name } }"""
      )

      response.errors shouldBe empty
      field(response.data, "getGroupChat") match {
        case ResponseValue.ObjectValue(_) => succeed
        case other                        => fail(s"expected a group chat but got: $other")
      }
      stringField(field(response.data, "getGroupChat"), "name") shouldBe "team"
    }

    "returns not found for an outsider" in {
      val response = execute(
        newInterpreter(),
        s"""{ getGroupChat(groupChatId: "${groupChatId.asString}", userAccountId: "${outsider.asString}") { id } }"""
      )

      response.errors.map(_.getMessage) should contain("not found")
    }

    "returns the members to a member and hides them from an outsider" in {
      val visible = execute(
        newInterpreter(),
        s"""{ getMembers(groupChatId: "${groupChatId.asString}", userAccountId: "${owner.asString}") { userAccountId role } }"""
      )
      visible.errors shouldBe empty
      val members = listField(visible.data, "getMembers")
      members should have size 2
      members.map(memberValue => stringField(memberValue, "userAccountId")) should contain theSameElementsAs Seq(
        owner.asString,
        member.asString
      )

      val hidden = execute(
        newInterpreter(),
        s"""{ getMembers(groupChatId: "${groupChatId.asString}", userAccountId: "${outsider.asString}") { userAccountId } }"""
      )
      hidden.errors shouldBe empty
      listField(hidden.data, "getMembers") shouldBe empty
    }

    "returns messages to a member and hides a message from an outsider" in {
      val visible = execute(
        newInterpreter(),
        s"""{ getMessages(groupChatId: "${groupChatId.asString}", userAccountId: "${member.asString}") { id text userAccountId } }"""
      )
      visible.errors shouldBe empty
      val messages = listField(visible.data, "getMessages")
      messages should have size 1
      stringField(messages.head, "text") shouldBe "hello"
      stringField(messages.head, "userAccountId") shouldBe owner.asString

      val hidden = execute(
        newInterpreter(),
        s"""{ getMessage(messageId: "${message.id.asString}", userAccountId: "${outsider.asString}") { id } }"""
      )
      hidden.errors.map(_.getMessage) should contain("not found")
    }

    "returns a single message to a member" in {
      val response = execute(
        newInterpreter(),
        s"""{ getMessage(messageId: "${message.id.asString}", userAccountId: "${owner.asString}") { id text } }"""
      )

      response.errors shouldBe empty
      stringField(field(response.data, "getMessage"), "text") shouldBe "hello"
    }
  }
}
