package edu.uic.cs553.sim.runtime

import akka.actor.{ActorRef, ActorSystem}
import edu.uic.cs553.sim.core.models.EnrichedGraph

object RuntimeBuilder:

  def run(graph: EnrichedGraph): Unit =
    val system = ActorSystem("sim")

    val nodeRefs: Map[Int, ActorRef] =
      graph.nodes.map { node =>
        node.id -> system.actorOf(NodeActor.props(node.id), s"node-${node.id}")
      }.toMap

    graph.nodes.foreach { node =>
      val outgoing =
        graph.edges.filter(_.from == node.id)

      val neighbors: Map[Int, ActorRef] =
        outgoing.map { edge =>
          edge.to -> nodeRefs(edge.to)
        }.toMap

      val allowedOnEdge: Map[Int, Set[String]] =
        outgoing.map { edge =>
          edge.to -> edge.allowedMessages
        }.toMap

      val pdf: Map[String, Double] =
        node.pdf.probabilities

      nodeRefs(node.id) ! NodeActor.Init(
        neighbors = neighbors,
        allowedOnEdge = allowedOnEdge,
        pdf = pdf
      )
    }

    nodeRefs.get(0).foreach(_ ! NodeActor.Start)