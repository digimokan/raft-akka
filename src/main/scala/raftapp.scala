package raft

import com.typesafe.config.ConfigFactory
import akka.actor.{ActorSystem, Props, PoisonPill}
import akka.routing.{RandomGroup, AddRoutee, RemoveRoutee, ActorRefRoutee, Broadcast}

object RaftApp extends App {

  // load constants from config file
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")

  /****************************************************************************
   * INITIALIZATION
   ****************************************************************************/

  println("\n****************************************************************************")
  println("INITIALIZING")
  println("****************************************************************************\n")

  // create local actor system
  val system = ActorSystem("RaftApp")

  // create 5 raft servers, each with an "ID" (an actor ref and assigned name)
  val serverA = ServerID("serverA", system.actorOf(Props(classOf[RaftServer], "serverA")))
  val serverB = ServerID("serverB", system.actorOf(Props(classOf[RaftServer], "serverB")))
  val serverC = ServerID("serverC", system.actorOf(Props(classOf[RaftServer], "serverC")))
  val serverD = ServerID("serverD", system.actorOf(Props(classOf[RaftServer], "serverD")))
  val serverE = ServerID("serverE", system.actorOf(Props(classOf[RaftServer], "serverE")))

  // add 5 servers to one list for easier management
  var serverIDs = List(serverA, serverB, serverC, serverD, serverE)

  // create group router: allows us to broadcast to all, or send to one randomly
  var raftGroup = system.actorOf(Props.empty.withRouter(RandomGroup(List())), name = "raftGroup")
  serverIDs.foreach( id => raftGroup ! AddRoutee(ActorRefRoutee(id.ref)) )
  Thread.sleep(200)

  // broadcast each server id to group (introduces servers to each other)
  serverIDs.foreach( id => raftGroup ! Broadcast(id) )
  Thread.sleep(200)

  /****************************************************************************
  * RUN ELECTIONS
  ****************************************************************************/

  println("\n****************************************************************************")
  println("RUNNING ELECTIONS")
  println("****************************************************************************\n")

  // now that initialization is done, start each server (i.e. election timers)
  raftGroup ! Broadcast(Run)

  // let all elections timeout
  Thread.sleep(electionTimeoutBase * 3)

  /****************************************************************************
   * CLEANUP
   ****************************************************************************/

  // stop all raft servers then group router, then stop guardian actors
  raftGroup ! Broadcast(PoisonPill)
  system.terminate()
}

