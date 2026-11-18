# app/ —— 面板手机 APK（Android 工程，即上游仓库的 `android/` 目录）

这就是那块“旧手机当电脑状态屏”的面板 App：`net.oxog.phonebox.deskpanel`。它把 agent 经
`adb reverse`/局域网送来的状态流画成小部件桌面（本项目的 home 页见 `../panel/README.md`），
并负责**息屏深度休眠策略**的全部手机端实现（充电策略在服务器侧执行，见
`../phone-behavior/README.md`）。工程根就是这个目录：`gradlew` 在这层，主模块在 `app/app/`，
面板前端是打包进 APK 的 WebView 资产（`app/app/src/main/assets/www/`，纯 JS，无 npm 构建步骤）。

## 相对上游 0.9.4 的全部本地改动清单

| 文件 | 改动 |
|---|---|
| `app/app/src/main/java/net/oxog/phonebox/deskpanel/MainActivity.kt` | power-standby 全套：hostAlive 数据判据（12 s agent 静默 + grace，`elapsedRealtime` 计时）、`ACTION_USB_STATE` 事件唤醒（枚举→亮 <1 s，15 s 轮询只作兜底）、深睡（熄屏 5 min 后暂停 WebView/SSE、逐包 killBackgroundProcesses，只留 2 个 sticky 接收器 + 60 s 不握锁探针）、唤醒时 `requestDismissKeyguard()`、放锁后 `requestLayout()`、满电 `STATUS=CHARGING|FULL` 视为插电的 HAL 兜底 |
| `app/app/src/main/AndroidManifest.xml` | 新增 3 个 normal 权限：`WAKE_LOCK`、`DISABLE_KEYGUARD`、`KILL_BACKGROUND_PROCESSES` |
| `app/app/src/main/assets/www/main.js` | 断流地板网：`lastStateAt` >5 s 无帧 → 本地把 push 值/内置 stat 卡/进程列表全折最低态（pushItems 随帧消费也在上游 main.js 改造内） |
| `app/app/src/main/assets/www/widgets/basic.js` | `fitBigValue()`：big value 每次渲染实测宽度，真超宽才按比例缩字号（保底 10 px）；topprocs 空表地板 |
| `app/app/src/main/assets/www/style.css` | big value 字号基数 `min(56cqh,24cqw)`/700 字重 + `@container (max-height:78px)` 矮卡减 chrome 税 |
| `app/app/build.gradle.kts` | `versionCode 31`、`versionName "0.9.4+fork"`（不再与上游互相踩降级） |
| `gradle/wrapper/gradle-wrapper.properties` | distributionUrl 换腾讯镜像（国内网络构建加速，有意保留） |

原理与数据流见 `../docs/ARCHITECTURE.md` §2/§3；改动动因的完整叙事在
`../docs/UPSTREAM-CHANGELOG.md` 的 Unreleased 一节。

## 编译（从零）

环境清单见 `../tools/README.md`（JDK17 + Android platform35/build-tools34 + gradle 8.11.1 wrapper
+ 阿里云 maven init）。一条命令跑完全部步骤：

```bash
PB_ANDROID_DIR=$HOME/PhoneBox_Strata/app ../tools/build-android.sh
# 产物: app/app/build/outputs/apk/release/app-release.apk（首次约 28 s，daemon 常驻后增量 11-13 s）
```

手动路径等价：`export JAVA_HOME=…; echo sdk.dir=$HOME/android-sdk > local.properties;
./gradlew assembleRelease`。**判 `$?` 再看产物**——gradle 失败时别拿旧 APK 去装（踩过）。

## 安装与升级

```bash
adb install -r -t -d app-release.apk          # -r 覆盖/-t test 属性/-d 允许降级
adb shell cmd package set-home-activity net.oxog.phonebox.deskpanel/.HomeAlias   # kiosk 桌面
```

release 配置无 keystore → 自动回落 **debug key 签名**，同 key 覆盖升级不丢数据与配对（跨官方签名则要先卸载）。
装后验收：`adb shell dumpsys window | grep mCurrentFocus` 应为 `…/.HomeAlias`；
`adb -s <serial> exec-out screencap -p` 截图核对控件。当前在装 APK 见 `../artifacts/README.md`。

## 调教点与已知边界

- `configured || connected` 取或是**有意的**：热重启（5V 不断）与 PMIC 停充期间面板不闪熄；
  代价是若插在“关机仍供电”的口（ErP 未开），面板会常亮。前提条件见 `../phone-setup/README.md`。
- 关键常量都在 MainActivity 顶部：`AGENT_SILENCE_MS`(12 s)、`STANDBY_POLL_MS`(15 s)、
  `DEEP_AFTER_MS`(300 s)；宽限秒数来自 agent 配置的 `display.powerStandbyDelaySec`(默认 5)。
- 满电时部分 HAL 报 `EXTRA_PLUGGED=0` 而 `STATUS=FULL`——已有兜底，别改回去。
- Lineage A11 的坑：`WebView` 只有 `onPause()/onResume()`（无 `resume()`）；
  `killAllBackgroundProcesses` 是 @hide 只能用公开逐包版；`cmd role`/`pm enable` 别家组件会失败。
- 历史版本链（vc24→31 及各 md5 指纹）见 `../artifacts/README.md`；证据归档在服务器 temp
  （`docs/CODE-SOURCES.md` 末节指路）。

## 测试

`../tools/ui-tests/`（上游 Playwright 面板回归，对着 `../tools/demo-server.py` 假 agent 跑）。
真机行为验收按 `../phone-behavior/README.md` 的“三场景”法执行。
