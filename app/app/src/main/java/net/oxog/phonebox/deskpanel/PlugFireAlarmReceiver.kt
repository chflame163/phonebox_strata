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
        // #10 dual insurance: this process-wide receiver runs in the SAME process as the service — direct call is
        // the reliable path (#9's startService hop silently swallowed it: dumpsys alarm proves the alarm DELIVERED
        // (1 wakeups, +34ms running) yet no verdict followed, so the hop was the dead end). Keep the explicit-
        // component startService as cold backup for a watcher that isn't live (service restart across the alarm).
        // #10b (real-off #10 forensics: DIAG showed the alarm DID ring at edge+~90s — verdict ran and re-armed the
        // proof-window timer, which then died silently ~2 min later; dumpsys alarm shows NO pending PLUGW alarm):
        // an exact alarm cancelled or dropped by the ROM mid-rearm must not leave the episode to stale-luck. The
        // receiver also stamps a heartbeat so the tick's watchdog (alarmRangAt) can see "the clock is alive" and,
        // when it ISN'T while the deadline has passed, fire honestly instead of waiting for staleMax.
        PlugWatcher.live?.noteAlarmBeat()
        c?.startService(android.content.Intent(c, PlugWatchService::class.java).setAction(PlugWatchService.ACTION_FIRE_ALARM))
        PlugWatcher.live?.onFireAlarm()
    }
}
