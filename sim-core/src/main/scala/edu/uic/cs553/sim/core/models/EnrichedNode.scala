package edu.uic.cs553.sim.core.models

final case class EnrichedNode(
  id: Int,
  pdf: Pdf,
  timerEnabled: Boolean,
  tickEveryMs: Int,
  inputEnabled: Boolean
)