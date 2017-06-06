package raft

import com.typesafe.config.ConfigFactory
import akka.actor.{ActorSystem, Props}

object RaftApp extends App {

  // load constants from config file
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")

  // create local actor system
  val system = ActorSystem("RaftApp")

  // create the raft tester (that will manage the raft servers and run tests)
  val raftTester = system.actorOf(Props[RaftTester], name = "raftTester")
  Thread.sleep(200)

  // start all the servers and observe an election and leader heartbeats
  raftTester ! StartAll
  Thread.sleep(electionTimeoutBase * 2)

  // crash the leader and watch the remaining servers elect a leader
  raftTester ! CrashLeader
  Thread.sleep(electionTimeoutBase * 2)

  // restart the leader as follower and watch it update its term as follower
  raftTester ! RestartLeader
  Thread.sleep(electionTimeoutBase * 2)

  // shutdown the raft servers, stop all actors
  raftTester ! Shutdown

  // stop guardian actors
  system.terminate()
}

