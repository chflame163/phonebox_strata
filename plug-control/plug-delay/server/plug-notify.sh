#!/usr/bin/env bash
# 关机(poweroff/reboot)时向 NAS 发一次 "SHUTDOWN" 握手。发两次以防丢包。
# 本脚本在 systemd 停止阶段(网络尚未下线时)执行。
set -uo pipefail
DIR=/usr/local/bin/plugdelay
source "$DIR/plug-delay.env"

python3 "$DIR/plug-send.py" "$NAS_IP" "$HB_PORT" "SHUTDOWN $(date +%s)" || true
sleep 1
python3 "$DIR/plug-send.py" "$NAS_IP" "$HB_PORT" "SHUTDOWN $(date +%s)" || true
