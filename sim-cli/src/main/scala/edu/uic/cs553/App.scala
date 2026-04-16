package edu.uic.cs553

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import java.io.File

import com.typesafe.config.{Config, ConfigFactory}

import edu.uic.cs553.sim.algorithms.{DistributedAlgorithm, HirschbergSinclairAlgorithm, Snapshot, Termination, TreeLeaderElection}
import edu.uic.cs553.sim.core.SimConfig
import edu.uic.cs553.sim.core.enrich.GraphEnricher
import edu.uic.cs553.sim.core.io.DotGraphLoader
import edu.uic.cs553.sim.core.models.Pdf
import edu.uic.cs553.sim.runtime.NodeActor
import edu.uic.cs553.sim.runtime.RuntimeBuilder
import edu.uic.cs553.sim.runtime.SimMetrics

object App:
  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()

    val graphDir    = config.getString("sim.graphDirectory")
    val runDuration = config.getInt("sim.runDurationMs")
    val seed        = config.getInt("sim.seed")

    // ── Load graph ──────────────────────────────────────────────────────────
    // If sim.graphFile is set, use that exact file; otherwise auto-pick the most
    // recently modified .ngs.dot in graphDirectory (useful for netgamesim/output).
    val dotFile =
      if config.hasPath("sim.graphFile") then
        val explicit = new File(config.getString("sim.graphFile"))
        if !explicit.exists() then
          println(s"ERROR: sim.graphFile not found: ${explicit.getAbsolutePath}")
          sys.exit(1)
        explicit
      else
        latestDotFile(graphDir).getOrElse {
          println(s"ERROR: no .ngs.dot file found in $graphDir")
          sys.exit(1)
        }
    println(s"Loading graph from: ${dotFile.getAbsolutePath}")

    val graph = DotGraphLoader.load(dotFile.getAbsolutePath)
    println(s"Nodes: ${graph.nodes.size}")
    println(s"Edges: ${graph.edges.size}")

    // ── Parse SimConfig from application.conf ───────────────────────────────
    val simCfg = parseSimConfig(config)

    // ── Enrich graph and start actor system ─────────────────────────────────
    val enrichedGraph = GraphEnricher.enrich(graph, simCfg)

    val algorithmFactories: Seq[() => DistributedAlgorithm] = Seq(
      () => new HirschbergSinclairAlgorithm,
      () => new TreeLeaderElection,
      () => new Snapshot,
      () => new Termination
    )

    val running = RuntimeBuilder.run(enrichedGraph, algorithmFactories, seed)

    // ── Inject external messages ────────────────────────────────────────────
    val injMode = config.getString("sim.injection.mode")
    injMode match
      case "file" =>
        // Deliver scheduled events on a daemon thread; main thread sleeps for runDuration.
        runFileInjection(config, running)
        Thread.sleep(runDuration)

      case "interactive" =>
        // Block the main thread on stdin so the JVM stays alive for as long as the user
        // needs.  Algorithms run in actor threads and are unaffected.  Press Ctrl+D to
        // finish — metrics are printed and the actor system shuts down immediately after.
        runInteractiveInjection(running)

      case other =>
        println(s"[WARN] unknown injection mode: $other — falling back to timed run")
        Thread.sleep(runDuration)

    println(SimMetrics.report())
    Await.result(running.system.terminate(), 5.seconds)

  // ── Config parsing ────────────────────────────────────────────────────────

  /**
   * Reads the sim.* config block and converts it into a SimConfig value.
   * All enrichment settings (PDFs, timers, edge labels) live here so that
   * GraphEnricher has no typesafe-config dependency and remains independently testable.
   */
  private def parseSimConfig(config: Config): SimConfig =
    val defaultPdf = parsePdf(config.getConfigList("sim.traffic.defaultPdf"))

    val perNodePdf: Map[Int, Pdf] =
      config.getConfigList("sim.traffic.perNodePdf").asScala
        .map(c => c.getInt("node") -> parsePdf(c.getConfigList("pdf")))
        .toMap

    // timerNodes: nodeId -> (tickEveryMs, mode)
    val timerNodes: Map[Int, (Int, String)] =
      config.getConfigList("sim.initiators.timers").asScala
        .map(c => c.getInt("node") -> (c.getInt("tickEveryMs"), c.getString("mode")))
        .toMap

    val inputNodes: Set[Int] =
      config.getConfigList("sim.initiators.inputs").asScala
        .map(_.getInt("node"))
        .toSet

    val defaultEdgeLabels: Set[String] =
      config.getStringList("sim.edgeLabeling.default").asScala.toSet

    val edgeLabelOverrides: Map[(Int, Int), Set[String]] =
      config.getConfigList("sim.edgeLabeling.overrides").asScala
        .map(c => (c.getInt("from"), c.getInt("to")) -> c.getStringList("allow").asScala.toSet)
        .toMap

    SimConfig(
      defaultPdf         = defaultPdf,
      perNodePdf         = perNodePdf,
      timerNodes         = timerNodes,
      inputNodes         = inputNodes,
      defaultEdgeLabels  = defaultEdgeLabels,
      edgeLabelOverrides = edgeLabelOverrides
    )

  /** Converts a HOCON list of { msg, p } entries into a Pdf. */
  private def parsePdf(entries: java.util.List[_ <: Config]): Pdf =
    Pdf(entries.asScala.map(c => c.getString("msg") -> c.getDouble("p")).toMap)

  // ── Injection modes ───────────────────────────────────────────────────────

  /**
   * File-driven injection.
   *
   * Reads the injection script line by line.  Each line describes one message:
   *   <delayMs>  <nodeId>  <kind>  <payload>
   *
   * Lines are sorted by delayMs and injected in order.  Delivery happens on a
   * daemon thread so the main thread can sleep for the full runDurationMs.
   */
  private def runFileInjection(
    config:  Config,
    running: RuntimeBuilder.RunningSimulation
  ): Unit =
    val path    = config.getString("sim.injection.file")
    val injFile = new File(path)
    if !injFile.exists() then
      println(s"[WARN] injection file not found: $path — skipping file injection")
    else
      val events =
        scala.io.Source.fromFile(injFile).getLines()
          .filterNot(l => l.trim.startsWith("#") || l.trim.isEmpty)
          .flatMap { line =>
            line.trim.split(" ", 4) match
              case Array(d, n, k, p) =>
                for delay <- d.toLongOption; node <- n.toIntOption
                yield (delay, node, k, p)
              case Array(d, n, k) =>
                for delay <- d.toLongOption; node <- n.toIntOption
                yield (delay, node, k, "")
              case _ =>
                println(s"[WARN] skipping malformed injection line: $line")
                None
          }
          .toList
          .sortBy(_._1)

      val thread = new Thread(() =>
        // Use foldLeft to track elapsed time functionally without a mutable variable.
        // The Long result is intentionally discarded; only the side effects matter.
        val _ = events.foldLeft(0L) { case (elapsed, (delay, nodeId, kind, payload)) =>
          val sleepMs = (delay - elapsed).max(0L)
          if sleepMs > 0 then Thread.sleep(sleepMs)
          running.nodeRefs.get(nodeId) match
            case Some(ref) =>
              println(s"[INJECT] → node $nodeId  kind=$kind  payload=$payload")
              ref ! NodeActor.ExternalInput(kind, payload)
            case None =>
              println(s"[INJECT] [WARN] node $nodeId not found — skipping")
          delay
        }
      )
      thread.setDaemon(true)
      thread.start()

  /**
   * Interactive injection mode.
   *
   * Blocks the main thread reading commands from stdin until the stream closes
   * (Ctrl+D).  Algorithms continue running in their actor threads throughout.
   * When stdin closes, control returns to main and the simulation shuts down.
   *
   * Command format:  <nodeId>  <kind>  <payload>
   * Example:         1 WORK my-job
   *
   * IMPORTANT: requires running from inside the sbt shell, not batch mode.
   *   Correct:   $ sbt          (enter shell first)
   *              > simCli/run   (then run from inside)
   *   Wrong:     $ sbt simCli/run   (batch mode — stdin is not forwarded)
   */
  private def runInteractiveInjection(running: RuntimeBuilder.RunningSimulation): Unit =
    println("─" * 50)
    println("Interactive injection ready.")
    println("Format : <nodeId> <kind> <payload>")
    println("Example: 1 WORK my-job")
    println("Stop   : Ctrl+D")
    println("─" * 50)

    @annotation.tailrec
    def readLoop(): Unit =
      val line = scala.io.StdIn.readLine()
      if line != null then
        line.trim.split(" ", 3) match
          case Array(n, k, p) =>
            n.toIntOption match
              case Some(nodeId) =>
                running.nodeRefs.get(nodeId) match
                  case Some(ref) =>
                    println(s"[INJECT] → node $nodeId  kind=$k  payload=$p")
                    ref ! NodeActor.ExternalInput(k, p)
                  case None => println(s"[INJECT] node $nodeId not found")
              case None => println(s"[INJECT] invalid node id: $n")
          case Array(n, k) =>
            n.toIntOption match
              case Some(nodeId) =>
                running.nodeRefs.get(nodeId) match
                  case Some(ref) =>
                    println(s"[INJECT] → node $nodeId  kind=$k")
                    ref ! NodeActor.ExternalInput(k, "")
                  case None => println(s"[INJECT] node $nodeId not found")
              case None => println(s"[INJECT] invalid node id: $n")
          case _ =>
            println("Format: <nodeId> <kind> <payload>")
        readLoop()

    readLoop()

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Returns the most recently modified .ngs.dot file in dir, excluding perturbed files. */
  private def latestDotFile(dir: String): Option[File] =
    Option(new File(dir).listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.getName.endsWith(".ngs.dot") && !f.getName.contains(".perturbed"))
      .sortBy(_.lastModified())
      .lastOption
