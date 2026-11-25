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
