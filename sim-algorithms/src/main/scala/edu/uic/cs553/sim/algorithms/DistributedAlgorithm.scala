package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext

/**
 * Plugin interface for distributed algorithms.
 *
 * Each node actor holds its own instance of every registered algorithm,
 * so instance variables are safe for per-node state without synchronization.
 *
 * All methods default to no-ops so algorithms only override what they need.
 */
trait DistributedAlgorithm:
  def name: String

  /** Called once when the node actor receives its Start signal. */
  def onStart(ctx: AlgorithmContext): Unit = ()

  /** Called on every timer tick (only for timer-enabled nodes). */
  def onTick(ctx: AlgorithmContext): Unit = ()

  /** Called for every inbound Envelope the node actor receives. */
  def onMessage(ctx: AlgorithmContext, from: Int, kind: String, payload: String): Unit = ()
