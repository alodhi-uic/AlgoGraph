package edu.uic.cs553.sim.core

/**
 * Context passed to every DistributedAlgorithm method.
 * Gives algorithms everything they need: identity, topology, messaging, and logging.
 * Adding new capabilities here extends all algorithms without changing method signatures.
 *
 * @param nodeId    the id of the node running this algorithm instance
 * @param neighbors the ids of all directly connected neighbors
 * @param send      send a message to a specific neighbor: send(to, kind, payload)
 * @param log       emit a log line
 */
final case class AlgorithmContext(
  nodeId:    Int,
  neighbors: Set[Int],
  send:      (Int, String, String) => Unit,
  log:       String => Unit
):
  /** Send the same message to every neighbor. */
  def broadcast(kind: String, payload: String): Unit =
    neighbors.foreach(send(_, kind, payload))

  /** Send to every neighbor except one (used when forwarding to avoid echoing back). */
  def broadcastExcept(exclude: Int, kind: String, payload: String): Unit =
    neighbors.filterNot(_ == exclude).foreach(send(_, kind, payload))
