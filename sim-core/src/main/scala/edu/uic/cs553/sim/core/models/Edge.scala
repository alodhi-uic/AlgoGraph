package edu.uic.cs553.sim.core.models

final case class Edge(
  from: Int,
  to: Int,
  weight: Option[Double] = None
)