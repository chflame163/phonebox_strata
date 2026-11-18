# 架构与数据流（一页看懂三套子系统）

## 0. 全景

```
                        ┌───────────────────────── 服务器(未关机时) ─────────────────────────┐
 Strata 引擎 :8080 ──┐  │  ~/.config/systemd/user/                                           │
 (/metrics /slots)   ├──┼─ pb-strata-pushd.service ──POST──┐                                 │
                     │  │  (常驻 1 s/轮, 自适应找引擎端口)   │                                 │
 xiaomi.plug.04 ─────┼──┼─ pb-plug-read.service(1 Hz 只读)─┼─→ PhoneBox agent :8765          │
 (siid11/piid2 瓦数) │  │  自算今日 ∑W·dt → state 文件       │   (phonebox.service, dotnet)  │
                     │  │                                   │      │  adb reverse tcp:8765   │
                     └──┴───────────── /tmp 缓存 ───────────┘      │  + SSE 1 Hz 状态帧      │
                                                   8765 loopback ←─┴──(或 LAN 8766+token)    │
                                                                            │               │
                                                     ┌──────────────────────▼───────────────┘
                                                     │  3T 手机 (LineageOS 18.1, kiosk 桌面)
                                                     │  PhoneBox deskpanel：push/stat/html 控件
                                                     └───────────────┬───────────────────────
                                                        关机/开机 → USB 判据与钩子（§3）
     服务器电源插座 ◄── 局域网 miio 写 siid2/piid1（只允许 NAS 写）── 群晖 NAS plug_watcher（§4）
```

## 1. 指标上屏链路（panel/）

引擎与插座的读数全部经 `POST /api/push/<key>`（agent 侧 loopback 免鉴权；LAN 走 :8766 + token）
进入 agent 的 `PushStore`（TTL 过期自动消失、持久化 `data.json`），再随 agent 的 **1 Hz SSE
状态帧 `state.pushItems`** 下发到手机——没有“每次变化多拉一刀 GET”的二次请求（fork 改动，
versionName 0.9.4+fork）。面板共 7 个 push key：`strata_tps / strata_prefill / strata_ctx /
strata_state / gpu_power / vram_gb / plug_power`，payload 一律带 `level`（着色）与 `ttl`，
阈值集中在 collector 文件头。契约与显示参数表见 `panel/README.md`。

断流兜底（五层地板，任何一层单独成立）：① collector 挂了 → TTL 自然过期；② agent 冷启动 →
`PushStore.Load()` 把 data.json 恢复值折成最低态（0/空数组，unit 保留）；③ agent 优雅停机 →
`SealAndFloorAll()` 全键折 0 并宽限 ~1.3 s 让告别帧经 SSE 出去；④ 意外断电/强杀 → 手机 main.js
监测 `lastStateAt`，5 s 无帧本地把 push 值、内置 stat 卡、进程列表全部折 0（vc29-31）；
⑤ 冷启动首帧即是地板值。实测：拔线/强杀后 ≤5 s 全页归 0。

## 2. 墙电采集链路（plug-control/power-read/）

插座 `xiaomi.plug.04` 的 `siid 11 / piid 2`（electric-power，watt，实测每秒真变、UDP RTT ~21 ms）
由常驻 reader 以 1 Hz **只读**拉取；插座自带电量属性 `piid 1` 在 `piid 3 way=false` 时只是功率
镜像，所以“今日 kWh”由 reader 自积分（∑W·dt、本地午夜归零、重启续算、>60 s 空洞不补计）。
reader 每拍写 `/tmp/pb_plug_cache.json`，pushd 顺带推成 `plug_power`（ttl 8 s）。
**这条链永不写插座**——写 `siid2/piid1` 就是切服务器自己的电（那是 §4 的 NAS 特权）。

## 3. 息屏/唤醒与充电（phone-behavior/ + app/）

- 熄屏判据是 **hostAlive**：agent 数据新鲜度（<12 s，`elapsedRealtime` 计时防校时跳变）
  **或**手机自身 USB 数据线事件。判死后再等 `powerStandbyDelaySec`(5 s) 宽限才放掉常亮锁
  （放锁后必须 `requestLayout()` 才会真正推给 WindowManager）。实测：stop agent → ~30 s 熄；
  物理拔线（ErP 生效）→ ~18 s 熄。为什么不信“USB 有没有电”：PMIC 停充时 VBUS/数据都在
  （充电保护会误判）、部分 ROM hub 断电不回落 USB 状态。
- 唤醒双路：主机开机重新枚举 → agent 的 adb reverse 规则随 USB 重连丢失 → agent 重建规则即
  `KEYCODE_WAKEUP` + 拉起面板（实测枚举→亮屏 <1 s）；同时手机端 `ACTION_USB_STATE` 接收器秒级
  自亮并 dismiss keyguard。系统 unit `phonebox-wake.service` 是第三重兜底（`After=local-fs.target`，
  不等网络）。
- 深睡（熄屏 5 min 后，仅 host 仍判死时）：暂停 WebView、停 SSE 循环、逐包
  `killBackgroundProcesses` 清系统预载，只留两个 sticky 接收器与 60 s 不握锁探针——熄屏放电
  68 mA → 27 mA，真过夜实测 ~15 mA。关机侧 `phonebox-sleep.service`(ExecStop=pb-sleep.sh v2)
  最多 3 个 adb 调用：stay_on=0 → keyevent 223 →（仍醒才补一次）；**故意不 force-stop 面板**
  （保住“枚举秒亮”）。开机后 agent keepAwake 把 `stay_on_while_plugged_in` 写回 7。
- 前提：主板 BIOS 必须开 **ErP**（关机断 USB 5V）；`configured || connected` 取或是有意的
  （热重启与停充期间不闪熄），代价是插在常供电口时面板会常亮。
- 充电：服务器 user timer 每分钟经 **adb root** 写 PMIC `charging_enabled`，三层策略
  （日常 65↔80 滞回 / 周 40→80 / 季 20→100+顶充 30 min），全程不切 USB 口、不影响数据链。

## 4. 关机延时断电（plug-control/plug-delay/）

服务器进程扛不住“关机后还要等 180 s”，所以判决放在常开的群晖 NAS：

```
服务器 plug-heartbeat.service ──UDP "HB <epoch>" 每 5 s──► NAS plug_watcher.py（UDP 12345）
服务器 plug-notify.service(ExecStop) ──"SHUTDOWN <epoch>"×2（关机瞬间）──► 同上
```

两条触发路共用一个防抖闸门：收到 SHUTDOWN 后等满 `SHUTDOWN_DELAY`(180 s)，或连续无心跳满
`STALE_TIMEOUT`(180 s)（异常兜底）。**握手一旦确立就忽略 stale、严格从握手时刻计时**——保证
“断电 = 握手后恰好 181 s 左右、绝不提前”（2026-11-17 真 poweroff 验收：journal 握手→181 s→
`nas_off.py` set siid2/piid1=false→回读 value:False→服务器重启回来 watcher 自动 rearm）。
NAS 自身重启有 90 s grace；fire 失败每 30 s 重试；只 fire 一次直到重新武装。恢复供电靠
`nas_off.py --on` 或米家 App，服务器靠 BIOS 来电自启回来。

## 5. 时序关键数（现机实测，换机后按此核对）

按键→面板亮 ≈ 40 s（POST/内核占大头，枚举后手机端 ≤1.2 s）；agent 停→熄屏 ~30 s；物理拔线→熄
~18 s；深睡=熄屏 +5 min（doze 下 alarm 有推迟，保守无妨）；意外断电→全页地板 ≤5 s；关机→插座
断电 = 握手 +181 s；pushd→面板一跳 1 s（SSE 帧内）。
