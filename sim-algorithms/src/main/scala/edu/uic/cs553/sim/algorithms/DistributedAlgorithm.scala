package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.runtime.NodeContext
import edu.uic.cs553.sim.runtime.NodeActor

trait DistributedAlgorithm:
  def name: String
  def onStart(ctx: NodeContext): Unit
  def onTick(ctx: NodeContext): Unit = ()
  def onMessage(ctx: NodeContext, msg: NodeActor.Envelope): Unit