# 脱敏对照表 —— 本仓库里没有一处现场真值

本仓库由运行中的现场资产重新整理而成。按约定，**服务器/NAS 地址、
部署用户名、密码、小米插座 IP 与设备 token、面板 LAN token、Strata 引擎 API key** 全部改为
占位符；通用骨架路径（`~/.config/...`、`/usr/local/bin/plugdelay/`、`/etc/systemd/...`）与端口
（8765/8766/8022/8080/12345）保留，因为它们不含身份信息、且是部署步骤的一部分。

## 占位符 ↔ 含义 ↔ 真值存放位置

| 占位符 | 是什么 | 真值在哪 |
|---|---|---|
| `<server-ip>` | Strata 服务器（跑 agent 的 Ubuntu）局域网地址 | 服务器 `~/.config/pb_hosts.env`：`PB_SERVER_IP` |
| `<nas-ip>` | 群晖 NAS（plug-delay 判决端）局域网地址 | 同上 `PB_NAS_IP`；另见服务器 `/usr/local/bin/plugdelay/plug-delay.env` 的 `NAS_IP` |
| `<plug-ip>` | 小米智能插座（xiaomi.plug.04）局域网地址 | 服务器 `~/.config/pb_plug.env`：`PB_PLUG_IP`；NAS `plug-secret.env` 同名一份（**两处各存**） |
| `<plug-token>` | 插座 32 位 miio token | 服务器 `~/.config/pb_plug.env`：`PB_PLUG_TOKEN`；NAS `plug-secret.env` 同名一份 |
| `<phone-ip>` | 面板手机的 Wi-Fi 地址（无线 adb 排障用） | `pb_hosts.env`：`PB_PHONE_IP` |
| `<gateway>` | 家里网关 | `pb_hosts.env`：`PB_GATEWAY_IP` |
| `<serial>` | 手机 adb 序列号 | `pb_hosts.env`：`PB_PHONE_SERIAL`（旧脚本里沿用的变量名是 `PB_SERIAL`，跑还原后的脚本时 export 一个即可） |
| `<host>` | 服务器主机名 | `pb_hosts.env`：`PB_HOSTNAME` |
| `<ssid>` | 家里 Wi-Fi 名 | `pb_hosts.env`：`PB_SSID` |
| `<lat>` / `<lon>` | 天气坐标 | `pb_hosts.env`：`PB_LAT`/`PB_LON`；运行态真值在 `~/.config/PhoneBox/config.json` |
| `<user>` | 服务器登录用户名（一切 `/home/<user>`、`<user>@<server-ip>`） | 就是部署者自己；映射变量 `PB_USER` |
| `<nas-user>` | NAS 家目录用户名（`/volume2/homes/<nas-user>/...`） | 映射变量 `PB_NAS_USER` |
| `<data-disk>` | 服务器数据盘挂载名（`/mnt/<data-disk>/temp/...` 存备份与日志） | 映射变量 `PB_DATA_DISK` |
| `<plug-token>`/`<lan-token>` 类凭据 | SSH 密码 | **从不落任何文件**（只在部署者本机），ssh 用 askpass 方式见主 README 排障附录 |
| `<REDACTED lan.token>` / `<REDACTED lan.webhookToken>` | 面板 8766/Webhook 的访问 token | 真值只在运行态 `~/.config/PhoneBox/config.json`；**新装 agent 自己生成，无需还原** |
| `REPLACE-WITH-STRATA-API-KEY` | Strata 引擎 API key 兜底值 | 运行时 collector 会自适应从引擎进程 `--config` 里读真 key；兜底才用环境变量 `PB_STRATA_FALLBACK_KEY` |

## 运行时环境变量契约（脚本要能跑，必须给的）

| 脚本 | 必需 | 可选（有默认值） |
|---|---|---|
| `plug-control/power-read/pb_plug_read.py` | `PB_PLUG_IP`、`PB_PLUG_TOKEN`（由 `~/.config/pb_plug.env` EnvironmentFile 注入） | `PB_PLUG_CACHE/STATE/LOOP/DT_CLAMP/SAVE_S/BEAT` |
| `phone-behavior/charge-guard/pb_chargeguard.sh` | 无硬必需（`<serial>` 占位在 `PB_SERIAL` 默认位） | `PB_SERIAL PB_NODE PB_STATE PB_LOG PB_PUSH PB_BEAT PB_STOP_LEVEL…`（全部在文件头） |
| `phone-behavior/standby/pb-sleep.sh` | 无（`<serial>` 是**裸占位**，部署时要直接 sed 成真值，见主 README Step 5） | — |
| `phone-behavior/standby/pb-wake.sh` | 无（`${PB_SERIAL:-<serial>}`） | `PB_SERIAL` |
| `panel/collector/pb_strata_push.py` | 无（引擎 key 自适应） | `PB_STRATA_FALLBACK_KEY PB_RESIDENT` |
| NAS `plug-control/plug-delay/nas/*` | `plug-watcher.env`（阈值+命令）、`plug-secret.env`（IP+token） | 见两个 `.example` 注释 |

## 还原与校验

```bash
# 还原单个文件（映射来自 ~/.config/pb_hosts.env + pb_plug.env）
tools/deanonymize.sh phone-behavior/standby/pb-wake.sh > /tmp/run.sh && bash /tmp/run.sh
# 只看映射齐不齐
tools/deanonymize.sh --check
# 提交前后扫一遍真值（期望输出为空）。模式**现场从真值文件派生**、绝不把真值写进命令本身，
# 否则文档自己就成了泄露点（历史上栽过两次）。用“模式文件 + git grep -F”做**固定串匹配**——
# 早先那版“sed 转义后拼 ERE”的写法实测在 Ubuntu sed 上直接报错、VALS 为空，空 VALS 会让
# grep -E "" 匹配一切而全仓假红；模式文件法没有转义坑，还天然支持带空格的值。
# 只扫 ≥4 字符的值（短用户名交给形态扫描）。例外：7/8 位短 SHA、APK md5、文件校验和是有意
# 保留的“文件指纹”而非秘密；device_list.masked.* 与 *.apk 跳过。
PF=$(mktemp); cut -d= -f2- ~/.config/pb_hosts.env ~/.config/pb_plug.env 2>/dev/null \
  | grep -v '^#' | tr -d '"' | grep -vE '^$|^<|REPLACE|REDACTED' | grep -E '^.{4,}$' > "$PF"
git grep -In -F -f "$PF" HEAD -- ':!*.masked.*' && echo '^^ 有泄露！' || echo CLEAN-VALUES
# （git grep 无命中时退出码非 0，所以上面是“有命中=坏”。形态扫描写成 @(192|10\.) 是为了
#   让这行文档自身不被自己的模式命中；短用户名/登录串形态都在这里兜底：）
git grep -InE '/home/[a-z]{1,3}\b|homes/[a-z]{1,3}\b|@(192|10\.)' HEAD -- ':!*.masked.*' || echo CLEAN-SHAPES
# 提交信息也要扫（-F 同用这份模式文件）：
git log --format=%B | grep -nF -f "$PF" || echo CLEAN-MSG
rm -f "$PF"
```

## 三条“裸占位会破坏语法”的自伤姿势（写文档/改脚本时保持）

1. shell 赋值右侧裸 `<x>` 会被读成输入重定向（`bash -n` 报错）→ 写 `${VAR:-<x>}`；
2. JSON 数值字段裸 `<x>` 不是合法 JSON → 写 `"<x>"`（deanonymize 的带引号规则排在裸规则之前，
   还原回来正好是裸数值）；
3. 对照表文档和 commit message 自己最容易把真值写回去（连“扫描模式示例”本身都会把 serial、
   密码片段、token 前缀、用户名、盘名整个塞回仓库）——所以扫描模式必须像上面那样从真值文件
   现场派生，且每次提交后同时扫 HEAD 树与 `git log --format=%B`。
