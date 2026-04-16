package edu.uic.cs553.sim.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * Simulation-wide metrics registry backed by lock-free atomic counters.
 *
 * NodeActor increments these counters on every message event.
 * App calls [[report]] at shutdown to emit a human-readable summary.
 *
 * Design rationale: AtomicLong gives thread-safe updates from concurrent
 * actor threads without locks or synchronization overhead.  A full Cinnamon
 * setup would replace this with instrumented Akka actors once an Akka 24.x
 * version is used; until then these manual counters surface the same key
 * signals (throughput, external stimuli, algorithm vs. application split).
 */
object SimMetrics:

  private val _totalReceived    = new AtomicLong(0)
  private val _algorithmMsgs    = new AtomicLong(0)
  private val _appMsgsSent      = new AtomicLong(0)
  private val _appMsgsDropped   = new AtomicLong(0)
  private val _externalInputs   = new AtomicLong(0)
  private val _timerTicks       = new AtomicLong(0)

  /** Called every time a NodeActor receives an Envelope (any kind). */
  def recordReceived(): Unit = _totalReceived.incrementAndGet()

  /** Called every time an algorithm control message is sent (bypasses edge labels). */
  def recordAlgorithmMsg(): Unit = _algorithmMsgs.incrementAndGet()

  /** Called when an application message is delivered (passed edge-label check). */
  def recordAppMsgSent(): Unit = _appMsgsSent.incrementAndGet()

  /** Called when an application message is blocked by an edge-label constraint. */
  def recordAppMsgDropped(): Unit = _appMsgsDropped.incrementAndGet()

  /** Called when an ExternalInput message is accepted by an input node. */
  def recordExternalInput(): Unit = _externalInputs.incrementAndGet()

  /** Called on every Tick fired by a timer node. */
  def recordTimerTick(): Unit = _timerTicks.incrementAndGet()

  /**
   * Produces a formatted metrics summary for printing at simulation shutdown.
   * All values are snapshots — no locking, so they reflect the state at call time.
   */
  def report(): String =
    val received  = _totalReceived.get()
    val algMsgs   = _algorithmMsgs.get()
    val appSent   = _appMsgsSent.get()
    val appDropped = _appMsgsDropped.get()
    val external  = _externalInputs.get()
    val ticks     = _timerTicks.get()

    s"""
       |╔══════════════════════════════════════╗
       |║      Simulation Metrics Summary      ║
       |╠══════════════════════════════════════╣
       |║  Messages received (total) : $received
       |║  Algorithm messages sent   : $algMsgs
       |║  App messages delivered    : $appSent
       |║  App messages dropped      : $appDropped  (blocked by edge labels)
       |║  External inputs injected  : $external
       |║  Timer ticks fired         : $ticks
       |╚══════════════════════════════════════╝
       |""".stripMargin
