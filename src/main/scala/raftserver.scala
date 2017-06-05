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
  * INITIAL LOGICS: ATOMIC UNITS COMBINED INTO INITIAL STATE
  *****************************************************************************/

  // initialize this server with list of known peers, then become follower
  def initialize (peerList:List[ServerID]) : Unit = {
    // add list of peers to our set of peers
    addPeers(peerList)
    // place saved msgs received prior to this init back in the msg queue
    unstashAll()
    // become follower (and process the saved msgs) for the first term (term 0)
    changeToFollowerState(0)
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
  * COMMON LOGICS: ATOMIC UNITS COMBINED INTO FOLLOWER/CANDIDATE/LEADER STATE
  *****************************************************************************/

  // reset our election timer to base + optional variance
  def resetElectionTimer (useVariance:Boolean) : Unit = {
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
    // print control info for testing purposes
    printf(f"${ownName}: set election timer ${timerValue.toDouble / 1000} sec\n")
  }

  // follower OR candidate determines whether to vote for a candidate
  def castVote (voteReq:VoteReq) : Boolean = {
    // if log checks ok and no vote yet cast, vote/revote "yes" for candidate
    if ( (votedFor == None) || (votedFor == voteReq.id) ) {
      votedFor = Some(voteReq.id)
      return true
    // else reply with a "no" vote
    } else {
      return false
    }
  }

  // follower OR candidate received VoteReq from a candidate
  def processVoteReq (voteReq:VoteReq) : Unit = {
    var dec =
      // req term < ownTerm: reply with a "no" vote
      if (voteReq.term < ownTerm) {
        false
      // req term == ownTerm: continue as candidate/follower and cast y/n vote
      } else if (voteReq.term == ownTerm) {
        castVote(voteReq)
      // req term > ownTerm: advance ownTerm, candidate becomes follower as nec
      } else {
        changeToFollowerState(voteReq.term)
        castVote(voteReq)
      }
    // send the yes/no reply back to candidate
    voteReq.id.ref ! VoteReply( Vote(ServerID(ownName, self), dec), ownTerm )
    // print control info for testing purposes
    printf(f"${ownName}: sent vote to id / decision / term ${voteReq.id.name} / ${dec} / ${ownTerm}\n")
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
    printf(f"${ownName}: received appendReq from id / term ${leaderId.name} / ${leaderTerm}\n")
  }

  /*****************************************************************************
  * FOLLOWER LOGICS: ATOMIC UNITS COMBINED INTO FOLLOWER STATE
  *****************************************************************************/

  def changeToFollowerState (newTerm:Int) : Unit = {
    // set our current term
    ownTerm = newTerm
    // reset who we voted for this term
    votedFor = None
    // reset election timer, with variance
    resetElectionTimer(true)
    // cancel the heartbeatTimer: follower does not need one
    if (heartbeatTimer != null)
      heartbeatTimer.cancel()
    // change message processing "receive" behavior
    become(follower)
    // print control info for testing purposes
    printf(f"${ownName}: becoming follower, term ${ownTerm}\n")
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
    printf(f"${ownName}: becoming candidate, term ${ownTerm}\n")
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
    printf(f"${ownName}: received voteReply from id / decision / term ${vote.id.name} / ${vote.decision} / ${term}\n")
  }

  // candidate tallies received vote and determines possible election results
  def tallyVote (vote:Vote) : Unit = {
    // add the received vote to set of collected votes
    votesCollected += vote
    // tally the total number of "yes" votes received
    val yesVotes = votesCollected.count(_.decision == true)
    // if total num yes votes is now majority, change to leader state
    if (yesVotes > peers.size)
      changeToLeaderState()
    // print control info for testing purposes
    printf(f"${ownName}: tallied votes from ${votesCollected.map(_.id.name).mkString(" ")}\n")
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
    // print control info for testing purposes
    printf(f"${ownName}: becoming leader, term ${ownTerm}\n")
  }

  // leader received AppendEntriesReply from follower/candidate/leader
  def processAppendEntriesReply (id:ServerID, success:Boolean, term:Int) : Unit = {
    // if follower/candidate/leader term > ownTerm, become follower
    if (term > ownTerm) {
      changeToFollowerState(term)
    }
    // print control info for testing purposes
    printf(f"${ownName}: received appendReply from id / term ${id.name} / ${term}\n")
  }

  // leader heartbeatTimeout received: send heartbeats to maintain leadership
  def broadcastHeartbeats () : Unit = {
    // send heatbeat msg (empty AppendEntriesReq) to each peer
    peers.foreach( id => id.ref ! AppendEntriesReq(ServerID(ownName, self), ownTerm) )
    // print control info for testing purposes
    printf(f"${ownName}: broadcasting heartbeats (term) ${ownTerm}\n")
  }

  /*****************************************************************************
  * SERVER STATES (i.e receive METHODS): SERVER WILL BE IN ONE OF THESE STATES
  *****************************************************************************/

  // INITIAL: uninitialized (i.e. waiting to receive peer list)
  def receive = {
    // init msg: add peers, start elec timer, become follower (& handle stashed)
    case InitWithPeers(peerList) => initialize(peerList)
    // some other msg: stash the msg and handle it afer server init to follower
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

