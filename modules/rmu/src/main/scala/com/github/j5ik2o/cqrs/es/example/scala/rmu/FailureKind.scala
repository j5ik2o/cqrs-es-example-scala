package com.github.j5ik2o.cqrs.es.example.scala.rmu

import java.util.Locale

/** Why an event could not be applied. The kind is stored with a recovery task so an operator can tell a data problem
  * from a storage outage.
  */
enum FailureKind {
  case EventPayload
  case UnsupportedEvent
  case JournalGap
  case ReadModelConstraint
  case RecoveryTaskStore
  case Unknown

  def asString: String = this match {
    case EventPayload        => "event_payload"
    case UnsupportedEvent    => "unsupported_event"
    case JournalGap          => "journal_gap"
    case ReadModelConstraint => "read_model_constraint"
    case RecoveryTaskStore   => "recovery_task_store"
    case Unknown             => "unknown"
  }
}

object FailureKind {

  def fromString(value: String): FailureKind =
    Option(value).map(_.trim.toLowerCase(Locale.ROOT)).getOrElse("") match {
      case "event_payload"         => FailureKind.EventPayload
      case "unsupported_event"     => FailureKind.UnsupportedEvent
      case "journal_gap"           => FailureKind.JournalGap
      case "read_model_constraint" => FailureKind.ReadModelConstraint
      case "recovery_task_store"   => FailureKind.RecoveryTaskStore
      case _                       => FailureKind.Unknown
    }
}
