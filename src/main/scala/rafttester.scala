package raft

import com.typesafe.config.ConfigFactory
import scala.collection.mutable.Set
import akka.actor.{Actor, ActorRef, Props, Stash, PoisonPill}
import akka.routing.{RandomGroup, AddRoutee, RemoveRoutee, ActorRefRoutee, Broadcast}
import akka.serialization.Serialization.serializedActorPath

class RaftTester () extends Actor {

  /*****************************************************************************
  * DATA FIELDS
  *****************************************************************************/

  // manage raft servers as name/ActorRef tuples
  case class ServerID (name:String, ref:ActorRef)

  // servers that are leaders (should only be one...but invariant may occur)
  var leaders = Set[ServerID]()

  // servers that are followers
  var followers = Set[ServerID]()

  // load constants from config file
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")

  /*****************************************************************************
  * INSTANTIATION CODE
  *****************************************************************************/

  println("\n****************************************************************************")
  println("CREATING SERVERS / SERVER GROUP")
  println("****************************************************************************\n")

  // create 5 raft servers, each with an "ID" (an actor ref and assigned name)
  val serverA = ServerID( "serverA", context.actorOf(Props[RaftServer], name = "serverA") )
  val serverB = ServerID( "serverB", context.actorOf(Props[RaftServer], name = "serverB") )
  val serverC = ServerID( "serverC", context.actorOf(Props[RaftServer], name = "serverC") )
  val serverD = ServerID( "serverD", context.actorOf(Props[RaftServer], name = "serverD") )
  val serverE = ServerID( "serverE", context.actorOf(Props[RaftServer], name = "serverE") )

  // add 5 servers to one list for easier management
  var serverIDs = List(serverA, serverB, serverC, serverD, serverE)

  // create group router: allows us to broadcast to all, or send to one randomly
  var raftGroup = context.actorOf(
    Props.empty.withRouter(RandomGroup(serverIDs.map(id => serializedActorPath(id.ref)))),
    name = "raftGroup"
  )

  // send the list of servers to each server (introduces servers to each other)
  serverIDs.foreach( id => id.ref ! InitWithPeers(serverIDs.map(_.ref)) )

  /*****************************************************************************
  * METHODS
  *****************************************************************************/

  def getName (nameRef:ActorRef) : String =
    serverIDs.filter(id => id.ref == nameRef)(0).name

  def getLeader () : Option[ServerID] =
    if (leaders.isEmpty) {
      None
    } else if (leaders.size > 1) {
      println("\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>><<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
      printf(f"INVARIANT! MORE THAN 1 LEADER: ${leaders.map(_.name).mkString(" ")} \n")
      println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>><<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n")
      None
    } else {
      Some(leaders.last)
    }

  def startAll () : Unit = {
    println("\n****************************************************************************")
    println("STARTING ALL SERVERS: OBSERVE AN ELECTION AND LEADER HEARTBEATS")
    println("****************************************************************************\n")

    raftGroup ! Broadcast(Start)
  }

  def crashLeader () : Unit = {

    println("\n****************************************************************************")
    println("CRASHING THE LEADER: OBSERVE A NEW ELECTION AND NEW LEADER HEARTBEATS")
    println("****************************************************************************\n")

    val leader = getLeader()
    leader match {
      case Some(ldr) =>
        ldr.ref ! Crash
      case None =>
        printf(f"\n>>>>>>>>>>>>>>>>>>>>>>>>>>> TESTER: NO LEADER TO CRASH <<<<<<<<<<<<<<<<<<<<<<<<<<<\n\n")
    }

  }

  def shutdown () : Unit = {
    raftGroup ! Broadcast(PoisonPill)

    println("\n****************************************************************************")
    println("SHUTTING DOWN ALL SERVERS, TERMINATING RAFT TEST")
    println("****************************************************************************\n")

    context.stop(self)
  }

  /*****************************************************************************
  * MSG PROCESSING BEHAVIOR
  *****************************************************************************/

  def receive = {

    case StartAll =>
      startAll()

    case CrashLeader =>
      crashLeader()

    case Shutdown =>
      shutdown()

    case InitMsg =>
      printf(f"${getName(sender)}: initialized with peers\n")

    case StartupMsg(term, elecTimer) =>
      printf(f"${getName(sender)} [T${term}]: started from crashed state as follower, ET ${elecTimer}\n")

    case FollowerMsg(followerRef, followerTerm) =>
      followers += ServerID(getName(followerRef), followerRef)

    case CandidateMsg(candRef, candTerm) =>
      printf(f"${getName(candRef)} [T${candTerm}]: ET expired, becoming candidate\n")

    case LeaderMsg(leaderRef, leaderTerm) =>
      leaders += ServerID(getName(leaderRef), leaderRef)

    case VoteReplyMsg (voterRef, voterTerm, voterDecision, candRef, candTerm) =>
      printf(f"${getName(voterRef)} [T${voterTerm}]: handled vote req from ${getName(candRef)}/T${candTerm}, replied ${voterDecision}\n")

    case VoteReceiptMsg(candRef, candTerm, wonElection, becameFollower, yesVotes, voterRef, voterTerm, voterDecision) =>
      if (becameFollower) {
        printf(f"${getName(candRef)} [T${candTerm}]: received voteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), aborted election and became follower\n")
      } else if (wonElection) {
        printf(f"${getName(candRef)} [T${candTerm}]: received voteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), achieved majority ${yesVotes} & becoming leader\n")
      } else { // received vote, haven't won yet so continuing election
        printf(f"${getName(candRef)} [T${candTerm}]: received voteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), have ${yesVotes} yes votes\n")
      }

    case AppendReqMsg (leaderRef:ActorRef, leaderTerm:Int, appenderRef:ActorRef) =>
      printf(f"${getName(leaderRef)} [T${leaderTerm}]: HT expired, sent empty appendReq to ${getName(appenderRef)}\n")

    case AppendReplyMsg (appenderRef, appenderTerm, appenderSuccess, leaderRef, leaderTerm) =>
      printf(f"${getName(appenderRef)} [T${appenderTerm}]: received appendReq from ${getName(leaderRef)}/T${leaderTerm}\n")

    case AppendReceiptMsg (leaderRef, leaderTerm, becameFollower, appenderRef, appenderTerm) =>
      printf(f"${getName(leaderRef)} [T${leaderTerm}]: received appendReply from ${getName(appenderRef)}/T${appenderTerm}\n")

  }

}

