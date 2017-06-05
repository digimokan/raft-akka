package raft

import akka.actor.ActorRef

sealed trait RaftAPI

final case class ServerID (name:String, ref:ActorRef) extends RaftAPI
final case class Vote (id:ServerID, decision:Boolean) extends RaftAPI

final case class InitWithPeers (peerList:List[ServerID]) extends RaftAPI
final case class VoteReq (id:ServerID, term:Int) extends RaftAPI
final case class VoteReply (vote:Vote, term:Int) extends RaftAPI
final case class AppendEntriesReq (leaderId:ServerID, leaderTerm:Int) extends RaftAPI
final case class AppendEntriesReply (id:ServerID, success:Boolean, term:Int) extends RaftAPI
final case object ElectionTimeout extends RaftAPI
final case object HeartbeatTimeout extends RaftAPI

