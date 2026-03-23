package edu.uic.cs553.sim.core.io

import edu.uic.cs553.sim.core.models.{Edge, Graph}
import scala.io.Source
import scala.util.matching.Regex

object DotGraphLoader {

  private val EdgePattern: Regex =
    """"(\d+)"\s*->\s*"(\d+)"(?:\s*\["weight"="([0-9.]+)"\])?""".r

  private val NodePattern: Regex =
    """"(\d+)"\s*\[""".r

  def load(path: String): Graph = {
    val lines = Source.fromFile(path).getLines().toList

    val parsedEdges =
      lines.flatMap {
        case EdgePattern(from, to, weight) =>
          Some(Edge(from.toInt, to.toInt, Option(weight).map(_.toDouble)))
        case _ => None
      }

    val nodeIdsFromEdges =
      parsedEdges.flatMap(e => Seq(e.from, e.to))

    val nodeIdsFromNodeLines =
      lines.flatMap {
        case NodePattern(id) => Some(id.toInt)
        case _               => None
      }

    val allNodes =
      (nodeIdsFromEdges ++ nodeIdsFromNodeLines).distinct.sorted

    Graph(
      nodes = allNodes,
      edges = parsedEdges
    )
  }
}