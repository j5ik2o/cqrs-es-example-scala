ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.3"

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
    "-Xsource:3.3.3",
    "-Yexplicit-nulls",
    "-Ykind-projector"
  ),
  resolvers ++= Resolver.sonatypeOssRepos("staging"),
  resolvers ++= Resolver.sonatypeOssRepos("releases"),
  semanticdbEnabled := true
)

lazy val root = (project in file("."))
  .settings(baseSettings)
  .settings(
    name := "cqrs-es-example-scala"
  )
