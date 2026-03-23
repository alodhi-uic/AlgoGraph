package edu.uic.cs553.sim.core.models

final case class EnrichedEdge(
  from: Int,
  to: Int,
  weight: Option[Double] = None,
  allowedMessages: Set[String]
)