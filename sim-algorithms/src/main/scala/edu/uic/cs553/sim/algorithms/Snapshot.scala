package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext

/**
 * Chandy-Lamport Global Snapshot Algorithm.
 *
 * Records a consistent global state of the distributed system without
 * stopping the computation.  Each node records its own local state and
 * the messages in transit on every incoming channel.
 *
 * How it works:
 *   1. The initiator (node 0) records its local state on the first timer
 *      tick, then sends a SNAP_MARKER on every outgoing channel.
 *   2. When a node receives SNAP_MARKER for the first time:
 *      a. It records its local state (message counts by kind received so far).
 *      b. It starts recording in-transit messages on all channels it has
 *         NOT yet received a marker from (those may still carry pre-snapshot msgs).
 *      c. It forwards SNAP_MARKER on all its outgoing channels.
 *   3. When a node receives SNAP_MARKER on a channel it is currently recording,
 *      it stops recording that channel — any messages received on that channel
 *      after the local state was taken but before this marker are "in transit".
 *   4. When all channels have delivered their markers the snapshot is complete
 *      and the node logs its local state plus any captured in-transit messages.
 *
 * Message encoding
 *   SNAP_MARKER : "<initiatorId>"  — propagates the snapshot wave
 *
 * Assumption: channels are FIFO (Akka local actor delivery is FIFO per pair).
 */
final class Snapshot extends DistributedAlgorithm:

  override def name: String = "chandy-lamport-snapshot"

  // ── per-node state ────────────────────────────────────────────────────────
  // Each NodeActor creates its own Snapshot instance (via factory), so these
  // vars are actor-local and mutated only during single-threaded message
  // delivery — no synchronization required.  See DistributedAlgorithm for rationale.
  private var initiated:        Boolean              = false
  private var recorded:         Boolean              = false

  /** Running count of application messages received before the snapshot. */
  private var msgCounts:        Map[String, Int]     = Map.empty

  /** The local state captured at snapshot time. */
  private var localState:       Map[String, Int]     = Map.empty

  /**
   * Channels still being recorded.
   * A channel is removed when its SNAP_MARKER arrives, indicating no more
   * pre-snapshot messages will come from that direction.
   */
  private var recordingChannels: Set[Int]            = Set.empty

  /**
   * Application messages received per channel after local state was taken,
   * up until the SNAP_MARKER arrived on that channel.
   * These are the "in-transit" messages for the global snapshot.
   */
  private var inTransit:        Map[Int, List[String]] = Map.empty

  // Message kinds that belong to distributed algorithms — not application traffic.
  private val algoKinds: Set[String] = Set(
    "SNAP_MARKER",
    "HS_PROBE", "HS_REPLY", "HS_LEADER",
    "TREE_MSG", "TREE_LEADER",
    "DS_WORK", "DS_ACK"
  )

  // ── lifecycle ─────────────────────────────────────────────────────────────

  override def onTick(ctx: AlgorithmContext): Unit =
    // Node 0 initiates the snapshot on its very first timer tick so that
    // some application traffic has already flowed through the system.
    if !initiated && ctx.nodeId == 0 then
      initiated = true
      initiateSnapshot(ctx)

  override def onMessage(
    ctx:     AlgorithmContext,
    from:    Int,
    kind:    String,
    payload: String
  ): Unit =
    kind match
      case "SNAP_MARKER" =>
        handleMarker(ctx, from)

      case appKind if !algoKinds.contains(appKind) =>
        // Count every application message received.
        msgCounts = msgCounts.updated(appKind, msgCounts.getOrElse(appKind, 0) + 1)
        // If this channel is still being recorded, capture the message as in-transit.
        if recordingChannels.contains(from) then
          inTransit = inTransit.updated(from, inTransit.getOrElse(from, Nil) :+ appKind)

      case _ => () // ignore other algorithm messages

  // ── handlers ─────────────────────────────────────────────────────────────

  private def initiateSnapshot(ctx: AlgorithmContext): Unit =
    recorded          = true
    localState        = msgCounts
    recordingChannels = ctx.neighbors
    inTransit         = ctx.neighbors.map(_ -> List.empty[String]).toMap
    ctx.log(s"[SNAP] node ${ctx.nodeId}: INITIATOR — local state = $localState")
    ctx.broadcast("SNAP_MARKER", ctx.nodeId.toString)

  private def handleMarker(ctx: AlgorithmContext, from: Int): Unit =
    if !recorded then
      // First marker received — take local state snapshot.
      recorded          = true
      localState        = msgCounts
      // All channels except the one the marker arrived on are still open.
      recordingChannels = ctx.neighbors - from
      inTransit         = (ctx.neighbors - from).map(_ -> List.empty[String]).toMap
      ctx.log(
        s"[SNAP] node ${ctx.nodeId}: recorded local state = $localState, " +
        s"now recording ${recordingChannels.size} channel(s)"
      )
      // Propagate the marker on all outgoing channels (including back to `from`,
      // so that node can close its recording on this channel too).
      ctx.broadcast("SNAP_MARKER", ctx.nodeId.toString)

    // Close the recording for the channel the marker just arrived on.
    recordingChannels = recordingChannels - from

    if recorded && recordingChannels.isEmpty then
      val nonEmpty = inTransit.filter(_._2.nonEmpty)
      val inTransitStr = if nonEmpty.isEmpty then "none" else nonEmpty.toString
      ctx.log(
        s"[SNAP] node ${ctx.nodeId}: SNAPSHOT COMPLETE — " +
        s"local state = $localState, in-transit = $inTransitStr"
      )
