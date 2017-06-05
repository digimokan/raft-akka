package raft

import akka.actor.{ActorSystem, Props}

object RaftApp extends App {

  // create local actor system
  val system = ActorSystem("RaftApp")

  // create the raft tester, that will manage the raft servers and run tests
  val raftTester = system.actorOf(Props[RaftTester], name = "raftTester")

  // shutdown the raft servers, stop all actors
  raftTester ! Shutdown

  // stop guardian actors
  system.terminate()
}

