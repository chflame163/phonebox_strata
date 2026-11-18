# plug-control/ —— 小米智能插座（xiaomi.plug.04）：功耗上屏 + NAS 联动延时断电

服务器整机电源就插在这只小米插座上。本目录两条链：

```
power-read/ ─ 1 Hz 只读采瓦数 → 手机面板 4×2 墙电控件（供“看”）
plug-delay/ ─ 服务器心跳/握手 → 群晖 NAS 判决 → 局域网 miio 断电（供“切”，关机后延迟 180 s）
```

## 三条红线（先记住再动手）

1. **写 `siid 2 / piid 1 = false` 就是切服务器自己的电**。全系统只有 NAS 的 `nas_off.py`
   （watcher 触发/手动 `--on`）和米家 App 有权写；`power-read` 那条链永远只发 `get_properties`。
   验证凭据通不通一律用 `nas_off.py --check`（纯只读）。
2. **180 s 延时在 NAS 的 watcher 里，不在插座里**。别去米家 App 再配“延时关闭”（插座自带那个
   实测只有 ~2 s，两边都设会叠加）。
3. **插座被重置/重新配网后 token 会变**，NAS `plug-secret.env` 与服务器 `~/.config/pb_plug.env`
   **两处都要换**，然后 `systemctl --user restart pb-plug-read`。

属性实测表（瓦数每秒真变、电量属性是功率镜像、无电压电流等）见
`docs/plug-04-properties.md`；spec：<https://home.miot-spec.com/spec/xiaomi.plug.04>
（`urn:miot-spec-v2:device:outlet:0000A002:xiaomi-04:1:0000D032`）。协议为局域网 miio v2
（UDP + AES，key/iv 由 32 位 token 导出），**不出网也能用**。

## 拿插座 IP 与 token

见 `docs/token-extraction.md`（Xiaomi-cloud-tokens-extractor 用法 + 本次实测被堵的四条路与
避坑清单）。真值存放：`<plug-ip>` + `<plug-token>`，两处各一份（见上红线 3）。

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

协议就两个词：`HB <epoch>` / `SHUTDOWN <epoch>`，UDP 端口 12345（`HB_PORT` 与 NAS
`LISTEN_PORT` 一致）。

## 部署 C —— plug-delay NAS 侧（群晖 DSM 7）

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

### 验收（先把阈值临时改 15 s 快速验，再改回 180）

1. watcher 日志持续“alive”（收到心跳）。2. 异常路：服务器 `systemctl stop plug-heartbeat` →
   ~180 s 后 `FIRE 心跳失联`；start 回来 → 自动“重新武装”。3. 计划路：`systemctl stop plug-notify`
   （触发 ExecStop）→ NAS 先“收到握手”，等满 `SHUTDOWN_DELAY` 才 FIRE。4. 时机全对才换真命令。
5. **真 reboot 全链已验（2026-11-17）**：握手→恰好 181 s→断→回读 `value:False`→服务器来电自启
   回来→watcher rearm。恢复送电：`nas_off.py --on` 或米家 App；服务器靠 BIOS 来电自启。
   握手是单向的：发出即倒计时，习惯用 `shutdown -c` 取消的要注意。

### NAS 现场与仓库版的差异（诚实记录）

NAS 上**当前实际在跑**的是 11-17 当天图快写的三个硬编码小脚本（`nas_off/on/get.py`，IP/token
写死在源码里、不进 git），`plug-watcher.py` 本体与仓库逐字节一致；本仓库的 `nas_off.py`
（env 读凭据 + `--check/--on` 三合一）是后来固化的等价终态，**尚未回灌 NAS**——迁移 = 拷
`nas_off.py` + 建 `plug-secret.env`，`PLUG_OFF_CMD` 路径本就同名不用动。

## docs/ 索引

`plug-04-properties.md`（实测属性表与镜像坑）、`token-extraction.md`（token 获取指南）、
`device_list.masked.txt`（家里小米设备清单的脱敏样本，展示了工具输出格式）、
`xiaomi-plug4-windows-guide.md`（GPT 生成的 Windows/miiocli 参考，示例 IP 为占位性质，结论以实测表为准）。
