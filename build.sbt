ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.4.0"

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
    "-Xlint",
    "-Ykind-projector"
  ),
  resolvers ++= Resolver.sonatypeOssRepos("staging"),
  resolvers ++= Resolver.sonatypeOssRepos("releases"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.17" % Test
  ),
  semanticdbEnabled := true
)

lazy val `command-domain` = (project in file("modules/command/domain"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-domain",
    libraryDependencies ++= Seq(
      "org.wvlet.airframe" %% "airframe-ulid"             % "24.4.0",
      "com.github.j5ik2o"  %% "event-store-adapter-scala" % "1.0.142"
    )
  )

lazy val `command-interface-adaptor-if` = (project in file("modules/command/interface-adaptor-if"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-interface-adaptor-if"
  )
  .dependsOn(`command-domain`)

lazy val `command-interface-adaptor-impl` = (project in file("modules/command/interface-adaptor-impl"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-interface-adaptor-impl"
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-if`)

lazy val `command-use-case` = (project in file("modules/command/use-case"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-command-use-case"
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-if`)

lazy val `query-interface-adaptor` = (project in file("modules/query/interface-adaptor"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-query-interface-adaptor"
  )

lazy val `bootstrap` = (project in file("modules/bootstrap"))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala-bootstrap"
  )
  .dependsOn(`command-domain`, `command-interface-adaptor-impl`, `command-use-case`, `query-interface-adaptor`)

lazy val root = (project in file("."))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala"
  )
  .aggregate(
    `command-domain`,
    `command-interface-adaptor-impl`,
    `command-use-case`,
    `query-interface-adaptor`,
    `bootstrap`
  )

// --- Custom commands
addCommandAlias("lint", ";scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;scalafixAll --check")
addCommandAlias("fmt", ";scalafixAll;scalafmtAll;scalafmtSbt;scalafixAll")
