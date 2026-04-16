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

Open `sim-cli/src/main/resources/application.conf` and set `graphDirectory` to the same output folder:

```hocon
sim {
  graphDirectory = "/absolute/path/to/AlgoGraph/netgamesim/output"
  runDurationMs  = 6000
  ...
}
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
  mode = "file"                        # or "interactive"
  file = "sim-cli/inputs/inject.txt"
}
```

**File mode** — each line of `inject.txt` describes one delivery event:

```
# <delayMs>  <nodeId>  <kind>  <payload>
1500 1 WORK  external-job-1
3000 2 PING  hello-from-driver
```

Lines are sorted by `delayMs` and delivered on a background thread, so the main thread still runs for the full `runDurationMs`.

**Interactive mode** — switch `mode = "interactive"` then type commands at stdin while the simulation runs:

```
<nodeId> <kind> <payload>
1 WORK my-job
```

---

## Distributed algorithms

Both algorithms implement `DistributedAlgorithm` and plug in via `App.scala`:

```scala
val algorithmFactories: Seq[() => DistributedAlgorithm] = Seq(
  () => new HirschbergSinclairAlgorithm,
  () => new TreeLeaderElection
)
```

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

---

## Experiment configurations

Three graph configurations that vary meaningful properties:

| Experiment | NetGameSim flag | `statesTotal` | Purpose |
|------------|-----------------|---------------|---------|
| Small ring | `-hs` | 10 | Verify HS correctness on minimal ring |
| Large ring | `-hs` | 100 | HS phase count and message volume at scale |
| Tree | `-TLE` | 10 | TLE correctness; test center-edge tiebreak |

To run a different experiment, update `statesTotal` in NetGameSim's `application.conf`, regenerate the graph, then re-run AlgoGraph (it auto-picks the latest file).

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

## Akka learning examples

Prof. Grechanik's PLANE examples that informed this project:

- [BasicActorComm](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/BasicActorComm.scala) — actor creation and message passing
- [ScheduleEventsRandomIntervals](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/ScheduleEventsRandomIntervals.scala) — timer-driven message generation
- [StateMachineWithActors](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/StateMachineWithActors.scala) — phase-based algorithm state
- [DiffusingComputation](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/DiffusingComputation.scala) — tree-style convergecast
- [ActorSystemExperiments](https://github.com/0x1DOCD00D/PLANE/blob/master/src/main/scala/Akka/ActorSystemExperiments.scala) — coordinated shutdown
