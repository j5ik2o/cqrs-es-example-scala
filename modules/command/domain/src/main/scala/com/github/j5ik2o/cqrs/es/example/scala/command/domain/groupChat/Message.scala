package com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.userAccount.UserAccountId

import java.time.Instant

final case class Message(id: MessageId, body: MessageBody, senderId: UserAccountId, createdAt: Instant)
