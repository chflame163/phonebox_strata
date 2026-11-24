#!/usr/bin/env bash
# pb-adb-reverse-guard: PhoneBox USB tunnel (adb reverse tcp:8765) reconciler.
# A phone reboot wipes the reverse rule and nothing rebuilds it -> panel/push dies silently.
# Runs as root systemd oneshot every 30s (timer); ExecStartPre waits for the device to enumerate.
set -u
SN=481fe5dc; PORTMAP="tcp:8765 tcp:8765"
wait_dev=0
while ! adb -s $SN get-state >/dev/null 2>&1; do
    wait_dev=$((wait_dev+1)); [ $wait_dev -gt 40 ] && exit 0   # 120s ceiling at boot, device may be off
    sleep 3
done
if ! adb -s $SN reverse --list 2>/dev/null | grep -q "8765"; then
    adb -s $SN reverse $PORTMAP >/dev/null 2>&1 && logger -t pb-adb-reverse-guard "rebuilt 8765 reverse (rule was missing)"
fi
