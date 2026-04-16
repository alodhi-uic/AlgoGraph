package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext

/**
 * Leader election for trees (works on any spanning tree / tree topology).
 *
 * How it works:
 *   1. Leaf nodes (degree 1) immediately send TREE_MSG(own id) to their single neighbor.
 *   2. An internal node waits until it has received TREE_MSG from all-but-one neighbor,
 *      then forwards max(own id, all received ids) to the remaining neighbor.
 *   3. The root is the node that eventually hears from ALL neighbors.
 *      It elects the maximum id seen as the leader and broadcasts TREE_LEADER.
 *   4. Every node that receives TREE_LEADER records the result and
 *      forwards it to all neighbors except the sender.
 *
 * Message encoding
 *   TREE_MSG    : "<maxId>"     — candidate id traveling up toward the root
 *   TREE_LEADER : "<leaderId>"  — elected leader id propagating downward
 */
final class TreeLeaderElection extends DistributedAlgorithm:

  override def name: String = "tree-leader-election"

  // ---- per-node state -------------------------------------------
  // Each NodeActor creates its own TreeLeaderElection instance (via factory), so
  // these vars are actor-local and mutated only during single-threaded message
  // delivery — no synchronization required.  See DistributedAlgorithm for rationale.
  // DistributedAlgorithm hooks return Unit so state must persist in instance fields.
  private var maxIdSeen: Int         = -1          // running max of all candidate IDs seen so far
  private var receivedFrom: Set[Int] = Set.empty   // neighbors that have sent TREE_MSG to us
  private var leader: Option[Int]    = None        // set once when TREE_LEADER arrives or we become root
  private var forwarded: Boolean     = false       // true after we send TREE_MSG upstream
  private var forwardedTo: Int       = -1          // the neighbor we forwarded to (center-edge tiebreak)

  // ---- lifecycle ------------------------------------------------

  override def onStart(ctx: AlgorithmContext): Unit =
    maxIdSeen = ctx.nodeId
    ctx.log(s"[TREE] node ${ctx.nodeId}: start neighbors=${ctx.neighbors.toList.sorted}")

    if ctx.neighbors.size == 1 then
      // Leaf: nothing left to wait for, send immediately
      val parent = ctx.neighbors.head
      forwarded   = true
      forwardedTo = parent
      ctx.log(s"[TREE] node ${ctx.nodeId}: leaf → sending TREE_MSG($maxIdSeen) to $parent")
      ctx.send(parent, "TREE_MSG", maxIdSeen.toString)

  override def onMessage(ctx: AlgorithmContext, from: Int, kind: String, payload: String): Unit =
    kind match
      case "TREE_MSG"    => handleMsg(ctx, from, payload)
      case "TREE_LEADER" => handleLeader(ctx, from, payload)
      case _             => ()

  // ---- handlers -------------------------------------------------

  private def handleMsg(ctx: AlgorithmContext, from: Int, payload: String): Unit =
    payload.toIntOption match
      case None =>
        ctx.log(s"[TREE] node ${ctx.nodeId}: malformed TREE_MSG payload=$payload")

      case Some(candidateId) =>
        receivedFrom = receivedFrom + from
        maxIdSeen    = maxIdSeen.max(candidateId)
        ctx.log(s"[TREE] node ${ctx.nodeId}: TREE_MSG from=$from candidate=$candidateId maxSeen=$maxIdSeen")

        val remaining = ctx.neighbors -- receivedFrom

        if remaining.isEmpty then
          // Heard from every neighbor — this node is a root candidate.
          // Center-edge case: if we already forwarded to `from`, both endpoints of that
          // edge raced to forward to each other.  Break symmetry with node ID: only the
          // higher-ID node becomes ROOT; the lower-ID node waits for the TREE_LEADER.
          val isCenterEdge = forwarded && forwardedTo == from
          if !isCenterEdge || ctx.nodeId > from then
            if leader.isEmpty then
              leader = Some(maxIdSeen)
              ctx.log(s"[TREE] node ${ctx.nodeId}: ROOT — elected leader=$maxIdSeen, broadcasting")
              ctx.broadcast("TREE_LEADER", maxIdSeen.toString)

        else if remaining.size == 1 then
          // Heard from all but one → forward max id upstream
          val next = remaining.head
          forwarded   = true
          forwardedTo = next
          ctx.log(s"[TREE] node ${ctx.nodeId}: forwarding TREE_MSG($maxIdSeen) to $next")
          ctx.send(next, "TREE_MSG", maxIdSeen.toString)

  private def handleLeader(ctx: AlgorithmContext, from: Int, payload: String): Unit =
    payload.toIntOption match
      case Some(leaderId) if leader.isEmpty =>
        leader = Some(leaderId)
        ctx.log(s"[TREE] node ${ctx.nodeId}: LEADER = $leaderId")
        // Propagate to all neighbors except the one that sent it
        ctx.broadcastExcept(from, "TREE_LEADER", payload)
      case _ => ()
