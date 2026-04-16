package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext

/**
 * Dijkstra-Scholten Termination Detection Algorithm.
 *
 * Detects when a distributed computation has terminated — i.e., every process
 * is passive and no messages are in transit.  The algorithm uses a dynamically
 * built spanning tree to collect acknowledgements from all participants.
 *
 * How it works:
 *   1. The root (node 0) initiates on its second timer tick by sending DS_WORK
 *      to all its neighbors and setting deficit = number of neighbors.
 *   2. A non-root node receiving DS_WORK for the first time:
 *      a. Joins the spanning tree — records the sender as its parent.
 *      b. Forwards DS_WORK to all other neighbors (its children in the tree).
 *      c. If it has no children (leaf) it sends DS_ACK to its parent immediately.
 *      d. Otherwise it waits until deficit (children count) drops to zero.
 *   3. A node receiving DS_WORK after it has already joined sends DS_ACK
 *      immediately (the extra message is an excess signal, not a new branch).
 *   4. On receiving DS_ACK:  deficit -= 1.
 *      - Non-root with deficit == 0: send DS_ACK to parent — subtree is done.
 *      - Root with deficit == 0: declare TERMINATION DETECTED.
 *
 * Message encoding
 *   DS_WORK : "compute"  — propagates work / detection wave down the tree
 *   DS_ACK  : "done"     — acknowledgement traveling back up the tree
 */
final class Termination extends DistributedAlgorithm:

  override def name: String = "dijkstra-scholten-termination"

  // ── per-node state ────────────────────────────────────────────────────────
  // Each NodeActor creates its own Termination instance (via factory), so these
  // vars are actor-local and mutated only during single-threaded message
  // delivery — no synchronization required.  See DistributedAlgorithm for rationale.

  private var initiated:  Boolean      = false
  private var tickCount:  Int          = 0

  /** The parent in the spanning tree; None means this is the root or not yet joined. */
  private var parent:     Option[Int]  = None

  /**
   * deficit = DS_WORK messages sent to children - DS_ACK messages received.
   * When deficit reaches zero the node's entire subtree has acknowledged.
   */
  private var deficit:    Int          = 0

  private var terminated: Boolean      = false

  // ── lifecycle ─────────────────────────────────────────────────────────────

  override def onTick(ctx: AlgorithmContext): Unit =
    tickCount += 1
    // Root initiates on the second tick — after the snapshot wave has settled.
    if !initiated && tickCount == 2 && ctx.nodeId == 0 then
      initiated = true
      deficit   = ctx.neighbors.size
      ctx.log(
        s"[DS] node ${ctx.nodeId}: ROOT — initiating termination detection, " +
        s"sending DS_WORK to ${ctx.neighbors.size} neighbor(s)"
      )
      ctx.broadcast("DS_WORK", "compute")

  override def onMessage(
    ctx:     AlgorithmContext,
    from:    Int,
    kind:    String,
    payload: String
  ): Unit =
    kind match
      case "DS_WORK" => handleWork(ctx, from)
      case "DS_ACK"  => handleAck(ctx)
      case _         => ()

  // ── handlers ─────────────────────────────────────────────────────────────

  /**
   * Handles an incoming DS_WORK message.
   *
   * If this node has not yet joined the spanning tree it becomes an active
   * participant: it records its parent and propagates work to its children.
   * If already in the tree (or this is the root) it sends DS_ACK immediately
   * to reject the duplicate branch.
   */
  private def handleWork(ctx: AlgorithmContext, from: Int): Unit =
    if ctx.nodeId != 0 && parent.isEmpty then
      // First DS_WORK — join the spanning tree
      parent = Some(from)
      val children = ctx.neighbors - from
      deficit = children.size

      if children.isEmpty then
        // Leaf node — nothing to propagate, acknowledge immediately
        ctx.log(s"[DS] node ${ctx.nodeId}: LEAF — joined tree (parent=$from), sending DS_ACK")
        ctx.send(from, "DS_ACK", "done")
      else
        ctx.log(
          s"[DS] node ${ctx.nodeId}: joined tree (parent=$from), " +
          s"forwarding DS_WORK to ${children.size} child(ren): ${children.toList.sorted}"
        )
        children.foreach(ctx.send(_, "DS_WORK", "compute"))
    else
      // Already in tree or this is the root — reject the extra branch
      ctx.send(from, "DS_ACK", "done")

  /**
   * Handles an incoming DS_ACK.
   *
   * Decrements the deficit.  When the deficit reaches zero this node's entire
   * subtree has finished: a non-root propagates DS_ACK to its parent, and the
   * root declares that termination has been detected.
   */
  private def handleAck(ctx: AlgorithmContext): Unit =
    deficit -= 1
    if deficit == 0 then
      parent match
        case None =>
          // Root: all branches have acknowledged — computation has terminated
          if !terminated then
            terminated = true
            ctx.log(
              s"[DS] node ${ctx.nodeId}: ROOT — TERMINATION DETECTED, " +
              s"all ${ctx.neighbors.size} subtrees have acknowledged"
            )
        case Some(p) =>
          // Non-root: subtree is done, propagate the acknowledgement upward
          ctx.log(s"[DS] node ${ctx.nodeId}: subtree complete, sending DS_ACK to parent $p")
          ctx.send(p, "DS_ACK", "done")
