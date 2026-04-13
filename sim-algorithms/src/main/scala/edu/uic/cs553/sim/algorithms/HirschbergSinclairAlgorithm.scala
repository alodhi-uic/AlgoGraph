package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext

/**
 * Hirschberg-Sinclair leader election for a bidirectional ring.
 *
 * Each active node probes in both directions (CW and CCW) with hop count 2^phase.
 * A probe with a larger origin id is forwarded; a smaller one is suppressed.
 * When a probe exhausts its hops, a reply travels back to the origin.
 * When both replies arrive, the node advances to the next phase.
 * If a probe circles the entire ring and returns to its sender, that sender is the leader.
 *
 * Message encoding
 *   HS_PROBE  : "originId|phase|direction|hopsLeft"   direction = CW | CCW
 *   HS_REPLY  : "originId|phase|direction"             direction = direction the reply is traveling
 *   HS_LEADER : "leaderId"
 */
final class HirschbergSinclairAlgorithm extends DistributedAlgorithm:

  override def name: String = "hirschberg-sinclair"

  // ---- per-node state (safe: each actor gets its own instance) ----
  private var active: Boolean       = true
  private var phase: Int            = 0
  private var cwReplied: Boolean    = false
  private var ccwReplied: Boolean   = false
  private var leader: Option[Int]   = None

  // Set to false if topology validation fails — all handlers short-circuit
  private var valid: Boolean = true

  // Ring neighbors resolved from ctx on first use
  private var cw: Int  = -1
  private var ccw: Int = -1

  // ---- lifecycle ------------------------------------------------

  override def onStart(ctx: AlgorithmContext): Unit =
    if !validateTopology(ctx) then () // error already logged
    else
      resolveNeighbors(ctx)
      ctx.log(s"[HS] node ${ctx.nodeId}: starting HS — CW=$cw CCW=$ccw")
      sendProbes(ctx)

  override def onMessage(ctx: AlgorithmContext, from: Int, kind: String, payload: String): Unit =
    if !valid then
      ctx.log(s"[HS] node ${ctx.nodeId}: ignoring message — topology is not a valid ring")
    else
      if cw == -1 then resolveNeighbors(ctx)
      kind match
        case "HS_PROBE"  => handleProbe(ctx, payload)
        case "HS_REPLY"  => handleReply(ctx, payload)
        case "HS_LEADER" => handleLeader(ctx, from, payload)
        case _           => ()

  // ---- topology validation --------------------------------------

  /**
   * Validates that this node looks like a valid HS ring participant.
   * A ring node must have exactly 2 neighbors — one CW, one CCW.
   *
   * This is a local check only (the algorithm sees only its own neighbors).
   * It catches the most common mistakes: chains, trees, stars, and isolated nodes.
   * It cannot detect broken cycles or disconnected components from a single node's view.
   */
  private def validateTopology(ctx: AlgorithmContext): Boolean =
    val n = ctx.neighbors.size
    if n != 2 then
      valid = false
      val reason = n match
        case 0 => "node is isolated (no neighbors) — HS requires a bidirectional ring"
        case 1 => "node has only 1 neighbor — this looks like a chain or tree, not a ring"
        case _ => s"node has $n neighbors — a ring node must have exactly 2 (one CW, one CCW)"
      ctx.log(s"[HS] ERROR node ${ctx.nodeId}: invalid topology — $reason")
      ctx.log(s"[HS] ERROR node ${ctx.nodeId}: generate a ring graph using topology = hirschbergsinclair in NetGameSim")
      false
    else
      true

  // ---- neighbor resolution --------------------------------------

  /**
   * Determines CW and CCW neighbors from the two ring neighbors.
   *
   * For a ring labeled 0..n-1, the CW neighbor of node i is always (i+1) % n.
   * We check directly: if neighbors contains nodeId+1 that is CW, otherwise
   * the ring has wrapped and CW is the smallest neighbor (which must be 0).
   */
  private def resolveNeighbors(ctx: AlgorithmContext): Unit =
    if cw == -1 && ctx.neighbors.size >= 2 then
      cw  = if ctx.neighbors.contains(ctx.nodeId + 1) then ctx.nodeId + 1
            else ctx.neighbors.min
      ccw = ctx.neighbors.find(_ != cw).get
      ctx.log(s"[HS] node ${ctx.nodeId}: resolved ring neighbors CW=$cw CCW=$ccw")

  // ---- probe / reply / leader -----------------------------------

  private def sendProbes(ctx: AlgorithmContext): Unit =
    val hops = hopCount(phase)
    ctx.log(s"[HS] node ${ctx.nodeId}: phase=$phase sending probes with hops=$hops")
    ctx.send(cw,  "HS_PROBE", encodeProbe(ctx.nodeId, phase, "CW",  hops))
    ctx.send(ccw, "HS_PROBE", encodeProbe(ctx.nodeId, phase, "CCW", hops))

  private def handleProbe(ctx: AlgorithmContext, payload: String): Unit =
    decodeProbe(payload) match
      case None =>
        ctx.log(s"[HS] node ${ctx.nodeId}: malformed HS_PROBE payload=$payload")

      case Some((originId, probePhase, dir, hopsLeft)) =>
        ctx.log(s"[HS] node ${ctx.nodeId}: PROBE origin=$originId phase=$probePhase dir=$dir hopsLeft=$hopsLeft")

        if originId == ctx.nodeId then
          // Probe circled the entire ring without suppression → this node is the leader.
          // Guard against duplicate: both CW and CCW probes can wrap around independently.
          if leader.isEmpty then
            ctx.log(s"[HS] node ${ctx.nodeId}: own probe returned — I am the LEADER")
            becomeLeader(ctx)
          else
            ctx.log(s"[HS] node ${ctx.nodeId}: own probe returned again (duplicate wrap), already leader")

        else if originId > ctx.nodeId then
          // Larger id: relay or turn into reply
          active = false
          if hopsLeft > 1 then
            val next = if dir == "CW" then cw else ccw
            ctx.send(next, "HS_PROBE", encodeProbe(originId, probePhase, dir, hopsLeft - 1))
          else
            // Hops exhausted: send reply in the opposite direction toward origin
            val replyDir  = opposite(dir)
            val replyNext = if replyDir == "CW" then cw else ccw
            ctx.send(replyNext, "HS_REPLY", encodeReply(originId, probePhase, replyDir))

        // originId < ctx.nodeId → suppress (do nothing)

  private def handleReply(ctx: AlgorithmContext, payload: String): Unit =
    decodeReply(payload) match
      case None =>
        ctx.log(s"[HS] node ${ctx.nodeId}: malformed HS_REPLY payload=$payload")

      case Some((originId, replyPhase, replyDir)) =>
        ctx.log(s"[HS] node ${ctx.nodeId}: REPLY origin=$originId phase=$replyPhase dir=$replyDir")

        if originId == ctx.nodeId then
          // Our own reply returned
          if replyDir == "CW" then cwReplied   = true
          else                     ccwReplied  = true

          if cwReplied && ccwReplied then
            ctx.log(s"[HS] node ${ctx.nodeId}: both replies received for phase $replyPhase → advancing")
            phase      = replyPhase + 1
            cwReplied  = false
            ccwReplied = false
            sendProbes(ctx)

        else
          // Not ours: forward toward origin
          val next = if replyDir == "CW" then cw else ccw
          ctx.send(next, "HS_REPLY", encodeReply(originId, replyPhase, replyDir))

  private def handleLeader(ctx: AlgorithmContext, from: Int, payload: String): Unit =
    payload.toIntOption match
      case Some(leaderId) if leader.isEmpty =>
        leader = Some(leaderId)
        ctx.log(s"[HS] node ${ctx.nodeId}: LEADER = $leaderId")
        // Forward to the neighbor we did not receive it from
        val next = if from == cw then ccw else cw
        ctx.send(next, "HS_LEADER", payload)
      case _ => ()

  private def becomeLeader(ctx: AlgorithmContext): Unit =
    leader = Some(ctx.nodeId)
    active = false
    ctx.log(s"[HS] node ${ctx.nodeId}: broadcasting LEADER = ${ctx.nodeId}")
    // Send in one direction only — handleLeader propagates it around the ring
    ctx.send(cw, "HS_LEADER", ctx.nodeId.toString)

  // ---- helpers --------------------------------------------------

  private def hopCount(p: Int): Int = math.pow(2, p.toDouble).toInt

  private def opposite(dir: String): String = if dir == "CW" then "CCW" else "CW"

  private def encodeProbe(originId: Int, phase: Int, dir: String, hops: Int): String =
    s"$originId|$phase|$dir|$hops"

  private def encodeReply(originId: Int, phase: Int, dir: String): String =
    s"$originId|$phase|$dir"

  private def decodeProbe(payload: String): Option[(Int, Int, String, Int)] =
    payload.split("\\|").toList match
      case o :: p :: d :: h :: Nil =>
        for o2 <- o.toIntOption; p2 <- p.toIntOption; h2 <- h.toIntOption
        yield (o2, p2, d, h2)
      case _ => None

  private def decodeReply(payload: String): Option[(Int, Int, String)] =
    payload.split("\\|").toList match
      case o :: p :: d :: Nil =>
        for o2 <- o.toIntOption; p2 <- p.toIntOption
        yield (o2, p2, d)
      case _ => None
