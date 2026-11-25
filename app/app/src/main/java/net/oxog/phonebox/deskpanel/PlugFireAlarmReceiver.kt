package net.oxog.phonebox.deskpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delivery hop for the #9 monotonic exact alarm (armFireTimer -> AlarmManager ELAPSED_REALTIME_WAKEUP). The alarm
 * rings even in doze and while the main thread is wedged — that's precisely when Handler.postDelayed used to stall
 * (real-offs #7/#8: 90 s verdicts landing at +320 s via stale backstop). We must NOT run the verdict here: this
 * process-wide receiver may fire with no service attached, and PlugWatcher.onFireAlarm() hops to the watcher's own
 * main handler anyway. No-op (safe drop) when no watcher is live — the next tick re-derives state from clocks.
 */
class PlugFireAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context?, i: Intent?) {
        if (i?.action != PlugWatcher.FIRE_ALARM_ACTION) return
        // FLAG_EXPLICIT_FOR_COMPONENT not needed: explicit class intent below pins the target.
        c?.startService(android.content.Intent(c, PlugWatchService::class.java).setAction(PlugWatchService.ACTION_FIRE_ALARM))
    }
}
