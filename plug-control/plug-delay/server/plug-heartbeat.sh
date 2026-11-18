#!/usr/bin/env bash
# 常驻:每 HB_INTERVAL 秒向 NAS push 一次 "HB" 心跳。
set -uo pipefail
DIR=/usr/local/bin/plugdelay
source "$DIR/plug-delay.env"

while true; do
    python3 "$DIR/plug-send.py" "$NAS_IP" "$HB_PORT" "HB $(date +%s)" || true
    sleep "${HB_INTERVAL:-5}"
done
