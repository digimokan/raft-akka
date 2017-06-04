package raft

import com.typesafe.config.ConfigFactory
import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.mutable.Set
import akka.actor.{Actor, ActorRef, Cancellable}

class RaftServer (newName:String) extends Actor {
  // config vars: election and heartbeat timeouts
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")
  val electionTimeoutVariance = ConfigFactory.load.getInt("election-timeout-variance")
  val heartbeatTimeout = ConfigFactory.load.getInt("heartbeat-timeout")
  var rand = Random                    // generate random timeouts
  import context.dispatcher            // for scheduleOnce

  // persistent state: these vars will survive server crash & restart
  val ownName = newName                // this server's assigned name
  var serverIDs = Set[ServerID]()      // other servers this server is aware of
  var currentTerm = 0                  // latest term this server is aware of
  var votedFor:Option[ServerID] = None // who this server voted for in curr term

  // volatile state: these vars will be re-initialized on server crash & restart
  var electionTimer:Cancellable = null // this server's election timer

  def receive () = {

    // initialization msg: this server introduced to ServerID(name, ref)
    case ServerID(name, ref) =>
      if (name != ownName) {
        serverIDs += ServerID(name, ref)
        printf(f"${ownName}: added ${name} to its known peers\n")
      }

    // initialization complete: start election timer
    case Run =>
      val timerValue = electionTimeoutBase + rand.nextInt(electionTimeoutVariance)
      electionTimer = context.system.scheduler.scheduleOnce(timerValue milliseconds, self, ElectionTimeout)
      printf(f"${ownName}: set election timer ${timerValue.toDouble / 1000} sec\n")

    // this server's election timer expired: become candidate and start election
    case ElectionTimeout =>
      printf(f"${ownName}: becoming candidate\n")

  } // receive ()

}

