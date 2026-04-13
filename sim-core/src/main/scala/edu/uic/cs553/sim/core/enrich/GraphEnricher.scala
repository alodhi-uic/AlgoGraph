package edu.uic.cs553.sim.core.enrich

import edu.uic.cs553.sim.core.SimConfig
import edu.uic.cs553.sim.core.models.*
import edu.uic.cs553.sim.core.validation.PdfValidator

/**
 * Enriches a raw graph with message-oriented metadata driven entirely by SimConfig.
 *
 * Enrichment adds three things:
 *   1. A probability mass function (PDF) to each node — controls what message kinds
 *      the node emits during background traffic generation.
 *   2. Timer and input flags — marks which nodes drive computation proactively
 *      (timer nodes) and which accept external stimuli (input nodes).
 *   3. Edge labels — each directed edge is annotated with the set of message kinds
 *      that are permitted to traverse it.  Algorithm control messages (e.g. HS_PROBE,
 *      TREE_MSG) always bypass these labels and are never blocked.
 */
object GraphEnricher:

  /**
   * Produces an EnrichedGraph from a raw Graph and a parsed SimConfig.
   * Validates all PDFs before building nodes so misconfigured distributions
   * fail fast rather than silently producing bad traffic.
   */
  def enrich(graph: Graph, cfg: SimConfig): EnrichedGraph =
    // Validate PDFs up-front so misconfiguration fails immediately
    PdfValidator.validate(cfg.defaultPdf)
    cfg.perNodePdf.values.foreach(pdf => PdfValidator.validate(pdf))

    val enrichedNodes = graph.nodes.map { id =>
      // Use the per-node PDF override when present, otherwise fall back to the default
      val pdf = cfg.perNodePdf.getOrElse(id, cfg.defaultPdf)

      // Timer tick interval comes from the config entry for this node (default 1000 ms)
      val tickEveryMs = cfg.timerNodes.get(id).map(_._1).getOrElse(1000)

      EnrichedNode(
        id           = id,
        pdf          = pdf,
        timerEnabled = cfg.timerNodes.contains(id),
        tickEveryMs  = tickEveryMs,
        inputEnabled = cfg.inputNodes.contains(id)
      )
    }

    val enrichedEdges = graph.edges.map { edge =>
      // Per-edge overrides replace the default label set for that directed edge
      val allowed = cfg.edgeLabelOverrides.getOrElse(
        (edge.from, edge.to),
        cfg.defaultEdgeLabels
      )
      EnrichedEdge(
        from            = edge.from,
        to              = edge.to,
        weight          = edge.weight,
        allowedMessages = allowed
      )
    }

    EnrichedGraph(nodes = enrichedNodes, edges = enrichedEdges)
