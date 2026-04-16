package edu.uic.cs553.sim.runtime

import akka.actor.{ActorRef, ActorSystem}
import edu.uic.cs553.sim.algorithms.DistributedAlgorithm
import edu.uic.cs553.sim.core.models.EnrichedGraph

object RuntimeBuilder:

  final case class RunningSimulation(
    system:   ActorSystem,
    nodeRefs: Map[Int, ActorRef]
  )

  /**
   * Builds an ActorSystem from an enriched graph and a set of algorithm factories.
   *
   * Each factory is called once per node so every node actor gets its own
   * fresh algorithm instance with independent state.
   *
   * @param graph              the enriched graph (nodes + labeled edges + PDFs)
   * @param algorithmFactories one factory per algorithm; called once per node
   * @param seed               global random seed from config; each node uses (seed XOR nodeId)
   *                           so nodes produce independent but reproducible message sequences
   */
  def run(
    graph:              EnrichedGraph,
    algorithmFactories: Seq[() => DistributedAlgorithm],
    seed:               Int = 0
  ): RunningSimulation =

    val system = ActorSystem("sim")

    // Create one actor per node; each gets its own algorithm instances
    val nodeRefs: Map[Int, ActorRef] =
      graph.nodes.map { node =>
        val algorithms = algorithmFactories.map(_())
        node.id -> system.actorOf(
          NodeActor.props(node.id, algorithms, seed),
          s"node-${node.id}"
        )
      }.toMap

    // Wire up neighbors and send Init to each actor
    graph.nodes.foreach { node =>
      val outgoing = graph.edges.filter(_.from == node.id)

      val neighbors: Map[Int, ActorRef] =
        outgoing.map(e => e.to -> nodeRefs(e.to)).toMap

      val allowedOnEdge: Map[Int, Set[String]] =
        outgoing.map(e => e.to -> e.allowedMessages).toMap

      nodeRefs(node.id) ! NodeActor.Init(
        neighbors     = neighbors,
        allowedOnEdge = allowedOnEdge,
        pdf           = node.pdf.probabilities,
        timerEnabled  = node.timerEnabled,
        tickEveryMs   = node.tickEveryMs,
        inputEnabled  = node.inputEnabled
      )
    }

    // Kick off the simulation — every node starts so all run onStart simultaneously
    nodeRefs.values.foreach(_ ! NodeActor.Start)

    RunningSimulation(system = system, nodeRefs = nodeRefs)
