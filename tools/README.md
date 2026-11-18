# tools —— 构建工具链与开发工具

规则：**大型工具一律不入库**，本文件给下载链接与版本钉；仓库里只放小脚本与配方。

## 版本钉（现机实测，全部装过并验收）

| 组件 | 版本 | 装法 |
|---|---|---|
| dotnet SDK（运行+构建 agent） | 10.0.115（≥10.0.100 即可，TFM net10.0） | Ubuntu 官方源 `apt install dotnet-sdk-10.0`（实测秒装）；或 https://learn.microsoft.com/dotnet/ |
| JDK（构建 APK） | OpenJDK 17（17.0.21） | `apt install openjdk-17-jdk`；`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` |
| Android SDK | platform **android-35** + build-tools **34.0.0**（compileSdk 35/min 28） | 免 GUI：直接解官方 zip 到 `~/android-sdk/`（见 `build-android.sh`），镜像 https://mirrors.cloud.tencent.com/AndroidSDK/ `platform-35_r01.zip`、`build-tools_r34-linux.zip`（官方对应 https://dl.google.com/android/repository/ 同名包） |
| Gradle | **8.11.1**（wrapper 已配好） | `app/gradle/wrapper/gradle-wrapper.properties` 的 distributionUrl 指向腾讯镜像 https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip，`./gradlew` 自动拉 |
| Maven 仓库镜像 | 阿里云 | 服务器 `~/.gradle/init.gradle`（`build-android.sh` 内含现成文本：gradle-plugin/google/public 三仓 + 兜底官方） |
| adb/fastboot | 1.0.41 | `apt install adb fastboot`（Ubuntu 自带即可）；或官方 platform-tools https://dl.google.com/android/repository/platform-tools-latest-linux.zip |
| Python（服务器） | 3.12（脚本只用 stdlib） | 系统自带 |
| python-miio | **0.5.12**（服务器与 NAS 各一个 venv） | `pip install python-miio`（国内走清华源 https://pypi.tuna.tsinghua.edu.cn/simple）；venv 放持久位置：服务器 `~/.venvs/miio`、NAS `/volume2/homes/<nas-user>/pdvenv`（**别放 /tmp**，会被清） |
| uhubctl（可选） | 任意近期版 | 仅在想用“软件拔线”（逐口断电 hub）时需要：https://github.com/mheese/uhubctl；注意现接线下车机口挂 root 口，不可逐口断电 |
| token_extractor.exe（不入库，19.6 MB） | sha256 前缀 `43079f88f7a7ad49`，md5 `a1e867c75fe08881bd334d05502c1d95` | https://github.com/fh489320599/Xiaomi-cloud-tokens-extractor Windows release；用法见 `../plug-control/docs/token-extraction.md` |
| LineageOS 18.1 ROM（不入库） | 见 `../phone-setup/README.md`（官方 + archive.org 镜像及 md5） | 刷机一次性 |
| Playwright（可选） | 按 `ui-tests/package.json` | `npx playwright install chromium`；只用于跑上游 UI 回归 |

## 仓库内脚本

- `build-android.sh` —— 全自动 APK 构建配方：等 JDK → 拉 SDK 两个 zip → 写 `local.properties` →
  换 wrapper 为腾讯镜像（失败自动换回官方）→ 写阿里云 `init.gradle` → `assembleRelease`。
  默认工程目录 `~/phonebox/app`，可用 `PB_ANDROID_DIR` 指到别处；日志 `$HOME/phonebox-build/build.log`。
  实测首次全量 ~28 s、增量 11-13 s（gradle daemon 常驻时）。
- `check-release.py` —— 上游 CI 的版本一致性断言（gradle versionName ≡ csproj Version ≡ tag）。
  **注意它是上游目录布局的产物**（按 `android/`、`agent/` 找文件），在本仓库新布局下若要跑需把
  路径参数改一下；本项目的放宽（versionName 容忍 `+fork` 后缀）已在其内。
- `demo-server.py` —— 上游的假 agent（本地起一个 :8765 喂面板假数据），配合 ui-tests 或纯前端调试。
- `smoke-agent.py` —— 冒烟：用隔离配置拉起一个刚构建的 agent，校验真实 API 面。新装/升级 agent 后可跑。
  其中 `panel/widgets/stack.js` 一项是**上游发布包**的资产检查——从源码新构建的 agent 二进制旁不抽
  `panel/` 目录（现网那份是当年 install.sh 落下的资产），冒烟遇 404 会自动打一行 skipped 并继续，不是错误。
- `ui-tests/` —— 上游 Playwright 面板回归（`npm i && npx playwright test`，会自起 demo-server）。
- `deanonymize.sh` —— 占位符↔现场值还原（见 `../docs/DESENSITIZATION.md`）。

## 排障常用的服务器侧观测口

`curl 127.0.0.1:8765/api/push|state|phones|lan|config`（loopback 免鉴权）；
`adb -s <serial> exec-out screencap -p` 截屏验收；`journalctl --user -u pb-strata-pushd -f` 看推送节拍。
