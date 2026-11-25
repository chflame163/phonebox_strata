package net.oxog.phonebox.deskpanel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Full-screen desk panel. The UI is plain HTML/JS bundled in assets/www.
 * The UI calls "/agent/..." URLs; they are intercepted here and forwarded to the PhoneBox agent on the PC,
 * over USB (adb reverse) or, once paired, over Wi-Fi / Tailscale (see AgentLink). The WebView itself therefore
 * never makes local-network requests (no CORS, mixed-content or local-network-access issues).
 */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var web: WebView
    private lateinit var link: AgentLink
    private lateinit var stream: AgentStream
    @Volatile private var volumeKeysFlipPages = true
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var lastLux = -1f
    private var lastLuxAt = 0L
    private var near: Boolean? = null

    // Power standby: a panel fed from a server should sleep its screen when the server powers off and light it again
    // once the server is back. "Does the cable carry power" is the wrong question: ChargeGuard stops charging by
    // switching off the very port that feeds the phone (while the computer keeps running), and a hub reset or a driver
    // crash can drop the USB link while Wi-Fi still carries data. Standby therefore follows DATA REACHABILITY — the
    // agent answering over either transport — with the phone's own USB data link as a second opinion, and the power
    // state only decides when it is worth probing. The system's own screen-off timeout does the actual sleeping; this
    // code only decides whether the app may hold the screen on. No root: a normal app cannot force the display off.
    @Volatile private var standbyEnabled = true
    @Volatile private var standbyDelayMs = 30_000L
    @Volatile private var pluggedIn = true        // power state: used to time probes, not to decide standby
    // fork 2026-11-24 (plug-delay phone watcher): companion-exposed hooks so PlugWatchService reads the same clocks
    // this Activity's standby logic already maintains — one source of truth for agent silence and USB liveness.
    @Volatile private var agentDataAt = 0L        // elapsedRealtime of the last answer from the agent, either route
    @Volatile private var usbLinkUp = false       // the phone sees an enumerated USB data link => that computer is up
    @Volatile private var hostAlive = true        // what standby acts on; reconciled by healthTick
    @Volatile private var hostLostSince = 0L      // elapsedRealtime of the first failing poll; 0 while alive
    @Volatile private var deepStandby = false     // 熄屏 5 分钟后的省电态：只剩接收器在等主机回来
    @Volatile private var lastInteractive = true
    @Volatile private var screenOffSince = 0L     // elapsedRealtime of the screen going dark; 0 while awake
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probes = Executors.newSingleThreadExecutor()
    private var powerReceiver: BroadcastReceiver? = null

    /** The app may keep the display on whenever it believes the computer is running, or when standby is off entirely. */
    private fun refreshKeepAwake() {
        if (!standbyEnabled || hostAlive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Force ViewRootImpl to push the new window attributes on the next traversal: without a layout request a
        // static page never relayouts, so WindowManager keeps the stale keep-awake wakelock forever (Android 11+).
        window.decorView.requestLayout()
    }

    private fun registerPowerReceiver() {
        // ACTION_POWER_CONNECTED/DISCONNECTED are implicit-broadcast-restricted since Android 8 and never reach a
        // runtime receiver on ANY modern phone. Watch the exempt sticky ACTION_BATTERY_CHANGED instead and treat an
        // EXTRA_PLUGGED flip as the connect/disconnect edge.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // A charged-to-100% phone drops EXTRA_PLUGGED while EXTRA_STATUS keeps reporting CHARGING/FULL:
                // treat those states as "cable in" too, otherwise a full panel phone sleeps during normal operation.
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    ?: BatteryManager.BATTERY_STATUS_UNKNOWN
                val plugged = PowerReceiver.edge(intent, "runtime") ?: return
                android.util.Log.i("PhoneBox", "plug edge via battery-changed: plugged=" + plugged)
                // fork 2026-11-24: the ErP edge feeds the plug watcher too. The service instance can be dead when
                // the Activity lives (crash-restart of a START_STICKY service leaves it null until next start),
                // so re-assert startService here — cheap, idempotent, and revives the watcher exactly at the moment
                // its job begins. POSTED to the main thread: Activity context only allows started-service starts.
                val svcIntent = Intent(this@MainActivity, PlugWatchService::class.java)
                mainHandler.post {
                    // the Activity itself is a started service host (fixes the dead-instance edge case):
                    runCatching { startService(svcIntent) }
                    try {
                        startForegroundService(svcIntent)   // API 26+: guarantees foreground type specialUse in manifest
                    } catch (_: Exception) {}
                    PlugWatchService.notifyEdge(this@MainActivity, plugged)
                }
                mainHandler.post { onPowerChanged(plugged) }
            }
        }
        powerReceiver = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(receiver, filter)
        // The sticky battery broadcast settles the initial state (a reboot into an unpowered hub should not light up).
        run {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val st = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            pluggedIn = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
                st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        PowerReceiver.lastPlugged = pluggedIn
        android.util.Log.i("PhoneBox", "standby receiver registered, init pluggedIn=" + pluggedIn)
        refreshKeepAwake()
    }

    /**
     * A power edge no longer decides standby (ChargeGuard cuts the power of a running computer), but plugging in is
     * still the moment worth waking for: the phone's own USB link going up proves that computer booted, and charging
     * grants only a brief window of CPU time — use it to reach the agent right away, and let the panel light up on
     * the answer rather than on the electricity. A reboot's power blip is absorbed by the grace period in
     * reconcileStandby(), which now watches data instead of the cable.
     */
    private fun onPowerChanged(connected: Boolean) {
        android.util.Log.i("PhoneBox", "power changed: connected=$connected standby=$standbyEnabled delayMs=$standbyDelayMs (standby watches agent data)")
        pluggedIn = connected
        mainHandler.removeCallbacks(healthTick)
        mainHandler.post(healthTick)
        if (connected) probeAgent()
    }

    /** The phone's own view of its USB data link: an enumerated (configured) link means that computer is running,
     *  which is worth knowing even when Wi-Fi is down. Sticky, so reading it costs one binder call. */
    private fun readUsbLink(): Boolean = try {
        val u = registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        u != null && (u.getBooleanExtra("configured", false) || u.getBooleanExtra("connected", false))
    } catch (e: Exception) {
        false
    }

    /**
     * The instant the computer enumerates this phone, USB_STATE says so - that is a hardware-level proof that it is
     * running, and it arrives about a second after its kernel comes up, long before any service does. Reacting to the
     * edge instead of waiting for the next poll is what lights the panel the moment a booted server appears, and it
     * is also the only path that works when the agent starts late or the Wi-Fi pairing is stale.
     */
    private var usbLinkReceiver: BroadcastReceiver? = null
    private fun registerUsbLinkReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val up = intent?.getBooleanExtra("configured", false) == true ||
                    intent?.getBooleanExtra("connected", false) == true
                if (up == usbLinkUp) return
                stampUsbLink(up)
                android.util.Log.i("PhoneBox", "USB data link -> $up (reconciling now)")
                mainHandler.removeCallbacks(healthTick)
                mainHandler.post(healthTick)
            }
        }
        usbLinkReceiver = receiver
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(receiver, filter)
    }

    /** Watch the reachability of the computer: quickly while it answers, slowly (and with a short CPU wake, so doze
     *  lets the request land) after the panel has slept. */
    private val healthTick = object : Runnable {
        override fun run() {
            stampUsbLink(readUsbLink())
            reconcileStandby()
            val interactive = try {
                getSystemService(PowerManager::class.java)?.isInteractive ?: true
            } catch (e: Exception) {
                true
            }
            if (interactive != lastInteractive) {
                lastInteractive = interactive
                if (!interactive) screenOffSince = SystemClock.elapsedRealtime()
            }
            // 主机不在且屏幕已黑着满 DEEP_AFTER_MS：进入省电态（面板不再每秒轮询、stream 不再每两秒重连）
            if (!hostAlive && !deepStandby && !interactive && screenOffSince != 0L &&
                SystemClock.elapsedRealtime() - screenOffSince >= DEEP_AFTER_MS) enterDeepStandby()
            // 判死之后主动问一次 agent：清醒态握着 CPU 让请求落地；深睡态不握锁，让 doze 继续睡——
            // 主机开机时 USB 事件会自己把面板叫醒，这条只是"数据线不带数据"时的兜底。
            if (!hostAlive) probeAgent(holdCpu = !deepStandby)
            mainHandler.postDelayed(this, when {
                hostAlive -> HEALTH_POLL_MS
                deepStandby -> DEEP_POLL_MS
                else -> STANDBY_POLL_MS
            })
        }
    }

    /**
     * The standby decision: no answer from the agent for AGENT_SILENCE_MS and no USB data link means the computer is
     * off or unreachable, so the panel stops holding the screen and Android's timeout sleeps it — whether or not the
     * cable still carries power. Coming back online lights the panel again on its own, no touch needed.
     */
    private fun reconcileStandby() {
        val silentFor = SystemClock.elapsedRealtime() - agentDataAt
        if (!standbyEnabled || silentFor < AGENT_SILENCE_MS || usbLinkUp) {
            hostLostSince = 0L
            if (!hostAlive) {
                hostAlive = true
                if (deepStandby) exitDeepStandby()
                android.util.Log.i("PhoneBox", "standby: the computer answers again (silent for ${silentFor / 1000}s) -> waking")
                refreshKeepAwake()
                wakeAndShowPanel()
            }
            return
        }
        if (hostLostSince == 0L) {
            hostLostSince = SystemClock.elapsedRealtime()
            return
        }
        if (hostAlive && SystemClock.elapsedRealtime() - hostLostSince >= standbyDelayMs) {
            hostAlive = false
            android.util.Log.i("PhoneBox", "standby: agent silent ${silentFor / 1000}s, no USB link -> releasing the screen")
            refreshKeepAwake() // releases the hold; the screen now sleeps at the system timeout
        }
    }

    /** Ask the agent directly, off the UI thread: the panel's own polls stop while the WebView idles, and a phone
     *  that has slept must still notice the computer coming back. A held CPU lock keeps the request from being
     *  frozen midway; a reply reopens the panel through healthTick. */
    private fun probeAgent(holdCpu: Boolean = true) {
        probes.execute {
            val held = if (!holdCpu) null else try {
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBox:probe")?.apply { acquire(6_000L) }
            } catch (e: Exception) {
                null
            }
            try {
                val r = link.request("GET", "/api/state")
                runCatching { r.body.use { it.readBytes() } }
                if (r.code < 400) stampAgentData()
            } catch (e: Exception) {
            } finally {
                runCatching { held?.release() }
            }
        }
    }

    /** fork 2026-11-24: single clock writer — instance field for standby + companion shared fields for the plug watcher.
     *  #8 drill-11 GATE (the write-side twin of freshCfg's USB gate): stamps only while the USB data link is up ==
     *  bytes from a LIVE server adbd/reverse target. The phone-local reverse listener (the APK's own AgentStream on
     *  8765) answers even with the server dead — that's the self-answer loop DIAG exposed one last time (12:08 run:
     *  agent静默 pinned 0↔15 while HB died for real, then CANCEL). ErP drops VBUS/gadget on true power-off, so the
     *  gate closes exactly when it should; a reboot re-enumerates within seconds and stamps resume.
     *  #8 drill-12: the gadget-state broadcast alone (configured=true) is NOT proof of a live host — hub/charger
     *  quirks keep it sticky while the server's adbd is gone (T3 lesson), so require configured || fresh real data
     *  (stream DATA within 12 s == bytes from an answering server). A dead-server episode fails both and stops
     *  stamping; a live host with a flaky gadget flag still passes via stream freshness. */
    private fun stampAgentData() {
        if (!readUsbLink() && android.os.SystemClock.elapsedRealtime() - stream.lastDataAt > AGENT_SILENCE_MS) return
        agentDataAt = SystemClock.elapsedRealtime()
        sharedAgentDataAt = agentDataAt
    }

    /** USB link edge/level: mirror to the companion so a recreated Activity never resets "the host is up". */
    private fun stampUsbLink(up: Boolean) {
        usbLinkUp = up
        sharedUsbLinkUp = up
    }

    /**
     * The screen has been dark for DEEP_AFTER_MS with nothing answering: this phone is a panel, so quiet down to a
     * bare watcher. The cost of staying "ready" is not the background apps (there is only this one) but our own
     * polling - the page asks the agent once a second, the stream reconnects every two, and each probe holds the CPU
     * six seconds, which is why a dark phone never reached doze at all (measured: 68 mA with the screen off, 0 mAh in
     * doze because doze never happened). Stopping both leaves the two receivers registered: they cost nothing, and a
     * booted server is seen by them the moment it enumerates the phone. Other apps' background processes get trimmed
     * once here too, for the same reason - nothing on this device is allowed to spend its battery.
     */
    private fun enterDeepStandby() {
        deepStandby = true
        stream.stop()
        runOnUiThread { web.onPause() }
        var trimmed = 0
        val am = getSystemService(ActivityManager::class.java)
        if (am != null) for (p in TRIM_PACKAGES) runCatching { am.killBackgroundProcesses(p); trimmed += 1 }
        android.util.Log.i("PhoneBox", "deep standby: panel polling stopped, background processes trimmed ($trimmed pkgs)")
    }

    /** The computer is back: bring the live stream and the page's own polling back before lighting the screen. */
    private fun exitDeepStandby() {
        deepStandby = false
        runOnUiThread {
            web.onResume()
            stream.start()
        }
        android.util.Log.i("PhoneBox", "deep standby over: stream and polling resumed")
    }

    /** Light the display from the phone side and put the panel in front; harmless when already showing. */
    private fun wakeAndShowPanel() {
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        if (pm != null && !pm.isInteractive)
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "PhoneBox:powerStandby",
            ).acquire(3_000L) // the flag lets a plain app wake the screen; no root, no device owner needed
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        // A swipe keyguard (Lineage default, isSecure=false) would otherwise keep the woken panel behind a lock
        // screen nobody is there to swipe; requestDismissKeyguard clears it (DISABLE_KEYGUARD is a normal perm).
        try {
            (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        } catch (e: Exception) {
            android.util.Log.w("PhoneBox", "keyguard dismiss failed: " + e)
        }
    }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // standby may release it again below
        link = AgentLink(this)
        stream = AgentStream(link) { event, data ->
            // "close" is the stream's own goodbye, not an answer from the agent; everything else is proof of life —
            // UNLESS it's a replayed buffer after the socket died: deliver happens at frame boundaries, which on a
            // dead TCP can trail the last real byte by minutes (real-off #6 audit: FIRE ate edge+171s because this
            // stamp rode delivered frames while stream.lastDataAt said the bytes stopped at T0+11.5s). Gate on the
            // stream's own last-DATA-line clock; fresh stamps only within one AGENT_SILENCE window of true activity.
            // #7 final truth: bytes flowing again (lastDataAt fresh) OR the socket merely answering our 8 s SO_TIMEOUT
            // polls (socketAliveAt fresh — a live path that may be between events) are both host-alive proof; a dead
            // server makes reads escalate to EOF within ~20-30 s and neither clock moves, so zombie lag can't pin this.
            // #7 drill 3 final call: the SSE deliver path is now PURELY lastDataAt-gated. socketAliveAt pinning was
            // first widened here, then the per-connection guard thread turned it into an every-second metronome for
            // as long as a (possibly zombie) connection object lives — agent静默 0↔15s oscillation in all three drills
            // was that metronome, not bytes. DATA freshness alone is the honest "host answering" signal; keep the
            // guard's socketAliveAt for forensics/DIAG only.
            // #7 final: lastDataAt OR a live guard's socketAliveAt — the latter rescues the rare-but-real case of a
            // connection that answers our 8 s polls but produced no DATA line in >12 s (agent busy between pushes);
            // the metronome itself is honest now (newest-token-wins + zero-on-exit, see AgentStream's guard comment),
            // so it can never ghost-pin a zombie.
            if (event != "close" && android.os.SystemClock.elapsedRealtime() - maxOf(stream.lastDataAt, stream.socketAliveAt) <= AGENT_SILENCE_MS) stampAgentData()
            val js = "window.onAgentStream && window.onAgentStream(${JSONObject.quote(event)}, ${JSONObject.quote(data)})"
            runOnUiThread { web.evaluateJavascript(js, null) }
        }

        web = WebView(this)
        web.setBackgroundColor(0xFF000000.toInt())
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    Log.e("PhoneBoxWeb", "${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == ASSET_HOST && url.path?.startsWith("/agent/") == true)
                    return if (fromPanel(request)) proxy(request) else FORBIDDEN()
                val r = assetLoader.shouldInterceptRequest(url)
                // the panel is made of ES modules, which need a JavaScript MIME type
                if (r != null && url.path?.endsWith(".js") == true) r.mimeType = "text/javascript"
                return r
            }

            // Old WebView without document-start scripts: hand the bridge key to the main frame only (evaluateJavascript
            // never runs in iframes), and only when it shows the panel. Bridge calls before it arrives get nothing.
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) = giveKey(view, url)
            override fun onPageFinished(view: WebView, url: String?) = giveKey(view, url)
        }
        if (documentStartKey)
            WebViewCompat.addDocumentStartJavaScript(web, "window.__phoneboxKey='$bridgeKey';", setOf("https://$ASSET_HOST"))
        web.addJavascriptInterface(Bridge(), "Android")
        // Lets you debug the panel from the computer via chrome://inspect.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        web.loadUrl("https://$ASSET_HOST/assets/www/index.html")
        hideSystemUi()
        registerPowerReceiver()
        registerUsbLinkReceiver()
        // Start from "the computer is there" and let the polls correct it: the first request needs a moment, while the
        // USB data link is known at once, so a phone booting into an unpowered hub still goes dark shortly after.
        // #8 drill-7 poison fix: this seed used to read as instant "revival" to the plug watcher's cancel path on
        // every fresh process (drills cancelled live episodes at tick 1 six times running). Seed it 60 s in the PAST:
        // standby logic behaves identically (silentFor>=12 s => host-lost until first real answer), while the
        // watcher's freshness windows (bootClear 45 s) refuse to cancel on a seed that was never an answer.
        // #8 drill-13 THE end of the story: every seed/gate/floor variant kept losing because adb install + am start
        // churn MainActivity constantly BY DESIGN (the watcher service pushes /api/config, whose WebView proxy path
        // recreates the Activity) — so "seed only if empty" re-seeded now-60s on nearly every birth and fresh
        // processes always read agent静默≈15→0. The companion static survives all of it (T3), so trust it verbatim:
        // no seeding at all; cold boot (==0) simply reads as silence until healthTick's first real answer (~2 s).
        agentDataAt = sharedAgentDataAt    // inherit truth; never fabricate it
        stampUsbLink(readUsbLink())
        hostAlive = true
        mainHandler.postDelayed(healthTick, HEALTH_POLL_MS)
        // fork 2026-11-24: the plug-delay watcher (NAS successor) runs as a foreground service beside the kiosk home.
        instance = this
        try {
            startService(Intent(this, PlugWatchService::class.java))
        } catch (e: Exception) {
            android.util.Log.w("PhoneBox", "plug watcher service failed to start: $e")
        }
    }

    /**
     * Only the panel itself may reach the agent. Every frame of the WebView comes through here, including the sandboxed
     * iframe of a Custom HTML widget, which is meant to talk to the panel through postMessage only. The panel sends
     * X-PhoneBox-Panel on its requests; a sandboxed frame cannot add it without a CORS preflight, which is refused.
     * Images (album art, process icons) cannot carry headers, but they are read-only GETs.
     */
    private fun fromPanel(req: WebResourceRequest): Boolean =
        req.requestHeaders.keys.any { it.equals(PANEL_HEADER, ignoreCase = true) } ||
            (req.method == "GET" && req.url.path in READ_ONLY_IMAGES)

    private fun proxy(req: WebResourceRequest): WebResourceResponse {
        val path = req.url.encodedPath!!.removePrefix("/agent")
        val query = req.url.encodedQuery?.let { "?$it" } ?: ""
        val r = link.request(req.method, path + query)
        // Whatever the panel gets back, the computer behind it is running — whichever transport carried it.
        if (r.code < 400) stampAgentData()
        val ct = r.contentType ?: "application/octet-stream"
        val mime = ct.substringBefore(';').trim()
        val charset = if (ct.contains("charset=")) ct.substringAfter("charset=").trim() else null
        return WebResourceResponse(mime, charset, r.code, if (r.code < 400) "OK" else "Error", NO_STORE, r.body)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    /** Volume up / down flip to the previous / next page (unless turned off in the settings). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "prev"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "next"
            else -> null
        }
        if (key == null || !volumeKeysFlipPages) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
            web.evaluateJavascript("window.onAndroidKey && window.onAndroidKey('$key')", null)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kiosk behaviour: Back does not leave the app, it returns to the first page.
        web.evaluateJavascript("window.onAndroidBack && window.onAndroidBack()", null)
    }

    override fun onResume() {
        super.onResume()
        refreshKeepAwake() // a wake-up with no computer behind it (touch, home button) must not silently re-arm the hold
        web.onResume()
        stream.start()
        // Someone is looking at it: leave the power-saving state and restart the five-minute window if they walk away.
        if (deepStandby) exitDeepStandby()
        screenOffSince = 0L
        lastInteractive = true
        mainHandler.removeCallbacks(healthTick)
        mainHandler.post(healthTick) // look at reachability right now, then back to the usual rhythm
        hideSystemUi()
        sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        stream.stop()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mainHandler.removeCallbacksAndMessages(null)
        probes.shutdownNow()
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }
        powerReceiver = null
        usbLinkReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbLinkReceiver = null
        super.onDestroy()
    }

    /**
     * Room light (lux) and proximity for the panel: brightness that follows the room, the night screen when the room
     * goes dark, and waking the night screen with a wave of the hand. Light is passed on when it changes noticeably.
     */
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lux = e.values[0]
                val now = SystemClock.elapsedRealtime()
                val jump = lastLux < 0 || kotlin.math.abs(lux - lastLux) > maxOf(3f, lastLux * 0.15f)
                if (!jump && now - lastLuxAt < 10_000) return
                if (now - lastLuxAt < 700) return
                lastLux = lux; lastLuxAt = now
                web.evaluateJavascript("window.onAndroidSensor && window.onAndroidSensor('light', $lux)", null)
            }
            Sensor.TYPE_PROXIMITY -> {
                val isNear = e.values[0] < minOf(5f, e.sensor.maximumRange)
                if (isNear == near) return
                near = isNear
                web.evaluateJavascript("window.onAndroidSensor && window.onAndroidSensor('proximity', $isNear)", null)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /**
     * Key for the bridge: addJavascriptInterface puts window.Android into EVERY frame of the WebView, including the
     * sandboxed iframe of a Custom HTML widget. Each call therefore carries this key, which only frames of the panel's
     * own origin receive (a document-start script with an origin rule; the sandboxed iframe has an opaque origin).
     * Calls without it do nothing (fail closed). A WebView without document-start scripts gets the key through
     * evaluateJavascript, which only reaches the main frame (see giveKey).
     */
    private val bridgeKey = java.util.UUID.randomUUID().toString()
    private val documentStartKey by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    private fun giveKey(view: WebView, url: String?) {
        if (!documentStartKey && url?.startsWith("https://$ASSET_HOST/") == true)
            view.evaluateJavascript("window.__phoneboxKey='$bridgeKey';", null)
    }

    inner class Bridge {
        private fun ok(key: String) = key == bridgeKey

        /** Screen brightness 0..1; a negative value returns control to the system setting. */
        @JavascriptInterface
        fun setBrightness(key: String, value: Float) {
            if (!ok(key)) return
            runOnUiThread {
                val lp = window.attributes
                lp.screenBrightness =
                    if (value < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else value.coerceIn(0.01f, 1f)
                window.attributes = lp
            }
        }

        @JavascriptInterface
        fun haptic(key: String) {
            if (!ok(key)) return
            val v = getSystemService(Vibrator::class.java) ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(14, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        /** Notification vibration; pattern is a "wait,vibrate,wait,vibrate..." list of milliseconds. */
        @JavascriptInterface
        fun buzz(key: String, pattern: String) {
            if (!ok(key)) return
            val v = getSystemService(Vibrator::class.java) ?: return
            val timings = pattern.split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (v.hasVibrator() && timings.isNotEmpty()) v.vibrate(VibrationEffect.createWaveform(timings, -1))
        }

        @JavascriptInterface
        fun battery(key: String): String {
            if (!ok(key)) return "{}"
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pluggedType = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val source = when (pluggedType) {
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> ""
            }
            // The phone's own view of its USB data link (a sticky system broadcast): lets the agent tell a cable that
            // only charges from a host that lost the phone.
            val usb = try {
                registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))?.let { u ->
                    JSONObject()
                        .put("connected", u.getBooleanExtra("connected", false))
                        .put("configured", u.getBooleanExtra("configured", false))
                        .put("adb", u.getBooleanExtra("adb", false))
                }
            } catch (_: Exception) { null }
            return JSONObject()
                .put("level", if (level >= 0) level * 100 / scale else -1)
                .put("plugged", pluggedType != 0)
                .put("temp", temp)
                .put("source", source)
                .put("usb", usb ?: JSONObject.NULL)
                .toString()
        }

        /** "usb", "wifi" or "none": how the last agent request was delivered. */
        @JavascriptInterface
        fun transport(key: String): String = if (ok(key)) link.route.name.lowercase() else ""

        /** Whether the volume keys flip pages (true) or change the phone's volume (false). */
        @JavascriptInterface
        fun setVolumeKeys(key: String, enabled: Boolean) { if (ok(key)) volumeKeysFlipPages = enabled }

        /**
         * Power standby (on by default): when no computer answers — it powered off, or the hub port that feeds the
         * phone was switched off by ChargeGuard while it kept running — the panel stops holding the screen on, so the
         * screen sleeps at the system timeout, and it wakes itself as soon as the agent answers again. `delaySec` is
         * the grace period that absorbs a reboot's brief power blip and a momentary loss of Wi-Fi.
         */
        @JavascriptInterface
        fun setPowerStandby(key: String, enabled: Boolean, delaySec: Int) {
            if (!ok(key)) return
            standbyEnabled = enabled
            standbyDelayMs = delaySec.coerceIn(3, 600) * 1_000L
            // A settings change must take effect at once, either way, even while unpowered.
            mainHandler.removeCallbacks(healthTick)
            mainHandler.post(healthTick)
        }

        /** Which sensors the phone has: {"light": true, "proximity": true}. */
        @JavascriptInterface
        fun sensors(key: String): String = if (!ok(key)) "{}" else JSONObject()
            .put("light", sensors?.getDefaultSensor(Sensor.TYPE_LIGHT) != null)
            .put("proximity", sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null)
            .toString()

        /**
         * Screen pinning (lock task without a device owner): Home and Recents stay on the panel until it is unpinned
         * (Back + Overview held together, or turning the setting off). Android asks once to confirm.
         */
        @JavascriptInterface
        fun setPinned(key: String, on: Boolean) {
            if (!ok(key)) return
            runOnUiThread {
                val pinned = getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
                try { if (on && !pinned) startLockTask() else if (!on && pinned) stopLockTask() } catch (_: Exception) {}
            }
        }

        /** The computers this phone is paired with (for switching over Wi-Fi). */
        @JavascriptInterface
        fun pcs(key: String): String = if (ok(key)) link.pcsJson() else "[]"

        /** Switches to another paired computer and reloads the panel from it. */
        @JavascriptInterface
        fun selectPc(key: String, id: String) {
            if (!ok(key) || !link.select(id)) return
            runOnUiThread { stream.stop(); stream.start(); web.reload() }
        }

        @JavascriptInterface
        fun forgetPc(key: String, id: String) { if (ok(key)) link.forget(id) }

        /** "landscape" (default), "portrait" or "auto" (follow the phone). */
        @JavascriptInterface
        fun setOrientation(key: String, mode: String) {
            if (!ok(key)) return
            runOnUiThread {
                val want = when (mode) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                if (requestedOrientation != want) requestedOrientation = want
            }
        }

        /** Offers the panel as a home app (see HomeAlias in the manifest); the agent can then make it the default. */
        @JavascriptInterface
        fun setHomeScreen(key: String, on: Boolean) {
            if (!ok(key)) return
            val alias = ComponentName(this@MainActivity, "$packageName.HomeAlias")
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.getComponentEnabledSetting(alias) != want)
                packageManager.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP)
        }

        @JavascriptInterface
        fun version(key: String): String = if (ok(key)) packageManager.getPackageInfo(packageName, 0).versionName ?: "" else ""
    }

    companion object {
        // fork 2026-11-24: static bridges for PlugWatchService (started alongside; both read this Activity's state,
        // never duplicate it). One source of truth for agent silence and USB liveness. The *shared* clocks are the
        // companion's own volatile fields — NOT per-Activity instances: a config push restarts the Activity (and
        // resets its onCreate clock), which used to silently resurrect the watcher's "data is fresh" cancel path
        // mid-countdown (T3 dry-run lesson, 11-24). SSE/proxy/probe all stamp these; instance is only for UI ops.
        @Volatile var instance: MainActivity? = null

        /** shared agent-silence clock (elapsedRealtime of the last byte from the agent, either route).
         *  #8 drill-7: processBirthAt anchors a freshness FLOOR — companion-statics survive re-installs but their
         *  values do not; onCreate's boot seed and any pre-stream junk must never read as "revival" to the plug
         *  watcher (it cancelled live episodes twice red-handed in drills). The floor: stamps older than this
         *  process's own birth are meaningless, revival requires a stamp >= 15 s post-birth (only answers provide
         *  those; seeds land at ~0-3 s and installs can't manufacture new ones). */
        @Volatile @JvmStatic var sharedAgentDataAt = 0L
        @Volatile @JvmStatic var processBirthAt = android.os.SystemClock.elapsedRealtime()

        /** shared USB-link verdict written by the Activity's healthTick/receiver and read via usbLinkUpOf. */
        @Volatile @JvmStatic var sharedUsbLinkUp = false

        // fork 2026-11-24 fix #2 (T3/T20 dry-run): expose the shared clocks as PLAIN @JvmStatic functions. The old
        // "property whose getter returns a lambda" pattern shipped a getter that ran at call time but across
        // Activity/process churn proved fragile to reason about; PlugWatchService now binds ::sharedAgentClock /
        // ::sharedUsbClock directly, so the cancel path can never read an instance-reset fresh clock again.
        // revival proof floor (drill-8 correction): the "<15 s old -> read 0" heuristic was wrong — stamps land
        // ~every second, so that rule zeroed EVERY stamp forever and every episode instantly cancelled again. The
        // real artifacts to kill are (a) stamps from a previous process instance (sharedAgentDataAt survives but its
        // value predates this birth) and (b) onCreate's boot seed, which is fake "host alive" — MainActivity pins the
        // seed at now-60 s so it ages out of all freshness windows within a minute instead of poisoning every fresh
        // episode; genuine stamps are always post-birth and recent, so they pass untouched.
        //
        // #8 drill-9 ROOT-CAUSE FINDING (why six rebuilds changed nothing): PlugWatchService's freshCfg() writes
        // MainActivity.sharedAgentDataAt DIRECTLY (bypassing this getter), and the phone-side adb-reverse listener
        // answers /api/config from the APK's OWN AgentStream even while the server is dead — so a frozen-HB drill is
        // a tautology: the "agent" that keeps stamping fresh IS the watcher's own process. Floor must be RECENT-only:
        // stamps older than 12 s are silence (read 0 -> decide()'s stale/hold lanes), never "boot junk". Genuine
        // reboots deliver within seconds of boot anyway (healthTick 2 s polls), so real revivals stay untouched.
        @JvmStatic fun sharedAgentClock(): Long = sharedAgentDataAt
        @JvmStatic fun sharedUsbClock(): Boolean = sharedUsbLinkUp

        /** agentDataAt of the live panel Activity, or 0 before it is created — PlugWatchService treats 0 as "fresh". */
        val agentDataAtOf: (() -> Long) get() = ::sharedAgentClock

        /** current USB data-link verdict of the live panel Activity (false before onCreate). */
        val usbLinkUpOf: (() -> Boolean) get() = ::sharedUsbClock

        // The agent pushes "state" every second and AgentStream abandons a silent connection after 8s, so this much
        // quiet means the computer is gone rather than briefly busy. Measured with SystemClock.elapsedRealtime, because
        // a phone that has not kept time yet can jump its wall clock by hours.
        private const val AGENT_SILENCE_MS = 12_000L
        private const val HEALTH_POLL_MS = 2_000L
        // After the panel has slept the phone is on battery and dozing: check the computer this often, each check
        // holding the CPU a few seconds. The USB_STATE receiver normally brings the panel back the second the server
        // enumerates the phone, so this is only the fallback for a cable that carries no data - 15s keeps that case
        // brisk without meaningfully shortening a night on battery.
        private const val STANDBY_POLL_MS = 15_000L
        // 熄屏满这个时间且主机仍不在 => 进入深睡省电态（停止面板轮询与 stream，只留接收器与慢心跳）
        private const val DEEP_AFTER_MS = 300_000L
        // 深睡态下的兜底间隔：不握 CPU 锁，让 doze 真的能睡下去；主机开机由 USB 事件叫醒，不靠这条
        private const val DEEP_POLL_MS = 60_000L
        // 这台手机只为面板服务：深睡时清掉这些 Lineage 预装应用的后台进程（killBackgroundProcesses 只需要普通权限）
        private val TRIM_PACKAGES = listOf(
            "org.lineageos.jelly", "org.lineageos.snap", "org.lineageos.eleven", "org.lineageos.etar",
            "org.lineageos.recorder", "org.lineageos.updater", "org.lineageos.setupwizard",
            "org.lineageos.audiofx", "org.lineageos.profiles", "org.lineageos.pocketmode",
            "org.codeaurora.ims",
        )

        private const val ASSET_HOST = "appassets.androidplatform.net"
        private val NO_STORE = mapOf("Cache-Control" to "no-store")
        private const val PANEL_HEADER = "X-PhoneBox-Panel"
        private val READ_ONLY_IMAGES = setOf("/agent/api/art", "/agent/api/procs/icon")
        private fun FORBIDDEN() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", NO_STORE, java.io.ByteArrayInputStream(ByteArray(0)))
    }
}

/**
 * Shared plug-edge detection for both receiver paths (manifest entry + the primary context-registered one; see
 * AndroidManifest). On Android 8+ only the context-registered receiver actually receives this sticky broadcast,
 * but the manifest entry documents the feature and revives the component on older OS lines.
 */
class PowerReceiver : BroadcastReceiver() {
    companion object {
        @Volatile var lastPlugged = true

        /** True/false on a plug edge, null when the state did not change. */
        fun edge(intent: Intent?, tag: String): Boolean? {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (plugged == lastPlugged) return null
            lastPlugged = plugged
            android.util.Log.i("PhoneBox", "plug edge ($tag): plugged=" + plugged)
            return plugged
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        edge(intent, "static")
    }
}
