#!/usr/bin/env python3
"""Strata speed + context-fill + box power -> PhoneBox panel (stdlib only).

Auto-discovers the running online serve (process running serve/server.py) for its --config json api_key and
--port, so it survives iq3s / UD swaps. Pushes webhook keys the panel reads with Pushed value widgets:
  strata_tps      decode tokens/sec
  strata_prefill  prefill tokens/sec
  strata_ctx      context in use on the **K tokens** scale (value = n_prompt_tokens/1024, unit KB, so the
                  widget's gauge max goes to 512); progress still carries the fill %, sub shows
                  "58.3% · 305,506 / 524,288 tokens"; level = CTX_WARN_KB / CTX_ERR_KB (320 / 440)
  gpu_power       GPU board power draw (W) — nvidia-smi power.draw; level by absolute watts
                  (<=GW_WARN ok, <=GW_ERR warn, above that error — 100/300 as of 2026-11-12 16:0x)
  vram_gb         GPU memory in use as GB (e.g. 23.1 — set the widget's gauge max to 24) — same nvidia-smi call.
                  progress still carries the %, sub "96.3% · 912 MiB free".
                  level = usage GB (>VR_WARN_GB warn, >VR_ERR_GB error; 14.0/23.2 per user 2026-11-17).
                  Headroom still rides in progress/sub - only the coloring moved to "in use GB".
                  (Renamed from vram_pct on 2026-11-12 15:5x when the value switched to GB; that key had
                  itself been dropped that afternoon and put back as the VRAM widget.)
  strata_state    the engine's live model state, for the "Model state" Custom HTML widget
                  (6 lit-able badges + progress bar). Raw /metrics live fields are passed through untouched;
                  the widget itself reproduces serve/web/app.js renderMonitor() (label / detail / pct / tone).
                  payload: {state, raw_state, queued, phase, prompt_read, prompt_total, prompt_tokens,
                            generated, max_tokens, tok_s, last_detail?, ttl:20}
                  state = live.state ("idle"|"reading"|"generating"|"unloaded"|"error"); the widget lights
                  "queued" itself when queued > 0, exactly like the engine's own Monitor.
  plug_power      live watts off the plug that feeds this server (pb_plug_read.py, siid 11 / piid 2,
                  watt, 1 s device refresh measured 2026-11-17) plus our own energy integral for the day:
                  today_wh / today_kwh / peak_w / avg_w / run_s. The plug's own piid 1 rides along as
                  dev_kwh for reference only - while piid 3 ("accumulate way") is false it mirrors piid 2
                  sample-for-sample, so it is no energy counter here; the day total is ours, reset at
                  local midnight and kept across restarts in ~/.local/state/pb_plug.state.
                  gauge full scale = PLUG_FULL (700 W), level = PW_WARN / PW_ERR (250 / 400 W), ttl 8 s.
On any read failure it skips the affected push so the last value expires by its ttl.
(cpu_power / power_total were dropped on 2026-11-12 per user request; vram_pct came back with the VRAM widget.)
"""
import json, glob, os, subprocess, time, urllib.request

AGENT = "http://127.0.0.1:8765"
FALLBACK_KEY = os.environ.get("PB_STRATA_FALLBACK_KEY", "REPLACE-WITH-STRATA-API-KEY")
FALLBACK_PORT = "8080"
# vram_gb 的着色 11-17 起按“已用 GB”（用户点名口径）：≤14 绿、14–23.2 橙、>23.2 红；
# 边界归前一档（恰好 14→绿、恰好 23.2→橙）。换算成余量约等于 ≤10.5G / ≤0.8G 才变色。
# 11-12 的“余量 2000/500 MiB”口径作废。
VR_WARN_GB, VR_ERR_GB = 14.0, 23.2
# gpu_power 按绝对瓦数分级（用户 11-12 16:0x 指定）：≤100W 绿、100–300W 橙、>300W 红。
# 之前是按 power.limit 的 50/80% 比例（350W 墙 ⇒ 175/280），改绝对值后 limit 只进缓存、不参与判定。
GW_WARN, GW_ERR = 100.0, 300.0
# strata_ctx 的着色按已用 context 的“K tokens”刻度（value = n_prompt_tokens/1024，满窗 512K ⇒ 面板 gauge
# max 填 512）：用户 11-12 16:1x 指定 320 以下绿、320–440 橙、>440 红。440K 正对着客户端 448K 的工作窗口，
# 320K=62.5% 满窗是“开始值得注意”的位置。恰好 320 绿、恰好 440 橙。
CTX_WARN_KB, CTX_ERR_KB = 320.0, 440.0
# 供电插座的实时功率按绝对瓦数分级（用户 2026-11-17 指定）：≤250W 绿、250-400 橙、>400 红。
# 实测这台整机：空载 79W、GPU 满载 547W（2026-11-17 连采 60 点 79-547W），所以三档都有真实工况对应。
# 恰好 250 归绿、恰好 400 归橙（判定用 >，与 gpu_power/strata_ctx 同一惯例）。
PW_WARN, PW_ERR = 250.0, 400.0
# 控件 gauge / 阶梯波形的满量程。插座量程本是 10000W，但整机峰值实测 547W，定 700 才有可读的波形行程。
PLUG_FULL = 700.0


def discover():
    port, api = FALLBACK_PORT, FALLBACK_KEY
    for cl in glob.glob("/proc/[0-9]*/cmdline"):
        try:
            args = [a.decode("utf-8", "replace") for a in open(cl, "rb").read().split(b"\0")]
        except Exception:
            continue
        if not any(a.endswith("serve/server.py") for a in args):
            continue
        for i, a in enumerate(args):
            if a == "--port" and i + 1 < len(args):
                port = args[i + 1]
            if a == "--config" and i + 1 < len(args):
                try:
                    api = json.load(open(args[i + 1])).get("api_key", api)
                except Exception:
                    pass
        break
    return f"http://127.0.0.1:{port}", api


def get_json(url, api):
    try:
        r = urllib.request.Request(url, headers={"Authorization": f"Bearer {api}"})
        return json.load(urllib.request.urlopen(r, timeout=2))
    except Exception:
        return None


def push(key, body):
    try:
        r = urllib.request.Request(
            f"{AGENT}/api/push/{key}", data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        urllib.request.urlopen(r, timeout=2)
        return True
    except Exception as e:
        print(f"push {key} failed: {e}")
        return False


PLUG_CACHE = "/tmp/pb_plug_cache.json"


def plug_cache(max_age=5.0):
    """pb_plug_read.py 每秒写的缓存（该进程负责 UDP miio 与当日能量积分，见其 docstring）。
    超过 max_age 秒没更新就返回 None：插座失联时不再推，让 ttl 8s 把控件淡到地板，而不是挂着一个死读数。
    """
    try:
        c = json.load(open(PLUG_CACHE))
        if time.time() - float(c.get("ts", 0)) > max_age:
            return None
        return c
    except Exception:
        return None


GPU_CACHE = "/tmp/pb_gpu_cache.json"


def gpu_stats_agent(timeout=1.0):
    """(draw, limit, used_mb, total_mb, temp_c) from the agent's own resident nvidia-smi -lms 1000
    stream: loopback GET /api/state, fields pc.gpu.power/powerLimit/vramUsed/vramTotal/temp - the
    same query gpu_stats() used to fork, at true 1 Hz freshness and zero subprocess cost
    (vram arrives in GB there, converted back to MiB for the old interface). All-None when
    the agent is not answering; gpu_stats() (nvidia-smi + 5 s cache) then serves as fallback."""
    try:
        g = json.load(urllib.request.urlopen(AGENT + "/api/state", timeout=timeout))["pc"]["gpu"]
        temp = g.get("temp")
        return (round(float(g["power"]), 1), round(float(g["powerLimit"]), 1),
                int(round(float(g["vramUsed"]) * 1024)), int(round(float(g["vramTotal"]) * 1024)),
                round(float(temp)) if temp is not None else None)
    except Exception:
        return None, None, None, None, None


def gpu_stats_any():
    res = gpu_stats_agent()
    return res if res[0] is not None else gpu_stats()


def gpu_stats(max_age=5.0):
    """(power_w, power_limit_w, vram_used_mb, vram_total_mb, temp_c)，nvidia-smi 每 max_age 秒才真查一次
    （结果缓存在 GPU_CACHE）。timer 提到 1s 后不能每秒 fork 一次 nvidia-smi：满载时它自己要等
    0.2-0.5s 的锁。显存是同一次查询顺带拿的，不额外加开销。失败时四项全 None。"""
    try:
        c = json.load(open(GPU_CACHE))
        # used/total 也在才算有效：老缓存只有瓦数，直接复用会让 vram_pct 静默消失
        # "temp" 也在才算命中：旧缓存只有四项，复用会把温度报成 KeyError 然后静默丢掉 sub
        if (time.time() - float(c.get("ts", 0)) < max_age and c.get("used") and c.get("total")
                and c.get("temp") is not None):
            return c["draw"], c["limit"], c["used"], c["total"], c["temp"]
    except Exception:
        pass
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=power.draw,power.limit,memory.used,memory.total,temperature.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=6).stdout.split("\n")[0]
        p = [x.strip() for x in out.split(",")]
        res = (round(float(p[0]), 1), round(float(p[1]), 1), int(float(p[2])), int(float(p[3])),
               int(float(p[4])))
        try:
            json.dump({"ts": time.time(), "draw": res[0], "limit": res[1], "used": res[2],
                       "total": res[3], "temp": res[4]}, open(GPU_CACHE, "w"))
        except Exception:
            pass
        return res
    except Exception:
        return None, None, None, None, None


def main():
    base, api = discover()
    m = get_json(f"{base}/metrics", api)
    slots = get_json(f"{base}/slots", api)

    live = (m or {}).get("live") or {}
    reqs = (m or {}).get("requests") or []
    latest = max(reqs, key=lambda r: r.get("time", 0)) if reqs else None

    # decode speed
    speed = live.get("tok_s") or live.get("tok_s_mean")
    if speed is None and latest:
        speed = latest.get("decode_tok_s")
    if speed is not None:
        try:
            s = round(float(speed), 1)
            push("strata_tps", {"value": s, "unit": "tok/s", "ttl": 20,
                                "level": "ok" if s >= 60 else ("warn" if s >= 20 else "error")})
        except (TypeError, ValueError):
            speed = None

    # prefill speed
    prefill = live.get("prefill_tok_s_mean")
    if prefill is None and latest:
        pt = latest.get("prompt_total") or latest.get("prompt_tokens") or 0
        reused = latest.get("reused") or 0
        pms = latest.get("prompt_ms") or 0
        if pms and pt > reused:
            prefill = (pt - reused) / (pms / 1000.0)
    if prefill is not None:
        try:
            p = round(float(prefill), 1)
            if p > 0:
                push("strata_prefill", {"value": p, "unit": "t/s", "ttl": 20})
        except (TypeError, ValueError):
            prefill = None

    # context fill %
    fill = None; used = ctxmax = 0
    for slot in (slots or []):
        if slot.get("id") == 0:
            nc = slot.get("n_ctx") or 0
            np = slot.get("n_prompt_tokens") or 0
            if nc:
                fill = round(np / nc * 100, 1)
                used, ctxmax = np, nc
            break
    if fill is not None:
        kb = round(used / 1024.0, 1)           # 值改成 K tokens 刻度（面板 gauge max=512）；百分比留进 progress/sub
        push("strata_ctx", {"value": kb, "unit": "KB", "progress": fill, "ttl": 20,
                            "sub": f"{fill}% · {used:,} / {ctxmax:,} tokens",
                            "level": "error" if kb > CTX_ERR_KB else
                                     ("warn" if kb > CTX_WARN_KB else "ok")})

    # model state: raw live fields + health.loaded 兜底，控件自己复刻引擎 Monitor 的文案与点亮逻辑
    health = get_json(f"{base}/health", api)
    st = live.get("state")
    if health is not None and health.get("loaded") is False and st in (None, "idle"):
        st = "unloaded"
    if st:
        body = {"state": st, "raw_state": live.get("state"),
                "queued": live.get("queued") or 0, "phase": live.get("phase"),
                "prompt_read": live.get("prompt_read"), "prompt_total": live.get("prompt_total"),
                "prompt_tokens": live.get("prompt_tokens"),
                "generated": live.get("generated"), "max_tokens": live.get("max_tokens"),
                "tok_s": live.get("tok_s"), "ttl": 20}
        if latest and latest.get("output_tokens") is not None:
            ld = latest.get("decode_tok_s")
            body["last_detail"] = f"last: {int(latest['output_tokens']):,} tokens" + (
                f" at {float(ld):,.1f} tok/s" if ld else "")
        # generating 的进度分母：引擎自带面板用 max_tokens（本会话客户端给的是 359,945 ⇒ 条子永远贴地）。
        # 这里另给一个经验分母 = 最近 8 个请求 output_tokens 的 p75（下限 512，避免一次短应答就把条顶满），
        # 控件优先用它，生成段就有了真实行程；无样本时控件退 indeterminate 扫动。
        # detail 里的 token 数字仍是引擎原口径，一点没改。
        outs = [int(r.get("output_tokens") or 0) for r in
                sorted(reqs, key=lambda r: r.get("time") or 0)][-8:]
        outs = sorted(o for o in outs if o > 0)
        if outs:
            body["out_est"] = max(outs[min(len(outs) - 1, int(0.75 * len(outs)))], 512)
        push("strata_state", body)

    # GPU power + VRAM（同一次 nvidia-smi 查询）。gpu_power 按 draw/limit 比例着色；
    # vram_gb 按已用 GB 着色（14 / 23.2，见 VR_WARN_GB/VR_ERR_GB）。
    gw, glimit, vu, vt, gtemp = gpu_stats_any()   # glimit 现在只进缓存，不参与着色（阈值见 GW_WARN/GW_ERR）
    if gw is not None and gw > 0:
        # sub 不是可选的装饰：用户已把 gpu_power / vram_gb / strata_ctx 三个并排成 gauge，内置 push 控件
        # 只有 payload 带 sub 才画圆环下面那行灰字，缺了就整块矮一截、三个圆心对不齐。这行放 GPU 温度，
        # 与另两个的「百分比 · 明细」同构（vram_gb: "96.3% · 912 MiB free"、ctx: "28.2% · … tokens"）。
        # 瓦数、着色阈值（GW_WARN/GW_ERR）与 ttl 一概没动。
        push("gpu_power", {"value": gw, "unit": "W", "ttl": 20,
                           "sub": f"{gtemp:.0f} °C" if gtemp is not None else "GPU temp n/a",
                           "level": "error" if gw > GW_ERR else
                                    ("warn" if gw > GW_WARN else "ok")})
    vram_txt = "n/a"
    if vu and vt:
        vp = round(100.0 * vu / vt, 1)
        gb = round(vu / 1024.0, 1)          # 用户 15:5x 要求：值直接显示 GB（如 23.5），gauge max 填 24
        free = vt - vu
        vram_txt = f"{gb}G/{round(vt / 1024.0, 1)}G ({vp}%, free {free} MiB)"
        # key 就叫 vram_gb（11-12 15:5x 用户定的，面板控件的 key 同步改）；progress 仍给百分比供其它样式用
        push("vram_gb", {"value": gb, "unit": "GB", "progress": vp, "ttl": 20,
                          "sub": f"{vp:.1f}% · {free} MiB free",
                          "level": "error" if gb > VR_ERR_GB else
                                   ("warn" if gb > VR_WARN_GB else "ok")})

    # 插座供电读数：采集进程每秒刷缓存，我们顺带推一个 key（瓦数 + 当日能量），零额外 UDP。
    pc = plug_cache()
    plug_txt = "n/a"
    if pc is not None and isinstance(pc.get("w"), (int, float)):
        pw = float(pc["w"])
        wh = float(pc.get("today_wh") or 0.0)
        pk = int(pc.get("peak_w") or 0)
        av = pc.get("avg_w")
        plug_txt = f"{pw:.0f}W today={wh / 1000.0:.2f}kWh peak={pk}W"
        push("plug_power", {
            "value": int(round(pw)), "unit": "W", "ttl": 8, "full": PLUG_FULL,
            "warn_w": PW_WARN, "err_w": PW_ERR,
            "progress": min(100.0, round(100.0 * pw / PLUG_FULL, 1)),
            "today_wh": round(wh, 1), "today_kwh": round(wh / 1000.0, 3),
            "peak_w": pk, "avg_w": av, "run_s": pc.get("run_s"),
            "dev_kwh": pc.get("dev_kwh"), "way": pc.get("way"), "on": pc.get("on"),
            "day": pc.get("day"),
            "sub": f"今日 {wh / 1000.0:.2f} kWh · 峰 {pk}W · 均 {int(av or 0)}W",
            "level": "error" if pw > PW_ERR else ("warn" if pw > PW_WARN else "ok"),
        })

    print(f"base={base} decode={speed} prefill={prefill} fill={fill} gpu_w={gw}@{gtemp}C vram={vram_txt} plug={plug_txt}", flush=True)


def _run_forever():
    """PB_RESIDENT=1 under a simple systemd unit: one process, one push per second. The
    oneshot+timer shape never reached 1 s - every round paid a fresh interpreter, its own
    nvidia-smi, and at boot the 1 s cadence tripped systemd's default start limit
    (10 s / 5 starts) and dropped whole rounds. Measured effective cadence: 2.2 s."""
    while True:
        start = time.time()
        try:
            main()
        except Exception as e:                      # a broken tick must not take the loop down
            print("tick failed:", e, flush=True)
        time.sleep(max(0.05, 1.0 - (time.time() - start)))


if __name__ == "__main__":
    import os
    if os.environ.get("PB_RESIDENT"):
        _run_forever()
    else:
        main()
