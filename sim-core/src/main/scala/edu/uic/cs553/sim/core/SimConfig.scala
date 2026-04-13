package edu.uic.cs553.sim.core

import edu.uic.cs553.sim.core.models.Pdf

/**
 * Parsed simulation configuration passed from the CLI layer into the enrichment layer.
 *
 * Keeping this as a plain case class (no typesafe-config import) means sim-core
 * stays free of external dependencies and GraphEnricher is testable without
 * needing a config file on the classpath.
 *
 * @param defaultPdf          PDF applied to every node unless overridden
 * @param perNodePdf          per-node PDF overrides keyed by node id
 * @param timerNodes          nodes that emit periodic messages: nodeId → (tickEveryMs, mode)
 * @param inputNodes          nodes that accept externally injected messages
 * @param defaultEdgeLabels   message kinds allowed on every edge by default
 * @param edgeLabelOverrides  per-edge overrides that replace the default for that directed edge
 */
final case class SimConfig(
  defaultPdf:         Pdf,
  perNodePdf:         Map[Int, Pdf],
  timerNodes:         Map[Int, (Int, String)],  // nodeId -> (tickEveryMs, mode)
  inputNodes:         Set[Int],
  defaultEdgeLabels:  Set[String],
  edgeLabelOverrides: Map[(Int, Int), Set[String]]
)
