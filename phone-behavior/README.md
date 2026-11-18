# phone-behavior/ —— 息屏深度休眠（主机侧钩子）与充电策略

手机侧的判据/深睡实现都在 APK 源码（`../app/README.md`）；本目录是**服务器侧**配套：
`standby/`（关机熄屏/开机亮屏的系统级钩子）、`charge-guard/`（PMIC 停充三层策略）、
`bootcheck/`（可选开机取证）。语义全图见 `../docs/ARCHITECTURE.md` §3。

## 1. standby/ —— 关机熄屏、开机亮屏、深睡省电

### 现行为（全部真机实测）

- 关机：`phonebox-sleep.service`（oneshot `RemainAfterExit`，`ExecStop=~/bin/pb-sleep.sh`）在
  poweroff/reboot 时执行 v2 脚本——最多 3 个 adb 调用（stay_on=0 → keyevent 223 → 仍醒才补 223）。
  **故意不 force-stop 面板进程**（保住“主机重新枚举那一秒点亮”的路）。v1 曾连打 12 个 adb 调用，
  与 session teardown 抢通道导致 223 被拆包、屏幕回锁屏再迟 24 s 才睡——别再改回去。
- 随后 ErP 断 USB 5V → 手机端 hostAlive 判死 + 5 s grace → 熄屏（实测拔线 ~18 s）→ 熄屏满 5 min
  进深睡（停 WebView/SSE、trim 预载、只留 60 s 心跳探针；过夜整机 ~15 mA，比不深睡省 60%+）。
- 开机：三重保障——agent 的 adb reverse 重建即 WAKEUP+开面板（主力，实测枚举→亮 <1 s）；
  手机端 USB_STATE 事件自亮+撤锁屏；`phonebox-wake.service` 兜底（**`After=local-fs.target`**
  不等网络，脚本自己等设备最长 2 min；WAKEUP + dismiss-keyguard×6 + am start）。
  按键→面板亮实测 ≈40 s，瓶颈全在 POST/内核（BIOS Fast Boot 可再省 10-15 s）。
- 开机后 agent 的 keepAwake 会把 `stay_on_while_plugged_in` 写回 7（熄屏能力由手机侧判据负责）。

### 前提

BIOS **ErP** 开（关机断 USB 5V）；手机 kiosk/授权/root 设置见 `../phone-setup/README.md`；
手机钟与服务器钟差可到分钟级——跨设备对表按**事件顺序**不按时间戳。

### 部署

```bash
mkdir -p ~/bin && cp standby/pb-sleep.sh standby/pb-wake.sh ~/bin/ && chmod +x ~/bin/pb-*.sh
SER=$(adb devices | sed -n 2p | cut -f1)                      # 手机序列号（=<serial> 的真值）
sed -i "s|<serial>|$SER|" ~/bin/pb-sleep.sh                   # 裸占位：不改也能跑，但改不到设备
sed -i "s|<serial>|$SER|" ~/bin/pb-wake.sh                    # 或留 ${PB_SERIAL:-…} 默认位、由环境给值
sudo cp standby/phonebox-sleep.service standby/phonebox-wake.service /etc/systemd/system/
# 单元里 /home/<user> 两处（ExecStop/ExecStart 与 Environment=HOME）= 跑 adb 授权的那个用户，
# root 服务借用它的 ~/.android 密钥；$USER 在你自己的 shell 里展开为部署用户名，正好
sudo sed -i "s|<user>|$USER|g" /etc/systemd/system/phonebox-sleep.service /etc/systemd/system/phonebox-wake.service
sudo systemctl daemon-reload && sudo systemctl enable --now phonebox-sleep phonebox-wake
```

注意 sleep/wake 需要 **root**（关机阶段与 multi-user 之前跑 adb）；adb 密钥与设备授权都在
`<user>` 家里，两单元已 `Environment=HOME=/home/<user>` 借好。别用 `adb … authorized=0` 模拟
“主机不在”——那只断数据不断 VBUS，是无效测试。

### 三场景验收（无需真关机）

1. `systemctl --user stop phonebox` → ~30 s 内熄屏（数据判据路）；`start` 回来 → 自动亮。
2. `adb reverse --remove-all` + `systemctl --user start phonebox` → 秒级亮屏进面板（模拟开机枚举）。
3. 真拔插：先 `adb tcpip 5555 && adb connect <phone-ip>:5555` 挂**无线 adb**再拔（否则断电就丢
   观测窗）；回插应 ~1 s 亮。取证 `logcat -d -v epoch` 切片；batterystats 判深睡用 `--history`
   的 `device_idle` 与电流，**别看** “screen doze”（此 ROM 恒 0）。

### bootcheck/（可选取证，现场默认开着）

user 级 oneshot，boot+120 s/+300 s 两拍抓 wakefulness/focus/logcat 当场 spool/截图，落
`/mnt/<data-disk>/temp/pb-bootcheck-*/`。cp `bootcheck/pb_bootcheck.sh` 到 `~/bin`、单元进
`~/.config/systemd/user/`、`systemctl --user enable pb-bootcheck` 即可；不装不影响任何功能。

## 2. charge-guard/ —— 长期插电电池的三层策略（服务器 cron + adb root）

写 PMIC sysfs `/sys/class/power_supply/battery/charging_enabled`（0=停充），**不切 USB 口**；
停充时 VBUS 与 adb 数据链都在，面板不受影响。agent 自带 ChargeGuard（uhubctl/HA 路）在现接线
下切不到手机口，保持 config `charge.enabled:false`，别在 8765 打开回去。

| 层 | 动作 | 默认参数 |
|---|---|---|
| 日常 | ≥80 停充 / ≤65 复充（滞回） | `PB_STOP_LEVEL=80 PB_START_LEVEL=65 PB_FLOOR_LEVEL=25` |
| 每周 | 40→80 一次完整校验 | `PB_W_DAYS=7 PB_W_START=40 PB_W_STOP=80` |
| 每季 | 20→100 + 满 100% 后顶充 30 min（校准燃料计），完成顺手刷新周校验时间 | `PB_Q_DAYS=90 PB_Q_START=20 PB_Q_STOP=100 PB_Q_TOPOFF_MIN=30` |

保护：≤8% 无条件复充并中止当期校验；放电>10 h/回充>18 h 自动退回日常并告警。状态文件
`~/.local/state/pb_chargeguard.state`（跨重启保留日程）；日志 `/mnt/<data-disk>/temp/pb_chargeguard.log`，
每 `PB_BEAT=30` 跳（≈30 min）一行心跳。**前提**：Lineage userdebug + 开发者选项开
“Root 身份调试”（脚本会自判 `id -u` 非 0 时自动 `adb root` 一次；手机重启后 adbd 回 shell 属预期）。

```bash
mkdir -p ~/bin && cp charge-guard/pb_chargeguard.sh ~/bin/ && chmod +x ~/bin/pb_chargeguard.sh
SER=$(adb devices | sed -n 2p | cut -f1)
sed -i "s|<serial>|$SER|" ~/bin/pb_chargeguard.sh            # PB_SERIAL 默认位；systemd 不会自动 source pb_hosts.env，所以直接 sed
mkdir -p ~/.config/systemd/user && cp charge-guard/pb-chargeguard.{service,timer} ~/.config/systemd/user/
sed -i "s|<user>|$(id -un)|g" ~/.config/systemd/user/pb-chargeguard.service
systemctl --user daemon-reload && systemctl --user enable --now pb-chargeguard.timer
~/bin/pb_chargeguard.sh status|calib weekly|calib qtr|on|off|reset   # 手动口
```

单元里已带 `Environment=PB_PUSH=0`（历史上推过 `charge_state` key，现已停推，恢复删该行）。
不碰手机验策略（dry-run 注入）：
`PB_ONESHOT=1 PB_LEVEL=39 PB_CHG=0 PB_NOW=<epoch> PB_STATE=/tmp/s PB_PUSH=0 ~/bin/pb_chargeguard.sh auto`。
24 条断言的状态机测试（pb_gtest.sh）属开发归档，未入库（服务器 temp 有存档）。
待观察项：停充+亮屏时电池补差放电实测可到 ~20%/h，65% 复充后能否爬回 80% 看心跳日志；
爬不动就把区间改浅（`PB_STOP_LEVEL=100 PB_START_LEVEL=90`）。
回滚一行：`systemctl --user disable --now pb-chargeguard.timer && adb -s <serial> shell "echo 1 > /sys/class/power_supply/battery/charging_enabled"`。
