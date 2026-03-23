package edu.uic.cs553

import edu.uic.cs553.sim.core.io.DotGraphLoader
import edu.uic.cs553.sim.core.enrich.GraphEnricher
import edu.uic.cs553.sim.runtime.RuntimeBuilder

object App:
  def main(args: Array[String]): Unit =
    val graph = DotGraphLoader.load("netgamesim/output/quickrun.ngs.ngs.dot")
    val enrichedGraph = GraphEnricher.enrich(graph)

    println(s"Nodes: ${graph.nodes.size}")
    println(s"Edges: ${graph.edges.size}")

    RuntimeBuilder.run(enrichedGraph)