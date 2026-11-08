package com.github.j5ik2o.cqrs.es.example.scala.fault

/** Runs the RMU fault-injection e2e against LocalStack and MySQL.
  *
  * The compose e2e script stops the long-running read model updater first, then runs this process with the app image.
  * The process exits non-zero on the first failing scenario, so CI does not need to parse the log.
  */
object FaultE2eMain {

  def main(args: Array[String]): Unit = {
    val context   = FaultContext.fromEnv()
    val scenarios = new FaultScenarios(context)
    var failure   = Option.empty[Throwable]

    try {
      context.awaitReady()
      run("startup scan with an update during the scan") {
        scenarios.startupScanWithConcurrentUpdate()
      }
      run("trimmed stream triggers a full resync") {
        scenarios.trimmedStreamTriggersFullResync()
      }
      run("expired iterator resumes the shard") {
        scenarios.expiredIteratorResumesShard()
      }
      run("retry exhaustion parks and recovers a task") {
        scenarios.retryExhaustionParksAndRecovers()
      }
      run("position guard rejects duplicates and gaps") {
        scenarios.positionGuardRejectsGaps()
      }
    } catch {
      case error: Throwable =>
        failure = Some(error)
    } finally {
      context.close()
    }

    failure match {
      case Some(error) =>
        error.printStackTrace()
        System.err.println(s"FAIL: fault-injection e2e: ${optionMessage(error)}")
        System.exit(1)
      case None =>
        println("PASS: RMU fault-injection e2e (5 scenarios)")
        System.exit(0)
    }
  }

  private def run(description: String)(scenario: => Unit): Unit = {
    println(s"RUN: $description")
    scenario
    println(s"PASS: $description")
  }

  private def optionMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)
}
