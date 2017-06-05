package raft

import com.typesafe.config.ConfigFactory
import akka.serialization.Serialization.serializedActorPath
import akka.actor.{Actor, Props, PoisonPill}
import akka.routing.{RandomGroup, AddRoutee, RemoveRoutee, ActorRefRoutee, Broadcast}

class RaftTester () extends Actor {

  // load constants from config file
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")

  println("\n****************************************************************************")
  println("CREATING SERVERS / SERVER GROUP")
  println("****************************************************************************\n")

  // create 5 raft servers, each with an "ID" (an actor ref and assigned name)
  val serverA = ServerID( "serverA", context.actorOf(Props(classOf[RaftServer], "serverA"), name = "serverA") )
  val serverB = ServerID( "serverB", context.actorOf(Props(classOf[RaftServer], "serverB"), name = "serverB") )
  val serverC = ServerID( "serverC", context.actorOf(Props(classOf[RaftServer], "serverC"), name = "serverC") )
  val serverD = ServerID( "serverD", context.actorOf(Props(classOf[RaftServer], "serverD"), name = "serverD") )
  val serverE = ServerID( "serverE", context.actorOf(Props(classOf[RaftServer], "serverE"), name = "serverE") )

  // add 5 servers to one list for easier management
  var serverIDs = List(serverA, serverB, serverC, serverD, serverE)

  // create group router: allows us to broadcast to all, or send to one randomly
  var raftGroup = context.actorOf(
    Props.empty.withRouter(RandomGroup(serverIDs.map(id => serializedActorPath(id.ref)))),
    name = "raftGroup"
  )

  // send the list of servers to each server (introduces servers to each other)
  serverIDs.map(_.ref).foreach( ref => ref ! InitWithPeers(serverIDs) )

  // pause so that printf output is distinct
  Thread.sleep(200)

  println("\n****************************************************************************")
  println("RUNNING ELECTIONS")
  println("****************************************************************************\n")

  // let all elections timeout
  Thread.sleep(electionTimeoutBase * 3)

  def shutdown () : Unit = {
    raftGroup ! Broadcast(PoisonPill)
    context.stop(self)
  }

  def receive = {
    case Shutdown => shutdown()
  }

}

