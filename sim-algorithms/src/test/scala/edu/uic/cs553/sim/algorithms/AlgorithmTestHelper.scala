package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.AlgorithmContext
import scala.collection.mutable

/**
 * Test harness for DistributedAlgorithm implementations.
 *
 * Provides a fake AlgorithmContext that captures outbound messages and log lines
 * so tests can assert on what an algorithm sent without spinning up an actor system.
 */
object AlgorithmTestHelper:

  /**
   * Sent message record: (destinationNodeId, kind, payload).
   */
  final case class SentMsg(to: Int, kind: String, payload: String)

  /**
   * Builds a fake AlgorithmContext and returns it together with the mutable
   * buffer of messages that have been sent through it.
   *
   * @param nodeId    the id of the node being simulated
   * @param neighbors the set of neighbor node ids
   */
  def makeContext(nodeId: Int, neighbors: Set[Int]): (AlgorithmContext, mutable.ListBuffer[SentMsg]) =
    val sent = mutable.ListBuffer.empty[SentMsg]
    val ctx = AlgorithmContext(
      nodeId    = nodeId,
      neighbors = neighbors,
      send      = (to, kind, payload) => sent += SentMsg(to, kind, payload),
      log       = _ => ()   // suppress output in tests
    )
    (ctx, sent)
