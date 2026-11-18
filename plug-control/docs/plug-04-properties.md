# xiaomi.plug.04 属性实测表（局域网 miio）

插座 = `xiaomi.plug.04`（型号串明写在 `miiocli device ... info` 里），MIoT spec URN
`urn:miot-spec-v2:device:outlet:0000A002:xiaomi-04:1:0000D032`
（<https://home.miot-spec.com/spec/xiaomi.plug.04>）。协议走 LAN miio v2（UDP，AES-128-CBC，
key/iv 由 32 位 token 导出），**不需要云、不需要米家账号在线**，所以 NAS 与服务器都不出网也能控制。

下表"实测"列全部来自 `panel/probe/plug_probe.py`（只读，2026-11-17 23:28 那轮 60 样本）与随后
`panel/phonebox/pb_plug_read.py` 的持续采集（2026-11-18 凌晨 bad=0/796 样本）。

| siid/piid | spec 里的 type | 单位 / 格式 / 量程 | access | 实测 |
|---|---|---|---|---|
| 2 / 1 | on（Switch） | bool | read, notify, **write** | 读写都 `code:0`；写 `false` = 切掉服务器电源 |
| 11 / 2 | electric-power | **watt** · uint16 · [0,10000] 步进 1 | read, notify | 60/60 `code:0`；观测 79–583 W；**相邻不同值间隔 min=median=1.00 s** |
| 11 / 1 | power-consumption | kWh · float · [0,65535] 步进 0.01 | read, notify | **逐样本等于 11/2**（547 → 547.0、79 → 79.0）→ 见下"电量为啥不能用" |
| 11 / 3 | power-consumption-accumulation-way | bool | read | **false** |
| 4 / 4、4 / 6 | （spec 把"延时关闭 / 延时开启剩余"挂在 service 008 名下） | — | read | 探测顺带读到 `2` 与 `480`，**语义未经对照 spec 核实**，别当依据；与我们的 180 s 无关（见最后一条） |

spec 里**没有**电压、电流属性；siid 9 是"最大功率限制"（过载保护阈值），不是读数。

## 电量为啥不能用、日能量怎么来的
11/1 在 `way=false`（=当前状态）下不是累计电表，而是**功率镜像**：60 秒窗口里它"掉了 468 kWh"。
要让它变成真累计得**写** 11/3 = true，那是写操作，本套代码一律不写（见下条红线）。
所以"今日耗电"由服务器侧自己积分：`∑W·dt`，本地午夜归零、进程重启续算、超 60 s 的采样空洞不补计
（实现 `panel/phonebox/pb_plug_read.py`，状态文件 `~/.local/state/pb_plug.state`）。
这么定还顺手躲开了"掉电后设备累计会不会清零"这个查不掉的未知——这只插座本来就被本机制断电。

## 只读红线（谁都不能碰 siid 2 / piid 1）
写 `siid2/piid1 = false` **就是切服务器自己的电**。全系统只有两处有权写它：

1. 群晖 `nas/nas_off.py`（watcher 触发时断电）；手动恢复用同一脚本的 `--on`。
2. 米家 App（人手）。

phonebox 面板这条链（`pb_plug_read.py` → push key `plug_power` → 4×2 波形控件）**永远只发
`get_properties`**。想验证凭据通不通，用 `nas_off.py --check`（只读），别拿 `--on/--off` 试。

## 时延与"180 秒到底在哪"
关机的 180 s 延时**不在插座里**，在群晖的 `nas/plug_watcher.py`（`SHUTDOWN_DELAY`/`STALE_TIMEOUT`）。
插座自己的 App 侧"延时关闭"设置与它无关，所以别去米家里再配一遍定时——两边都设会变成叠加延时。
反过来，也正因为判决在 NAS 上，服务器关机后不可能提前打断自己的电。

## 换插座 / 重置配网后要做什么
插座被重置并重新配网 ⇒ **token 一定换**（IP 也可能换）。三处都要跟：
`nas/plug-secret.env`（群晖）、服务器 `~/.config/pb_plug.env`（phonebox reader 用），
以及 NAS 上 `plug-watcher.env` 里若走 miiocli 写法时内联的那对值。
验证顺序：`nas_off.py --check`（只读，能读到开关与瓦数即通）→ 重启 reader
（`systemctl --user restart pb-plug-read`）→ 看 `GET /api/push` 里 `plug_power` 又活。
