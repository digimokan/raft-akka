# raft-akka

Partial implementation of the [Raft](https://raft.github.io/) consensus
algorithm in scala/akka.  Currently, the leader-election is fully complete and
every conceivable leader-election scenario can be tested with the built-in
testing framework.

## Table of Contents

- [Motivation / Design](#motivation--design)
- [Installation](#installation)
  - [Windows](#windows)
  - [macOS](#macos)
  - [Linux](#linux)
- [Compiling And Running](#compiling-and-running)
- [Architecture](#architecture)
  - [High Level Architecture](#high-level-architecture)
  - [Source Code Layout](#source-code-layout)
- [Tests](#tests)
  - [Leader Election Tests](#leader-election-tests)
- [Meta](#meta)
  - [Developers](#developers)
  - [License](#license)

## Motivation / Design

This raft implementation was completed as final project for Ljubomir Perkovic's
Spring 2017 course in Distributed Systems II.  In starting the project, I had
two main goals: keep the code that does the actual raft algorithm tasks as tidy
and self-documenting as possible (i.e. source code brevity), and ensure that
every possible edge case of raft server interaction was fully testable and
observable.  These two goals taken together proved more work than I initially
imagined....

To keep the raft algorithm tasks as implemented in the code tidy and
understandable, it follows that no descriptive printing statements should be
embedded and littered throughout the code.  Also, functionality that starts,
stops, and "disconnects" servers from their peers involves a lot of logic, and
this code should not clutter the raft server tasks.  Accomplishing the brevity
goal in isolation is not too difficult, but if one focused on this goal alone,
raft servers would perform their tasks and interact without being observable,
and without any prodding or direction from outside their isolated system.

To fully test raft server interaction across all the edge cases, it is
necessary to keep track of leaders, followers, intentionally isolated servers,
messages sent for the first time or possibly as repeated attempts, etc.  The
raft servers must possess some way of communicating this information to the
entity testing them.  To observe the system via the SBT command-line interface,
regular print statements are required.  Both of these features, if implemented
embedded in the raft server code, would accomplish the testing/observation goal,
but again, they would make the first goal impossible.

The solution I came up with for accomplishing the two main goals was to wrap
all raft intra-server message receiving/sending into "receiveSomeMsg" and
"sendSomeMsg" wrapper methods of the RaftServer class.  This strategy allowed
for a single call to each of these functions whenever a message was received by
another raft server, or sent to another raft server.  The wrapper methods
encapsulated two kinds of methods: "pure" raft methods that did the actual
algorithm tasks of raft, and "control" methods that sent various required
pieces of information back to the tester (RaftTester).  The code for doing the
algorithm tasks was thus implmenented uncluttered by any functionality other
than the code required to be a raft server.  Also, under this implementation
the complicated code of engineering the [observable tests](#tests) can be
totally confined to the methods of the RaftTester class.  Raft servers need only
perform their tasks as true raft servers, and respond to a minimum of basic
control commands, such as starting, stopping, and "isolating" themselves from
other raft servers.

This implementation was difficult to engineer, but once developed, allowed a
high degree of controllability by the RaftTester class, and also completely
concise raft server code that was much easier to write and reason about.  In
the end, much more time was spent developing the testing framework and
configuring the raft servers to run within the framework, but I believe it is
an implementation that is much more durable, testable, and extendable than a
simpler implementation with embedded testing and observation code.

## Installation

This program requires Scala Build Tool (SBT) Version 0.13 to be installed.

### Windows

Follow the official SBT instructions on the [SBT Wiki](http://www.scala-sbt.org/0.13/docs/Installing-sbt-on-Windows.html)

### macOS

Follow the official SBT instructions on the [SBT Wiki](http://www.scala-sbt.org/0.13/docs/Installing-sbt-on-Mac.html)

### Linux

Follow the official SBT instructions on the [SBT Wiki](http://www.scala-sbt.org/0.13/docs/Installing-sbt-on-Linux.html)

## Compiling And Running

1. Clone the project into a local project directory:

   ```bash
   $ git clone git@github.com:digimokan/raft-akka.git
   ```

2. Change to the local project directory:

   ```bash
   $ cd raft-akka
   ```

3. Start SBT and enter the SBT REPL:

   ```bash
   $ sbt
   ```

4. Within SBT REPL, compile the program:

   ```
   > compile
   ```

5. Within SBT REPL, run the program and the built-in tests:

   ```
   > run
   ```

## Architecture

### High Level Architecture

![Architecture](/readme/architecture.png)

### Source Code Layout

```
├── /project/                 # project settings
│ ├── /build.properties/      # specifies sbt version to build with
├── /src/                     # settings and source files
│ ├── /main/                  # settings and source files
│   ├── /resources/           # settings
│     ├── /application.conf   # constants to use in configuring servers
│   ├── /scala/               # components (classes)
│     ├── /messages.scala     # messages to pass between app/server/tester
│     ├── /raftapp.scala      # main entry point for program, runs tester
│     ├── /rafttester.scala   # creates servers and controls/tests them
│     ├── /raftserver.scala   # a raft server
```

## Tests

### Leader Election Tests

1. **Normal Election.**  First, introduce raft servers to each other
(accomplished via message passing).  Next, broadcast a "Start" command to each
server.  Raft servers always start as followers in term 0.  Observe one of the
servers time out, change to the candidate state, and begin an election.  Most
likely, two servers will not time out at the same time.  Once the server wins
the election, observe the server send regular heartbeats (implemented as empty
AppendEntries RPCs) to the other servers, which will have them reset their
election timers, preventing another election and preserving the new leader's
role as leader.

2. **Crashed Leader.**  Have the tester, which is keeping track of followers,
candidates, and leaders, send a crash message to the current leader.  The
crashed leader will have all of its volatile state erased, and the other servers
will be unable to communicate with the crashed leader.  In the absence of the
regular heartbeat messages received from the now-crashed leader, one of the
remaining servers will have its election timer expire, start an election, and
become the new leader.

3. **Restart Crashed Leader.**  Send a "start" command to the crashed leader.
Since all raft servers start as followers, the crashed leader actually starts as
a new follower in term 0.  Note that the remaining servers, which elected a new
leader, are all running in term 1.  Since the new leader is sending out its
heartbeats to maintain leadership, the new follower (i.e. crashed leader) will
receive an AppendEntries RPC with term 1, which is greater than its own term of
0.  The new follower will then update its term and become a follower in term 1.

4. **Disconnect Current Leader.**  Send a "disconnect" command to the current
leader.  The now-disconnected leader is still operating, but is unable to
receive any messages.  Also, the messages it does send out (i.e. AppendEntries
RPCs) do not reach any of their recipients.  The disconnected leader, per the
raft logic, will simply continue to send these futile AppendEntries RPCs.
Observe the same behavior by the remaining connected servers: they will elect
a new leader for a new term, and carry on.

5. **Reconnect Disconnected Leader.**  Send a "reconnect" command to the
disconnected leader.  This reconnected leader can can now receive AppendEntries
RPCs from the leader elected while it was disconnected, and its own
AppendEntries RPCs are also able to reach the other servers.  Observe one of two
possible outcomes: the reconnected leader receive an AppendEntriesRPC from a
server in a greater term than its own, and revert to follower state, or the
reconnected leader will receive a _reply_ to one of its own sent AppendEntries
RPCs, with the term of the replying server included in the reply, and when it
sees this later term in the reply, will revert to follower state.

6. **Start Simultaneous Election.**  First, crash all servers, then start all
servers (all as followers in term 0).  Next, send an ElectionTimeout to both
the first server and the last server in the group.  This ElectionTimeout message
sent to both servers will simulate that both servers' election timers expired
at the same time.  Observe each of the two new candidates request votes from
the other servers in parallel.  Due to the fact that raft servers only vote for
one server in a single term, and due to the fact that there is an odd number of
servers in the group, one of the two candidates must necessarily win its
election.  Observe the winning candidate become the new leader, and then observe
the losing candidate revert to follower state when it receives an AppendEntries
RPC from the new leader.

7. **Start Simultaneous Contested Election.**  First, crash all servers, then
start all servers (all as followers in term 0).  Next, crash one of servers
(the tester in this case will crash the next-to-last server in the group).  Just
like the prior test, now send an ElectionTimeout to both the first server and
the last server in the group.  If the message timing is close enough (i.e. you
may need to run this test a few times to see the election actually be
contested), observe the two new candidates each receive an equal number of votes
(in this case, two votes).  Neither server will gain a majority, so each will
continue in candidate state.  Note that you can observe the two candidates send
repeated vote requests to the crashed server (when their heartbeat timers
expire) in attempts to gain the critical vote from the unresponsive/crashed
server.  Eventually, you will observe an election timer run out: this timer may
be an election timer on one of the non-candidate servers, or on one of the
candidates themselves.  This new candidate will run for election in a new term
and gain the required majority of votes and become the leader in the new term.

## Meta

### Developers

- [**digimokan**](https://github.com/digimokan)

### License

Distributed under the [GPLv3](LICENSE) License

