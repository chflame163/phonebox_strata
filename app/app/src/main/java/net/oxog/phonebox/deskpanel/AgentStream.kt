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
                reply.body.bufferedReader(Charsets.UTF_8).use { r ->
                    var event = "message"
                    val data = StringBuilder()
                    while (alive()) {
                        val line = r.readLine() ?: break
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
