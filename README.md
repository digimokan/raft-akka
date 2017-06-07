# raft-akka

Partial implementation of the [Raft](https://raft.github.io/) consensus
algorithm in scala/akka.  Currently, the leader-election is fully complete and
every conceivable leader-election scenario can be tested with the built-in
testing framework.

## Table of Contents

- [Motivation / Design](#motivation--design)
- [Installation](#installation)
  - [Windows](#windows)
  - [macOS](#macOS)
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
Spring 2017 course in Distributed Systems II.

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

1. test 1

2. test 2

## Meta

### Developers

- [**digimokan**](https://github.com/digimokan)

### License

Distributed under the [GPLv3](LICENSE) License

