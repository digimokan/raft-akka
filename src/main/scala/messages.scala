package raft

import akka.actor.ActorRef

sealed trait RaftAPI
final case class ServerID (name:String, ref:ActorRef) extends RaftAPI

