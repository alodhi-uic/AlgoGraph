# AlgoGraph — NetGameSim to Akka Distributed Algorithms Simulator

CS553 Course Project · University of Illinois Chicago

Turns randomly generated graphs from [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim) into a running Akka Classic actor system where every graph node is an actor and every graph edge is a typed message channel. Distributed algorithms plug into the same message substrate without touching the runtime.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java (JDK) | 17 or later |
| sbt | 1.9 or later |
| Scala | 3.3.7 (managed by sbt) |
| NetGameSim | built locally (see below) |

---

## Repository layout

```
AlgoGraph/
├── sim-core/            # Graph model, edge labels, node PDFs, SimConfig, validation
├── sim-runtime-akka/    # Akka actor runtime — NodeActor, RuntimeBuilder
├── sim-algorithms/      # Distributed algorithm implementations
│   ├── DistributedAlgorithm.scala     # plug-in trait
│   ├── HirschbergSinclairAlgorithm.scala
│   └── TreeLeaderElection.scala
├── sim-cli/             # Command-line entry point — App.scala
│   ├── inputs/inject.txt              # file-driven injection script
│   └── src/main/resources/application.conf
└── netgamesim/output/   # Generated graph artifacts (git-ignored)
```

---

## Step 1 — Build NetGameSim

AlgoGraph reads `.ngs.dot` graph files produced by NetGameSim. Clone and build it first.

```bash
git clone https://github.com/0x1DOCD00D/NetGameSim.git
cd NetGameSim
sbt compile
```

Edit `GenericSimUtilities/src/main/resources/application.conf` and set `outputDirectory` to the absolute path of AlgoGraph's output folder:

```hocon
NGSimulator {
  outputDirectory = "/absolute/path/to/AlgoGraph/netgamesim/output"
  ...
}
```

Then generate a graph. Use one of the topology flags below:

```bash
# Random topology (default)
sbt run

# Bidirectional ring — required for Hirschberg-Sinclair
sbt "run -hs"

# Spanning tree — required for Tree Leader Election
sbt "run -TLE"
```

Each run writes a `.ngs.dot` file and a `.ngs` binary to the configured output directory.

---

## Step 2 — Configure AlgoGraph

`sim-cli/src/main/resources/application.conf` is pre-configured with a relative path that works out of the box when you run from the AlgoGraph root:

```hocon
sim {
  graphDirectory = "netgamesim/output"   # relative to project root — no edit needed
  runDurationMs  = 6000
  ...
}
```

If you need to point to a different directory, override at runtime without touching the file:

```bash
sbt -Dsim.graphDirectory=/your/path simCli/run
```

All other settings (PDFs, timers, edge labels, injection) are documented inline in the same file.

---

## Step 3 — Run the simulation

From the AlgoGraph root:

```bash
# Compile everything
sbt compile

# Run the simulation
sbt "simCli/runMain edu.uic.cs553.App"

# Run tests
sbt test
```

The app automatically picks the most recently modified `.ngs.dot` file in `graphDirectory`, so no filename is needed. Perturbed graphs (`.perturbed.ngs.dot`) are excluded automatically.

---

## Configuration reference

All settings live in `sim-cli/src/main/resources/application.conf`.

### Background traffic

```hocon
sim.traffic {
  defaultPdf = [
    { msg = "PING",   p = 0.50 },
    { msg = "GOSSIP", p = 0.30 },
    { msg = "WORK",   p = 0.20 }
  ]
  perNodePdf = [
    # { node = 3, pdf = [{ msg = "WORK", p = 1.0 }] }
  ]
}
```

### Timer and input nodes

```hocon
sim.initiators {
  timers = [
    { node = 0, tickEveryMs = 1000, mode = "pdf" }
  ]
  inputs = [
    { node = 1 },
    { node = 2 }
  ]
}
```

Timer nodes emit one message per tick sampled from the node PDF. Input nodes accept externally injected messages from the driver.

### Edge labels

```hocon
sim.edgeLabeling {
  default   = ["PING", "CONTROL", "GOSSIP", "WORK"]
  overrides = [
    # { from = 0, to = 1, allow = ["PING", "CONTROL"] }
  ]
}
```

Algorithm control messages (e.g. `HS_PROBE`, `TREE_MSG`) always bypass edge label checks and are never blocked.

### External message injection

```hocon
sim.injection {
  mode = "file"
  file = "sim-cli/inputs/inject.txt"
}
```

**File mode** — each line of `inject.txt` describes one delivery event:

```
# <delayMs>  <nodeId>  <kind>  <payload>
1500 1 WORK  external-job-1
3000 2 PING  hello-from-driver
```

Lines are sorted by `delayMs` and delivered on a background daemon thread. The main thread sleeps for `runDurationMs` then shuts down.

**Interactive mode** — switch `mode = "interactive"`, then launch from **inside** the sbt shell so stdin is forwarded to the forked JVM:

```bash
# Step 1 — enter the sbt shell
sbt

# Step 2 — run from inside the shell (stdin is now forwarded)
simCli/run
```

Type commands while the simulation runs, then press `Ctrl+D` to finish:

```
1 WORK my-job
2 PING hello
^D          ← shuts down and prints metrics
```

> **Note:** `sbt simCli/run` (batch mode) does **not** forward stdin. You must enter the sbt shell first.

---

## Distributed algorithms

All four algorithms implement `DistributedAlgorithm` and plug in via `App.scala`:

```scala
val algorithmFactories: Seq[() => DistributedAlgorithm] = Seq(
  () => new HirschbergSinclairAlgorithm,
  () => new TreeLeaderElection,
  () => new Snapshot,
  () => new Termination
)
```

RuntimeBuilder calls each factory once per node so every actor gets independent mutable state.  Adding or removing an algorithm is a one-line change here — the runtime and actors need no modification.

### Hirschberg-Sinclair (HS) — bidirectional ring leader election

Requires a ring topology. Generate with `sbt "run -hs"` in NetGameSim.

Each node probes both clockwise (CW) and counter-clockwise (CCW) directions. Probes travel up to `2^phase` hops. A node suppresses probes from smaller IDs, reflects probes from larger IDs, and advances phases when both directions reply. The node whose own probe returns from both directions declares itself leader and broadcasts `HS_LEADER`.

**Topology validation:** HS checks that every node has exactly 2 neighbors on startup and prints a descriptive error (not a crash) if the graph is not a ring.

**Expected output:**

```
[HS] node 7: LEADER elected: 9
```

### Tree Leader Election (TLE) — spanning tree

Requires a tree (acyclic) topology. Generate with `sbt "run -TLE"` in NetGameSim.

Leaves send their ID up immediately. Internal nodes wait for all-but-one neighbor to report, then forward the maximum ID seen to the remaining neighbor. The root (node that hears from all neighbors) broadcasts the elected leader downward.

**Center-edge tiebreak:** when two adjacent nodes simultaneously become root candidates (both waiting on each other as the last neighbor), the higher-ID node wins. The lower-ID node waits for the `TREE_LEADER` broadcast.

**Expected output:**

```
[TREE] node 3: ROOT — elected leader=9, broadcasting
[TREE] node 0: LEADER = 9   # all 10 nodes converge on the same value
```

### Chandy-Lamport Snapshot — consistent global state capture

Works on any connected topology.  Node 0 initiates on its first timer tick by recording its local state and flooding `SNAP_MARKER` on all outgoing channels.

When a node receives its first `SNAP_MARKER` it records its own local state (message counts by kind), starts buffering any application messages that arrive on channels not yet marked (those are "in transit"), and re-broadcasts the marker.  When a marker arrives on every incoming channel the recording closes and the node logs its complete snapshot.

**Key property:** channels are FIFO (Akka local delivery guarantee), which ensures the marker always arrives after any application message that was in flight before it was sent.

**Expected output:**
```
[SNAP] node 0: INITIATOR — local state = Map(GOSSIP -> 3)
[SNAP] node 4: SNAPSHOT COMPLETE — local state = Map(GOSSIP -> 1), in-transit = none
```

### Dijkstra-Scholten Termination Detection

Works on any connected topology.  Node 0 initiates on its second timer tick (after the snapshot wave has settled) by sending `DS_WORK` to all neighbors and setting `deficit = neighbors.size`.

Each non-root node joins the spanning tree on first `DS_WORK`: it records the sender as its parent, forwards `DS_WORK` to its remaining neighbors (children), and waits until all children acknowledge.  Leaves send `DS_ACK` immediately.  Once `deficit` reaches zero a non-root sends `DS_ACK` to its parent; the root declares **TERMINATION DETECTED**.

Duplicate `DS_WORK` messages (node already in tree) are rejected immediately with `DS_ACK` to avoid inflating deficits.

**Expected output:**
```
[DS] node 0: ROOT — initiating termination detection, sending DS_WORK to 3 neighbor(s)
[DS] node 0: ROOT — TERMINATION DETECTED, all 3 subtrees have acknowledged
```

---

## Experiment configurations

Three configurations that vary graph properties in ways that directly exercise the algorithms:

### Experiment 1 — Small general graph (default baseline)

**Purpose:** verify all four algorithms run correctly end to end on a small graph.  HS detects non-ring topology and silences itself gracefully; TLE elects the max-ID leader; Snapshot captures a clean global state; DS detects termination.

```hocon
sim {
  graphDirectory = "netgamesim/output"
  runDurationMs  = 6000
  traffic.defaultPdf = [
    { msg = "PING",   p = 0.50 },
    { msg = "GOSSIP", p = 0.30 },
    { msg = "WORK",   p = 0.20 }
  ]
  initiators.timers = [{ node = 0, tickEveryMs = 1000, mode = "pdf" }]
}
```

Generate graph: `sbt run` in NetGameSim with `statesTotal = 10`.

---

### Experiment 2 — Fast timer, heavy traffic (Snapshot in-transit capture)

**Purpose:** increase the probability that application messages are in-flight between nodes when the `SNAP_MARKER` wave propagates.  A faster tick interval and a heavier GOSSIP/WORK mix keeps more messages circulating, making it likely that the snapshot captures non-empty `in-transit` channels.

```hocon
sim {
  runDurationMs  = 8000
  traffic.defaultPdf = [
    { msg = "GOSSIP", p = 0.60 },
    { msg = "WORK",   p = 0.30 },
    { msg = "PING",   p = 0.10 }
  ]
  initiators.timers = [
    { node = 0, tickEveryMs = 300, mode = "pdf" },
    { node = 1, tickEveryMs = 300, mode = "pdf" }
  ]
}
```

Look for `in-transit = Map(...)` (non-empty) in the `[SNAP] SNAPSHOT COMPLETE` log lines.

---

### Experiment 3 — Larger graph (DS spanning-tree depth test)

**Purpose:** on a larger graph the Dijkstra-Scholten spanning tree is deeper, so `DS_ACK` acknowledgements must propagate through more hops before the root can declare termination.  This tests that the deficit counter aggregates correctly across a multi-level tree and produces no false positives.

```hocon
sim {
  runDurationMs  = 10000
  initiators.timers = [{ node = 0, tickEveryMs = 1500, mode = "pdf" }]
  initiators.inputs = [{ node = 1 }, { node = 2 }, { node = 3 }]
}
```

Generate graph: `sbt run` in NetGameSim with `statesTotal = 50`.  Count the `[DS] subtree complete` log lines — they should equal `(total nodes - 1)` before `TERMINATION DETECTED` appears.

To switch experiments: update NetGameSim `application.conf`, regenerate the graph, adjust `application.conf` above, then `sbt simCli/run` (AlgoGraph auto-picks the latest `.ngs.dot` file).

---

## Design decisions

**Algorithm isolation via `AlgorithmContext`**
Algorithms receive a context object (`AlgorithmContext`) instead of a direct `ActorRef`. This decouples algorithm logic from Akka internals and makes algorithms independently testable. The context exposes `send`, `broadcast`, `broadcastExcept`, and `log`.

**Two message paths**
`NodeActor` maintains two send paths: `sendAlgorithmMsg` (bypasses edge-label checks — control traffic must always be deliverable) and `sendAppMsg` (enforces edge labels — application traffic is constrained). This prevents algorithm messages from being silently dropped by label mismatch.

**Config-driven enrichment via `SimConfig`**
`GraphEnricher` takes a `SimConfig` case class, not a raw `Config` object. This keeps `sim-core` free of the typesafe-config dependency and makes `GraphEnricher` testable without a config file. `App.scala` in `sim-cli` owns all config parsing.

**Per-node algorithm instantiation**
Algorithm factories are called once per node so each actor gets independent mutable state. A single shared instance would cause race conditions between node actors.

**Reproducibility**
Each `NodeActor` creates its own `Random(id)` seeded by node ID. Traffic generation is therefore deterministic under a fixed graph and fixed configuration.

---

## Cinnamon instrumentation (Lightbend Telemetry)

This project integrates [Cinnamon](https://doc.akka.io/docs/cinnamon/current/) — Lightbend's commercial telemetry agent — for JVM and actor-system observability.

### What is enabled

```scala
// project/plugins.sbt
addSbtPlugin("com.lightbend.cinnamon" % "sbt-cinnamon" % "2.21.4")

// build.sbt (simCli)
.enablePlugins(Cinnamon)
run  / cinnamon := true
test / cinnamon := true
```

```hocon
# application.conf
cinnamon {
  akka.actors {
    default-by-class {
      includes = "/user/*"
      report-by = class
    }
  }
  chmetrics { reporters += "console-reporter" }
}
```

### What you see on every run

```
[INFO] [Cinnamon] Agent version 2.21.4
[INFO] [Cinnamon] Agent found Akka Actor version: 2.8.8
[INFO] License check succeeded for Lightbend user ... License is valid until 2026-05-31.
```

### Known limitation

Cinnamon 2.21.4 supports Akka 24.10; this project uses Akka Classic 2.8.8, so per-actor throughput/mailbox metrics are not available (the agent logs a warning and skips actor instrumentation).  JVM metrics (heap, GC, threads) and dispatcher metrics are still collected.

Manual `AtomicLong` counters in `SimMetrics` supplement Cinnamon for message-level telemetry and are printed at the end of every run:

```
╔══════════════════════════════════════╗
║      Simulation Metrics Summary      ║
╠══════════════════════════════════════╣
║  Messages received (total) : 76
║  Algorithm messages sent   : 59
║  App messages delivered    : 17
║  App messages dropped      : 0  (blocked by edge labels)
║  External inputs injected  : 2
║  Timer ticks fired         : 5
╚══════════════════════════════════════╝
```

---

## Akka learning examples

Prof. Grechanik's PLANE examples that informed this project:

- [BasicActorComm](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/BasicActorComm.scala) — actor creation and message passing
- [ScheduleEventsRandomIntervals](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/ScheduleEventsRandomIntervals.scala) — timer-driven message generation
- [StateMachineWithActors](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/StateMachineWithActors.scala) — phase-based algorithm state
- [DiffusingComputation](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/DiffusingComputation.scala) — tree-style convergecast
- [ActorSystemExperiments](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/ActorSystemExperiments.scala) — coordinated shutdown
