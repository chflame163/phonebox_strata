# panel/ —— 控件数据采集、控件样式与手机页面布局

三块内容：`collector/`（把 Strata 引擎与墙电数据推给面板的采集脚本及 systemd 单元）、
`widgets/`（两个自定义 HTML 控件的源文件，即“控件样式”）、`layout/`（脱敏后的 agent 配置快照，
即“页面布局”的可还原副本）。数据流全图见 `../docs/ARCHITECTURE.md` §1。

## 1. 七个 push key 的契约（采集 → 显示）

| key | 数据源 | 显示方式（8765 里怎么建） | 阈值/着色（脚本文件头常量） | ttl |
|---|---|---|---|---|
| `strata_tps` | 引擎 `/metrics` `live.tok_s`（decode） | Pushed value，style=big value，**2×1** | level：≥60 ok / ≥20 warn / 否则 error | 20 s |
| `strata_prefill` | 同上 `prefill_tok_s_mean`，idle 回落自算 | Pushed value，big，**2×1** | 同上 | 20 s |
| `strata_ctx` | `/slots` `n_prompt_tokens/1024`（**K tokens 刻度**，unit 写的 KB 不是字节） | Pushed value，gauge，2×2，**max=512** | `CTX_WARN_KB=320` / `CTX_ERR_KB=440`（440 正对 448K 工作窗） | 20 s |
| `strata_state` | 引擎 `/metrics` live 原样透传 + `out_est` 估算 | `widgets/strata_model_state_html_widget.html`，Custom HTML 4×2 | 控件按 payload 画 6 灯+进度条 | 20 s |
| `gpu_power` | agent `/api/state` 的 `pc.gpu`（顺 agent 自己的 nvidia-smi 流，零 fork；nvidia-smi 直采只做兜底） | Pushed value，gauge，2×2，max=350 | `GW_WARN=100 / GW_ERR=300`（绝对瓦数；`sub` 带 GPU 温度） | 20 s |
| `vram_gb` | 同上一次 nvidia-smi 的 `memory.used/total` | Pushed value，gauge，2×2，**max=24** | `VR_WARN_GB=14.0 / VR_ERR_GB=23.2`（按**已用 GB**） | 20 s |
| `plug_power` | `../plug-control/power-read/` 的 reader 经 `/tmp/pb_plug_cache.json` 接力 | `widgets/plug_power_html_widget.html`，Custom HTML 4×2 | 阈值随 payload 下发（`PW_WARN,PW_ERR=250,400`，量程 `PLUG_FULL=700`） | 8 s |

内置 Pushed value 控件**只有 payload 带 `level` 才着色、带 `sub` 才画环下灰字**——所有 key 都已
补齐；gauge 的 max 只在 8765 控件参数里定。着色判定一律“恰好等于阈值算前一档”。改口径只改
`collector/pb_strata_push.py` 文件头的常量行，面板端不用动。

## 2. collector 部署

```bash
mkdir -p ~/bin && cp collector/pb_strata_push.py ~/bin/          # 纯 stdlib，无需 venv
mkdir -p ~/.config/systemd/user && cp collector/pb-strata-pushd.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now pb-strata-pushd
# 前提：该用户已 loginctl enable-linger（见 ../agent/README.md）
```

脚本**自适应**引擎：扫 `/proc` 找跑 `serve/server.py` 的进程，从它的 `--config` json 读
api_key、`--port`——引擎换实例不用改脚本；读不到时才用环境变量 `PB_STRATA_FALLBACK_KEY` 兜底。
GPU 四项走 agent loopback `/api/state`（agent 在就不 fork nvidia-smi；脚本挂了 nvidia-smi+5 s
缓存兜底）。验收：`curl -s 127.0.0.1:8765/api/push | jq '.items|keys'` 应为 7 个 key，
且各 `at` 每秒前进。回滚到旧节奏：`systemctl --user disable --now pb-strata-pushd &&
cp collector/pb-strata-push.{service,timer} ~/.config/systemd/user/ && systemctl --user enable --now pb-strata-push.timer`
（oneshot+1 s timer 那对就是为回滚留的）。

## 3. 页面布局（home 12 卡 + 2 个副页）

快照 `layout/agent-config-snapshot-20261118.json`（含全部控件坐标与参数；lan token/坐标为
占位符）。home（12×6 网格）实拍位姿：

| 控件 | 类型 | 位/尺 | | 控件 | 类型 | 位/尺 |
|---|---|---|---|---|---|---|
| Top CPU 进程 | topprocs | x0 y0 4×4 | | Model state | HTML | x8 y0 4×2 |
| 墙电波形 | HTML | x4 y0 4×2 | | GPU POWER gauge | push | x4 y2 2×2 |
| VRAM gauge | push | x6 y2 2×2 | | CTX gauge | push | x8 y2 2×2 |
| Speed big | push | x10 y2 2×1 | | Prefill big | push | x10 y3 2×1 |
| CPU/GPU/RAM/NET 四 stat 卡 | stat | x0/3/6/9 y4 各 3×2 | | 副页：System Monitor（3 chart+1 meters）、Calendar and Weather（calendar/clock/forecast/air） | | |

两种从零重建法：**A. 快照回植**——把快照里 `<REDACTED lan.token>`、`<REDACTED lan.webhookToken>`、
`"<lat>"`/`"<lon>"` 填成真值（token 直接抄新装 agent 自动生成的那两条），写到
`~/.config/PhoneBox/config.json`，`systemctl --user restart phonebox` 并 force-stop+重启面板进程
让新 config 冷读。**B. 手工搭**——浏览器开 `http://127.0.0.1:8765` 编辑器页，按上表逐个 Add，
两个 HTML 控件选 Custom HTML 并把 `widgets/*.html` **全文粘贴**。

⚠️ 纪律：8765 页面每次保存都会**整体重写** config.json——不要在页面开着未保存时再用脚本写文件
（后保存的一方覆盖前者）；控件 HTML 改了源文件，必须在 8765 重贴一次（页面里存的是全文副本）。

## 4. 控件样式备注

`strata_model_state_html_widget.html` 复刻引擎自带 Monitor 卡的文案与六灯淡显
（`opacity .38` 同色淡、非变灰），含两处对引擎的有意升级（generating 分母用 `out_est` p75
估算、无可靠分母时画 32% 淡底+扫光）。`plug_power_html_widget.html` 是 step-after 阶梯波+
今日 kWh（恒两位小数），量程自适应（下限 warn×0.8，线出界不画），9 s 无新值转 dead。
big value 的字号加大与自适应缩字在 **APK 资产**里（`../app/README.md` 的 style.css/basic.js）。

## 5. 已知坑

缓存命中条件必须含 `used/total/temp`，否则旧缓存让 vram/sub **静默消失**；脚本里删 key 后面板
旧值还会挂一个 ttl 窗口，要立即消失用 `DELETE /api/push/<key>`；HTML 沙箱只有
`window.phonebox.on('push'|'state'|'weather'|'work'|'quotas')` 可拿数据、没有 fetch；
控件在 240×86 小格会自动缩到 7 px 字下限，别按大屏字号质疑。离线四态预览生成器（gen_verify /
gen_pw_multi）属开发归档，未入本仓库（服务器 temp，见 `../docs/CODE-SOURCES.md` 末节）。
