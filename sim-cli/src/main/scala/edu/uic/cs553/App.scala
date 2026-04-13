package edu.uic.cs553

import scala.concurrent.Await
import scala.concurrent.duration.*
import java.io.File

import com.typesafe.config.ConfigFactory

import edu.uic.cs553.sim.algorithms.{DistributedAlgorithm, HirschbergSinclairAlgorithm, TreeLeaderElection}
import edu.uic.cs553.sim.core.enrich.GraphEnricher
import edu.uic.cs553.sim.core.io.DotGraphLoader
import edu.uic.cs553.sim.runtime.NodeActor
import edu.uic.cs553.sim.runtime.RuntimeBuilder

object App:
  def main(args: Array[String]): Unit =
    val config       = ConfigFactory.load()
    val graphDir     = config.getString("sim.graphDirectory")
    val runDurationMs  = config.getInt("sim.runDurationMs")
    val inputDelayMs   = config.getInt("sim.inputDelayMs")

    // Auto-pick the latest .ngs.dot file (excludes .perturbed.dot)
    val dotFile = latestDotFile(graphDir).getOrElse {
      println(s"ERROR: no .ngs.dot file found in $graphDir")
      sys.exit(1)
    }

    println(s"Loading graph from: ${dotFile.getAbsolutePath}")
    val graph = DotGraphLoader.load(dotFile.getAbsolutePath)

    println(s"Nodes: ${graph.nodes.size}")
    println(s"Edges: ${graph.edges.size}")

    val enrichedGraph = GraphEnricher.enrich(graph)

    val algorithmFactories: Seq[() => DistributedAlgorithm] = Seq(
      () => new HirschbergSinclairAlgorithm,
      () => new TreeLeaderElection
    )

    val running = RuntimeBuilder.run(enrichedGraph, algorithmFactories)

    Thread.sleep(inputDelayMs)

    running.nodeRefs.get(1).foreach { ref =>
      ref ! NodeActor.ExternalInput(kind = "WORK", payload = "external-job-1")
    }

    Thread.sleep(runDurationMs)

    Await.result(running.system.terminate(), 5.seconds)

  /** Returns the most recently modified .ngs.dot file in dir, excluding perturbed files. */
  private def latestDotFile(dir: String): Option[File] =
    Option(new File(dir).listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.getName.endsWith(".ngs.dot") && !f.getName.contains(".perturbed"))
      .sortBy(_.lastModified())
      .lastOption
