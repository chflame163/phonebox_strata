#!/usr/bin/env bash
# 群晖任务计划"开机触发"备选自启(systemd enable 不便时用这个)。
# 先在控制面板>任务计划里建一个 root 开机脚本任务指向本文件;脚本本身后台拉起 watcher。
DIR="$(cd "$(dirname "$0")" && pwd)"
LOG=/var/log/plug-watcher.log

# 已在跑则不重复启动
if pgrep -f "plug_watcher.py" >/dev/null 2>&1; then
    echo "$(date '+%F %T') watcher 已在运行,跳过启动" >> "$LOG"
else
    nohup python3 "$DIR/plug_watcher.py" --env "$DIR/plug-watcher.env" >> "$LOG" 2>&1 &
    echo "$(date '+%F %T') 已拉起 plug_watcher (pid $!)" >> "$LOG"
fi
