package edu.uic.cs553.sim.core.models

final case class Graph(
  nodes: Seq[Int],
  edges: Seq[Edge]
)