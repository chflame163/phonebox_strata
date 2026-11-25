# plug-control/ —— 小米智能插座（xiaomi.plug.04）：功耗上屏 + 联动延时断电

服务器整机电源就插在这只小米插座上。本目录两条链：

```
power-read/ ─ 1 Hz 只读采瓦数 → 手机面板 4×2 墙电控件（供“看”）
plug-delay/ ─ 服务器心跳/握手 → watcher 判决 → 局域网 miio 断电（供“切”，关机后延迟 180 s）
```

watcher 现在有两个实现：**NAS 版**（`plug-delay/nas/`，python 常驻）与**手机版**
（`plug-delay/phone/` + app 里的 `PlugWatcher`）。判决语义一致。**2026-11-25 定稿：手机版转正
在岗（delay=150、dry=false），NAS 版已停用卸载**——11-24 晚手机 dry-run 全绿后 NAS 按红线 1
stop+disable 让位，11-25 双路径真机关机验收通过（poweroff→167s 真断电 / reboot→59s CANCEL 保电），
当天把 NAS 上的 service unit、`plug-delay/` 目录与 miio venv 全部删除；红线 1 互斥约束随单边在岗
自然解除，仓库里的 NAS 版只作等价参考留存。

## 取证双通道（真关机对账必备，2026-11-25 #7 后加）

watcher 每个判决点都会打 `DIAG hb静默=Xs agent静默=Ys usb链=Z` 行（logcat + plug_watch.log + push key
plug_diagtrail 滚动尾 12 条），事后对账不用再猜哪个时钟拖了闸——#4/#5/#6/#7 四轮各猜一次各错一半的教训。
服务器侧对照：journalctl -b -1 grep plug-heartbeat/notify + `last` 关机区间 = T0 与 HB 真死时刻；手机侧
healthd st=3/c<0 = ErP 塌电物理时刻（deep-standby 断 USB 也产生同款读数，勿当插座断电证据——#4 误读过一次）。

## 三条红线（先记住再动手）

1. **任一时刻只有一个 watcher 有权写插座——NAS 与手机互斥、二选一**。换班 = 一边 enable 前
   另一边必须 disable（手机版转正当天执行的即是此条：NAS `stop+disable` → 手机装机 dry-run
   全绿 → 待真机转正）。米家 App 的人工开关是旁路，随时可用作恢复送电。
2. **写 `siid 2 / piid 1 = false` 就是切服务器自己的电**。只有在岗 watcher（NAS `nas_off.py`
   或手机 PlugWatcher 非 dry 模式）和米家 App 有权写；`power-read` 那条链永远只发
   `get_properties`。验证凭据通不通一律走只读：NAS `nas_off.py --check`、手机版看 fire 后的
   `CHECK_OK` 日志（同样纯只读）。
3. **延时在 watcher 里，不在插座里**（NAS 版曾 180 s；手机版由 agent 配置 `display.plugDelaySec`
   决定，定稿值 150 s）。别去米家 App 再配“延时关闭”（插座自带那个实测只有 ~2 s，
   两边都设会叠加）。**插座被重置/重新配网后 token 会变**：NAS `plug-secret.env`、服务器
   `~/.config/pb_plug.env`、agent 配置 `display.plugToken` **三处都要换**，然后
   `systemctl --user restart pb-plug-read`。

属性实测表（瓦数每秒真变、电量属性是功率镜像、无电压电流等）见
`docs/plug-04-properties.md`；spec：<https://home.miot-spec.com/spec/xiaomi.plug.04>
（`urn:miot-spec-v2:device:outlet:0000A002:xiaomi-04:1:0000D032`）。协议为局域网 miio v2
（UDP + AES，key/iv 由 32 位 token 导出），**不出网也能用**。

## 拿插座 IP 与 token

见 `docs/token-extraction.md`（Xiaomi-cloud-tokens-extractor 用法与注意事项）。真值存放：`<plug-ip>` + `<plug-token>`，两处各一份（见上红线 3）。

## 部署 A —— power-read（服务器）

```bash
# 1) venv（持久位置，/tmp 会被重启清掉）
python3 -m venv ~/.venvs/miio && ~/.venvs/miio/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple python-miio
#    （netifaces/cryptography 要编译的话先 sudo apt install python3-dev gcc）
# 2) 凭据文件（0600）：两行 PB_PLUG_IP=…  PB_PLUG_TOKEN=…
umask 077; ${EDITOR:-vi} ~/.config/pb_plug.env
# 3) 脚本与单元
mkdir -p ~/bin && cp power-read/pb_plug_read.py ~/bin/
mkdir -p ~/.config/systemd/user && cp power-read/pb-plug-read.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now pb-plug-read
```

脚本缺 `PB_PLUG_IP/PB_PLUG_TOKEN` 直接拒启；常驻 1 Hz 只读 siid11/piid2，自算当日能量
（状态 `~/.local/state/pb_plug.state`，重启续算），写 `/tmp/pb_plug_cache.json` 等 pushd 接力。
验收：`curl -s 127.0.0.1:8765/api/push | grep -o plug_power`（需 `../panel/` collector 在跑）；
`journalctl --user -u pb-plug-read` 无 failed。一次性只读探测/属性验尸用 `power-read/probe/plug_probe.py`
（换新型号插座时先跑它，60 s 采样出粒度与 code 表）。上屏控件参数见 `../panel/README.md`。
观察项：1 Hz UDP 长跑稳定性（现 ~796 样本 bad=0，24 h 级长跑仍未结案）。

## 部署 B —— plug-delay 服务器侧

```bash
sudo mkdir -p /usr/local/bin/plugdelay
sudo cp plug-delay/server/{plug-send.py,plug-heartbeat.sh,plug-notify.sh,plug-delay.env.example} /usr/local/bin/plugdelay/
sudo cp /usr/local/bin/plugdelay/plug-delay.env.example /usr/local/bin/plugdelay/plug-delay.env
sudo sed -i "s|<nas-ip>|<真实NAS地址>|" /usr/local/bin/plugdelay/plug-delay.env   # 或 sudo vi
sudo cp plug-delay/server/plug-{heartbeat,notify}.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now plug-heartbeat    # 常驻 5 s UDP 心跳
sudo systemctl enable plug-notify             # 拉起即可；关机(ExecStop)时发 SHUTDOWN 握手×2
```

协议就两个词：`HB <epoch>` / `SHUTDOWN <epoch>`，UDP 端口 12345（`HB_PORT` 与 watcher
`LISTEN_PORT`/手机 `display.plugUdpPort` 一致）。**目的地只有这一个 env**：手机版 watcher 在岗时
`NAS_IP=<手机 Wi-Fi IP>`（当前现场值），NAS 版在岗时改回 NAS IP——换班只改这一行，服务器没有 Wi-Fi，
单播走以太网。(11-24 首次 T2 reboot 就是因为这里还指着一台已 disabled 的机器而握手全空跑；顺带发现
**手机重启会清掉 adb reverse**，8765 断流——已在 app 侧加自愈，见部署 C2。)

## 部署 C —— plug-delay NAS 侧（群晖 DSM 7，已退役 · 仅参考）

> **2026-11-25 状态**：NAS 版已停用并从机器上卸载（unit/目录/venv 全删）。以下内容保留作
> 部署史与等价实现参考；手机版转正流程见下一节 C2。

```bash
# 家目录按现场：<nas-user> 常见在 /volume2/homes/ 而不是 /volume1（本机如此）
mkdir -p /volume2/homes/<nas-user>/plug-delay && cp -r plug-delay/nas /volume2/homes/<nas-user>/plug-delay/
cd /volume2/homes/<nas-user>/plug-delay/nas
cp plug-watcher.env.example plug-watcher.env      # 填路径里的 <nas-user>；首次保持 dry-run 的 echo 命令
cp plug-secret.env.example  plug-secret.env && chmod 600 plug-secret.env   # PB_PLUG_IP + PB_PLUG_TOKEN
# miio venv（判决器本身纯 stdlib，只有 nas_off.py 需要 miio；py3.8 + v22.0.4 实测可跑）
python3 -m venv /volume2/homes/<nas-user>/pdvenv
/volume2/homes/<nas-user>/pdvenv/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple python-miio
/volume2/homes/<nas-user>/pdvenv/bin/python3 -I nas_off.py --check     # 只读验凭据，绝不断电
# 把 plug-watcher.env 里 PLUG_OFF_CMD 换成 nas_off.py 真命令（注释里有 miiocli 等价写法二选一）
sudo cp plug-watcher.service /etc/systemd/system/   # 先改 ExecStart 两处路径的 <nas-user>
sudo systemctl daemon-reload && sudo systemctl enable --now plug-watcher
```

自启二选一：DSM 7 有 systemd，上面这样 enable 即可（**已实测**：NAS 重启后 watcher 自起、持续
收心跳）；systemd 不便就用 `start_plug_watcher.sh` 挂“控制面板→任务计划→开机触发”的 root 任务。
SSH 自动化注意：群晖 SecureSignIn 会因频繁连接挂住会话——**单连接少开、`-o ConnectTimeout=15`**；
NAS 上没有 pgrep；heredoc 喂 sudo 会抢 stdin。

## 部署 C2 —— plug-delay 手机版（PhoneBox app，**2026-11-25 定稿转正、唯一在岗**）

判决器直接跑在常驻充电的桌面面板手机里（kiosk home + 前台服务），agent 侧只需把
`display.plug*` 开起来；协议与验证工具见 `plug-delay/phone/README.md`，状态机语义 = NAS 版的镜像
（掉电沿+delay、握手 arm+delay+hostDead、stale 兜底、电量下限——插电=永不拒臂）。

### 定稿时序实测（11-25 真关机 #3/#11/#12 + reboot，全部生产参数 delay=150/dry=false）

| 轮次 | 路径 | 结果 |
|---|---|---|
| #3（上午） | poweroff→OFF_OK | 端到端 232 s（当时 delay=180），writeOff 仅 343 ms——push 劫持修复实锤 |
| #11 | poweroff→OFF_OK | **167 s**：ARM+0s（握手）→沿 +14.9s（HB 静默 15s 门槛）→FIRE=沿+151.6s（≈delay±2，#9/#10 定时器改造后 delay 路首次真正生效）→OFF_OK=FIRE+338ms；服务器重启后自动 REARM |
| #12 | reboot→CANCEL | **59.3 s** 内 agent 复活→CANCEL，插座全程通电（bootConfirm=60s 确死闸顶住握手歧义）——宽容面实证 |

判据口径：**poweroff 起算 OFF_OK ≈167±20 s；reboot 必须 CANCEL 零 FIRE**。端到端固定开销 =
HB 静默门槛 15s + delay 150s（确死/闹钟/writeOff 均 <1s）。想再快只能砍这两个阈值，代价是
reboot 宽容与确死强度——现形态已收官。

```bash
# agent 配置（PUT /api/settings 或改 ~/.config/PhoneBox/config.json 后等一拍缓存）：
#   display.plugWatcher=true, plugDryRun=false(转正态), plugDelaySec=150/bootConfirmSec=60/bootClearSec=45,
#   plugCritLevel(只真电池生效), plugUdpPort=12345, plugIp/plugToken（0600 处存放，别进 git）
# 手机侧装机（服务器 adb 直连即可）+ Doze 白名单（root，一次性）：
adb -s <sn> install -r -t -d app-release.apk
adb -s <sn> root && adb -s <sn> shell dumpsys deviceidle whitelist +net.oxog.phonebox.deskpanel
# NAS 版让位（红线 1）：sudo systemctl stop plug-watcher && disable   # 群晖不支持 --now，分两步
```

验收（全程 dry，不写插座）：停 agent → UDP `SHUTDOWN` → 日志 `ARM`；agent 保持死 → delay+确认到点
`FIRE(dry)` 且随后 `CHECK_OK`（真机只读 round-trip，协议绿灯就在这一步）；fire 前拉起 agent →
`REARM/CANCEL`。判决历史看两处：手机 `files/plug_watch.log`（拔电也在）与面板 System 页两卡
（push keys `plug_delay_state`/`plug_watch_log`）。**真 off 转正只允许一次窗口**：NAS disable 已在岗、
手机 plugDryRun=false、现场有人盯米家 App 旁路时才做。

### 11-24 深夜 T2 reboot 实测记录（教训固化）

真 `sudo reboot` 抓到两个只有重启才暴露的问题，均已修/已记：

1. **握手目的地没换班**：`plug-delay.env` 仍指 NAS → SHUTDOWN 全空跑，手机全程 IDLE（reboot
   本身无害，但计划路等于是零覆盖）。改 env=手机 IP + `systemctl restart plug-heartbeat`，随后
   stop/start notify 实测 `ARM→CANCEL：UDP 心跳恢复`、HB 静默 90s 自动 `ARM`。换班口诀：**只改 env 一行**。
2. **手机重启清 adb reverse**：面板 USB 隧道断（push failed: unexpected end of stream），且服务器
   `adb kill-server/start-server` 会踢掉插座读数的 adb-触发单元。**现场临时** =
   `adb -s <sn> reverse tcp:8765 tcp:8765`；**根治=服务器侧 guard 单元**（11-24 深夜部署并 enable）：
   `/usr/local/bin/plugdelay/adb-reverse-guard.sh` + root 单元 `pb-adb-reverse-guard.service/.timer`
   （每 30 s 校验 `adb reverse --list`、缺失即补建，开机先等设备上线 ≤120 s）。留存脚本已拷入本目录
   `server/adb-reverse-guard.*`。**重启后仍建议瞄一眼** guard timer 与面板 push。

之后补跑 T3 全链一次通过（快速语义 delay=30/confirm=15）：`23:11:01 ARM → 23:11:41 FIRE(dry) →
CHECK_OK(插座 value=true, 76W) → 拉起 agent → REARM`，测完阈值已回产 180/60。

**真 off 第一轮（11-24 深夜，dry=false）——断电成功、时序偏晚、自启未触发**：
`23:36:37 ARM(握手) → 23:41:44 FIRE "掉电沿后已满 180s" → OFF_OK verify value:false（插座真断）`。
现场观察 ≈5 分钟才断电——根因：本 wiring 下 VBUS 不塌、USB 恒 configured，**结构性掉电沿永远捕不到**，
fire 走的是握手兜底路，倒计时从"agent HTTP 静默满 bootClear(45s)+tick"起算（晚 ~126s）。修复=心跳即沿：
`handleUdp` 记 HB 拍钟，armed 下 HB 静默 ≥15s 立即 `掉电沿捕获`（HB-backed 边仍保留 hostDead 闸防 Wi-Fi
抖动切自己电；plug-loss/USB-gadget 两类结构边自证、只等 delay）。演练实拍：`23:57:05 ARM → 23:57:13 掉电沿捕获
(plugged=false usb=true, HB 静默) → 23:57:40 CANCEL(心跳恢复)`——沿快了 8s、取消路毫发无损。
**BIOS AC-back 自启经用户亲手验证有效（插座通电即开机）**；当晚断电后 ~4 分钟没醒是因为来电时机/或那次恰为
standby 假死，按电源键即回——转正验收以「FIRE→OFF_OK(False)→整机断电」为硬判据，自启回来 REARM 为软判据。
观测器教训：ping 用 `ping -c1`（bash `/dev/tcp` 写法在 sh 下必假 DOWN，首轮三行后自己退场）。

**T2 第二轮真 reboot（11-24 深夜，env 已指手机）——计划路首次实拍**：
`23:25:28 ARM(UDP SHUTDOWN) → 23:25:58 FIRE(dry, arm+30s=快速 delay 到点) → CHECK_OK(value=true,136W)
→ 23:26:28 REARM(agent 随服务器 23:26:06 回来了)`。手机整机没断电、插座全程 on，dry 语义兜住了危险面。
**定性结论：SHUTDOWN 握手是 fire-and-forget，reboot/poweroff 在 watcher 眼里不可区分**——开机若 <delay
宽限内回来则 CANCEL（NAS 版靠 reboot_grace=90s + 默认 delay=180s 兜这个场景）；手机在岗时**绝不要拿真
reboot 测取消路**，需要重启请先顺手 `systemctl stop plug-heartbeat`（静默满 stale 才兜底 fire）。
真 off 转正窗口的判据链就是本条时序的 dry=false 版。测完阈值已回产 180/60、CFG 快照确认 delay=180。

### 验收（先把阈值临时改 15 s 快速验，再改回 180）

1. watcher 日志持续“alive”（收到心跳）。2. 异常路：服务器 `systemctl stop plug-heartbeat` →
   ~180 s 后 `FIRE 心跳失联`；start 回来 → 自动“重新武装”。3. 计划路：`systemctl stop plug-notify`
   （触发 ExecStop）→ NAS 先“收到握手”，等满 `SHUTDOWN_DELAY` 才 FIRE。4. 时机全对才换真命令。
5. **真 reboot 全链已验（2026-11-17）**：握手→恰好 181 s→断→回读 `value:False`→服务器来电自启
   回来→watcher rearm。恢复送电：`nas_off.py --on` 或米家 App；服务器靠 BIOS 来电自启。
   握手是单向的：发出即倒计时，习惯用 `shutdown -c` 取消的要注意。

### NAS 现场与仓库版的差异（历史记录）

NAS 上 11-25 卸载前实际在跑的是 11-17 当天图快写的三个硬编码小脚本（`nas_off/on/get.py`，
IP/token 写死在源码里、不进 git），`plug_watcher.py` 本体与仓库逐字节一致；本仓库的 `nas_off.py`
（env 读凭据 + `--check/--on` 三合一）是后来固化的等价终态。**2026-11-25：NAS 版整套已从机器删除**
（service disable+unit rm、`/volume2/homes/<nas-user>/plug-delay/` 与 `pdvenv/` 整目录），
仓库留存可随时重建。手机版转正后不再需要“回灌 NAS”这一步。

### 定稿改动清单（11-25，对应 commit 链 ef32a4b..HEAD）

- `app/.../AndroidManifest.xml`：USE_EXACT_ALARM 声明 + PlugFireAlarmReceiver 注册
- `MainActivity.kt`：SSE delivered per-connection guard pin、stream.lastDataAt 真字节门、
  USB-gadget stamp 门（loopback 自答僵尸时钟三刀）
- `PlugWatcher.kt`：#9/#10 判决引擎——delay 路撤 powerOffAt==0 死码门、AlarmManager
  ELAPSED_REALTIME_WAKEUP exact alarm + PI generation requestCode、tick-rescue 看门狗、
  DIAG 三表时钟面包屑 push（plug_diagtrail）、LAN-alive 闸+握手豁免+episode-lifetime、
  writeOff 预检闸、staleMax=max(180, delay+120)、默认 delay=90→现场 cfg 150
- `PlugWatchService.kt`：ACTION_FIRE_ALARM hop + receiver 直连双保险（#10b）
- `PlugFireAlarmReceiver.kt`：新增——exact alarm 投递 hop（noteAlarmBeat + 冷备 startService）

### 脱敏状态核查（git push 前必做，11-25 已核）

tracked 文件内 grep `<plug-ip>/<plug-token>/现场 Wi-Fi IP/设备 SN/MAC/SSH 口令` 全零命中：
插座 token、服务器/NAS/手机三台机器的局域网地址均未入库（.env 本体与 agent config.json 都不进
git，README 用 `<nas-ip>`/`<sn>`/`<plug-token>` 占位）。`server/adb-reverse-guard.sh` 的现场 SN
已改为 `PB_ADG_SN` 环境变量优先、仓库默认值仅示例性质。

## docs/ 索引

`plug-04-properties.md`（实测属性表与镜像坑）、`token-extraction.md`（token 获取指南）、
`device_list.masked.txt`（家里小米设备清单的脱敏样本，展示了工具输出格式）、
`xiaomi-plug4-windows-guide.md`（GPT 生成的 Windows/miiocli 参考，示例 IP 为占位性质，结论以实测表为准）。
