package com.github.j5ik2o.cqrs.es.example.scala.command.domain

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator

private[domain] object UlidSupport {

  def newUlid(): String = UlidCreator.getMonotonicUlid().toString

  def parse(value: String, prefix: String): Either[String, String] = {
    val trimmed        = value.trim
    val prefixWithDash = s"$prefix-"
    val raw =
      if (trimmed.regionMatches(true, 0, prefixWithDash, 0, prefixWithDash.length))
        trimmed.substring(prefixWithDash.length)
      else trimmed
    if (Ulid.isValid(raw)) Right(Ulid.from(raw).toString) else Left(s"invalid ULID: $value")
  }
}
