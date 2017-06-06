package raft

import com.typesafe.config.ConfigFactory
import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.mutable.Set
import akka.actor.{Actor, ActorRef, Cancellable, Stash}

class RaftServer () extends Actor with Stash {

  // manage "votes" that servers cast for candidates as ActorRef/decision tuples
  case class Vote (ref:ActorRef, decision:Boolean)

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
  var peers = Set[ActorRef]()           // other servers we are aware of
  var ownTerm = 0                       // latest term we are aware of
  var votedFor:Option[ActorRef] = None  // who we voted for in curr term

  // volatile state: these vars will be re-initialized on server crash & restart
  var electionTimer:Cancellable = null  // elec timer for followers/candidates
  var heartbeatTimer:Cancellable = null // hb timer for candidates/leaders
  var votesCollected = Set[Vote]()      // servers that voted for us

  /*****************************************************************************
  * TESTING: WRAPPERS THAT COMMUNICATE WITH TESTER
  *****************************************************************************/

  // report back all state changes and behavior to tester/parent
  val tester = parent

  // tell tester we initialized with peers
  def sendInitMsg () : Unit = {
    tester ! InitMsg
  }

  // tell tester we started up and became follower with term 0
  def sendStartupMsg (elecTimer:Double) : Unit = {
    tester ! StartupMsg(ownTerm, elecTimer)
  }

  // tell tester our election timer expired and we became candidate
  def sendCandidateMsg () : Unit = {
    tester ! CandidateMsg(self, ownTerm)
  }

  // tell tester we became a follower (for whatever reason)
  def sendFollowerMsg () : Unit = {
    tester ! FollowerMsg(self, ownTerm)
  }

  // tell tester we won our election and became leader
  def sendLeaderMsg () : Unit = {
    tester ! LeaderMsg(self, ownTerm)
  }

  // send vote reply back to candidate
  // tell tester we sent a vote reply back to a candidate
  def sendVoteReplyMsg (voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean, candRef:ActorRef, candTerm:Int) : Unit = {
    candRef ! VoteReply(voterRef, voterTerm, voterDecision)
    tester ! VoteReplyMsg(voterRef, voterTerm, voterDecision, candRef, candTerm)
  }

  // tell tester we recv vote reply, possible elec win, possible chg to follower
  def sendVoteReceiptMsg (candRef:ActorRef, candTerm:Int, wonElection:Boolean, becameFollower:Boolean, yesVotes:Int, voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean) : Unit = {
    tester ! VoteReceiptMsg(candRef, candTerm, wonElection, becameFollower, yesVotes, voterRef, voterTerm, voterDecision)
  }

  // send append req to each appender
  // tell tester we sent an append req to each appender
  def sendAppendReqMsg (leaderRef:ActorRef, leaderTerm:Int, appenderRef:ActorRef) : Unit = {
    appenderRef ! AppendReq(leaderRef, leaderTerm)
    tester ! AppendReqMsg(leaderRef, leaderTerm, appenderRef)
  }

  // send append reply back to leader
  // tell tester we received an append req from leader
  def sendAppendReplyMsg (appenderRef:ActorRef, appenderTerm:Int, appenderSuccess:Boolean, leaderRef:ActorRef, leaderTerm:Int) : Unit = {
    leaderRef ! AppendReply(appenderRef, appenderTerm, appenderSuccess)
    tester ! AppendReplyMsg(appenderRef, appenderTerm, appenderSuccess, leaderRef, leaderTerm)
  }

  // tell tester we received append reply, possible chg to follower
  def sendAppendReceiptMsg (leaderRef:ActorRef, leaderTerm:Int, becameFollower:Boolean, appenderRef:ActorRef, appenderTerm:Int) : Unit = {
    tester ! AppendReceiptMsg(leaderRef, leaderTerm, becameFollower, appenderRef, appenderTerm)
  }

  /*****************************************************************************
  * INITIAL LOGICS: ATOMIC UNITS COMBINED INTO UNINITIALIZED STATE
  *****************************************************************************/

  // initialize this server with list of known peers
  def initialize (peerList:List[ActorRef]) : Unit = {
    // add list of peers to our set of peers
    addPeers(peerList)
    // place saved msgs received prior to this init back in the msg queue
    unstashAll()
    // become initialized (crashed state) and process the saved msgs
    changeToCrashedState()
    // send control msg to tester
    sendInitMsg()
  }

  // add a server to our list of known peers
  def addPeer (peer:ActorRef) : Unit = {
    if (peer != self)
      peers += peer
  }

  // add many servers to our list of known peers
  def addPeers (peerList:List[ActorRef]) : Unit = {
    peerList.foreach( ref => addPeer(ref) )
  }

  /*****************************************************************************
  * INITIALIZED LOGICS: ATOMIC UNITS COMBINED INTO INITIALIZED STATE
  *****************************************************************************/

  // First Start: server has been init with set of peers: now wait to start
  // Post-Start: crash/down the server and erase all volatile state
  def changeToCrashedState () : Unit = {
    // place saved msgs received prior to this init back in the msg queue
    unstashAll()
    // process the saved msgs
    become(crashed)
    // erase all volatile state
    if (electionTimer != null)
      electionTimer.cancel()
    if (heartbeatTimer != null)
      heartbeatTimer.cancel()
    votesCollected.clear()
  }

  /*****************************************************************************
  * COMMON LOGICS: ATOMIC UNITS COMBINED INTO FOLLOWER/CANDIDATE/LEADER STATE
  *****************************************************************************/

  // start from down/crashed state as follower in term 0
  def start () : Unit = {
    val elecTimer = changeToFollowerState(0)
    // send control msg to tester
    sendStartupMsg(elecTimer)
  }

  // crash/down the server and erase all volatile state
  def crash () : Unit = {
    changeToCrashedState()
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

    // return the new timer value in seconds
    return (timerValue.toDouble / 1000)

  }

  // voter (follower OR candidate) received vote request from a candidate
  def processVoteReq (candRef:ActorRef, candTerm:Int) : Unit = {

    // determine whether to vote for candidate
    def castVote (candRef:ActorRef) : Boolean = {
      // if log checks ok and no vote yet cast, record our vote for candidate
      if (votedFor == None)
        votedFor = Some(candRef)
      // return true if we voted yes NOW for candidate, OR voted yes in the past
      return (votedFor == Some(candRef))
    }

    // save the yes/no vote
    var decision =
      // req term < ownTerm: reply with a "no" vote
      if (candTerm < ownTerm) {
        false
      // candTerm == ownTerm: continue as candidate/follower and cast y/n vote
      } else if (candTerm == ownTerm) {
        castVote(candRef)
      // candTerm > ownTerm: adv ownTerm, cand becomes follow as nec, cast vote
      } else {
        changeToFollowerState(candTerm)
        castVote(candRef)
      }

    // send VoteReply to candidate, send control msg to tester
    sendVoteReplyMsg(self, ownTerm, decision, candRef, candTerm)

  }

  // appender (follower/candidate/leader) received append req from leader
  def processAppendReq (leaderRef:ActorRef, leaderTerm:Int) : Unit = {

    // placeholder for log checks
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

    // send append reply back to leader, send control msg to tester
    sendAppendReplyMsg(self, ownTerm, success, leaderRef, leaderTerm)

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
    // send control msg to tester
    sendFollowerMsg()
    // return value of newly-set timer
    return elecTimer
  }

  /*****************************************************************************
  * CANDIDATE LOGICS: ATOMIC UNITS COMBINED INTO CANDIDATE STATE
  *****************************************************************************/

  def changeToCandidateState (newTerm:Int) : Unit = {

    // start an election (attempt to become leader)
    def startElection () : Unit = {
      // record that we voted for ourself this term
      votedFor = Some(self)
      // clear the set of servers that sent a yes/no vote to us
      votesCollected.clear()
      // have server collect vote for itself
      votesCollected += Vote(self, true)
      // request a yes/no vote from each server
      peers.foreach( voterRef => voterRef ! VoteReq(self, ownTerm) )
    }

    // set our current term, start election
    ownTerm = newTerm
    startElection()

    // reset our election timer MAX possible value, change "receive" behavior
    resetElectionTimer(false)
    become(candidate)

    // send control msg to tester
    sendCandidateMsg()

  }

  // candidate received vote reply from a voter
  def processVoteReply (voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean) : Unit = {

    // collect received vote, tally total number of "yes" votes
    votesCollected += Vote(voterRef, voterDecision)
    val yesVotes = votesCollected.count(vote => (vote.decision == true))

    // check if reply term > ownTerm: abort election and become follower
    val becameFollower =
      if (voterTerm > ownTerm) {
        changeToFollowerState(voterTerm)
        true
      } else false

    // check if total num yes votes now majority: change to leader state
    val wonElection =
      if (yesVotes > (peers.size / 2)) {
        changeToLeaderState()
        true
      } else false

    // send control msg to tester
    sendVoteReceiptMsg(self, ownTerm, wonElection, becameFollower, yesVotes, voterRef, voterTerm, voterDecision)

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
    // send control msg to tester
    sendLeaderMsg()
  }

  // leader received append reply from appender (follower/candidate/leader)
  def processAppendReply (appenderRef:ActorRef, appenderTerm:Int, appenderSuccess:Boolean) : Unit = {
    // if appender term > ownTerm, become follower
    val becameFollower =
      if (appenderTerm > ownTerm) {
        changeToFollowerState(appenderTerm)
        true
    } else false

    // send control msg to tester
    sendAppendReceiptMsg(self, ownTerm, becameFollower, appenderRef, appenderTerm)
  }

  // leader heartbeatTimeout received: send heartbeats to maintain leadership
  def broadcastHeartbeats () : Unit = {
    // send empty append req to each peer, send control msg to tester
    peers.foreach( appenderRef => sendAppendReqMsg(self, ownTerm, appenderRef) )
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

  // CRASHED/DOWN: have peer list, waiting to start & ignoring all other msgs
  def crashed : Receive = {
    // start from crashed/down state as follower in term 0
    case Start => start()
  }

  // FOLLOWER: respond to VoteReq/AppendReq from candidates and leaders
  def follower : Receive = {
    // crash this server: wait to startup again as follower in term 0
    case Crash => crash()
    // start from crashed/down state as follower in term 0
    case Start => start()
    // our election timer expired: become candidate and start election
    case ElectionTimeout => changeToCandidateState(ownTerm + 1)
    // received VoteReq from candidate that started (or is continuing) an election
    case VoteReq(candRef, candTerm) => processVoteReq(candRef, candTerm)
    // received AppendReq from a new leader
    case AppendReq(leaderRef, leaderTerm) => processAppendReq(leaderRef, leaderTerm)
  }

  // CANDIDATE: respond to VoteReply, and to VoteReq from other candidates
  def candidate : Receive = {
    // crash this server: wait to startup again as follower in term 0
    case Crash => crash()
    // start from crashed/down state as follower in term 0
    case Start => start()
    // election timer expired without collecting majority: start a NEW election
    case ElectionTimeout => changeToCandidateState(ownTerm + 1)
    // received a reply to a vote request sent by us
    case VoteReply(voterRef, voterTerm, voterDecision) => processVoteReply(voterRef, voterTerm, voterDecision)
    // received VoteReq from another candidate holding SIMULTANEOUS election
    case VoteReq(candRef, candTerm) => processVoteReq(candRef, candTerm)
    // received AppendReq from a server already established as leader
    case AppendReq(leaderRef, leaderTerm) => processAppendReq(leaderRef, leaderTerm)
  }

  // LEADER: respond to AppendReq/Reply from follows, cands, leads
  def leader : Receive = {
    // crash this server: wait to startup again as follower in term 0
    case Crash => crash()
    // start from crashed/down state as follower in term 0
    case Start => start()
    // our heartbeat timer expired: send heartbeats to maintain leadership
    case HeartbeatTimeout => broadcastHeartbeats()
    // received AppendReq from a leader server in later term
    case AppendReq(leaderRef, leaderTerm) => processAppendReq(leaderRef, leaderTerm)
    // received AppendReply from an appender (follower/candidate/leader)
    case AppendReply(appenderRef, appenderTerm, appenderSuccess) => processAppendReply(appenderRef, appenderTerm, appenderSuccess)
  }

}

