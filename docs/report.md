# AlgoGraph — Project Report

CS553 Distributed Systems · University of Illinois Chicago  
Author: Apoorv Lodhi

---

## 1. System Overview

AlgoGraph is an end-to-end pipeline that converts randomly generated graphs from NetGameSim into a running Akka Classic actor system, runs background application traffic through it, and executes distributed algorithms on top of the same message substrate.

The pipeline has four stages:

1. **Graph generation** — NetGameSim produces a `.ngs.dot` file describing nodes and weighted directed edges.
2. **Graph enrichment** — `GraphEnricher` annotates each node with a probability mass function (PDF) for traffic generation and each edge with a set of permitted message kinds (edge labels).
3. **Runtime** — `RuntimeBuilder` creates one Akka actor per node and one `ActorRef` channel per directed edge, wires them together, and starts the simulation.
4. **Algorithms** — Four distributed algorithms run as plug-in modules on every node, sharing the same message delivery infrastructure.

---

## 2. Architecture and Design Decisions

### 2.1 Algorithm plug-in interface

Algorithms implement a three-hook trait:

```scala
trait DistributedAlgorithm:
  def onStart(ctx: AlgorithmContext): Unit
  def onTick(ctx: AlgorithmContext):  Unit = ()
  def onMessage(ctx: AlgorithmContext, from: Int, kind: String, payload: String): Unit = ()
```

`AlgorithmContext` gives every algorithm its node identity, neighbor set, a `send` function, and a `log` function — nothing Akka-specific leaks into algorithm code. This makes algorithms independently unit-testable without an actor system.

RuntimeBuilder calls each algorithm factory once per node so every actor holds its own independent instance. A single shared instance would cause race conditions between actors.

### 2.2 Two message-sending paths

`NodeActor` maintains two distinct send paths to prevent algorithm control messages from being silently dropped by application-layer label constraints:

| Path | Used by | Edge-label check |
|------|---------|-----------------|
| `sendAlgorithmMsg` | Algorithms via `AlgorithmContext.send` | **Bypassed** — control traffic must always be deliverable |
| `sendAppMsg` | Background traffic generation | **Enforced** — only permitted kinds traverse a given channel |

### 2.3 State machine via `context.become`

`NodeActor` uses Akka's `context.become` to eliminate mutable fields. The actor starts in an uninitialised handler that only accepts `Init`. Once `Init` arrives, it transitions to the `running` handler whose topology state (`neighbors`, `allowedOnEdge`, `pdf`, `inputEnabled`) is captured as immutable `val` parameters — no `var` fields on the class.

This follows the `StateMachineWithActors` pattern from the course PLANE examples.

### 2.4 Config-driven enrichment via `SimConfig`

`GraphEnricher` takes a plain `SimConfig` case class rather than a raw typesafe-config `Config` object. This keeps `sim-core` free of external dependencies and makes enrichment testable without a config file on the classpath. All config parsing is confined to `App.scala` in `sim-cli`.

### 2.5 Edge label enforcement

Each directed edge carries a `Set[String]` of permitted application message kinds (e.g. `PING`, `GOSSIP`, `WORK`). When a node generates background traffic, `sendAppMsg` checks this set before delivering. Messages not in the set are dropped and counted as `appMsgsDropped` in `SimMetrics`. Algorithm control messages (e.g. `HS_PROBE`, `TREE_MSG`, `DS_WORK`) always bypass this check.

### 2.6 PDF sampling and reproducibility

Each node samples message kinds from its configured PDF using inverse-transform (CDF) sampling: the PDF is scanned left-to-right accumulating probabilities; a uniform random draw selects the first bucket whose cumulative sum exceeds the draw. The RNG is seeded with `(sim.seed XOR nodeId)`, making traffic generation deterministic — the same graph and config always produce the same message sequence. Change `sim.seed` in `application.conf` for a different traffic pattern without touching the graph.

### 2.7 Computation initiation

Two mechanisms start computation:

- **Timer nodes** — emit one PDF-sampled message per tick via Akka's `timers.startTimerAtFixedRate`. This is non-blocking: the scheduler sends a `Tick` message to the actor rather than sleeping a thread.
- **Input nodes** — accept `ExternalInput` messages from the CLI driver. File injection mode reads a script of `(delayMs, nodeId, kind, payload)` events and delivers them on a daemon thread. Interactive mode blocks the main thread on `stdin` until `Ctrl+D`.

---

## 3. Distributed Algorithms

### 3.1 Hirschberg-Sinclair Leader Election

**Topology requirement:** bidirectional ring (every node has exactly 2 neighbors).

**Protocol:** Each active node probes CW and CCW with hop count `2^phase`. A probe from a larger origin is relayed; a smaller one is suppressed (the originator becomes passive). When a probe exhausts its hops a reply travels back. When both replies arrive the node advances to the next phase. If a probe circles the entire ring and returns to its sender, that node is the leader and broadcasts `HS_LEADER`.

**Correctness argument:** At each phase the set of active nodes is a subset of those with locally maximum IDs within intervals of size `2^phase`. After `ceil(log2 n)` phases exactly one node remains active — the global maximum. Message complexity is `O(n log n)`.

**Topology validation:** On `onStart` each node checks that it has exactly 2 neighbors. If not, it logs a descriptive error and silences itself, preventing malformed messages from polluting the log.

**Sample output (10-node ring):**
```
[HS] node 5: starting HS — CW=1 CCW=9
[HS] node 5: phase=0 sending probes with hops=1
[HS] node 4: LEADER = 9
```

---

### 3.2 Tree Leader Election

**Topology requirement:** any connected acyclic graph (tree).

**Protocol:** Leaf nodes (degree 1) immediately send `TREE_MSG(ownId)` to their single neighbor. An internal node waits until all-but-one neighbor has reported, then forwards `max(ownId, all received)` to the remaining neighbor. The root (node that hears from every neighbor) broadcasts `TREE_LEADER(maxId)` downward.

**Center-edge tiebreak:** When two adjacent nodes simultaneously forward to each other (a mutual "last neighbor" situation), both would become root. The higher-ID node wins; the lower-ID node waits for the broadcast. State variables `forwarded` and `forwardedTo` detect this case.

**Sample output (10-node tree):**
```
[TREE] node 2: ROOT — elected leader=9, broadcasting
[TREE] node 9: LEADER = 9    ← all 10 nodes converge
```

---

### 3.3 Chandy-Lamport Global Snapshot

**Topology requirement:** any connected graph with FIFO channels (Akka local delivery is FIFO per actor pair).

**Protocol:** Node 0 initiates on its first timer tick by recording its local state (message counts by kind) and flooding `SNAP_MARKER` on all outgoing channels. When a node receives its first marker it records its local state, starts buffering application messages on still-open channels (those are "in transit"), and re-broadcasts the marker. When a marker arrives on every incoming channel the recording closes.

**Consistency argument:** Because channels are FIFO, the marker on channel `(i→j)` arrives after all application messages that `i` sent before taking its snapshot. This guarantees that no application message is double-counted: it either appears in `i`'s recorded outbox or in `j`'s in-transit buffer, never both.

**Sample output (10-node graph, clean snapshot):**
```
[SNAP] node 0: INITIATOR — local state = Map(GOSSIP -> 3)
[SNAP] node 1: recorded local state = Map(GOSSIP -> 3), now recording 2 channel(s)
[SNAP] node 0: SNAPSHOT COMPLETE — local state = Map(GOSSIP -> 3), in-transit = none
```

All 10 nodes completed the snapshot. `in-transit = none` indicates no messages were in-flight at snapshot time — consistent with the light traffic load (one timer node, 1-second tick interval).

---

### 3.4 Dijkstra-Scholten Termination Detection

**Topology requirement:** any connected graph.

**Protocol:** Node 0 initiates on its second timer tick by sending `DS_WORK` to all neighbors with `deficit = neighbors.size`. A non-root node receiving `DS_WORK` for the first time joins the spanning tree (records parent, forwards to children, sets deficit = children count). Leaves send `DS_ACK` immediately. When deficit reaches zero a non-root sends `DS_ACK` to its parent. When the root's deficit reaches zero, termination is declared.

Duplicate `DS_WORK` (node already in tree) is rejected immediately with `DS_ACK` to avoid inflating deficits.

**Correctness argument:** The spanning tree is built dynamically. Every node sends exactly one `DS_ACK` to its parent after all its subtree descendants have acknowledged. The root's deficit therefore reaches zero if and only if every node in the graph has participated — global termination.

**Sample output (10-node graph):**
```
[DS] node 0: ROOT — initiating termination detection, sending DS_WORK to 3 neighbor(s)
[DS] node 6: LEAF — joined tree (parent=0), sending DS_ACK
[DS] node 1: subtree complete, sending DS_ACK to parent 0
[DS] node 0: ROOT — TERMINATION DETECTED, all 3 subtrees have acknowledged
```

---

## 4. Configuration

All simulation parameters are in `sim-cli/src/main/resources/application.conf`. Key settings:

| Setting | Default | Effect |
|---------|---------|--------|
| `sim.graphDirectory` | `"sample-graphs"` | Directory scanned for `.ngs.dot` files |
| `sim.runDurationMs` | `6000` | How long the simulation runs in file-injection mode |
| `sim.seed` | `42` | Global RNG seed; XOR'd with nodeId for per-node independence |
| `sim.traffic.defaultPdf` | 50% PING, 30% GOSSIP, 20% WORK | Background message distribution |
| `sim.initiators.timers` | node 0, every 1000ms | Periodic message source |
| `sim.initiators.inputs` | nodes 1 and 2 | Accept external injection |
| `sim.edgeLabeling.default` | PING, CONTROL, GOSSIP, WORK | Permitted kinds on every edge |
| `sim.injection.mode` | `"file"` | `"file"` or `"interactive"` |

---

## 5. Experiment Results

### Experiment 1 — Small general graph (baseline)

**Graph:** 10 nodes, 18 directed edges, general topology (sample-graphs/tree-10nodes.ngs.dot)  
**Config:** default — 1 timer node (node 0, 1000ms), file injection (2 events), 6s run

**Results:**

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

- **TLE** elected leader = 9 (highest node ID). All 10 nodes agreed.
- **Snapshot** completed on all 10 nodes. `in-transit = none` — snapshot was clean.
- **DS** detected termination after 3 subtrees acknowledged. Spanning tree depth = 3 levels.
- **HS** detected invalid topology (tree, not ring) and silenced itself gracefully — no crash.
- Edge label enforcement: 0 messages dropped — all generated traffic matched allowed kinds.

### Experiment 2 — Faster timer (Snapshot in-transit capture)

**Config change:** `tickEveryMs = 300`, heavier GOSSIP/WORK PDF, 2 timer nodes  
**Expected effect:** Higher message rate increases the probability that application messages are in-flight when the `SNAP_MARKER` wave propagates, producing non-empty `in-transit` captures.

To reproduce:
```hocon
sim.traffic.defaultPdf = [
  { msg = "GOSSIP", p = 0.60 },
  { msg = "WORK",   p = 0.30 },
  { msg = "PING",   p = 0.10 }
]
sim.initiators.timers = [
  { node = 0, tickEveryMs = 300, mode = "pdf" },
  { node = 1, tickEveryMs = 300, mode = "pdf" }
]
```

### Experiment 3 — Larger graph (DS spanning-tree depth)

**Config change:** `statesTotal = 50` in NetGameSim, `tickEveryMs = 1500`  
**Expected effect:** A deeper spanning tree means `DS_ACK` propagates through more hops before the root declares termination. Validates that the deficit counter aggregates correctly across a multi-level tree.

To reproduce: generate a 50-node graph with NetGameSim, set `graphDirectory = "netgamesim/output"`.

---

## 6. Cinnamon Instrumentation

Lightbend Telemetry (Cinnamon 2.21.4) is integrated via the sbt plugin. The agent loads on every run and the Lightbend license validates successfully:

```
[INFO] [Cinnamon] Agent version 2.21.4
[INFO] License check succeeded. License is valid until 2026-05-31.
```

**Known limitation:** Cinnamon 2.21.4 supports Akka 24.10; this project uses Akka Classic 2.8.8. Per-actor throughput and mailbox metrics are unavailable. JVM metrics (heap, GC, threads) and dispatcher metrics are still collected by the agent.

Manual `AtomicLong` counters in `SimMetrics` supplement Cinnamon for message-level telemetry, printed at the end of every run (see Experiment 1 results above).

---

## 7. Testing

31 ScalaTest unit tests across 6 suites, all passing:

| Suite | Tests | What it covers |
|-------|-------|----------------|
| `PdfValidatorSpec` | 5 | PDF validation: sum-to-1, non-negative, tolerance |
| `GraphEnricherSpec` | 6 | Node PDF assignment, timer/input flags, edge labels, per-node overrides |
| `HirschbergSinclairSpec` | 4 | Probe relay, suppression, reply forwarding, leader election |
| `TreeLeaderElectionSpec` | 5 | Leaf initiation, internal forwarding, root election, center-edge tiebreak, leader propagation |
| `SnapshotSpec` | 5 | Initiator fires once, state recording, marker propagation, in-transit capture |
| `TerminationSpec` | 6 | Root initiation timing, leaf ACK, internal forwarding, deficit countdown, duplicate DS_WORK |

Run with: `sbt test`
