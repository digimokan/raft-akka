package raft

import akka.actor.ActorRef

sealed trait RaftAPI

final case class InitWithPeers (peerList:List[ActorRef]) extends RaftAPI
final case class VoteReq (candRef:ActorRef, candTerm:Int) extends RaftAPI
final case class VoteReply (voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean) extends RaftAPI
final case class AppendReq (leaderRef:ActorRef, leaderTerm:Int) extends RaftAPI
final case class AppendReply (appenderRef:ActorRef, appenderTerm:Int, appenderSuccess:Boolean) extends RaftAPI
final case object Start extends RaftAPI
final case object Crash extends RaftAPI
final case object Disconnect extends RaftAPI
final case object Reconnect extends RaftAPI
final case object ElectionTimeout extends RaftAPI
final case object HeartbeatTimeout extends RaftAPI

sealed trait RaftControlAPI

final case object InitMsg extends RaftControlAPI
final case class StartupMsg (term:Int, elecTimer:Double) extends RaftControlAPI
final case class CrashMsg (ref:ActorRef, term:Int) extends RaftControlAPI
final case class FollowerMsg (ref:ActorRef, term:Int) extends RaftControlAPI
final case class CandidateMsg (ref:ActorRef, term:Int) extends RaftControlAPI
final case class LeaderMsg (ref:ActorRef, term:Int) extends RaftControlAPI
final case class VoteReqMsg (candRef:ActorRef, candTerm:Int, voterRef:ActorRef) extends RaftControlAPI
final case class VoteReplyMsg (voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean, candRef:ActorRef, candTerm:Int) extends RaftControlAPI
final case class VoteReceiptMsg (candRef:ActorRef, candTerm:Int, wonElection:Boolean, becameFollower:Boolean, yesVotes:Int, voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean) extends RaftControlAPI
final case class AppendReqMsg (leaderRef:ActorRef, leaderTerm:Int, appenderRef:ActorRef) extends RaftControlAPI
final case class AppendReplyMsg (appenderRef:ActorRef, appenderTerm:Int, appenderSuccess:Boolean, leaderRef:ActorRef, leaderTerm:Int) extends RaftControlAPI
final case class AppendReceiptMsg (leaderRef:ActorRef, leaderTerm:Int, becameFollower:Boolean, appenderRef:ActorRef, appenderTerm:Int) extends RaftControlAPI

sealed trait RaftTestAPI

final case object Shutdown extends RaftTestAPI
final case object StartAll extends RaftTestAPI
final case object StartAllSimulElec extends RaftTestAPI
final case object CrashAll extends RaftTestAPI
final case object CrashLeader extends RaftTestAPI
final case object RestartLeader extends RaftTestAPI
final case object DisconnectLeader extends RaftTestAPI
final case object ReconnectLeader extends RaftTestAPI

