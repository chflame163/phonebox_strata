# 代码来源与谱系（全新仓库，但代码不是凭空写的）

本仓库是 2026-11-18 从运行中的现场资产**重新整理**出来的全新本地 git 仓库（无任何历史提交）。
最终源码从服务器 `~/phonebox`（分支 `main-anonymized`，tip `c348e8a`） 取出后按功能目录重组。

## 上游项目

- 主体来自 **https://github.com/ersinkoc/phonebox**（MIT，Copyright (c) 2026 Ersin KOÇ）。
  本仓库 `app/`（= 上游 `android/`）与 `agent/` 的基线是上游 **`d15399d`（v0.9.4，2026-11-11）**。
- 根目录 `LICENSE` 即上游 MIT 原文，全部衍生改动同受该许可证约束。上游版本沿革见
  `docs/UPSTREAM-CHANGELOG.md`（其中 `Unreleased` 一节 = 本项目改动摘要）。

  

## 各目录的来源

| 目录                                                    | 来源                                                                                                                                                                   |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `app/`                                                  | 上游 `android/` 工程 + 本项目手机侧改动（power-standby 数据判据、事件唤醒、深睡、断流地板网、big value 字号自适应）                                                     |
| `agent/`                                                | 上游 `agent/` + 本项目后台改动（pushItems 随 1 Hz 状态帧、冷启动/停机地板 `Floored`/`SealAndFloorAll`、powerStandbyDelay 默认 5 s）+ 上游测试套件                       |
| `panel/`、`phone-behavior/`、`plug-control/power-read/` | 现场部署在跑的**最终态**脚本与 systemd unit（原 60 余个 patch/TESTLOG/截图开发归档未入本仓库，见下文“归档在哪”）               |
| `plug-control/plug-delay/`、`plug-control/docs/`        | 原独立项目「Ubuntu服务器联动群晖NAS控制智能插座」（自研，纯标准库判决器 + python-miio 执行），其 README/RESTORE 内容并入本仓库文档                                     |
| `tools/`                                                | 上游开发小工具（check-release / demo-server / smoke-agent / ui-tests）+ 构建配方 `build-android.sh`（原 `pb_build.sh`，路径已适配新布局）+ 还原脚本 |
| `docs/UPSTREAM-CHANGELOG.md`                            | 上游 `CHANGELOG.md` 原文（顶部加出处说明）                                                                                                                             |
| `artifacts/`                                            | 当前在装的 APK（编译产物，指纹见该目录 README）                                                                                                                        |

## 上游自带但未搬进本仓库的东西

网站与宣传物料（`docs/` 的截图/gif）、安装器工程（`packaging/`）、Scoop/winget 清单（`bucket/`）、
`scripts/` 安装脚本、`extras/`（另一台设备的 debloat）、`.github/` CI。需要时去上游仓库
`ersinkoc/phonebox` 对应 tag 取；本仓库的 agent 部署不依赖它们（从源码构建，见 `agent/README.md`）。

## 第三方来源与指纹

- **面板手机 ROM**：LineageOS 18.1（oneplus3 树，3T 变体）。官方 https://download.lineageos.org/oneplus3
  ；当时实装的社区存档 = archive.org item `lineage-18.1-20240305-nightly-oneplus3-signed`
  （zip md5 `a0a9f3b16f6288b9ffe0838834bc3ec5…`、recovery md5 `8d254ca631856e064dada632748ee082…`，官方签名）。详见 `phone-setup/README.md`。
- **插座 token 提取工具**：`token_extractor.exe`（19.6 MB，不入库）来自
  https://github.com/fh489320599/Xiaomi-cloud-tokens-extractor 的 Windows release
  （md5 `a1e867c75fe08881bd334d05502c1d95`，sha256 前缀 `43079f88f7a7ad49`）。用法见
  `plug-control/docs/token-extraction.md`。
- **python-miio** 0.5.12（PyPI，服务器与 NAS 各建 venv）；**小米 MIoT spec**：
  `urn:miot-spec-v2:device:outlet:0000A002:xiaomi-04:1:0000D032`（https://home.miot-spec.com/spec/xiaomi.plug.04）。
- `plug-control/docs/xiaomi-plug4-windows-guide.md` 是一篇 AI(GPT) 生成的局域网控制参考文档，
  其中的示例 IP/token 均为占位性质，实测结论以 `docs/plug-04-properties.md`（自研 probe 实测）为准。
- `panel/widgets/strata_model_state_html_widget.html` 的视觉与文案复刻自 **Strata 引擎**自带的
  `serve/web` Monitor 页（引擎不在本仓库，见下）。

## 外部依赖：Strata 推理引擎（不在本仓库范围）

`panel/collector/pb_strata_push.py` 只消费引擎的 HTTP 契约：`/health`（免鉴权）、`/metrics`、
`/slots`（Bearer 引擎 API key，collector 会自适应从引擎进程的 `--config` json 里读）。引擎换版本
只要这些端点字段不漂移，面板链路就不受影响。整机没有引擎时，其余功能（墙电、充电、息屏策略）
照常工作，只是 strata_* 四个 key 断供过期。

## 开发过程归档在哪（本仓库“只留最终态”舍掉的部分）

服务器 `~/phonebox` 本地 git 的全部提交历史与脱敏前档案；服务器
`/mnt/<data-disk>/temp/phonebox-*`、`pb-*` 各目录里的 patch 脚本、TESTLOG 实测记录、逐秒时间线
CSV 与截图证据（2026-11-12 ~ 11-18 每一轮改动的验收现场）；插座联动项目本地 git（含未脱敏原件
`device_list.txt`、旧版硬编码 `nas_off/on/get.py`）。要追任何一条“当初为什么这么做”，去这些
归档；本仓库的 README 只写结论与操作。
