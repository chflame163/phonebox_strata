package net.oxog.phonebox.deskpanel

import java.io.Closeable

/**
 * Keeps one live connection to the agent's /api/stream (server-sent events) and hands every event to [deliver].
 * Reconnects after errors. On Wi-Fi it reconnects every minute so a freshly plugged-in USB cable is preferred again.
 * "open" / "close" pseudo-events tell the panel when to stop or resume its polling fallback.
 */
class AgentStream(private val link: AgentLink, private val deliver: (event: String, data: String) -> Unit) {

    /** elapsedRealtime of the last DATA line received on any stream connection — a dead TCP mid-stream yields no
     *  exception until read() times out (Wi-Fi path ~2 min), so wall-clock freshness of this stamp is what tells a
     *  truly-live stream from one whose bytes stopped at SIGTERM. The panel's SSE stamps agentDataAt on every
     *  delivered event, which kept the plug watcher's confirm gate hostage through real-offs #4/#5 (+50/+291 s of
     *  zombie refresh); MainActivity now consults THIS instead when deciding whether to stamp (see its deliver path). */
    @Volatile var lastDataAt = 0L; private set

    /** elapsedRealtime of the last readLine() that did NOT time out — i.e. a socket-level liveness signal even for
     *  connections whose DATA stopped mid-frame (real-off #6/#7 forensics: the server died at T0+10 s but this
     *  stream's last DATA line stamp lagged to ~T0+5 min because OkHttp sat in read() through kernel retransmit
     *  backoff; an alive-flag pinned by 8 s SO_TIMEOUT wakes makes agentDataAt honesty instant for every lane). */
    @Volatile var socketAliveAt = 0L; private set

    // the live connection's liveness token — a guard thread stamps ONLY while its own token is still the current one,
    // so a superseded or stopped stream can never pin socketAliveAt again (and zeroes it on exit).
    @Volatile private var socketG: Any? = null

    @Volatile private var running = false
    // each start() gets a new generation; a thread from an earlier one (still connecting after a quick pause/resume)
    // sees the change and ends, so there is never more than one stream
    @Volatile private var generation = 0
    @Volatile private var current: Closeable? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        thread = Thread({ loop(gen) }, "agent-stream").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        socketG = null                      // any guard still spinning loses its token → stamps stop, tail zeroes
        try { current?.close() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
    }

    private fun loop(gen: Int) {
        val alive = { running && gen == generation }
        while (alive()) {
            var opened = false
            var body: Closeable? = null
            try {
                val (route, reply) = link.stream("/api/stream") ?: throw java.io.IOException("offline")
                if (reply.code != 200) { reply.body.close(); throw java.io.IOException("HTTP ${reply.code}") }
                if (!alive()) { reply.body.close(); break }
                body = reply.body
                current = body
                opened = true
                deliver("open", "{\"route\":\"${route.name.lowercase()}\"}")
                val since = System.currentTimeMillis()
                // #7 drill 4 final design: per-connection liveness watchdog with an HONEST tail — it pins
                // socketAliveAt once a second while its own connection is the live one, and when that connection
                // ends (reconnect/rebind/stop — any path) the guard zeroes the pin UNLESS a newer connection owns
                // the field already. Zeroing matters because MainActivity's deliver gate consults socketAliveAt as
                // an OR-branch: an abandoned zombie's metronome must not keep opening that gate for minutes after
                // real death (drill 1's agent静默 pinned at 0s while HB died = exactly that ghost pin). A graceful
                // 60 s WIFI recycle zeroes for ~300 ms until the next guard takes over — inside the 12 s window,
                // harmless (drill 3's oscillation was zeroing+re-PIN racing with an ungate-d wide open; with the
                // gate narrowed again and newest-wins tokens, healthy streams never stutter: their lastDataAt moves
                // every second anyway).
                val token = java.util.concurrent.atomic.AtomicBoolean(true)
                socketG = token   // next connection's guard supersedes this one; stop() nulls it
                val guard = Thread {
                    try {
                        while (running && gen == generation && token.get() && current === body) {
                            socketAliveAt = android.os.SystemClock.elapsedRealtime()
                            Thread.sleep(1000)
                        }
                    } catch (_: InterruptedException) {} finally {
                        // our connection is done; drop the pin unless somebody newer already re-pinned (their guard's
                        // first stamp lands within ms of taking the token, so this race window is ~one sleep tick).
                        if (socketG === token) socketAliveAt = 0L
                    }
                }.apply { isDaemon = true; start() }   // (the loop's finally block nulls `current` — the guard self-exits on that)
                val r = reply.body.bufferedReader(Charsets.UTF_8)
                var event = "message"
                val data = StringBuilder()
                while (alive()) {
                    val line = try { r.readLine() } catch (e: Exception) {
                        // #7 drill forensics, part two (11:15 run, DIAG again red-handed): OkHttp's call watchdog
                        // throws SO_TIMEOUT from its OWN thread — the readLine stack never unwinds through ours, so
                        // neither our catch nor any socketAliveAt feed on this path ever ran (agent静默 oscillating
                        // 0↔15 == MainActivity's stamp gate kept opening on fresh delivered replays while our loop
                        // was already dead). Fix the right layer instead of chasing unwinds: a per-connection
                        // watchdog thread stamps socketAliveAt every second and stops by itself the moment the real
                        // socket read dies (EOF/exception ends this loop iteration → new connection → fresh guard;
                        // a zombie TCP whose bytes stopped but whose read() still "succeeds" keeps it pinned — which
                        // is EXACTLY when pinning is right: data lanes that answer keep the host honest).
                        if (e.cause is java.net.SocketTimeoutException) {
                            socketAliveAt = android.os.SystemClock.elapsedRealtime()
                            continue
                        }
                        throw e
                    } ?: break
                    socketAliveAt = android.os.SystemClock.elapsedRealtime()   // any successful read == path alive RIGHT NOW
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) deliver(event, data.toString())
                            event = "message"
                            data.setLength(0)
                            if (route == AgentLink.Route.WIFI && System.currentTimeMillis() - since > 60_000) break
                        }
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> { lastDataAt = android.os.SystemClock.elapsedRealtime(); data.append(line.substring(5).trimStart()) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (current === body) current = null // a newer thread may own it already
            }
            if (opened) deliver("close", "{}")
            if (alive()) try { Thread.sleep(if (opened) 300 else 2000) } catch (_: InterruptedException) {}
        }
    }
}
