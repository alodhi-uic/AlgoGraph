package edu.uic.cs553.sim.algorithms

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import AlgorithmTestHelper.*

class TerminationSpec extends AnyFunSuite with Matchers:

  test("root sends DS_WORK to all neighbors on second tick"):
    val algo        = new Termination
    val (ctx, sent) = makeContext(nodeId = 0, neighbors = Set(1, 3))

    algo.onTick(ctx) // tick 1 — no action
    algo.onTick(ctx) // tick 2 — initiates

    val work = sent.filter(_.kind == "DS_WORK")
    work.map(_.to).toSet shouldBe Set(1, 3)

  test("root does not initiate on first tick"):
    val algo        = new Termination
    val (ctx, sent) = makeContext(nodeId = 0, neighbors = Set(1, 2))

    algo.onTick(ctx) // tick 1 only

    sent.filter(_.kind == "DS_WORK") shouldBe empty

  test("leaf node sends DS_ACK to parent immediately after receiving DS_WORK"):
    val algo        = new Termination
    val (ctx, sent) = makeContext(nodeId = 5, neighbors = Set(4))

    algo.onMessage(ctx, from = 4, kind = "DS_WORK", payload = "compute")

    sent should contain (SentMsg(to = 4, kind = "DS_ACK", payload = "done"))

  test("internal node forwards DS_WORK to children and waits for ACKs"):
    val algo        = new Termination
    val (ctx, sent) = makeContext(nodeId = 1, neighbors = Set(0, 2, 9))

    algo.onMessage(ctx, from = 0, kind = "DS_WORK", payload = "compute")

    // Should forward DS_WORK to children (all neighbors except parent 0)
    val work = sent.filter(_.kind == "DS_WORK")
    work.map(_.to).toSet shouldBe Set(2, 9)
    // Should NOT yet send DS_ACK (waiting for children)
    sent.filter(_.kind == "DS_ACK") shouldBe empty

  test("internal node sends DS_ACK to parent after all children acknowledge"):
    val algo        = new Termination
    val (ctx, sent) = makeContext(nodeId = 1, neighbors = Set(0, 2, 9))

    // Join tree — parent=0, children={2,9}, deficit=2
    algo.onMessage(ctx, from = 0, kind = "DS_WORK", payload = "compute")

    // First ACK from child 2 — deficit=1, not done yet
    algo.onMessage(ctx, from = 2, kind = "DS_ACK", payload = "done")
    sent.filter(_.kind == "DS_ACK") shouldBe empty

    // Second ACK from child 9 — deficit=0, send ACK to parent
    algo.onMessage(ctx, from = 9, kind = "DS_ACK", payload = "done")
    sent should contain (SentMsg(to = 0, kind = "DS_ACK", payload = "done"))

  test("duplicate DS_WORK (node already in tree) is acknowledged immediately"):
    val algo        = new Termination
    val (ctx, sent) = makeContext(nodeId = 3, neighbors = Set(0, 4))

    // Join tree with parent=0
    algo.onMessage(ctx, from = 0, kind = "DS_WORK", payload = "compute")
    sent.clear()

    // Receive DS_WORK again from neighbor 4 — should ACK immediately
    algo.onMessage(ctx, from = 4, kind = "DS_WORK", payload = "compute")
    sent should contain (SentMsg(to = 4, kind = "DS_ACK", payload = "done"))
