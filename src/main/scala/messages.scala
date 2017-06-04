package raft

import akka.actor.ActorRef

sealed trait RaftAPI

final case class ServerID (name:String, ref:ActorRef) extends RaftAPI
final case class InitWithPeers (peers:List[ServerID]) extends RaftAPI
final case class VoteReq (term:Int, name:String) extends RaftAPI
final case class VoteReply (vote:Boolean) extends RaftAPI
final case object ElectionTimeout extends RaftAPI

