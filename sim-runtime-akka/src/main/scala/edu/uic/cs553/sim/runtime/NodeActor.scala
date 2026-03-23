package edu.uic.cs553.sim.runtime

import akka.actor.{Actor, ActorRef, Props, Timers}
import scala.concurrent.duration.*
import scala.util.Random

object NodeActor:
  def props(id: Int): Props = Props(new NodeActor(id))

  sealed trait Msg

  final case class Init(
    neighbors: Map[Int, ActorRef],
    allowedOnEdge: Map[Int, Set[String]],
    pdf: Map[String, Double],
    timerEnabled: Boolean,
    tickEveryMs: Int,
    inputEnabled: Boolean
  ) extends Msg

  final case class Envelope(
    from: Int,
    kind: String,
    payload: String
  ) extends Msg

  final case class ExternalInput(
    kind: String,
    payload: String
  ) extends Msg

  case object Start extends Msg
  private case object Tick extends Msg

class NodeActor(id: Int) extends Actor with Timers:
  import NodeActor.*

  private var neighbors: Map[Int, ActorRef] = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf: Map[String, Double] = Map.empty
  private var inputEnabled: Boolean = false

  private val rng = new Random(id)

  def receive: Receive =
    case Init(nbrs, allowed, pdf0, timerEnabled, tickEveryMs, inputFlag) =>
      neighbors = nbrs
      allowedOnEdge = allowed
      pdf = pdf0
      inputEnabled = inputFlag

      println(s"Node $id initialized with neighbors: ${neighbors.keys.toList.sorted}")

      if timerEnabled then
        timers.startTimerAtFixedRate("tick", Tick, tickEveryMs.millis)
        println(s"Node $id started timer with interval ${tickEveryMs}ms")

      if inputEnabled then
        println(s"Node $id is configured as an input node")

    case Start =>
      generateAndSendOneMessage()

    case Tick =>
      generateAndSendOneMessage()

    case ExternalInput(kind, payload) =>
      if inputEnabled then
        println(s"Node $id accepted external input: $kind, payload=$payload")
        sendInjectedMessage(kind, payload)
      else
        println(s"Node $id rejected external input because it is not an input node")

    case Envelope(from, kind, payload) =>
      println(s"Node $id received $kind from $from with payload: $payload")

  private def generateAndSendOneMessage(): Unit =
    val kind = sampleMessageKind()
    val payload = s"generated-by-$id"
    sendByKind(kind, payload)

  private def sendInjectedMessage(kind: String, payload: String): Unit =
    sendByKind(kind, payload)

  private def sendByKind(kind: String, payload: String): Unit =
    val eligibleNeighbors =
      neighbors.keys
        .filter(to => allowedOnEdge.getOrElse(to, Set.empty).contains(kind))
        .toList
        .sorted

    eligibleNeighbors.headOption match
      case Some(to) =>
        neighbors(to) ! Envelope(id, kind, payload)
        println(s"Node $id sent $kind to $to")
      case None =>
        println(s"Node $id could not send $kind because no edge allows it")

  private def sampleMessageKind(): String =
    if pdf.isEmpty then
      "PING"
    else
      val r = rng.nextDouble()

      pdf.toList
        .scanLeft("" -> 0.0) { case ((_, acc), (kind, p)) =>
          kind -> (acc + p)
        }
        .tail
        .collectFirst { case (kind, cumulative) if r <= cumulative => kind }
        .getOrElse(pdf.keys.headOption.getOrElse("PING"))