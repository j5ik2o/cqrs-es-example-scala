package com.github.j5ik2o.cqrs.es.example.scala.command.domain

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator

private[domain] object UlidSupport {

  def newUlid(): String = UlidCreator.getMonotonicUlid().toString

  def parse(value: String, prefix: String): Either[String, String] = {
    val trimmed = value.trim
    val raw =
      if (trimmed.startsWith(s"$prefix-")) trimmed.substring(prefix.length + 1) else trimmed
    if (Ulid.isValid(raw)) Right(raw) else Left(s"invalid ULID: $value")
  }
}
