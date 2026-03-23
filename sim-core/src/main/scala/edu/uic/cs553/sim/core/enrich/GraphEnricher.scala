package edu.uic.cs553.sim.core.enrich

import edu.uic.cs553.sim.core.models.*
import edu.uic.cs553.sim.core.validation.PdfValidator

object GraphEnricher:

  def enrich(graph: Graph): EnrichedGraph =
    val defaultPdf = Pdf(
      Map(
        "PING" -> 0.5,
        "GOSSIP" -> 0.3,
        "WORK" -> 0.2
      )
    )

    PdfValidator.validate(defaultPdf)

    val enrichedNodes =
      graph.nodes.map { id =>
        val timerEnabled = id == 0
        val tickEveryMs = 1000
        val inputEnabled = id == 1 || id == 2

        EnrichedNode(
          id = id,
          pdf = defaultPdf,
          timerEnabled = timerEnabled,
          tickEveryMs = tickEveryMs,
          inputEnabled = inputEnabled
        )
      }

    val enrichedEdges =
      graph.edges.map { edge =>
        val allowed =
          if edge.from == 0 && edge.to == 1 then Set("PING", "CONTROL", "GOSSIP")
          else if edge.from == 0 && edge.to == 2 then Set("WORK", "ACK")
          else Set("PING", "CONTROL", "GOSSIP", "WORK")

        EnrichedEdge(
          from = edge.from,
          to = edge.to,
          weight = edge.weight,
          allowedMessages = allowed
        )
      }

    EnrichedGraph(
      nodes = enrichedNodes,
      edges = enrichedEdges
    )