package edu.uic.cs553.sim.algorithms

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import AlgorithmTestHelper.*

class HirschbergSinclairSpec extends AnyFunSuite with Matchers:

  test("node with only 1 neighbor is rejected as invalid ring topology without crashing"):
    val algo        = new HirschbergSinclairAlgorithm
    val (ctx, sent) = makeContext(nodeId = 3, neighbors = Set(2))

    // Should not throw — topology validation is graceful
    noException should be thrownBy algo.onStart(ctx)
    // Invalid node must not send any probes
    sent.filter(_.kind == "HS_PROBE") shouldBe empty

  test("node with 3 neighbors is rejected as invalid ring topology without crashing"):
    val algo        = new HirschbergSinclairAlgorithm
    val (ctx, sent) = makeContext(nodeId = 5, neighbors = Set(2, 6, 8))

    noException should be thrownBy algo.onStart(ctx)
    sent.filter(_.kind == "HS_PROBE") shouldBe empty

  test("valid ring node with exactly 2 neighbors sends CW and CCW probes on start"):
    val algo        = new HirschbergSinclairAlgorithm
    // Node 4 in a ring: neighbors are 3 (CCW) and 5 (CW)
    val (ctx, sent) = makeContext(nodeId = 4, neighbors = Set(3, 5))

    algo.onStart(ctx)

    val probes = sent.filter(_.kind == "HS_PROBE")
    probes should have size 2
    // One probe goes CW, one CCW
    probes.map(_.to).toSet shouldBe Set(3, 5)

  test("invalid node ignores incoming HS_PROBE messages after topology rejection"):
    val algo        = new HirschbergSinclairAlgorithm
    val (ctx, sent) = makeContext(nodeId = 3, neighbors = Set(2))

    algo.onStart(ctx)  // marks invalid
    algo.onMessage(ctx, from = 2, kind = "HS_PROBE", payload = "2|0|CW|1")

    // Must not reply or forward after being invalidated
    sent.filter(m => m.kind == "HS_PROBE" || m.kind == "HS_REPLY") shouldBe empty
