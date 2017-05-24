lazy val commonSettings = Seq(
  name := "raft-scala",
  version := "0.1",
  scalaVersion := "2.12.2",
  scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation"),
  libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.1",
  libraryDependencies += "com.typesafe.akka" %% "akka-remote" % "2.5.1"
)

lazy val root = (project in file ("."))
  .aggregate(server, client, core)

lazy val server = (project in file ("server"))
  .settings(commonSettings)
  .settings(name := "Server")
  .dependsOn(core)

lazy val client = (project in file ("client"))
  .settings(commonSettings)
  .settings(name := "Client")
  .dependsOn(core)

lazy val core = (project in file ("core"))
  .settings(commonSettings)
  .settings(name := "Core")

