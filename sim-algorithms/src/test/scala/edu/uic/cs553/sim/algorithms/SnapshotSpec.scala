package edu.uic.cs553.sim.algorithms

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import AlgorithmTestHelper.*

class SnapshotSpec extends AnyFunSuite with Matchers:

  test("initiator records local state and broadcasts SNAP_MARKER on first tick"):
    val algo        = new Snapshot
    val (ctx, sent) = makeContext(nodeId = 0, neighbors = Set(1, 2, 3))

    algo.onTick(ctx)

    val markers = sent.filter(_.kind == "SNAP_MARKER")
    markers.map(_.to).toSet shouldBe Set(1, 2, 3)

  test("non-initiator does not send SNAP_MARKER on tick"):
    val algo        = new Snapshot
    val (ctx, sent) = makeContext(nodeId = 5, neighbors = Set(2, 8))

    algo.onTick(ctx)

    sent.filter(_.kind == "SNAP_MARKER") shouldBe empty

  test("node records state and forwards SNAP_MARKER on first marker received"):
    val algo        = new Snapshot
    val (ctx, sent) = makeContext(nodeId = 2, neighbors = Set(0, 5, 7))

    // Receive some application traffic first
    algo.onMessage(ctx, from = 5, kind = "PING", payload = "p1")
    algo.onMessage(ctx, from = 7, kind = "WORK", payload = "w1")

    // Now receive first SNAP_MARKER from node 0
    algo.onMessage(ctx, from = 0, kind = "SNAP_MARKER", payload = "0")

    // Should broadcast SNAP_MARKER to all neighbors
    val markers = sent.filter(_.kind == "SNAP_MARKER")
    markers.map(_.to).toSet shouldBe Set(0, 5, 7)

  test("application messages received while recording a channel are captured as in-transit"):
    val algo        = new Snapshot
    val (ctx, sent) = makeContext(nodeId = 2, neighbors = Set(0, 5))

    // Receive first SNAP_MARKER from 0 — channel 5 is now being recorded
    algo.onMessage(ctx, from = 0, kind = "SNAP_MARKER", payload = "0")

    // Application message from channel 5 while it is still being recorded
    algo.onMessage(ctx, from = 5, kind = "GOSSIP", payload = "g1")

    // When SNAP_MARKER arrives from 5, recording stops (GOSSIP was in-transit)
    // This should complete without error — we can't directly inspect inTransit
    // but we verify no exception is thrown and the node processed correctly
    noException should be thrownBy algo.onMessage(ctx, from = 5, kind = "SNAP_MARKER", payload = "5")

  test("initiator only fires once even if ticked multiple times"):
    val algo        = new Snapshot
    val (ctx, sent) = makeContext(nodeId = 0, neighbors = Set(1, 2))

    algo.onTick(ctx)
    algo.onTick(ctx)
    algo.onTick(ctx)

    // SNAP_MARKER should be sent exactly once per neighbor (not 3x)
    sent.count(_.kind == "SNAP_MARKER") shouldBe 2
