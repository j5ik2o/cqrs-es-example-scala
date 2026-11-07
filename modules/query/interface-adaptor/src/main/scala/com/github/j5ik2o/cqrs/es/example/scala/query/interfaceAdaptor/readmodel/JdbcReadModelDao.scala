package com.github.j5ik2o.cqrs.es.example.scala.query.interfaceAdaptor.readmodel

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Member
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.Message
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageBody
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.MessageId
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import scala.util.Try
import scala.util.Using

final case class ReadModelDatabaseConfig(url: String, user: String, password: String)

object JdbcReadModelDao {

  private lazy val driverLoaded: Boolean =
    Try(Class.forName("com.mysql.cj.jdbc.Driver")).isSuccess
}

/** MySQL implementation of the read model.
  *
  * Reads are scoped by `user_account_id`: a caller only sees active group chats that it belongs to, and members and
  * messages are visible only while the caller is an active member of their group chat.
  */
final class JdbcReadModelDao(config: ReadModelDatabaseConfig) extends ReadModelDao {

  import JdbcReadModelDao.driverLoaded

  override def findGroupChat(groupChatId: String, userAccountId: String): Try[Option[GroupChatRecord]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT gc.id, gc.name, gc.owner_id, gc.created_at, gc.updated_at
          |FROM group_chats gc
          |JOIN members m ON m.group_chat_id = gc.id
          |WHERE gc.id = ? AND gc.disabled = FALSE AND m.disabled = FALSE AND m.user_account_id = ?
          |LIMIT 1""".stripMargin,
        Seq(groupChatId, userAccountId)
      )(mapGroupChat).headOption
    }

  override def findGroupChats(userAccountId: String): Try[Vector[GroupChatRecord]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT DISTINCT gc.id, gc.name, gc.owner_id, gc.created_at, gc.updated_at
          |FROM group_chats gc
          |JOIN members m ON m.group_chat_id = gc.id
          |WHERE gc.disabled = FALSE AND m.disabled = FALSE AND m.user_account_id = ?
          |ORDER BY gc.created_at, gc.id""".stripMargin,
        Seq(userAccountId)
      )(mapGroupChat)
    }

  override def findMember(groupChatId: String, userAccountId: String): Try[Option[MemberRecord]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT m.id, m.group_chat_id, m.user_account_id, m.role, m.created_at, m.updated_at
          |FROM members m
          |JOIN group_chats gc ON gc.id = m.group_chat_id
          |WHERE gc.id = ? AND gc.disabled = FALSE AND m.disabled = FALSE AND m.user_account_id = ?
          |LIMIT 1""".stripMargin,
        Seq(groupChatId, userAccountId)
      )(mapMember).headOption
    }

  override def findMembers(groupChatId: String, userAccountId: String): Try[Vector[MemberRecord]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT m.id, m.group_chat_id, m.user_account_id, m.role, m.created_at, m.updated_at
          |FROM members m
          |JOIN group_chats gc ON gc.id = m.group_chat_id
          |WHERE gc.id = ? AND gc.disabled = FALSE AND m.disabled = FALSE
          |AND EXISTS (
          |  SELECT 1 FROM members requester
          |  WHERE requester.group_chat_id = m.group_chat_id
          |    AND requester.disabled = FALSE
          |    AND requester.user_account_id = ?
          |)
          |ORDER BY m.created_at, m.id""".stripMargin,
        Seq(groupChatId, userAccountId)
      )(mapMember)
    }

  override def findMessage(messageId: String, userAccountId: String): Try[Option[MessageRecord]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT msg.id, msg.group_chat_id, msg.user_account_id, msg.text, msg.created_at, msg.updated_at
          |FROM messages msg
          |JOIN group_chats gc ON gc.id = msg.group_chat_id
          |WHERE msg.id = ? AND msg.disabled = FALSE AND gc.disabled = FALSE
          |AND EXISTS (
          |  SELECT 1 FROM members m
          |  WHERE m.group_chat_id = msg.group_chat_id
          |    AND m.disabled = FALSE
          |    AND m.user_account_id = ?
          |)
          |LIMIT 1""".stripMargin,
        Seq(messageId, userAccountId)
      )(mapMessage).headOption
    }

  override def findMessages(groupChatId: String, userAccountId: String): Try[Vector[MessageRecord]] =
    withConnection { connection =>
      query(
        connection,
        """SELECT msg.id, msg.group_chat_id, msg.user_account_id, msg.text, msg.created_at, msg.updated_at
          |FROM messages msg
          |JOIN group_chats gc ON gc.id = msg.group_chat_id
          |WHERE msg.group_chat_id = ? AND msg.disabled = FALSE AND gc.disabled = FALSE
          |AND EXISTS (
          |  SELECT 1 FROM members m
          |  WHERE m.group_chat_id = msg.group_chat_id
          |    AND m.disabled = FALSE
          |    AND m.user_account_id = ?
          |)
          |ORDER BY msg.created_at, msg.id""".stripMargin,
        Seq(groupChatId, userAccountId)
      )(mapMessage)
    }

  override def lastProcessedSeqNr(aggregateId: String): Try[Long] =
    withConnection { connection =>
      selectPositionForUpdate(connection, aggregateId)
    }

  override def insertGroupChat(
      aggregateId: String,
      name: String,
      administrator: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """INSERT INTO group_chats
          |  (id, disabled, name, owner_id, last_processed_sequence_number, created_at, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?)
          |ON DUPLICATE KEY UPDATE
          |  disabled = VALUES(disabled),
          |  name = VALUES(name),
          |  owner_id = VALUES(owner_id),
          |  last_processed_sequence_number = VALUES(last_processed_sequence_number),
          |  updated_at = VALUES(updated_at)""".stripMargin,
        Seq(
          aggregateId,
          false,
          name,
          administrator.userAccountId.asString,
          seqNr,
          timestamp(occurredAt),
          timestamp(occurredAt)
        )
      )
      upsertMember(connection, aggregateId, administrator, occurredAt, seqNr)
    }

  override def deleteGroupChat(aggregateId: String, occurredAt: Instant, seqNr: Long): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """UPDATE group_chats
          |SET disabled = TRUE, last_processed_sequence_number = ?, updated_at = ?
          |WHERE id = ?""".stripMargin,
        Seq(seqNr, timestamp(occurredAt), aggregateId)
      )
    }

  override def updateGroupChatName(
      aggregateId: String,
      name: String,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """UPDATE group_chats
          |SET name = ?, last_processed_sequence_number = ?, updated_at = ?
          |WHERE id = ?""".stripMargin,
        Seq(name, seqNr, timestamp(occurredAt), aggregateId)
      )
    }

  override def insertMember(
      aggregateId: String,
      member: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      upsertMember(connection, aggregateId, member, occurredAt, seqNr)
    }

  override def deleteMember(
      aggregateId: String,
      userAccountId: UserAccountId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """UPDATE members
          |SET disabled = TRUE, last_processed_sequence_number = ?, updated_at = ?
          |WHERE group_chat_id = ? AND user_account_id = ?""".stripMargin,
        Seq(seqNr, timestamp(occurredAt), aggregateId, userAccountId.asString)
      )
    }

  override def insertMessage(
      aggregateId: String,
      message: Message,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """INSERT INTO messages
          |  (id, disabled, group_chat_id, user_account_id, text,
          |   last_processed_sequence_number, created_at, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          |ON DUPLICATE KEY UPDATE
          |  disabled = VALUES(disabled),
          |  group_chat_id = VALUES(group_chat_id),
          |  user_account_id = VALUES(user_account_id),
          |  text = VALUES(text),
          |  last_processed_sequence_number = VALUES(last_processed_sequence_number),
          |  updated_at = VALUES(updated_at)""".stripMargin,
        Seq(
          message.id.asString,
          false,
          aggregateId,
          message.senderId.asString,
          message.body.value,
          seqNr,
          timestamp(occurredAt),
          timestamp(occurredAt)
        )
      )
    }

  override def updateMessage(
      aggregateId: String,
      messageId: MessageId,
      body: MessageBody,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """UPDATE messages
          |SET text = ?, last_processed_sequence_number = ?, updated_at = ?
          |WHERE id = ?""".stripMargin,
        Seq(body.value, seqNr, timestamp(occurredAt), messageId.asString)
      )
    }

  override def deleteMessage(
      aggregateId: String,
      messageId: MessageId,
      occurredAt: Instant,
      seqNr: Long
  ): Try[ProjectionResult] =
    withPosition(aggregateId, seqNr) { connection =>
      execute(
        connection,
        """UPDATE messages
          |SET disabled = TRUE, last_processed_sequence_number = ?, updated_at = ?
          |WHERE id = ?""".stripMargin,
        Seq(seqNr, timestamp(occurredAt), messageId.asString)
      )
    }

  private def upsertMember(
      connection: Connection,
      aggregateId: String,
      member: Member,
      occurredAt: Instant,
      seqNr: Long
  ): Unit =
    execute(
      connection,
      """INSERT INTO members
        |  (id, group_chat_id, user_account_id, role, disabled,
        |   last_processed_sequence_number, created_at, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        |ON DUPLICATE KEY UPDATE
        |  id = VALUES(id),
        |  role = VALUES(role),
        |  disabled = VALUES(disabled),
        |  last_processed_sequence_number = VALUES(last_processed_sequence_number),
        |  updated_at = VALUES(updated_at)""".stripMargin,
      Seq(
        member.id.asString,
        aggregateId,
        member.userAccountId.asString,
        member.role.asString,
        false,
        seqNr,
        timestamp(occurredAt),
        timestamp(occurredAt)
      )
    )

  private def withPosition(aggregateId: String, seqNr: Long)(apply: Connection => Unit): Try[ProjectionResult] =
    withConnection { connection =>
      val previousAutoCommit = connection.getAutoCommit
      connection.setAutoCommit(false)
      try {
        // Create the position row in one statement before locking it. Two readers that both see no row would
        // otherwise race on the insert and, with some isolation levels, deadlock on the lock upgrade.
        ensurePositionRow(connection, aggregateId)
        val last = selectPositionForUpdate(connection, aggregateId)
        if (seqNr <= last) {
          connection.rollback()
          ProjectionResult.Duplicate
        } else if (seqNr != last + 1L) {
          throw new ReadModelGapException(aggregateId, last + 1L, seqNr)
        } else {
          apply(connection)
          upsertPosition(connection, aggregateId, seqNr)
          connection.commit()
          ProjectionResult.Applied
        }
      } catch {
        case error: Throwable =>
          connection.rollback()
          throw error
      } finally {
        connection.setAutoCommit(previousAutoCommit)
      }
    }

  private def ensurePositionRow(connection: Connection, aggregateId: String): Unit =
    execute(
      connection,
      """INSERT INTO read_model_positions (aggregate_id, last_processed_sequence_number)
        |VALUES (?, 0)
        |ON DUPLICATE KEY UPDATE aggregate_id = aggregate_id""".stripMargin,
      Seq(aggregateId)
    )

  private def selectPositionForUpdate(connection: Connection, aggregateId: String): Long = Using.resource(
    connection.prepareStatement(
      "SELECT last_processed_sequence_number FROM read_model_positions WHERE aggregate_id = ? FOR UPDATE"
    )
  ) { statement =>
    statement.setString(1, aggregateId)
    Using.resource(statement.executeQuery()) { resultSet =>
      if (resultSet.next()) resultSet.getLong(1) else 0L
    }
  }

  private def upsertPosition(connection: Connection, aggregateId: String, seqNr: Long): Unit =
    execute(
      connection,
      """INSERT INTO read_model_positions (aggregate_id, last_processed_sequence_number)
        |VALUES (?, ?)
        |ON DUPLICATE KEY UPDATE last_processed_sequence_number = VALUES(last_processed_sequence_number)""".stripMargin,
      Seq(aggregateId, seqNr)
    )

  private def mapGroupChat(resultSet: ResultSet): GroupChatRecord =
    GroupChatRecord(
      id = resultSet.getString("id"),
      name = resultSet.getString("name"),
      ownerId = resultSet.getString("owner_id"),
      createdAt = resultSet.getTimestamp("created_at").toInstant,
      updatedAt = resultSet.getTimestamp("updated_at").toInstant
    )

  private def mapMember(resultSet: ResultSet): MemberRecord =
    MemberRecord(
      id = resultSet.getString("id"),
      groupChatId = resultSet.getString("group_chat_id"),
      userAccountId = resultSet.getString("user_account_id"),
      role = resultSet.getString("role"),
      createdAt = resultSet.getTimestamp("created_at").toInstant,
      updatedAt = resultSet.getTimestamp("updated_at").toInstant
    )

  private def mapMessage(resultSet: ResultSet): MessageRecord =
    MessageRecord(
      id = resultSet.getString("id"),
      groupChatId = resultSet.getString("group_chat_id"),
      userAccountId = resultSet.getString("user_account_id"),
      text = resultSet.getString("text"),
      createdAt = resultSet.getTimestamp("created_at").toInstant,
      updatedAt = resultSet.getTimestamp("updated_at").toInstant
    )

  private def query[A](
      connection: Connection,
      sql: String,
      parameters: Seq[Any]
  )(mapRow: ResultSet => A): Vector[A] =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      bind(statement, parameters)
      Using.resource(statement.executeQuery()) { resultSet =>
        val rows = Vector.newBuilder[A]
        while (resultSet.next()) {
          rows += mapRow(resultSet)
        }
        rows.result()
      }
    }

  private def execute(connection: Connection, sql: String, parameters: Seq[Any]): Int =
    Using.resource(connection.prepareStatement(sql)) { statement =>
      bind(statement, parameters)
      statement.executeUpdate()
    }

  private def bind(statement: PreparedStatement, parameters: Seq[Any]): Unit =
    parameters.zipWithIndex.foreach { case (parameter, index) =>
      val position = index + 1
      parameter match {
        case value: String    => statement.setString(position, value)
        case value: Long      => statement.setLong(position, value)
        case value: Int       => statement.setInt(position, value)
        case value: Boolean   => statement.setBoolean(position, value)
        case value: Timestamp => statement.setTimestamp(position, value)
        case other =>
          throw new IllegalArgumentException(s"unsupported JDBC parameter type: ${other.getClass.getName}")
      }
    }

  private def withConnection[A](apply: Connection => A): Try[A] =
    Try {
      val _          = driverLoaded
      val connection = DriverManager.getConnection(config.url, config.user, config.password)
      try apply(connection)
      finally connection.close()
    }

  private def timestamp(instant: Instant): Timestamp = Timestamp.from(instant)
}
