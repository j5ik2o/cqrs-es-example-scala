ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.6.4"

val esaVersion       = "5.1.0"
val circeVersion     = "0.14.17"
val calibanVersion   = "3.2.1"
val http4sVersion    = "0.23.38"
val zioVersion       = "2.1.27"
val awsSdkVersion    = "2.57.4"
val ulidVersion      = "5.2.4"
val scalaTestVersion = "3.2.20"
val logbackVersion   = "1.6.6"

lazy val baseSettings = Seq(
  organization := "com.github.j5ik2o",
  homepage := Some(url("https://github.com/j5ik2o/cqrs-es-example-scala")),
  licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      id = "j5ik2o",
      name = "Junichi Kato",
      email = "j5ik2o@gmail.com",
      url = url("https://blog.j5ik2o.me")
    )
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/j5ik2o/cqrs-es-example-scala"),
      "scm:git@github.com:j5ik2o/cqrs-es-example-scala.git"
    )
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-encoding",
    "UTF-8",
    "-Wunused:all"
  ),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  ),
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,
  Test / fork := true,
  Test / parallelExecution := false
)

lazy val `command-domain` = (project in file("modules/command/domain"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-domain",
    libraryDependencies ++= Seq(
      "com.github.f4b6a3" % "ulid-creator" % ulidVersion
    )
  )

lazy val `command-interface-adaptor-if` = (project in file("modules/command/interface-adaptor-if"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-interface-adaptor-if"
  )
  .dependsOn(`command-domain`)

lazy val `command-processor` = (project in file("modules/command/processor"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-processor"
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-if`)

lazy val `command-interface-adaptor-impl` = (project in file("modules/command/interface-adaptor-impl"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-interface-adaptor-impl",
    libraryDependencies ++= Seq(
      "io.github.j5ik2o"      %% "event-store-adapter-scala" % esaVersion,
      "software.amazon.awssdk" % "dynamodb"                  % awsSdkVersion,
      "io.circe"              %% "circe-core"                % circeVersion,
      "io.circe"              %% "circe-parser"              % circeVersion,
      "com.github.ghostdogpr" %% "caliban"                   % calibanVersion,
      "dev.zio"               %% "zio"                       % zioVersion
    )
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-if`, `command-processor`)

lazy val `query-interface-adaptor` = (project in file("modules/query/interface-adaptor"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-query-interface-adaptor"
  )

lazy val `rmu` = (project in file("modules/rmu"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-rmu",
    libraryDependencies ++= Seq(
      "io.github.j5ik2o" %% "event-store-adapter-scala" % esaVersion
    )
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-impl`)

lazy val `bootstrap` = (project in file("modules/bootstrap"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-bootstrap",
    Compile / run / mainClass := Some(
      "com.github.j5ik2o.cqrs.es.example.scala.bootstrap.WriteApiServer"
    ),
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "dynamodb"              % awsSdkVersion,
      "software.amazon.awssdk" % "url-connection-client" % awsSdkVersion,
      "com.github.ghostdogpr" %% "caliban-http4s"        % calibanVersion,
      "org.http4s"            %% "http4s-ember-server"   % http4sVersion,
      "org.http4s"            %% "http4s-dsl"            % http4sVersion,
      "dev.zio"               %% "zio-interop-cats"      % "23.1.0.14",
      "ch.qos.logback"         % "logback-classic"       % logbackVersion
    )
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-if`, `command-interface-adaptor-impl`, `command-processor`)

lazy val root = (project in file("."))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala"
  )
  .aggregate(
    `command-domain`,
    `command-interface-adaptor-if`,
    `command-processor`,
    `command-interface-adaptor-impl`,
    `query-interface-adaptor`,
    `rmu`,
    `bootstrap`
  )

// --- Custom commands
addCommandAlias("lint", ";scalafmtCheckAll;scalafmtSbtCheck;scalafixAll --check")
addCommandAlias("fmt", ";scalafixAll;scalafmtAll;scalafmtSbt;scalafixAll")
