package edu.uic.cs553.sim.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import edu.uic.cs553.sim.core.enrich.GraphEnricher
import edu.uic.cs553.sim.core.models.{Edge, Graph, Pdf}

class GraphEnricherSpec extends AnyFunSuite with Matchers:

  // A simple 3-node graph: 0→1, 1→2, 0→2
  private val graph = Graph(
    nodes = Seq(0, 1, 2),
    edges = Seq(Edge(0, 1), Edge(1, 2), Edge(0, 2))
  )

  private val defaultPdf = Pdf(Map("PING" -> 0.5, "GOSSIP" -> 0.3, "WORK" -> 0.2))

  private def baseConfig(
    timerNodes:          Map[Int, (Int, String)] = Map.empty,
    inputNodes:          Set[Int]                = Set.empty,
    perNodePdf:          Map[Int, Pdf]           = Map.empty,
    defaultEdgeLabels:   Set[String]             = Set("PING", "GOSSIP"),
    edgeLabelOverrides:  Map[(Int, Int), Set[String]] = Map.empty
  ): SimConfig = SimConfig(
    defaultPdf         = defaultPdf,
    perNodePdf         = perNodePdf,
    timerNodes         = timerNodes,
    inputNodes         = inputNodes,
    defaultEdgeLabels  = defaultEdgeLabels,
    edgeLabelOverrides = edgeLabelOverrides
  )

  test("nodes listed as timer nodes have timerEnabled = true"):
    val cfg      = baseConfig(timerNodes = Map(0 -> (500, "pdf")))
    val enriched = GraphEnricher.enrich(graph, cfg)
    enriched.nodes.find(_.id == 0).get.timerEnabled shouldBe true
    enriched.nodes.find(_.id == 1).get.timerEnabled shouldBe false

  test("timer node carries the configured tick interval"):
    val cfg      = baseConfig(timerNodes = Map(0 -> (250, "pdf")))
    val enriched = GraphEnricher.enrich(graph, cfg)
    enriched.nodes.find(_.id == 0).get.tickEveryMs shouldBe 250

  test("nodes listed as input nodes have inputEnabled = true"):
    val cfg      = baseConfig(inputNodes = Set(1, 2))
    val enriched = GraphEnricher.enrich(graph, cfg)
    enriched.nodes.find(_.id == 1).get.inputEnabled shouldBe true
    enriched.nodes.find(_.id == 0).get.inputEnabled shouldBe false

  test("per-node PDF override replaces the default PDF for that node only"):
    val customPdf = Pdf(Map("WORK" -> 1.0))
    val cfg       = baseConfig(perNodePdf = Map(1 -> customPdf))
    val enriched  = GraphEnricher.enrich(graph, cfg)
    enriched.nodes.find(_.id == 1).get.pdf shouldBe customPdf
    enriched.nodes.find(_.id == 0).get.pdf shouldBe defaultPdf

  test("edge label override replaces the default label set for that directed edge"):
    val override1 = Map((0, 1) -> Set("WORK", "ACK"))
    val cfg       = baseConfig(edgeLabelOverrides = override1)
    val enriched  = GraphEnricher.enrich(graph, cfg)
    enriched.edges.find(e => e.from == 0 && e.to == 1).get.allowedMessages shouldBe Set("WORK", "ACK")
    // Other edges should still use the default
    enriched.edges.find(e => e.from == 1 && e.to == 2).get.allowedMessages shouldBe Set("PING", "GOSSIP")

  test("all nodes receive the default PDF when no per-node overrides are configured"):
    val cfg      = baseConfig()
    val enriched = GraphEnricher.enrich(graph, cfg)
    enriched.nodes.foreach(_.pdf shouldBe defaultPdf)
