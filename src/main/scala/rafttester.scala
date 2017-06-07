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

  // keep track of servers that are followers/candidates/leaders
  var followers = Set[ServerID]()
  var candidates = Set[ServerID]()
  var leaders = Set[ServerID]()

  // leader that tester intentionally crashes/disconnects
  var crashedLeader:Option[ServerID] = None
  var disconnectedLeader:Option[ServerID] = None

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

  def startAllSimulElec () : Unit = {
    println("\n****************************************************************************")
    println("STARTING ALL SERVERS: OBSERVE SIMULATED SIMULTANEOUS ELECTIONS")
    println("****************************************************************************\n")

    raftGroup ! Broadcast(Start)
    serverIDs.head.ref ! ElectionTimeout
    serverIDs.last.ref ! ElectionTimeout
  }


  def crashAll () : Unit = {
    println("\n****************************************************************************")
    println("CRASHING ALL SERVERS: OBSERVE NO COMMUNICATION")
    println("****************************************************************************\n")

    raftGroup ! Broadcast(Crash)
  }


  def crashLeader () : Unit = {

    println("\n****************************************************************************")
    println("CRASHING THE LEADER: OBSERVE A NEW ELECTION AND NEW LEADER HEARTBEATS")
    println("****************************************************************************\n")

    val leader = getLeader()
    leader match {
      case Some(ldr) =>
        ldr.ref ! Crash
        crashedLeader = Some(ldr)
      case None =>
        printf(f"\n>>>>>>>>>>>>>>>>>>>>>>>>>>> TESTER: NO LEADER TO CRASH <<<<<<<<<<<<<<<<<<<<<<<<<<<\n\n")
    }

  }

  def restartLeader () : Unit = {

    println("\n*****************************************************************************")
    println("RESTARTING CRASHED LEADER AS FOLLOWER: OBSERVE IT UPDATE ITS TERM AS FOLLOWER")
    println("*****************************************************************************\n")

    crashedLeader match {
      case Some(ldr) =>
        ldr.ref ! Start
      case None =>
        printf(f"\n>>>>>>>>>>>>>>>>>>>>>>>>>>> TESTER: NO LEADER TO RESTART <<<<<<<<<<<<<<<<<<<<<<<<<<<\n\n")
    }

  }

  def disconnectLeader () : Unit = {

    println("\n****************************************************************************")
    println("DISCONNECTING THE LEADER: OBSERVE A NEW ELECTION AND NEW LEADER HEARTBEATS")
    println("****************************************************************************\n")

    // CAUTION: tester still has two leaders in its leader list after disconnect
    val leader = getLeader()
    leader match {
      case Some(ldr) =>
        ldr.ref ! Disconnect
        disconnectedLeader = Some(ldr)
      case None =>
        printf(f"\n>>>>>>>>>>>>>>>>>>>>>>>>>>> TESTER: NO LEADER TO DISCONNECT <<<<<<<<<<<<<<<<<<<<<<<<<<<\n\n")
    }

  }

  def reconnectLeader () : Unit = {

    println("\n*****************************************************************************")
    println("RECONNECTING OLD LEADER: OBSERVE IT --REVERT-- TO FOLLOWER")
    println("*****************************************************************************\n")

    disconnectedLeader match {
      case Some(ldr) =>
        ldr.ref ! Reconnect
      case None =>
        printf(f"\n>>>>>>>>>>>>>>>>>>>>>>>>>>> TESTER: NO LEADER TO RECONNECT <<<<<<<<<<<<<<<<<<<<<<<<<<<\n\n")
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

    case StartAllSimulElec =>
      startAllSimulElec()

    case CrashAll =>
      crashAll()

    case CrashLeader =>
      crashLeader()

    case RestartLeader =>
      restartLeader()

    case DisconnectLeader =>
      disconnectLeader()

    case ReconnectLeader =>
      reconnectLeader()

    case Shutdown =>
      shutdown()

    case InitMsg =>
      printf(f"${getName(sender)}: initialized with peers\n")

    case StartupMsg(term, elecTimer) =>
      printf(f"${getName(sender)} [T${term}]: started from crashed state as follower, ET ${elecTimer}\n")

    case CrashMsg(ref, term) =>
      followers -= ServerID(getName(ref), ref)
      candidates -= ServerID(getName(ref), ref)
      leaders -= ServerID(getName(ref), ref)

    case FollowerMsg(ref, term) =>
      followers += ServerID(getName(ref), ref)
      candidates -= ServerID(getName(ref), ref)
      leaders -= ServerID(getName(ref), ref)

    case CandidateMsg(ref, term) =>
      followers -= ServerID(getName(ref), ref)
      candidates += ServerID(getName(ref), ref)
      leaders -= ServerID(getName(ref), ref)

    case LeaderMsg(ref, term) =>
      followers -= ServerID(getName(ref), ref)
      candidates -= ServerID(getName(ref), ref)
      leaders += ServerID(getName(ref), ref)

    case VoteReqMsg (candRef, candTerm, voterRef) =>
      printf(f"${getName(candRef)} [T${candTerm}]: ET expired, sent VoteReq to ${getName(voterRef)}\n")

    case VoteReplyMsg (voterRef, voterTerm, voterDecision, candRef, candTerm) =>
      printf(f"${getName(voterRef)} [T${voterTerm}]: received VoteReq from ${getName(candRef)}/T${candTerm}, replied ${voterDecision}\n")

    case VoteReceiptMsg(candRef, candTerm, wonElection, becameFollower, yesVotes, voterRef, voterTerm, voterDecision) =>
      if (becameFollower) {
        printf(f"${getName(candRef)} [T${candTerm}]: received VoteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), aborted election and became follower\n")
      } else if (wonElection) {
        printf(f"${getName(candRef)} [T${candTerm}]: received VoteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), achieved majority ${yesVotes} & becoming leader\n")
      } else { // received vote, haven't won yet so continuing election
        printf(f"${getName(candRef)} [T${candTerm}]: received VoteReply from ${getName(voterRef)}/T${voterTerm} (${voterDecision}), have ${yesVotes} yes votes\n")
      }

    case AppendReqMsg (leaderRef:ActorRef, leaderTerm:Int, appenderRef:ActorRef) =>
      printf(f"${getName(leaderRef)} [T${leaderTerm}]: HT expired, sent empty AppendReq to ${getName(appenderRef)}\n")

    case AppendReplyMsg (appenderRef, appenderTerm, appenderSuccess, leaderRef, leaderTerm) =>
      printf(f"${getName(appenderRef)} [T${appenderTerm}]: received AppendReq from ${getName(leaderRef)}/T${leaderTerm}\n")

    case AppendReceiptMsg (leaderRef, leaderTerm, becameFollower, appenderRef, appenderTerm) =>
      printf(f"${getName(leaderRef)} [T${leaderTerm}]: received AppendReply from ${getName(appenderRef)}/T${appenderTerm}\n")

  }

}

