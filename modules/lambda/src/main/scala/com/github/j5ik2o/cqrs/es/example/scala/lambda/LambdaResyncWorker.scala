package com.github.j5ik2o.cqrs.es.example.scala.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.github.j5ik2o.cqrs.es.example.scala.rmu.Resyncer

import scala.util.Failure
import scala.util.Success

/** Runs a full head resync on demand or on a schedule.
  *
  * A Lambda deployment does not run the local reader's startup scan. When the event source mapping is disabled longer
  * than the stream retention window, or a failed record can no longer be read from the stream, this worker rebuilds the
  * lag from the journal. The position guard makes a repeated run harmless.
  */
final class LambdaResyncWorker(resyncer: Resyncer) {

  /** Lambda instantiates the handler class with a no-argument constructor. */
  def this() = this(LambdaRmuDependencies.create().resyncer)

  def handleRequest(input: Object, context: Context): java.util.Map[String, Object] =
    resyncer.resync() match {
      case Success(result) =>
        val response = new java.util.LinkedHashMap[String, Object]()
        response.put("heads", Int.box(result.heads))
        response.put("skipped", Int.box(result.skipped))
        response.put("advanced", Int.box(result.advanced))
        response.put("failed", Int.box(result.failed))
        response
      case Failure(error) =>
        throw error
    }
}
