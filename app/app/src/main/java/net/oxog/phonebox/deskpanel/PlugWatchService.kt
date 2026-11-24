package net.oxog.phonebox.deskpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/**
 * Foreseat carrier for [PlugWatcher] — the panel app is a kiosk home Activity, so a started foreground service
 * keeps the watcher alive across doze with one persistent low notification (开发计划 §5.2). It reuses MainActivity's
 * AgentLink for two things: reading the config snapshot (GET /api/config → display.plug* keys) and pushing events
 * (POST /api/push/plug_delay_state) — both ride whatever transport is up (USB reverse first, Wi-Fi pinning second).
 *
 * Doze note: MainActivity's deep-standby probe loop keeps this process's Handler ticking at 15 s/60 s anyway; the
 * service adds durability, not wake-ups. The doze whitelist (`dumpsys deviceidle whitelist +pkg`, root one-liner)
 * is a deployment prerequisite — without it the 180 s countdown can stretch to minutes and §4.2 timing dies.
 */
class PlugWatchService : Service() {

    lateinit var link: AgentLink
    lateinit var watcher: PlugWatcher
    private var cfgSnapshot: Map<String, Any?> = emptyMap()
    @Volatile private var cfgFetchedAt = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTI_ID, buildNoti())
        link = AgentLink(this)
        watcher = PlugWatcher(
            context = this,
            cfg = { freshCfg() },
            agentDataAt = MainActivity.agentDataAtOf,   // the panel Activity's clock — one source of truth
            usbLinkUp = { MainActivity.usbLinkUpOf() || readUsbLink() },
            batteryLevel = { stickyBatteryLevel() },
            pushEvent = { j -> io.execute { pushThroughAgent(j) } },
        )
        // NOTE the verdict journal lives in PlugWatcher.log (files/plug_watch.log + a tail push under key
        // plug_watch_log) — one writer, no duplication here. The panel's Watch Log card reads that second key.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        watcher.seed(stickyPlugged())
        try { registerReceiver(debugReceiver, android.content.IntentFilter(ACTION_DEBUG_ARM), Context.RECEIVER_NOT_EXPORTED) } catch (_: Exception) {}
        try { registerReceiver(usbLinkReceiver, android.content.IntentFilter("android.hardware.usb.action.USB_STATE"), Context.RECEIVER_NOT_EXPORTED) } catch (_: Exception) {}
        watcher.start()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(debugReceiver) }
        runCatching { unregisterReceiver(usbLinkReceiver) }
        watcher.stop()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    /** Config at most once per 30 s — the agent answers instantly on USB, and while it is off we keep the last snapshot.
     *  A successful GET doubles as an "agent is answering" signal for the plug watcher (shared clock): while the
     *  server lives, this poll keeps sharedAgentDataAt fresh even if the WebView's own stream idles. */
    private fun freshCfg(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        if (now - cfgFetchedAt < 30_000 && cfgSnapshot.isNotEmpty()) return cfgSnapshot
        cfgFetchedAt = now
        try {
            // loopback GET straight at 8765 (adb-reverse tunnel) — the agent's /api/state & /api/config accept
            // unauthenticated loopback; link.request would add Bearer LAN auth and 401 on tokenless pairings, which
            // used to freeze the cfg snapshot and left the cancel path reading a stale clock.
            val c = java.net.URL("http://127.0.0.1:8765/api/config").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 800; c.readTimeout = 3000
            val code = c.responseCode
            if (code < 400) {
                MainActivity.sharedAgentDataAt = SystemClock.elapsedRealtime()   // answering = host alive
                val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val display = JSONObject(body).optJSONObject("config")?.optJSONObject("display")
                    ?: JSONObject(body).optJSONObject("display") ?: JSONObject()
                // PlugWatcher.readCfg keys everything as "display.plug*" — flatten WITH that prefix, otherwise the
                // snapshot misses every key and the watcher silently runs on its hardcoded defaults (dry-run lesson,
                // 2026-11-24: fire waited for the 300 s stale floor instead of the configured 60 s).
                cfgSnapshot = display.keys().asSequence().associateWith { "display.$it" to display.opt(it) }
                    .entries.associate { it.value.first to it.value.second }
            }
        } catch (_: Exception) {}
        return cfgSnapshot
    }

    /** POST with the payload as body (AgentLink.send posts a zero-length fixed stream, which PushStore would
     *  floor to "{}" — we need the value itself). The USB reverse tunnel answers even while the server sleeps,
     *  because adb reverse is kept by the adb daemon on THIS phone (usbMode=adb survives ErP loss). */
    private fun pushThroughAgent(j: JSONObject) {
        // the watcher's journal push rides its own key; everything else is the state card
        val keyName = if (j.optString("title") == "watch log") "plug_watch_log" else "plug_delay_state"
        try {
            val ttl = j.optInt("ttl", 300)
            val c = java.net.URL("http://127.0.0.1:8765/api/push/$keyName?ttl=$ttl")
                .openConnection() as java.net.HttpURLConnection
            // agent's loopback /api/* accepts POST without a token (phone-side USB tunnel is the trust boundary)
            c.connectTimeout = 800; c.readTimeout = 3000
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            val b = j.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            val code = c.responseCode
            if (code >= 400) android.util.Log.w("PlugWatchPush", "agent push rc=$code")
            (if (code >= 400) c.errorStream else c.inputStream)?.close()
        } catch (e: Exception) {
            android.util.Log.w("PlugWatchPush", "push failed: ${e.message}")
        }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun readUsbLink(): Boolean = try {
        val u = registerReceiver(null, android.content.IntentFilter("android.hardware.usb.action.USB_STATE"))
        u != null && (u.getBooleanExtra("configured", false) || u.getBooleanExtra("connected", false))
    } catch (e: Exception) { false }

    /** The service's own USB_STATE receiver: edges drive the watcher even while the Activity is recreated/paused,
     *  and (like MainActivity's) it survives deep sleep as a sticky hook — this is the ErP edge's primary path. */
    private val usbLinkReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val up = intent?.getBooleanExtra("configured", false) == true ||
                intent?.getBooleanExtra("connected", false) == true
            watcher.onUsbLink(up)
        }
    }

    private fun stickyIntent(): Intent? = try {
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: Exception) { null }

    /** BatteryManager.BATTERY_* constants inlined (1/2/4/3). T15 field lesson: the framework's view can go
     *  STALE-TRUE across a hub power cycle (healthd discharge, dumpsys "USB powered" stuck true for 7+ min), so
     *  CHARGING/FULL status alone resurrects "plugged" only while DISCHARGE state hasn't been seen — trust the
     *  flag OR fresh charging, but never let a stale flag beat a live discharge status. */
    private fun stickyPlugged(i: Intent? = stickyIntent()): Boolean {
        i ?: return true
        val pluggedFlag = (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        if (status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING)
            return false
        return pluggedFlag || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Percent, or FULL_TRICK_LEVEL when the HAL says FULL while plugged (MainActivity's guard for full-charge phones). */
    private fun stickyBatteryLevel(): Int {
        val i = stickyIntent() ?: return -1
        // CRITICAL: feed the watcher's live plug flag from every sticky read (framework EXTRA_PLUGGED) — this is
        // what makes the ErP loss edge unmissable even when the BATTERY_CHANGED broadcast itself is swallowed by
        // doze or a receiver gap (开发计划 §4.3-1's runtime half; the whitelist covers the wakeups).
        val plugged = stickyPlugged(i)
        watcher.setPlugged(plugged)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (!plugged) return level * 100 / scale
        return if (i.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_FULL)
            PlugWatcher.FULL_TRICK_LEVEL else level * 100 / scale
    }

    /** MainActivity's powerReceiver forwards edges here (static entry point → the running instance). */
    companion object {
        const val CHANNEL_ID = "plug-watcher"
        const val NOTI_ID = 42
        @Volatile var instance: PlugWatchService? = null

        /** Edge from the Activity's receiver — plus a sticky re-read, so a missed broadcast still sets the flag. */
        fun notifyEdge(svcContext: Context?, plugged: Boolean) {
            val s = instance ?: return
            // refresh the watcher's live plug flag from this edge too (its tick samples via these sticky reads)
            s.watcher.setPlugged(plugged)
            s.watcher.onPlugEdge(plugged)
        }

        /** Debug aid: adb shell am broadcast -a net.oxog.phonebox.PLUGW_DRY_ARM — arm without touching services. */
        const val ACTION_DEBUG_ARM = "net.oxog.phonebox.PLUGW_DRY_ARM"
    }

    /** Local test hook (no production path ever calls this): UDP-less arm for the dry-run matrix. */
    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            android.util.Log.i("PlugWatcher", "DEBUG arm via broadcast")
            watcher.debugArm()
        }
    }


    private fun buildNoti(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "插座延时断电判决", NotificationManager.IMPORTANCE_LOW))
        // android.R.drawable icons are private since API 30 — a tiny vector of our own (ic_plug_noti) is the safe pick.
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("plug watcher")
            .setContentText("dry-run 判决监听中")
            .setSmallIcon(R.drawable.ic_plug_noti)
            .setOngoing(true)
            .build()
    }
}
