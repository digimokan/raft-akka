package raft

import com.typesafe.config.ConfigFactory
import akka.serialization.Serialization.serializedActorPath
import akka.actor.{Actor, ActorRef, Props, Stash, PoisonPill}
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

  def getName (inref:ActorRef) : String =
    serverIDs.filter(_.ref == inref)(0).name

  def shutdown () : Unit = {
    raftGroup ! Broadcast(PoisonPill)

    println("\n****************************************************************************")
    println("SHUTTING DOWN ALL SERVERS, TERMINATING RAFT TEST")
    println("****************************************************************************\n")

    context.stop(self)
  }

  def startAll () : Unit = {
    println("\n****************************************************************************")
    println("STARTING ALL SERVERS: OBSERVE AN ELECTION AND LEADER HEARTBEATS")
    println("****************************************************************************\n")

    raftGroup ! Broadcast(Start)
  }

  def receive = {

    case StartAll => startAll()

    case Shutdown => shutdown()

    case InitMsg =>
      printf(f"${getName(sender)}: initialized with peers\n")

    case StartupMsg(term, elecTimer) =>
      printf(f"${getName(sender)} [T${term}]: started from crashed state as follower, ET ${elecTimer}\n")

    case VoteReplyMsg (voterTerm, voterDecision, candRef, candTerm) =>
      printf(f"${getName(sender)} [T${voterTerm}]: handled vote req from ${getName(candRef)}/T${candTerm}, replied ${voterDecision}\n")

    case AppendEntriesReplyMsg (term, success, leaderRef, leaderTerm) =>
      printf(f"${getName(sender)} [T${term}]: received appendReq from ${getName(leaderRef)}/T${leaderTerm}\n")

    case CandidateMsg(candTerm) =>
      printf(f"${getName(sender)} [T${candTerm}]: ET expired, becoming candidate\n")

    case VoteReceiptMsg(candTerm, wonElection, becameFollower, yesVotes, voterRef, voterTerm, voterDecision) =>
      if (becameFollower) {
        printf(f"${getName(sender)} [T${candTerm}]: received voteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), aborted election and became follower\n")
      } else if (wonElection) {
        printf(f"${getName(sender)} [T${candTerm}]: received voteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), achieved majority ${yesVotes} & becoming leader\n")
      } else { // received vote, haven't won yet so continuing election
        printf(f"${getName(sender)} [T${candTerm}]: received voteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), have ${yesVotes} yes votes\n")
      }

  }

}

