package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.runtime.NodeActor
import edu.uic.cs553.sim.runtime.NodeContext

final class LoggingAlgorithm extends DistributedAlgorithm:
  override def name: String = "logging"

  override def onStart(ctx: NodeContext): Unit =
    ctx.log(s"[Algorithm: $name] node ${ctx.nodeId} observed onStart")

  override def onTick(ctx: NodeContext): Unit =
    ctx.log(s"[Algorithm: $name] node ${ctx.nodeId} observed onTick")

  override def onMessage(ctx: NodeContext, msg: NodeActor.Envelope): Unit =
    ctx.log(
      s"[Algorithm: $name] node ${ctx.nodeId} observed message ${msg.kind} from ${msg.from}"
    )