package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext

/** Passive observer that logs every lifecycle event. Useful for debugging. */
final class LoggingAlgorithm extends DistributedAlgorithm:
  override def name: String = "logging"

  override def onStart(ctx: AlgorithmContext): Unit =
    ctx.log(s"[LOG] node ${ctx.nodeId}: onStart neighbors=${ctx.neighbors.toList.sorted}")

  override def onTick(ctx: AlgorithmContext): Unit =
    ctx.log(s"[LOG] node ${ctx.nodeId}: onTick")

  override def onMessage(ctx: AlgorithmContext, from: Int, kind: String, payload: String): Unit =
    ctx.log(s"[LOG] node ${ctx.nodeId}: message kind=$kind from=$from payload=$payload")
