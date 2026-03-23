package edu.uic.cs553.sim.runtime

import akka.actor.ActorRef

final case class NodeContext(
  nodeId: Int,
  neighbors: Map[Int, ActorRef],
  allowedOnEdge: Map[Int, Set[String]],
  send: (Int, String, String) => Unit,
  log: String => Unit
)