package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** Raised when the reader fell behind the stream retention window. The caller runs a full resync (DY-15). */
final class StreamTrimmedException(val shardId: String, cause: Throwable)
    extends RuntimeException(s"the stream records of shard $shardId were trimmed", cause)
