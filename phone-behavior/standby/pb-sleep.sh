#!/bin/sh
# 关机瞬间：屏幕要立刻黑、而且此后没有任何东西消耗这台手机的电。
# v2 2026-11-15: 原版连打 12 个 adb 调用，和 session teardown 抢 adb 通道，
# 223 的 down/up 曾被拆成长按电源语义 -> 熄屏 2.6s 后 policy 又把人叫醒(看到锁屏
# 挂 keyguard,再过 ~24s 数据判据才把屏放睡)。现砍到最多 3 个 adb 调用:
# trim 交给手机自己的深睡(熄屏 5 分钟后 enterDeepStandby 做同样的事,整晚差 ~0.1mAh,
# 实测关机日志 agent silent 21s releasing 一切正常)。
# 面板进程依旧不 force-stop——它留着当守门人,主机重新枚举那一秒点亮屏幕。
A="adb -s <serial>"
$A shell settings put global stay_on_while_plugged_in 0 2>/dev/null
$A shell input keyevent 223 2>/dev/null
sleep 1.5
# 双保险:万一这次 223 又被拆拍/没吃到,补一次(此刻 adb server 已活过最初几百 ms,通道稳定)
if $A shell dumpsys power 2>/dev/null | grep -qm1 'mWakefulness=Awake'; then
  $A shell input keyevent 223 2>/dev/null
fi
exit 0
