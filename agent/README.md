# agent/ —— 服务器侧 PhoneBox 后台（.NET 10，Linux）

跑在 Strata 服务器上的用户级服务：采集整机指标（CPU/内存/网络/磁盘/温度 + GPU 常驻
`nvidia-smi -lms 1000` 流），经 adb/局域网把面板拉起来并喂 1 Hz 状态流，维护 push 值存储，
提供 8765 设置页（布局编辑器）与 8766 LAN HTTPS。开机“重新枚举→亮屏进面板”的唤醒主力
`AdbReverse` 是上游内置机制，本项目未改。

## 相对上游 0.9.4 的全部本地改动清单

| 文件 | 改动 |
|---|---|
| `PhoneBox.Agent/PushStore.cs` | `Floored()`：载入 data.json 时每条恢复值折最低态（对象→`{"value":0}` 保 unit、数组→`[]`、标量→0，**At 必须重打**否则页面不重绘；ExpiresAt 置空直到真推覆盖）；`SealAndFloorAll()`：优雅停机时密封存储、全键折 0、立即落盘；落盘防抖用上游 `_savePending`（本项目早期自研版已被上游修复取代，采用上游实现） |
| `PhoneBox.Agent/Program.cs` | `/api/state` 帧带 `pushItems = push.Snapshot()`（push 值进 1 Hz 状态帧，免二次 GET）；`ApplicationStopping` 接线地板告别 + ~1.3 s 宽限让最后一帧经 SSE 送达 |
| `PhoneBox.Agent/Config.cs` + 3× `config.default*.json` + `settings.html` | `display.powerStandby` 字段与开关；`powerStandbyDelaySec` 出厂默认 30→**5 s** |
| `../tools/check-release.py` | 放宽一行：容忍 versionName 的 `+fork` 后缀（上游断言 gradle 与 csproj 逐字相等） |

行为语义与实测数值见 `../docs/ARCHITECTURE.md` §1。

## 构建

```bash
cd agent
dotnet build -c Release PhoneBox.Agent        # agent/ 根下没有 sln 且躺着两个工程——**必须指到工程目录**（TFM net10.0）
dotnet test  -c Release PhoneBox.Agent.Tests  # 331 个测试全绿是本仓库验收线
# 产物在 agent/PhoneBox.Agent/bin/Release/net10.0/ 顶层：apphost PhoneBoxAgent、
# PhoneBoxAgent.dll、config.default*.json、templates.json、widgets.json ——同级那 11 个文件即部署内容
```

冒烟：`../tools/smoke-agent.py <apphost> --version <ver>`（隔离配置起一个真 agent 校验 API 面）。

## 部署（Ubuntu，无图形会话也行）

```bash
mkdir -p ~/.local/share/PhoneBox/app
cp -r agent/PhoneBox.Agent/bin/Release/net10.0/. ~/.local/share/PhoneBox/app/
# ↑ 把该目录**顶层全部文件**拷走：`PhoneBoxAgent.deps.json` 与 `.runtimeconfig.json` 同样是 dotnet
#   运行必需（别按“七个名字”挑着拷）；`.pdb`、嵌套的 `linux-x64/` 旧打包残留捎带无妨。
#   2026-11-18 dry-run 对账：构建目录与现网在用目录 11 个文件逐名一致，可整目录照搬。
# 覆盖正在跑的文件用 cp --remove-destination（否则 ETXTBSY）
loginctl enable-linger <user>                                  # headless 常驻的关键
mkdir -p ~/.config/systemd/user && cp systemd/phonebox.service ~/.config/systemd/user/
sed -i "s|<user>|$(id -un)|g" ~/.config/systemd/user/phonebox.service   # 单元不 sed 就 enable，systemd-analyze verify 会报 Command 不存在
systemctl --user daemon-reload && systemctl --user enable --now phonebox
```

首启自动生成 `~/.config/PhoneBox/config.json`（含新生成的 lan token）。验收：
`curl -s 127.0.0.1:8765/api/state | jq .pc.gpu` 有 util/温度/瓦数/显存字段；手机插入后
journal 应出现 `adb reverse set up, panel opened`。`DOTNET_ROOT=/usr/lib/dotnet` 已在单元内
（apt 版 dotnet 的位置，tar 装法改这行）。

## 数据文件（换机必搬）

`~/.config/PhoneBox/` 三件套：`config.json`（**面板布局真源** + LAN token，脱敏快照见
`../panel/layout/`）、`data.json`（push 地板持久化，可丢）、`lan-cert.pfx`（**LAN 证书——
手机按指纹 pinning 配对，换服务器必须搬走它**，否则手机要重新配对）。

## HTTP API（8765 = loopback 免鉴权；8766 = LAN HTTPS + `lan.token`）

`GET /api/state|config|push|phones|lan|pair`；`POST /api/push/<key>`（body 纯文本/数字，或
JSON `{value,title,sub,unit,icon,color,level(ok|warn|error),progress(0-1|0-100),items[],ttl}`，
ttl 也可 `?ttl=`；≤200 keys、64 KB、重启不丢但会被折成地板值）；`DELETE /api/push/<key>`。
8766/WAP 走 `Authorization: Bearer <lan token>` 或 `?token=`。settings 页浏览器开
`http://127.0.0.1:8765`。

## 升级/回滚

换代一行：`cp --remove-destination <新产物> ~/.local/share/PhoneBox/app/ && systemctl --user restart phonebox`；
回滚同法拷旧 dll。agent 与 APK 的升级顺序**任意**：新 agent+旧 app 走 version-bump 二次拉兜底，
旧 agent+新 app 忽略缺失的 pushItems 照常。
