package edu.uic.cs553

import scala.concurrent.Await
import scala.concurrent.duration.*

import edu.uic.cs553.sim.algorithms.{HirschbergSinclairAlgorithm, TreeLeaderElection}
import edu.uic.cs553.sim.core.enrich.GraphEnricher
import edu.uic.cs553.sim.core.io.DotGraphLoader
import edu.uic.cs553.sim.runtime.NodeActor
import edu.uic.cs553.sim.runtime.RuntimeBuilder

object App:
  def main(args: Array[String]): Unit =
    val graph = DotGraphLoader.load("/Users/apoorvlodhi/Distributed/NetGameSim/outputs/NetGraph_12-04-26-16-45-47.ngs.dot")
    val enrichedGraph = GraphEnricher.enrich(graph)

    println(s"Nodes: ${graph.nodes.size}")
    println(s"Edges: ${graph.edges.size}")

    val algorithmFactories: Seq[() => edu.uic.cs553.sim.algorithms.DistributedAlgorithm] = Seq(
      () => new HirschbergSinclairAlgorithm,
      () => new TreeLeaderElection
    )

    val running = RuntimeBuilder.run(enrichedGraph, algorithmFactories)

    Thread.sleep(1500)

    running.nodeRefs.get(1).foreach { ref =>
      ref ! NodeActor.ExternalInput(
        kind = "WORK",
        payload = "external-job-1"
      )
    }

    Thread.sleep(4000)

    Await.result(running.system.terminate(), 5.seconds)