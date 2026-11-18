# phone-setup/ —— 从裸机手机到“面板机”（以一加 3T 为例）

面板机要求：Android 9+ 且 System WebView 够新、可 adb 常驻授权、可当 kiosk 桌面、（充电策略）
需 adb root。3T 的氧 OS 9.0.6 被判死：framework 的 WebView glue 类（`android.webkit.PacProcessor`）
被裁、framework.jar 是 soon_zip 定制结构，官方 Android 8+ 专供 WebView（135/138）都必崩，内置
M66 又跑不动面板——所以刷 **LineageOS 18.1（Android 11，官方终点版，自带 WebView 122，面板够用）**。
换别的手机：先 `dumpsys webviewupdate` 看 WebView ≥ 近两年的大版本就不必刷机，其余步骤照旧。

## ROM 与镜像来源

- 官方：https://wiki.lineageos.org/devices/oneplus3/install/variant2 （变体选 **3T**；文件名里的
  `oneplus3` 是 3/3T 共用代号）+ https://download.lineageos.org/oneplus3 （选 18.1 的 build，
  按页面 sha256 校验；**Gapps 可不装**）。注：官方 v2 API 后来已删 oneplus3（老机型归档），
  下载入口若失效就用社区存档。
- 本次实刷存档：archive.org item `lineage-18.1-20240305-nightly-oneplus3-signed`（官方签名）：
  zip md5 `a0a9f3b16f6288b9ffe0838834bc3ec5…`、recovery md5 `8d254ca631856e064dada632748ee082…`。
  印度版 A3010 直接用 A3000/A3003 的树（官方设备列表不写 A3010，社区通用）。

## 刷机步骤（要点，细节以 wiki 为准）

1. 解锁会清全机数据；3T 这台 bootloader 原生已解锁（`fastboot oem device-info` 验）。
2. 关机 → 音量上+电源 进 fastboot。**坑**：一个不稳定的旧 USB 口能让 fastboot 传输必败——
   换口再试（换口后 19 MB 只要 0.5 s）。
3. **坑**：OOS 下 `fastboot flash recovery` 必败（recovery 分区 size:0 + Protocol error）。
   正确姿势：`fastboot boot recovery.img` 纯 RAM 引导 Lineage recovery，刷入动作让它自己写对。
4. recovery：Wipe data/factory reset → Advanced → ADB Sideload → `adb sideload <zip>` → 重启。

## 首启后的“面板机”配置清单

```text
1  跳过账号向导；连家里 Wi-Fi（天气/校时需要）；关于手机连点 Build 7 次开开发者选项
2  开 USB 调试；服务器上 adb devices → 手机端点“允许调试”（勾选始终允许）
3  开发者选项开 “Root 身份调试”（=ADB root；充电策略脚本要 root 写 PMIC）
4  adb install -r -t -d <apk>（见 ../app/README.md；或直接装 ../artifacts 里的现场版）
5  adb shell cmd package set-home-activity net.oxog.phonebox.deskpanel/.HomeAlias   # kiosk
   （A11 的 cmd role / pm enable 别人组件都会失败，只有 set-home-activity 这条路）
6  adb shell settings put system screen_off_timeout 10000     # 熄屏超时 10 s（grace 5 + 10 ≈ 拔线18s熄）
7  adb shell settings put global auto_time 1                   # 校时（3T 钟曾慢 2 h）
8  面板亮度手动降到 50-60%（省电与烧屏友好）；夜间可开 8765 里的 night 主题
9  天气坐标与 home 页控件按 ../panel/README.md 配好
10 验收：agent journal 出现 panel opened；screencap 截图核对；然后跑
   ../phone-behavior/README.md 的“三场景”与一次真关机/真开机
```

服务器端配套（同一次部署里做完）：`apt install adb fastboot`；装 `udev/91-oneplus.rules` 与
`udev/92-fastboot.rules` 进 `/etc/udev/rules.d/`，然后
`sudo udevadm control --reload && sudo udevadm trigger`（Ubuntu 自带的通用规则对 2a70 不生效，
没有这两条 adb 只能 root 跑）；BIOS：**ErP 开**（关机必须断 USB 5V，这是熄屏链的核心前提）、
来电自启开（断电恢复链用）、Fast Boot 可选再快 10-15 s。

## USB 口与供电事实（3T + Z790 现机实测，换机可类比）

充电口电流被 Lineage 固定在 1.7 A 检测档（换背板直连/前置 hub 都一样），瓶颈在亮屏负载本身；
手机现挂 xhci root 口**不能**逐口软件断电——想“软件拔线”需要接到可 ppps 断电的 hub 口
（`sudo uhubctl -l <devpath> -p <port> -a off|on`，实测 SS/HS 双层联动断电、恢复后 adb 自动回弹）。
停充保护走的是 PMIC 开关（`../phone-behavior/README.md`），不依赖口断电。

## 反悔与兜底

刷回官方：OxygenOS 9.0.6 全量镜像 fastboot 逐分区刷回（bootloader 保持解锁不影响面板行为）。
Lineage 20+ 对 3T 只有非官方构建，不建议升。刷机不影响服务器侧资产，重连 adb 重新装 APK 即可。
