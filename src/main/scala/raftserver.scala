package raft

import com.typesafe.config.ConfigFactory
import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.mutable.Set
import akka.actor.{Actor, ActorRef, Cancellable, Stash}

class RaftServer (newName:String) extends Actor with Stash {

  /*****************************************************************************
  * SERVER DATA STATE (FIELDS)
  *****************************************************************************/

  // config vars: election and heartbeat timeouts
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")
  val electionTimeoutVariance = ConfigFactory.load.getInt("election-timeout-variance")
  val heartbeatTimeout = ConfigFactory.load.getInt("heartbeat-timeout")
  var rand = Random                     // generate random timeouts
  import context._                      // for scheduleOnce, become

  // persistent state: these vars will survive server crash & restart
  val ownName = newName                 // our assigned name
  var peers = Set[ServerID]()           // other servers we are aware of
  var ownTerm = 0                       // latest term we are aware of
  var votedFor:Option[ServerID] = None  // who we voted for in curr term

  // volatile state: these vars will be re-initialized on server crash & restart
  var electionTimer:Cancellable = null  // elec timer for followers/candidates
  var heartbeatTimer:Cancellable = null // hb timer for candidates/leaders
  var votesCollected = Set[Vote]()      // servers that voted for us

  /*****************************************************************************
  * TESTING: WRAPPERS THAT COMMUNICATE WITH TESTER
  *****************************************************************************/

  // report back all state changes and behavior to tester/parent
  val tester = parent

  // tell tester that we initialized with peers
  def sendInitMsg () : Unit = {
    tester ! InitMsg
  }

  // tell tester that we started up and became follower with term 0
  def sendStartupMsg (term:Int, elecTimer:Double) : Unit = {
    tester ! StartupMsg(term, elecTimer)
  }

  // send vote reply back to candidate
  // tell tester that we sent a vote reply back to a candidate
  def sendVoteReplyMsg (decision:Boolean, candRef:ActorRef, candTerm:Int) : Unit = {
    candRef ! VoteReply( Vote(ServerID(ownName, self), decision), ownTerm )
    tester ! VoteReplyMsg(ownTerm, decision, candRef, candTerm)
  }
  /*****************************************************************************
  * INITIAL LOGICS: ATOMIC UNITS COMBINED INTO UNINITIALIZED STATE
  *****************************************************************************/

  // initialize this server with list of known peers
  def initialize (peerList:List[ServerID]) : Unit = {
    // add list of peers to our set of peers
    addPeers(peerList)
    // place saved msgs received prior to this init back in the msg queue
    unstashAll()
    // become initialized (and process the saved msgs)
    changeToInitializedState()
    // send control msg to tester
    sendInitMsg()
  }

  // add a server to our list of known peers
  def addPeer (id:ServerID) : Unit = {
    if (id.name != ownName)
      peers += id
  }

  // add many servers to our list of known peers
  def addPeers (peerList:List[ServerID]) : Unit = {
    peerList.foreach( id => addPeer(id) )
  }

  /*****************************************************************************
  * INITIALIZED LOGICS: ATOMIC UNITS COMBINED INTO INITIALIZED STATE
  *****************************************************************************/

  // server has been initialized with set of peers: now wait to start
  def changeToInitializedState () : Unit = {
    // place saved msgs received prior to this init back in the msg queue
    unstashAll()
    // process the saved msgs
    become(initialized)
  }

  /*****************************************************************************
  * COMMON LOGICS: ATOMIC UNITS COMBINED INTO FOLLOWER/CANDIDATE/LEADER STATE
  *****************************************************************************/

  // start from down/crashed state as follower in term 0
  def start () : Unit = {
    val elecTimer = changeToFollowerState(0)
    // send control msg to tester
    sendStartupMsg(ownTerm, elecTimer)
  }

  // reset our election timer to base + optional variance
  def resetElectionTimer (useVariance:Boolean) : Double = {
    // set timer to its max possible value, or set to a base + some randomness
    val variance =
      if (useVariance)
        rand.nextInt(electionTimeoutVariance)
      else
        electionTimeoutBase
    val timerValue = electionTimeoutBase + variance
    // cancel the current running timer if it has been set, then start new one
    if (electionTimer != null)
      electionTimer.cancel()
    electionTimer = context.system.scheduler.scheduleOnce(timerValue milliseconds, self, ElectionTimeout)
    return (timerValue.toDouble / 1000)
  }

  // follower OR candidate received VoteReq from a candidate
  def processVoteReq (voteReq:VoteReq) : Unit = {

    // determine whether to vote for candidate
    def castVote (voteReq:VoteReq) : Boolean = {
      // if log checks ok and no vote yet cast, record our vote for candidate
      if ( (votedFor == None) || (votedFor == Some(voteReq.id)) )
        votedFor = Some(voteReq.id)
      // return true if we voted yes NOW for candidate, OR voted yes in the past
      return (votedFor == Some(voteReq.id))
    }

    // save the yes/no vote
    var dec =
      // req term < ownTerm: reply with a "no" vote
      if (voteReq.term < ownTerm) {
        false
      // req term == ownTerm: continue as candidate/follower and cast y/n vote
      } else if (voteReq.term == ownTerm) {
        castVote(voteReq)
      // req term > ownTerm: adv ownTerm, cand becomes follow as nec, cast vote
      } else {
        changeToFollowerState(voteReq.term)
        castVote(voteReq)
      }

    // send VoteReply to candidate, send control msg to tester
    sendVoteReplyMsg(dec, voteReq.id.ref, voteReq.term)

  }

  // follower/candidate/leader received AppendEntriesReq from leader
  def processAppendEntriesReq (leaderId:ServerID, leaderTerm:Int) : Unit = {
    var success:Boolean = false
    // leaderTerm < ownTerm: ignore req, reply false (req lead becomes follow)
    if (leaderTerm < ownTerm) {
      success = false
    // leaderTerm == ownTerm: follower simply replies to leader's req
    } else if (leaderTerm == ownTerm) {
      // reset election timer, with variance
      resetElectionTimer(true)
      success = true
    // leaderTerm > ownTerm: advance ownTerm, cand/lead becomes follower as nec
    } else {
      changeToFollowerState(leaderTerm)
      success = true
    }
    // send the true/false reply back to leader server
    leaderId.ref ! AppendEntriesReply( ServerID(ownName, self), success, ownTerm )
    // print control info for testing purposes
    printf(f"${ownName} [T${ownTerm}]: received appendReq from ${leaderId.name}/T${leaderTerm}\n")
  }

  /*****************************************************************************
  * FOLLOWER LOGICS: ATOMIC UNITS COMBINED INTO FOLLOWER STATE
  *****************************************************************************/

  def changeToFollowerState (newTerm:Int) : Double = {
    // set our current term
    ownTerm = newTerm
    // reset who we voted for this term
    votedFor = None
    // reset election timer, with variance
    val elecTimer = resetElectionTimer(true)
    // cancel the heartbeatTimer: follower does not need one
    if (heartbeatTimer != null)
      heartbeatTimer.cancel()
    // change message processing "receive" behavior
    become(follower)
    return elecTimer
  }

  /*****************************************************************************
  * CANDIDATE LOGICS: ATOMIC UNITS COMBINED INTO CANDIDATE STATE
  *****************************************************************************/

  def changeToCandidateState (newTerm:Int) : Unit = {
    // set our current term
    ownTerm = newTerm
    // start an election (vote for ourself, request votes from other servers)
    startElection()
    // reset our election timer to its FULL/MAX possible timer value
    resetElectionTimer(false)
    // change message processing "receive" behavior
    become(candidate)
    // print control info for testing purposes
    printf(f"${ownName} [T${ownTerm}]: ET expired, becoming candidate\n")
  }

  // start an election (attempt to become leader)
  def startElection () : Unit = {
    // record that we voted for ourself this term
    votedFor = Some(ServerID(ownName, self))
    // clear the set of servers that sent a yes/no vote to us
    votesCollected.clear()
    // have server collect vote for itself
    votesCollected += Vote(ServerID(ownName, self), true)
    // request a yes/no vote from each server
    peers.foreach( id => id.ref ! VoteReq(ServerID(ownName, self), ownTerm) )
  }

  // candidate received vote reply from a server
  def processVoteReply (vote:Vote, term:Int) : Unit = {
    // reply term > ownTerm: abort election and become follower
    if (term > ownTerm) {
      changeToFollowerState(term)
    // else tally the vote and determine possible election results
    } else {
      tallyVote(vote)
    }
    if (heartbeatTimer == null) {
      printf(f"${ownName} [T${ownTerm}]: received voteReply from ${vote.id.name}/T${term}, ${vote.decision}\n")
    } else {
      printf(f"${ownName} [T${ownTerm}]: received voteReply from ${vote.id.name}/T${term}, ${vote.decision}, achieved majority ${votesCollected.size} & becoming leader\n")
    }
  }

  // candidate tallies received vote and determines possible election results
  def tallyVote (vote:Vote) : Unit = {
    // add the received vote to set of collected votes
    votesCollected += vote
    // tally the total number of "yes" votes received
    val yesVotes = votesCollected.count(_.decision == true)
    // if total num yes votes is now majority, change to leader state
    if (yesVotes > (peers.size / 2))
      changeToLeaderState()
  }

  /*****************************************************************************
  * LEADER LOGICS: ATOMIC UNITS COMBINED INTO LEADER STATE
  *****************************************************************************/

  def changeToLeaderState () : Unit = {
    // cancel the electionTimer: leader does not need one
    if (electionTimer != null)
      electionTimer.cancel()
    // do heartbeat broadcast now, and repeat the heartbeats at intervals
    if (heartbeatTimer != null)
      heartbeatTimer.cancel()
    heartbeatTimer = context.system.scheduler.schedule(0 milliseconds, heartbeatTimeout milliseconds, self, HeartbeatTimeout)
    // change message processing "receive" behavior
    become(leader)
  }

  // leader received AppendEntriesReply from follower/candidate/leader
  def processAppendEntriesReply (id:ServerID, success:Boolean, term:Int) : Unit = {
    // if follower/candidate/leader term > ownTerm, become follower
    if (term > ownTerm) {
      changeToFollowerState(term)
    }
    // print control info for testing purposes
    printf(f"${ownName} [T${ownTerm}]: received appendReply from ${id.name}/T${term}\n")
  }

  // leader heartbeatTimeout received: send heartbeats to maintain leadership
  def broadcastHeartbeats () : Unit = {
    // send heatbeat msg (empty AppendEntriesReq) to each peer
    peers.foreach( id => id.ref ! AppendEntriesReq(ServerID(ownName, self), ownTerm) )
    // print control info for testing purposes
    printf(f"${ownName} [T${ownTerm}]: HT expired, broadcasting heartbeats\n")
  }

  /*****************************************************************************
  * SERVER STATES (i.e receive METHODS): SERVER WILL BE IN ONE OF THESE STATES
  *****************************************************************************/

  // INITIAL: uninitialized (i.e. waiting to receive peer list)
  def receive = {
    // init msg: add peers
    case InitWithPeers(peerList) => initialize(peerList)
    // some other msg: stash the msg and handle it afer server inititialized
    case _ => stash()
  }

  // INITIALIZED: have list of peers, waiting to start (as follower)
  def initialized : Receive = {
    // start from down/crashed state as follower in term 0
    case Start => start()
    // some other msg: stash the msg and handle it afer server started
    case _ => stash()
  }

  // FOLLOWER: respond to VoteReq/AppendEntries from candidates and leaders
  def follower : Receive = {
    // our election timer expired: become candidate and start election
    case ElectionTimeout => changeToCandidateState(ownTerm + 1)
    // received vote req from server that started (or is continuing) an election
    case VoteReq(id, term) => processVoteReq(VoteReq(id, term))
    // received AppendEntriesReq from a new leader
    case AppendEntriesReq(leaderId, leaderTerm) => processAppendEntriesReq(leaderId, leaderTerm)
  }

  // CANDIDATE: respond to VoteReply, and to VoteReq from other candidates
  def candidate : Receive = {
    // election timer expired without collecting majority: start a NEW election
    case ElectionTimeout => changeToCandidateState(ownTerm + 1)
    // received a reply to a vote request sent by us
    case VoteReply(vote, term) => processVoteReply(vote, term)
    // received vote req from another server holding SIMULTANEOUS election
    case VoteReq(id, term) => processVoteReq(VoteReq(id, term))
    // received AppendEntriesReq from a server already established as leader
    case AppendEntriesReq(leaderId, leaderTerm) => processAppendEntriesReq(leaderId, leaderTerm)
  }

  // LEADER: respond to AppendEntriesReq/Reply from follows, cands, leads
  def leader : Receive = {
    // our heartbeat timer expired: send heartbeats to maintain leadership
    case HeartbeatTimeout => broadcastHeartbeats()
    // received AppendEntriesReq from a leader server in later term
    case AppendEntriesReq(leaderId, leaderTerm) => processAppendEntriesReq(leaderId, leaderTerm)
    // received AppendEntriesReply from a follower/candidate/leader
    case AppendEntriesReply(id, success, term) => processAppendEntriesReply(id, success, term)
  }

}

