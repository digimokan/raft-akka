lazy val root = (project in file(".")).settings(
  name := "raft-scala",
  version := "0.1",
  scalaVersion := "2.12.2",
  scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-feature"),
  resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.1"
)

