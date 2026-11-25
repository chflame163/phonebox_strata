package net.oxog.phonebox.deskpanel

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Phone-side plug watcher — the successor to the Synology NAS's plug_watcher.py
 * (plug-control/plug-delay/). Same job, different observer: this phone is fed by the very server whose supply
 * it may cut, so it rides on battery through the shutdown. Decision semantics follow 手机替代NASWatcher开发计划_20261124 §4:
 *
 *   idle ──(agent silent ≥ BOOT_CONFIRM_S while booted, or UDP "SHUTDOWN")──► armed ──(+SHUTDOWN_DELAY_S after
 *   the ErP power-loss edge; a plain arm waits until stale)──► fired ──(server data/USB back / manual --on)──► idle(rearm)
 *
 * off 语义定稿 = **掉电沿 + SHUTDOWN_DELAY_S**（不是握手+180）：boot 场景由 <45s 解除、≥60s 确认甄别，reboot 永不 fire。
 * 取消路（T4 同构）：armed 期间 agent 数据恢复或 USB 重新 configured → cancelled 回 idle。
 * stale 兜底（ErP 失效/插常供电口）：静默满 max(300s, 2×delay) 仍未见掉电沿 → 强制 fire(dry)。
 * 电池下限：level < CRIT_LEVEL 拒绝进入 armed，只推面板告警。
 * dry=true（默认，Phase 3 全程）：判决与发火照常执行并打日志+push，但从不写插座；get_properties 验据照常。
 *
 * The app's watcher is only ever the plug's writer while the NAS unit is disabled (二者互斥二选一, §4.5) — and in
 * dry-run mode neither writes at all.
 */
class PlugWatcher(
    private val context: Context,
    /** Reads one config snapshot per arm decision: keys under display.* of the agent's config.json. */
    private val cfg: () -> Map<String, Any?>,
    /** Same clock as MainActivity.standby: elapsedRealtime of the last byte from the agent (SSE or probe). 0 before first frame. */
    private val agentDataAt: () -> Long,
    /** The phone's own USB data link to the server (sticky USB_STATE), truthy = that computer is up. */
    private val usbLinkUp: () -> Boolean,
    /** Sticky BATTERY_CHANGED level in percent, -1 unknown. */
    private val batteryLevel: () -> Int,
    /** Push one event through the agent's loopback API (POST /api/push/plug_delay_state via AgentLink.request). The
     *  server is off most of the time this matters, so a failure is simply dropped — the panel's own floor net and
     *  logcat carry the story instead (开发计划 §6: 判据 = App 日志 + push key). */
    private val pushEvent: (JSONObject) -> Unit,
) {
    enum class State { IDLE, ARMED, FIRED }

    @Volatile var state = State.IDLE; private set
    @Volatile private var lastLog = ""   // dedupe the logcat spam of tick-driven transitions

    // The tick loop is a dedicated PLAIN thread (not an executor worker): pool threads on this ROM get reaped
    // while idle and their parked runnables are never picked up again — the tickLoop() submit silently vanished
    // twice (T3 dry-run, 2026-11-24). A plain Thread survives doze exactly like MainActivity's 60 s probe loop.
    @Volatile private var tickThread: Thread? = null
    // Lane discipline (real-off #2 post-mortem, 2026-11-25): the SUPPLY-CUT write gets its OWN thread — it must
    // never share a queue with the cosmetic panel pushes. Observed on the wire: FIRE at +187.9s but OFF_OK only
    // at +313s. Root cause = pushEvent's HTTP POST to the reverse tunnel whose agent endpoint lives ON THE DYING
    // SERVER; ECONNREFUSED on loopback makes Android back off reconnects (1s/2s/4s…) and those retries held the
    // single-threaded io executor hostage, writeOff queued behind it. The tunnel is a server-side unit, so after
    // poweroff nobody re-arms it until boot — pushes are pure best-effort by design (log file is the real record).
    private val plugIo = Executors.newSingleThreadExecutor()      // miio writes/reads: the only lane that may cut power
    @Volatile private var pushThread: Thread? = null              // one-shot plain thread per event (pool workers get reaped on this ROM — tick-loop lesson)
    private fun pushAsync(r: Runnable) {
        val t = Thread(r, "plug-push"); t.isDaemon = true
        pushThread = t
        try { t.start() } catch (_: OutOfMemoryError) { pushThread = null }  // thread storm is worse than a lost panel card
    }
    private val main = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    @Volatile private var running = false

    // decision bookkeeping (elapsedRealtime basis, immune to wall-clock jumps)
    @Volatile private var armedAt = 0L            // when the arm happened (stale clock starts here)
    @Volatile private var powerOffAt = 0L         // ErP edge observed while armed; 0 = no edge yet
    @Volatile private var firedAt = 0L
    @Volatile private var prevPlugged = true      // plug-edge memory shared with MainActivity's receivers
    @Volatile private var lastPluggedFlag = true  // live plug state as far as the watcher knows (sticky + edges)
    @Volatile private var lastArmByUdp = false    // true when the most recent arm came from a UDP SHUTDOWN (handshake, not boot silence)
    @Volatile private var edgeWasHb = false       // provenance of powerOffAt: HB-silence edges keep the hostDead gate

    companion object {
        /** BatteryManager.BATTERY_STATUS_FULL sentinel passed through batteryLevel() by MainActivity when the HAL
         *  reports FULL while still charging — mirrors MainActivity's "charged phone keeps EXTRA_PLUGGED dropped" guard. */
        const val FULL_TRICK_LEVEL = 101
    }

    private data class Cfg(
        val enabled: Boolean, val dry: Boolean, val delayS: Int, val bootConfirmS: Int,
        val bootClearS: Int, val staleMaxS: Int, val critLevel: Int, val listenPort: Int,
        val plugIp: String, val plugToken: String,
    )

    private var lastCfgKeys = ""   // one-line cfg tracer: fires whenever the snapshot's key set CHANGES (rare —
                                   // config edits), cheap enough to keep in production as a deploy-time breadcrumb

    private fun readCfg(): Cfg {
        val c = cfg()
        if (c.keys.sorted().joinToString(",") != lastCfgKeys) {
            lastCfgKeys = c.keys.sorted().joinToString(",")
            android.util.Log.i("PlugWatcher", "CFG keys=${lastCfgKeys.take(300)} delay=${c["display.plugDelaySec"]} dry=${c["display.plugDryRun"]}")
        }
        val delay = (c["display.plugDelaySec"] as? Number)?.toInt()?.coerceIn(15, 3600) ?: 180
        return Cfg(
            enabled = (c["display.plugWatcher"] as? Boolean) ?: true,
            dry = (c["display.plugDryRun"] as? Boolean) ?: true,
            delayS = delay,
            bootConfirmS = (c["display.plugBootConfirmSec"] as? Number)?.toInt()?.coerceIn(10, 600) ?: 60,
            bootClearS = (c["display.plugBootClearSec"] as? Number)?.toInt()?.coerceIn(5, 300) ?: 45,
            staleMaxS = maxOf(300, delay * 2),
            critLevel = (c["display.plugCritLevel"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 8,
            listenPort = (c["display.plugUdpPort"] as? Number)?.toInt()?.coerceIn(1024, 65535) ?: 12345,
            plugIp = c["display.plugIp"] as? String ?: "",
            plugToken = c["display.plugToken"] as? String ?: "",
        )
    }

    /** MainActivity wires this from its power receiver as a FAST path (plugged == false is the ErP loss edge);
     *  tick also samples batteryLevel()/USB independently, so a broadcast swallowed by doze never loses the edge. */
    fun onPlugEdge(plugged: Boolean) {
        prevPlugged = plugged
        if (!running) return
        main.post { val c = readCfg(); if (c.enabled && state == State.ARMED) captureEdge(c, hbSilenceMs()) }
    }

    /** USB data-link edge from either receiver (Activity or service): link down = the host's stack stopped —
     *  the ErP-equivalent moment on this wiring (VBUS can stay hot while the hub port dies, and vice versa). */
    fun onUsbLink(up: Boolean) {
        if (!running || !readCfg().enabled) return
        if (up) usbLinkDownCount.set(0) else usbLinkDownCount.incrementAndGet()
        if (!up && state == State.IDLE) {
            // host link died without a handshake: arm with the loss edge ALREADY set (this is the real-shutdown moment)
            lastArmByUdp = false
            val c = readCfg()
            arm(c, "USB 数据链断开（掉电沿即时成立）", fromBoot = true)
            powerOffAt = SystemClockElapsed.get()   // §4.2 counts from this instant
            pushState(c, reason = "power-edge(usb-down)")
        } else if (!up && state == State.ARMED) {
            main.post { captureEdge(readCfg()) }    // armed already (handshake first): start the clock now
        }
        // up=true needs no action here: decide()'s cancel path sees agent-data freshness (the authoritative signal;
        // a reboot's fresh frames retire this episode), never raw USB liveness — that could cancel on a blip.
    }

    /** Capture the ErP loss edge exactly once while armed. Idempotent; also used by the tick sampler.
     *  T3/T15 field lesson (2026-11-24): on this hub wiring VBUS never truly drops AND the framework battery
     *  view can lag/stale, so the plug-edge alone is not trusted — onUsbLink(down) is the primary edge source
     *  (gadget suspend == host gone), and captureEdge fires whenever EITHER says loss. */
    private fun captureEdge(c: Cfg, hbSilentMs: Long = -1L) {
        if (state != State.ARMED || powerOffAt != 0L) return
        // Real-off field lesson (2026-11-24 late night): on this wiring VBUS never drops and USB stays "configured",
        // so the ErP edge class is structurally DEAD here — only battery/USB-loss can satisfy the old predicate, and
        // neither happens when the server dies while the phone keeps charging. FIRE therefore came from the
        // handshake-fallback lane at ARM+306 s (agent-HTTP silence crossed bootClear 45 s + a tick ~126 s after
        // the UDP heartbeat died — the countdown mis-timed by exactly that gap). Fix: heartbeat silence >= 15 s is
        // ITSELF the loss edge (HB interval 5 s; 3 misses = the host's stack stopped talking), captured from
        // decide()'s tick where hbAt is fresh. Reboot safety keeps its shape: any HB/agent-data resume within
        // bootClearS still cancels the episode BEFORE delay can elapse, same semantics as the NAS design.
        val byHb = hbSilentMs >= 15_000L
        if (!pluggedNow() || usbLinkDownCount.get() > 0 && !usbLinkUp() || byHb) {
            powerOffAt = SystemClockElapsed.get()
            edgeWasHb = byHb
            log("掉电沿捕获（plugged=${pluggedNow()} usb=${usbLinkUp()}），${c.delayS}s 后发火（dry=${c.dry}）")
            pushState(c, reason = "power-edge")
        }
    }

    private val usbLinkDownCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** UDP heartbeat freshness clock — handleUdp stamps it on every "HB" beat; the watcher's own 5-s-window truth.
     *  Field lesson (real-off, 2026-11-24): HB silence is the ONLY structural loss edge on this wiring (VBUS never
     *  drops, USB stays configured while the host dies) — >=15 s quiet == capture it, countdown starts NOW. */
    @Volatile private var hbAt = 0L
    private fun hbSilenceMs(): Long = if (hbAt == 0L) -1L else SystemClockElapsed.get() - hbAt

    /** "Still powered" per the phone's own battery — the service refreshes this from its sticky read every tick. */
    private fun pluggedNow(): Boolean = lastPluggedFlag

    /** The service pushes the live plug verdict here each tick (doze-swallowed broadcasts can't stale it). */
    fun setPlugged(v: Boolean) { lastPluggedFlag = v }

    /** MainActivity/service calls this once the sticky battery state has settled, before enabling the watcher. */
    fun seed(plugged: Boolean) { prevPlugged = plugged; lastPluggedFlag = plugged }

    fun start() {
        if (running) return
        running = true
        bindUdp()
        val t = Thread({ tickLoop() }, "plug-tick").apply { isDaemon = true }
        tickThread = t
        t.start()
        log("watcher 启动（tick 循环 + UDP ${readCfg().listenPort}）")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { tickThread?.interrupt() }
        tickThread = null
    }

    /** Re-bind after a config port change (MainActivity calls this when the agent's config frame moves plugUdpPort). */
    fun rebind(port: Int) {
        val cur = socket?.localPort
        if (cur != null && cur == port) return
        runCatching { socket?.close() }
        bindUdp()
        log("UDP 重绑到 $port")
    }

    private fun bindUdp() {
        Thread({
            try {
                val c = readCfg()
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(c.listenPort))
                socket = s
                // SO_TIMEOUT turns the blocking receive into a ~4 s slice: on every wake we check whether HB has
                // been silent >= 15 s while ARMED and capture the edge RIGHT THEN — event-driven, no waiting for
                // decide()'s next 15 s tick (real-off #2 timing audit: that sampling cost 29.7 s of pure phase on
                // top of the intended 15 s threshold). A timed-out receive is NOT an error; only stop()/rebind()'s
                // close() exits the loop via the running guard. HB cadence is 5 s, so slices never starve handleUdp.
                s.soTimeout = 4_000
                val buf = ByteArray(64)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        val payload = String(buf, 0, p.length, Charsets.UTF_8).trim()
                        if (payload.startsWith("SHUTDOWN") || payload.startsWith("HB")) handleUdp(payload, readCfg())
                    } catch (_: java.net.SocketTimeoutException) {
                        hbWatchTick()
                        continue
                    } catch (_: Exception) {
                        continue // closed by stop()/rebind(): loop guard ends it
                    }
                }
            } catch (e: Exception) {
                log("UDP 监听失败: ${e.message}")
            }
        }, "plug-udp").apply { isDaemon = true }.start()
    }

    /** Fast-path edge capture on the UDP thread — the same predicate captureEdge() uses, minus its idempotence
     *  guard (which makes this a no-op if the tick already got there first). CANCEL discipline is untouched:
     *  any HB resume still cancels while inside bootClearS, so a Wi-Fi flake can never cut our own supply. */
    private fun hbWatchTick() {
        if (state != State.ARMED || powerOffAt != 0L) return
        val c = readCfg()
        if (!c.enabled) return
        captureEdge(c, hbSilenceMs())
    }

    /** Debug aid for the dry-run matrix (broadcast entry, never a production path). */
    fun debugArm() { val c = readCfg(); if (state == State.IDLE) { lastArmByUdp = true; arm(c, "DEBUG broadcast", fromBoot = false) } }

    /** UDP 冗余路：SHUTDOWN 直接确立 armed（Wi-Fi 曾掉线时握手可补救）；HB 证明服务器在线 → 取消。 */
    private fun handleUdp(payload: String, c: Cfg) {
        if (!c.enabled) return
        when {
            payload.startsWith("SHUTDOWN") -> if (state == State.IDLE) {
                lastArmByUdp = true
                arm(c, "UDP SHUTDOWN 握手", fromBoot = false)
            } else if (state == State.FIRED) log("fired 态收到 $payload（已发火，忽略）")
            payload.startsWith("HB") -> {
                hbAt = SystemClockElapsed.get()   // beat clock: any resume also proves the host is back
                when (state) {
                    State.ARMED -> cancel("UDP 心跳恢复")
                    State.FIRED -> rearm(c, "UDP 心跳恢复")
                    else -> {}
                }
            }
        }
    }

    private fun tickLoop() {
        var n = 0
        while (running) {
            try {
                // fresh battery state FIRST: decide()/captureEdge must never act on a stale sticky snapshot —
                // the hub's phantom "connected" (data dead, VBUS hot) is exactly that case (T3 lesson, 11-24).
                batteryLevel()               // side effect: service refreshes the watcher's plug flag from the sticky intent
                val c = readCfg()
                decide(c)
                if (++n % 5 == 0) log("tick alive（state=$state tickCount=$n）")   // heartbeat line ~75 s cadence
            } catch (e: Exception) {
                log("tick 异常: ${e.message}")
            }
            // tick cadence mirrors standby polling: armed/IDLE every 15 s (awake rhythm; deep sleep's 60 s probe
            // windows still bound the countdown), fired slows to 30 s rearm polls. Doze whitelisting keeps this
            // honest (README red line #4); a swallowed broadcast is no risk because decide() samples battery itself.
            sleepMs(if (state == State.FIRED) 30_000L else 15_000L)
        }
    }

    /** The state machine, one pass. Mirrors plug_watcher.py's loop() with the phone-side edges folded in. */
    private fun decide(c: Cfg) {
        if (!c.enabled) {
            if (state != State.IDLE) resetToIdle("watcher 已停用")
            return
        }
        val now = SystemClockElapsed.get()
        val silentFor = if (agentDataAt() == 0L) 0L else now - agentDataAt() // no frame yet: treat as fresh (boot phase)
        // heartbeat clock: UDP HB frames land in hbAt via handleUdp; >=15 s of silence == the host stopped talking.
        val hbQuiet = hbSilenceMs()
        val usbUp = usbLinkUp()
        // The ErP edge itself is captured by onPlugEdge (Activity receiver, fast path) and decide()'s next tick;
        // here we only enforce §4.2's timing gate: an armed-with-edge episode may not fire before the phone has
        // also gone fully silent for bootClearS (the cable dies seconds after SIGTERM, while agent frames are
        // still in flight — arming on that would mis-time the countdown).
        when (state) {
            State.IDLE -> {
                // silence+arm without handshake is boot-phase noise ONLY while the battery says VBUS is still on;
                // once unplugged, persistent silence = dead host (ErP wiring), so the UDP-less anomaly path must arm too.
                val bootPhase = silentFor >= c.bootConfirmS * 1000L && !usbUp && pluggedNow()
                if (!bootPhase && silentFor >= c.staleMaxS * 1000L) {
                    lastArmByUdp = false
                    arm(c, "agent 静默 ${silentFor / 1000}s（stale 阈值确认，非开机相位）", fromBoot = true)
                }
            }
            State.ARMED -> {
                // 取消路主判据 = AGENT DATA FRESHNESS (USB 框架态与插座 VBUS 在本 wiring 下会滞留假阳性，
                // T3/T6 dry-run 实测：hub 断电后 configured=true 与 Charging 都能挂 40 s+)。数据重新在流即视为复活；
                // USB link up 仅作辅助信号（真关机它必随 agent 静默而下，reboot 它是开机早期证据 — 两者都保留）。
                val armedAgo = now - armedAt
                if (armedAgo >= c.bootClearS * 1000L && silentFor < c.bootClearS * 1000L) {
                    cancel("agent 数据恢复（静默 ${silentFor / 1000}s < ${c.bootClearS}s）")
                    return
                }
                // §4.2 off 语义：掉电沿(或握手 arm 后 agent 确已死透)+delay；无沿则等 stale 兜底。
                captureEdge(c, hbQuiet)   // tick-sampled edge: HB silence >=15s arms the countdown immediately
                val edgeAgo = if (powerOffAt == 0L) -1L else now - powerOffAt
                val hostDead = silentFor >= c.bootConfirmS * 1000L   // agent 静默过 boot 确认阈值 = 主机确死
                // HB-backed edges still need agent-death confirmation (a Wi-Fi flake must not cut the server's own
                // supply); structural edges (plug-loss / USB-gadget-death) are self-proving and fire on delay alone.
                val edgeProven = if (edgeWasHb) silentFor >= c.bootConfirmS * 1000L else true
                if (edgeAgo >= c.delayS * 1000L && edgeProven) {
                    fire(c, "掉电沿后已满 ${c.delayS}s")
                } else if (powerOffAt == 0L && armedAgo >= c.delayS * 1000L && hostDead) {
                    // 握手 arm + delay 过点 + agent 确死 — ErP/hub 沿没捕获（本 wiring VBUS 假上电）时的兜底火路
                    fire(c, "握手 arm+${armedAgo / 1000}s 且 agent 确死（沿未捕获，hub/VBUS 假上电）")
                } else if (edgeAgo < 0 && silentFor >= c.staleMaxS * 1000L) {
                    fire(c, "stale 兜底：静默 ${silentFor / 1000}s ≥ ${c.staleMaxS}s 且未见掉电沿（ErP 失效？）")
                }
            }
            State.FIRED -> {
                // rearm 路：服务器重新在线（插座已手动 --on、服务器已回来）——同样以数据新鲜度为准
                if (silentFor < c.bootClearS * 1000L) rearm(c, "agent 数据恢复在线")
            }
        }
    }

    private fun arm(c: Cfg, why: String, fromBoot: Boolean) {
        val level = batteryLevel()
        // CRIT is a floor on the BATTERY side — plugged (FULL_TRICK_LEVEL sentinel) means "never cut supply", so it
        // must NEVER be crit-rejected. An in-range-but-huge threshold once read 74% phone as crit vs a 99 config; the
        // level >= 100 guard keeps charging phones arming exactly like before (开发计划 §4.2's battery floor story).
        // FULL_TRICK_LEVEL(101) is the sentinel MainActivity/the service pass when the HAL says FULL while plugged —
        // it sits OUTSIDE the 0..crit range only by luck; guard explicitly so a config crit ≥ 101 (or a future level
        // scale change) can never reject a charging phone. Battery floor logic per 开发计划 §4.2.
        if (level in 0 until c.critLevel && level != FULL_TRICK_LEVEL) {
            log("拒 arm（电量 $level% < CRIT ${c.critLevel}%）：$why")
            pushState(c, "idle", "crit-battery($level%)", dry = c.dry)
            return
        }
        state = State.ARMED
        armedAt = SystemClockElapsed.get()
        // A UDP handshake arm IS a planned-shutdown signal — the ErP loss that follows is the event §4.2 counts
        // from, whenever it lands (edge capture in decide()/onPlugEdge starts the delay clock). A silence-only arm
        // with the cable already out is boot-phase noise: never start the countdown from it (T2 must stay silent).
        // The USB-link path pre-sets powerOffAt right after calling arm() (that instant IS its loss edge).
        powerOffAt = 0L
        val routeTag = if (!fromBoot) "，握手路" else ""   // templates with in-expression conditionals can confuse older parsers; keep it boring
        log("ARM：$why（dry=${c.dry}$routeTag）")
        pushState(c, reason = if (fromBoot) "arm(silence)" else "arm(handshake)")
    }

    private fun cancel(why: String) {
        state = State.IDLE; armedAt = 0L; powerOffAt = 0L
        log("CANCEL：$why")
        pushState(readCfg(), reason = "cancelled($why)")
    }

    private fun fire(c: Cfg, why: String) {
        state = State.FIRED
        firedAt = SystemClockElapsed.get()
        powerOffAt = 0L
        // Order discipline (see plugIo's comment above): the verdict line + write FIRST, panel card SECOND —
        // pushState must never sit in front of the supply cut, even as a queued task. log()'s own pushes ride
        // throwaway threads now, so nothing cosmetic can delay OFF any more.
        log("FIRE：$why | dry=${c.dry}")
        if (c.dry) {
            // dry-run 只读验据照常：证明协议/凭据到得了插座，唯独不写。
            plugIo.execute { verifyReadOnly(c) }
        } else {
            plugIo.execute { writeOff(c) }
        }
        pushState(c, reason = "fired($why)")
    }

    private fun rearm(c: Cfg, why: String) {
        state = State.IDLE; armedAt = 0L; powerOffAt = 0L; firedAt = 0L
        log("REARM：$why（本轮已复位）")
        pushState(c, reason = "rearm")
    }

    private fun resetToIdle(why: String) {
        state = State.IDLE; armedAt = 0L; powerOffAt = 0L
        log("回 idle：$why")
        pushState(readCfg(), reason = why)
    }

    // ---- plug side ----

    private fun propsGet(): JSONArray = JSONArray()
        .put(JSONObject().put("siid", 2).put("piid", 1))
        .put(JSONObject().put("siid", 11).put("piid", 2))

    /** --check 等价路：handshake + get_properties，一个写操作都没有。返回可读结果串或异常文本。 */
    private fun checkOnce(c: Cfg): String = try {
        val r = MiioClient(c.plugIp, c.plugToken).call("get_properties", propsGet())
        "CHECK_OK ${r.optJSONArray("result") ?: r}"
    } catch (e: Exception) {
        "CHECK_FAIL ${e.message}"
    }

    /** adb-smoke entry point: read-only credential probe with the current config snapshot. */
    fun checkNow(): String = checkOnce(readCfg())

    /** Timed diagnostic (real-off #2 hunt, 2026-11-25): runs the SAME get_properties exchange writeOff rides and
     *  logs CHECK_OK + wall ms. Entry = debug broadcast (same pattern as PLUGW_DRY_ARM); expect <1000 ms online —
     *  anything slower is the plug/transport, not our queuing. */
    fun debugCheck() {
        pushAsync {
            val r = try {
                val c = readCfg()
                val d = MiioClient(c.plugIp, c.plugToken)
                val (obj, ms) = d.callTimed("get_properties", propsGet())
                "CHECK_OK ${ms}ms ${obj.optJSONArray("result") ?: obj}"
            } catch (e: Exception) { "CHECK_FAIL ${e.message}" }
            log("DEBUG $r")
        }
    }

    fun checkNowSync(): String = try {
        val c = readCfg()
        val d = MiioClient(c.plugIp, c.plugToken)
        val (r, ms) = d.callTimed("get_properties", propsGet())
        "CHECK_OK ${ms}ms ${r.optJSONArray("result") ?: r}"
    } catch (e: Exception) {
        "CHECK_FAIL ${e.message}"
    }

    private fun verifyReadOnly(c: Cfg) {
        if (c.plugIp.isBlank() || c.plugToken.length != 32) {
            log("dry fire：插座凭据未配置，跳过只读验据")
            return
        }
        log("dry fire 只读验据 → ${checkOnce(c)}")
    }

    /** 真发火：set siid2/piid1=false + 回读确认；失败按 FIRE_RETRY=30s 由 tick 重试（幂等：fired 态下重复调）。 */
    private fun writeOff(c: Cfg) {
        if (c.plugIp.isBlank() || c.plugToken.length != 32) {
            log("fire 写插座跳过：凭据未配置")
            return
        }
        try {
            val d = MiioClient(c.plugIp, c.plugToken)
            val set = JSONArray().put(JSONObject().put("siid", 2).put("piid", 1).put("value", false))
            d.call("set_properties", set)
            val back = d.call("get_properties", JSONArray().put(JSONObject().put("siid", 2).put("piid", 1)))
            log("OFF_OK verify $back")
        } catch (e: Exception) {
            log("fire 写插座失败：${e.message}（30s 后随 tick 重试）")
            // keep FIRED but retry the write — mirrors NAS fire()'s rc!=0 retry path
            main.postDelayed({ if (running && state == State.FIRED) plugIo.execute { writeOff(c) } }, 30_000L)
        }
    }

    /** Manual trigger for adb/broadcast entry: --on semantics. Kept off the hot path; watcher never powers back up by itself. */
    fun powerOn() = plugIo.execute {
        val c = readCfg()
        if (c.dry) { log("powerOn 在 dry 模式下被忽略（真上电请走 NAS nas_off.py --on 或米家 App）"); return@execute }
        try {
            MiioClient(c.plugIp, c.plugToken).call(
                "set_properties",
                JSONArray().put(JSONObject().put("siid", 2).put("piid", 1).put("value", true)),
            )
            log("ON_OK")
        } catch (e: Exception) { log("powerOn 失败：${e.message}") }
    }

    private fun pushState(c: Cfg, forced: String? = null, reason: String = "", dry: Boolean = c.dry) {
        val name = forced ?: state.name.lowercase()
        try {
            pushEvent(JSONObject()
                .put("value", name)
                .put("title", "插座判决")
                .put("sub", if (reason.length > 60) reason.take(60) + "…" else reason)
                .put("level", when (name) { "fired" -> "error"; "armed" -> "warn"; else -> "ok" })
                .put("icon", "🔌")
                // ttl must outlive the gaps between events (a fired marker lingers an hour; idle/armed heartbeat
                // every tick with 300 s), otherwise PushStore expires the key and the panel's floor net hides it.
                .put("ttl", if (name == "fired") 3600 else 300))
        } catch (_: Exception) {}
    }

    /** Verdict history lives where the agent's own API cannot reach — the phone watches ITS OWN supply, so a local
     *  append-only log (files/plug_watch.log) is the only record that survives a power cut. The panel card shows the
     *  tail over USB reverse; this file is the post-mortem source. */
    private val histFile by lazy { File(context.filesDir, "plug_watch.log") }

    private fun logLine(msg: String): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg

    /** Push the whole journal tail (the watcher's own lines) under a second push key — the panel's Watch Log card. */
    private fun pushLogTail() {
        try {
            if (!histFile.exists()) return
            val bytes = histFile.inputStream().use { ins ->
                if (histFile.length() > 4_000) ins.skip(histFile.length() - 4_000)
                ins.readBytes()
            }
            pushEvent(JSONObject()
                .put("value", String(bytes, Charsets.UTF_8).trim())
                .put("title", "watch log")
                .put("level", "info")
                .put("ttl", 3600))
        } catch (_: Exception) {}
    }

    private fun log(msg: String) {
        if (msg == lastLog) return
        lastLog = msg
        android.util.Log.i("PlugWatcher", msg)
        try {
            // rotation keeps the file bounded; tail-first means a push at any moment carries the newest lines
            histFile.appendText(logLine(msg) + "\n")
            if (histFile.length() > 60_000) {
                val tail = histFile.inputStream().use { it.skip(histFile.length() - 30_000); it.readBytes() }
                histFile.writeBytes(tail)
            }
        } catch (_: Exception) {}   // logging must never kill a decision
        pushAsync { pushLogTail() }  // off any shared lane: a dead tunnel may burn threads, it may never burn the OFF path
    }

    private fun sleepMs(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}

/** Tiny indirection so unit-testable pure logic can swap the clock (SystemClock lives in android.* and is final-ish). */
object SystemClockElapsed {
    @Volatile var override: (() -> Long)? = null
    fun get(): Long = override?.invoke() ?: android.os.SystemClock.elapsedRealtime()
}
