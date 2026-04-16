package edu.uic.cs553.sim.runtime

import akka.actor.{Actor, ActorRef, Props, Timers}
import scala.concurrent.duration.*
import scala.util.Random

import edu.uic.cs553.sim.algorithms.DistributedAlgorithm
import edu.uic.cs553.sim.core.AlgorithmContext

object NodeActor:
  def props(id: Int, algorithms: Seq[DistributedAlgorithm], seed: Int = 0): Props =
    Props(new NodeActor(id, algorithms, seed))

  sealed trait Msg

  final case class Init(
    neighbors:     Map[Int, ActorRef],
    allowedOnEdge: Map[Int, Set[String]],
    pdf:           Map[String, Double],
    timerEnabled:  Boolean,
    tickEveryMs:   Int,
    inputEnabled:  Boolean
  ) extends Msg

  final case class Envelope(from: Int, kind: String, payload: String) extends Msg
  final case class ExternalInput(kind: String, payload: String)        extends Msg
  case object Start                                                     extends Msg
  private case object Tick                                              extends Msg

/**
 * Actor that represents a single graph node in the simulation.
 *
 * Message protocol
 * ────────────────
 *   Init          — wires the actor's topology: neighbor ActorRefs, per-edge label sets,
 *                   node PDF, timer flag, and input flag.  Sent once by RuntimeBuilder
 *                   before the simulation starts.
 *   Start         — fires all algorithm onStart callbacks and generates one background
 *                   message.  Sent after every actor has received Init.
 *   Tick          — periodic timer event (only timer-enabled nodes schedule this).
 *                   Drives algorithm onTick callbacks and one PDF-sampled background message.
 *   ExternalInput — message injected from the CLI driver (file injection or test harness).
 *                   Only accepted when the node is marked inputEnabled in SimConfig.
 *   Envelope      — peer-to-peer message from another node, carrying from/kind/payload.
 *                   Triggers algorithm onMessage callbacks on every arrival.
 *
 * Two send paths
 * ──────────────
 *   sendAlgorithmMsg — used by algorithms via AlgorithmContext; bypasses edge-label checks
 *                      so algorithm control messages (HS_PROBE, TREE_MSG, DS_WORK, etc.)
 *                      are never silently dropped by application-layer label constraints.
 *   sendAppMsg       — used for background application traffic; enforces edge labels so only
 *                      permitted message kinds traverse a given channel.
 *
 * Algorithm integration
 * ─────────────────────
 *   Each NodeActor holds a Seq[DistributedAlgorithm] (one instance per registered algorithm,
 *   created fresh per node by RuntimeBuilder).  On every lifecycle event the actor calls the
 *   corresponding hook on every algorithm, passing an AlgorithmContext that bundles the
 *   node's identity, neighbor set, and the sendAlgorithmMsg send path.
 */
class NodeActor(id: Int, algorithms: Seq[DistributedAlgorithm], seed: Int = 0) extends Actor with Timers:
  import NodeActor.*

  // Actor-local mutable state — populated once via the Init message before Start fires.
  // Mutation inside an actor is safe because Akka guarantees single-threaded message
  // delivery per actor; no external synchronization or atomic references are needed.
  private var neighbors:     Map[Int, ActorRef]    = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf:           Map[String, Double]   = Map.empty
  private var inputEnabled:  Boolean               = false

  // XOR of global seed and node id: nodes produce independent sequences that are all
  // reproducible — change sim.seed in application.conf to get a different traffic pattern
  // while keeping the same graph topology.
  private val rng = new Random(seed.toLong ^ id.toLong)

  def receive: Receive =

    case Init(nbrs, allowed, pdf0, timerEnabled, tickEveryMs, inputFlag) =>
      neighbors     = nbrs
      allowedOnEdge = allowed
      pdf           = pdf0
      inputEnabled  = inputFlag
      println(s"Node $id initialized neighbors=${neighbors.keys.toList.sorted}")
      if timerEnabled then
        timers.startTimerAtFixedRate("tick", Tick, tickEveryMs.millis)

    case Start =>
      algorithms.foreach(_.onStart(buildContext()))
      generateAndSendOneMessage()

    case Tick =>
      SimMetrics.recordTimerTick()
      algorithms.foreach(_.onTick(buildContext()))
      generateAndSendOneMessage()

    case ExternalInput(kind, payload) =>
      if inputEnabled then
        SimMetrics.recordExternalInput()
        println(s"Node $id accepted external input kind=$kind")
        sendByKind(kind, payload)
      else
        println(s"Node $id rejected external input (not an input node)")

    case Envelope(from, kind, payload) =>
      SimMetrics.recordReceived()
      println(s"Node $id received $kind from $from payload=$payload")
      algorithms.foreach(_.onMessage(buildContext(), from, kind, payload))

  // ---- context --------------------------------------------------

  /**
   * Builds an AlgorithmContext for the current actor state.
   * Algorithm messages bypass edge-label filtering so control traffic
   * is never blocked by application-layer label constraints.
   */
  private def buildContext(): AlgorithmContext =
    AlgorithmContext(
      nodeId    = id,
      neighbors = neighbors.keySet,
      send      = sendAlgorithmMsg,
      log       = println
    )

  // ---- message sending ------------------------------------------

  /**
   * Sends an algorithm control message directly to a neighbor ref,
   * bypassing edge-label checks (labels constrain application traffic only).
   */
  private def sendAlgorithmMsg(to: Int, kind: String, payload: String): Unit =
    neighbors.get(to) match
      case Some(ref) =>
        SimMetrics.recordAlgorithmMsg()
        ref ! Envelope(id, kind, payload)
      case None =>
        println(s"Node $id: cannot send $kind to unknown neighbor $to")

  /**
   * Sends an application message subject to edge-label constraints.
   */
  private def sendAppMsg(to: Int, kind: String, payload: String): Unit =
    val allowed = allowedOnEdge.getOrElse(to, Set.empty)
    if allowed.contains(kind) then
      neighbors.get(to) match
        case Some(ref) =>
          SimMetrics.recordAppMsgSent()
          ref ! Envelope(id, kind, payload)
        case None => println(s"Node $id: no ref for neighbor $to")
    else
      SimMetrics.recordAppMsgDropped()
      println(s"Node $id: edge to $to does not allow $kind")

  private def generateAndSendOneMessage(): Unit =
    sendByKind(sampleMessageKind(), s"generated-by-$id")

  private def sendByKind(kind: String, payload: String): Unit =
    neighbors.keys
      .filter(to => allowedOnEdge.getOrElse(to, Set.empty).contains(kind))
      .toList.sorted
      .headOption
      .foreach(sendAppMsg(_, kind, payload))

  /**
   * Samples one message kind from the node's PDF using inverse-transform (CDF) sampling.
   *
   * The PDF is scanned left-to-right with a running cumulative sum.  A uniform random
   * draw r ∈ [0,1) selects the first bucket whose cumulative probability exceeds r,
   * which correctly weights selection by each entry's probability mass.
   *
   * The RNG is seeded with the node id (see constructor), so traffic generation is
   * deterministic: re-running with the same graph and config always reproduces the
   * same message sequence per node.
   */
  private def sampleMessageKind(): String =
    if pdf.isEmpty then "PING"
    else
      val r = rng.nextDouble()
      pdf.toList
        .scanLeft("" -> 0.0) { case ((_, acc), (kind, p)) => kind -> (acc + p) }
        .tail
        .collectFirst { case (kind, cumulative) if r <= cumulative => kind }
        .getOrElse(pdf.keys.headOption.getOrElse("PING"))
