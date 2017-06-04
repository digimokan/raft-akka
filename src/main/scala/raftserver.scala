package raft

import com.typesafe.config.ConfigFactory
import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.mutable.Set
import akka.actor.{Actor, ActorRef, Cancellable, Stash}

class RaftServer (newName:String) extends Actor with Stash {
  /*****************************************************************************
  * SERVER STATE (FIELDS)
  *****************************************************************************/

  // config vars: election and heartbeat timeouts
  val electionTimeoutBase = ConfigFactory.load.getInt("election-timeout-base")
  val electionTimeoutVariance = ConfigFactory.load.getInt("election-timeout-variance")
  val heartbeatTimeout = ConfigFactory.load.getInt("heartbeat-timeout")
  var rand = Random                    // generate random timeouts
  import context._                     // for scheduleOnce, become

  // persistent state: these vars will survive server crash & restart
  val ownName = newName                // this server's assigned name
  var serverIDs = Set[ServerID]()      // other servers this server is aware of
  var currentTerm = 0                  // latest term this server is aware of
  var votedFor:Option[ServerID] = None // who this server voted for in curr term

  // volatile state: these vars will be re-initialized on server crash & restart
  var electionTimer:Cancellable = null // this server's election timer

  /*****************************************************************************
  * SERVER LOGICS: ATOMIC FUNCTIONAL UNITS THAT WILL BE COMBINED INTO A STATE
  *****************************************************************************/

  // add a server to this server's list of known peers
  def addPeer (id:ServerID) : Unit = {
    if (id.name != ownName)
      serverIDs += id
  }

  // add many servers to this server's list of known peers
  def addPeers (peers:List[ServerID]) : Unit = {
    peers.foreach( id => addPeer(id) )
  }

  // reset this server's election timer to base + variance
  def resetElectionTimer () : Unit = {
    val timerValue = electionTimeoutBase + rand.nextInt(electionTimeoutVariance)
    electionTimer = context.system.scheduler.scheduleOnce(timerValue milliseconds, self, ElectionTimeout)
    printf(f"${ownName}: set election timer ${timerValue.toDouble / 1000} sec\n")
  }

  /*****************************************************************************
  * SERVER STATES (i.e receive METHODS): SERVER WILL BE IN ONE OF THESE STATES
  *****************************************************************************/

  // INITIAL: uninitialized (i.e. not yet have peer list)
  def receive = {
    // init msg: add peers, start elec timer, become follower (& handle stashed)
    case InitWithPeers(peers) =>
      addPeers(peers)
      resetElectionTimer()
      unstashAll()
      become(follower)
    // some other msg: stash the msg and handle it afer server init to follower
    case _ =>
      stash()
  }

  // FOLLOWER: respond to VoteReq and AppendEntries from candidates and leaders
  def follower : Receive = {
    // this server's election timer expired: become candidate and start election
    case ElectionTimeout => printf(f"${ownName}: becoming candidate\n")
  }

}

