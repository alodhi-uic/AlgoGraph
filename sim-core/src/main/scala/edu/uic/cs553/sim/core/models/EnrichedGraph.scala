package edu.uic.cs553.sim.core.models

final case class EnrichedGraph(
  nodes: Seq[EnrichedNode],
  edges: Seq[EnrichedEdge]
)