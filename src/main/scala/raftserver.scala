package raft

import scala.collection.mutable.Set
import akka.actor.{Actor, ActorRef}

class RaftServer (newName:String) extends Actor {
  val ownName = newName           // this server's assigned name
  var serverIDs = Set[ServerID]() // other servers this server knows

  def receive () = {
    // initialization msg: this server introduced to ServerID(name, ref)
    case ServerID(name, ref) =>
      if (name != ownName) {
        serverIDs += ServerID(name, ref)
        printf(f"Server ${ownName}: added ${name} to its set\n")
      }
  }
}

