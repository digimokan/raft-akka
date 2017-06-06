package raft

import akka.actor.ActorRef

sealed trait RaftAPI

final case class InitWithPeers (peerList:List[ActorRef]) extends RaftAPI
final case class VoteReq (candRef:ActorRef, candTerm:Int) extends RaftAPI
final case class VoteReply (voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean) extends RaftAPI
final case class AppendReq (leaderRef:ActorRef, leaderTerm:Int) extends RaftAPI
final case class AppendReply (appenderRef:ActorRef, appenderTerm:Int, appenderSuccess:Boolean) extends RaftAPI
final case object Start extends RaftAPI
final case object ElectionTimeout extends RaftAPI
final case object HeartbeatTimeout extends RaftAPI

final case object InitMsg extends RaftAPI
final case class StartupMsg (term:Int, elecTimer:Double) extends RaftAPI
final case class CandidateMsg (candRef:ActorRef, candTerm:Int) extends RaftAPI
final case class VoteReplyMsg (voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean, candRef:ActorRef, candTerm:Int) extends RaftAPI
final case class VoteReceiptMsg (candRef:ActorRef, candTerm:Int, wonElection:Boolean, becameFollower:Boolean, yesVotes:Int, voterRef:ActorRef, voterTerm:Int, voterDecision:Boolean)
final case class AppendReqMsg (leaderRef:ActorRef, leaderTerm:Int, appenderRef:ActorRef) extends RaftAPI
final case class AppendReplyMsg (appenderRef:ActorRef, appenderTerm:Int, appenderSuccess:Boolean, leaderRef:ActorRef, leaderTerm:Int) extends RaftAPI
final case class AppendReceiptMsg (leaderRef:ActorRef, leaderTerm:Int, becameFollower:Boolean, appenderRef:ActorRef, appenderTerm:Int) extends RaftAPI

sealed trait RaftTestAPI

final case object Shutdown extends RaftTestAPI
final case object StartAll extends RaftTestAPI

