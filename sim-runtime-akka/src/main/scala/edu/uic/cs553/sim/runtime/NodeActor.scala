package edu.uic.cs553.sim.runtime

import akka.actor.{Actor, ActorRef, Props}
import scala.util.Random

object NodeActor:
  def props(id: Int): Props = Props(new NodeActor(id))

  sealed trait Msg

  final case class Init(
    neighbors: Map[Int, ActorRef],
    allowedOnEdge: Map[Int, Set[String]],
    pdf: Map[String, Double]
  ) extends Msg

  final case class Envelope(
    from: Int,
    kind: String,
    payload: String
  ) extends Msg

  case object Start extends Msg

class NodeActor(id: Int) extends Actor:
  import NodeActor.*

  private var neighbors: Map[Int, ActorRef] = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf: Map[String, Double] = Map.empty

  private val rng = new Random(id)

  def receive: Receive =
    case Init(nbrs, allowed, pdf0) =>
      neighbors = nbrs
      allowedOnEdge = allowed
      pdf = pdf0
      println(s"Node $id initialized with neighbors: ${neighbors.keys.toList.sorted}")
      println(s"Node $id PDF: $pdf")

    case Start =>
      val kind = sampleMessageKind()
      val payload = s"generated-by-$id"

      val eligibleNeighbors =
        neighbors.keys
          .filter(to => allowedOnEdge.getOrElse(to, Set.empty).contains(kind))
          .toList
          .sorted

      eligibleNeighbors.headOption match
        case Some(to) =>
          neighbors(to) ! Envelope(id, kind, payload)
          println(s"Node $id sampled $kind and sent it to $to")
        case None =>
          println(s"Node $id sampled $kind but no edge allows it")

    case Envelope(from, kind, payload) =>
      println(s"Node $id received $kind from $from with payload: $payload")

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