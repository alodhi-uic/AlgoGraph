package edu.uic.cs553.sim.algorithms

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import AlgorithmTestHelper.*

class TreeLeaderElectionSpec extends AnyFunSuite with Matchers:

  test("leaf node sends TREE_MSG to its single neighbor on start"):
    val algo       = new TreeLeaderElection
    val (ctx, sent) = makeContext(nodeId = 3, neighbors = Set(1))

    algo.onStart(ctx)

    sent should have size 1
    sent.head shouldBe SentMsg(to = 1, kind = "TREE_MSG", payload = "3")

  test("internal node forwards max ID upstream after hearing from all-but-one neighbor"):
    val algo        = new TreeLeaderElection
    val (ctx, sent) = makeContext(nodeId = 2, neighbors = Set(0, 5, 7))

    algo.onStart(ctx)
    // Hear from neighbors 5 and 7 — one remaining (0), so forward upstream
    algo.onMessage(ctx, from = 5, kind = "TREE_MSG", payload = "5")
    algo.onMessage(ctx, from = 7, kind = "TREE_MSG", payload = "9")

    // Should forward max(2, 5, 9) = 9 to the remaining neighbor (0)
    sent.filter(_.kind == "TREE_MSG") should contain (SentMsg(to = 0, kind = "TREE_MSG", payload = "9"))

  test("root broadcasts TREE_LEADER after hearing from all neighbors"):
    val algo        = new TreeLeaderElection
    val (ctx, sent) = makeContext(nodeId = 4, neighbors = Set(1, 2))

    algo.onStart(ctx)
    algo.onMessage(ctx, from = 1, kind = "TREE_MSG", payload = "7")
    algo.onMessage(ctx, from = 2, kind = "TREE_MSG", payload = "9")

    // Node 4 heard from all neighbors — it is the root
    val leaderMsgs = sent.filter(_.kind == "TREE_LEADER")
    leaderMsgs should not be empty
    leaderMsgs.foreach(_.payload shouldBe "9")

  test("center-edge: lower-ID node does not become root when both sides forward to each other"):
    // Nodes 3 (lower) and 6 (higher) are each other's last remaining neighbor.
    // Node 3 forwards to 6 first; then receives from 6.
    // Because 3 < 6 and they mutually forwarded, node 3 must NOT broadcast TREE_LEADER.
    val algo3        = new TreeLeaderElection
    val (ctx3, sent3) = makeContext(nodeId = 3, neighbors = Set(0, 6))

    algo3.onStart(ctx3)
    // Hear from neighbor 0 → remaining = {6}, forward to 6
    algo3.onMessage(ctx3, from = 0, kind = "TREE_MSG", payload = "8")
    // Now receive from 6 (the node we forwarded to) → center-edge case, 3 < 6 so yield
    algo3.onMessage(ctx3, from = 6, kind = "TREE_MSG", payload = "9")

    sent3.filter(_.kind == "TREE_LEADER") shouldBe empty

  test("TREE_LEADER is forwarded to all neighbors except the sender"):
    val algo        = new TreeLeaderElection
    val (ctx, sent) = makeContext(nodeId = 5, neighbors = Set(2, 8, 9))

    algo.onStart(ctx)
    // Receive leader broadcast from neighbor 2
    algo.onMessage(ctx, from = 2, kind = "TREE_LEADER", payload = "9")

    val forwards = sent.filter(_.kind == "TREE_LEADER")
    // Should forward to 8 and 9 but NOT back to 2
    forwards.map(_.to).toSet shouldBe Set(8, 9)
    forwards.foreach(_.payload shouldBe "9")
