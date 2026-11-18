#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性**只读**探测插座：属性 code、功率刷新粒度、RTT、电量属性语义。全程不发 set_properties。

这是 2026-11-17 决定"能不能每秒采功率"的那次实测脚本（结论已固化进 docs/plug-04-properties.md）：
60 次采样全 code:0、相邻不同值的间隔 min=median=1.00 s、UDP RTT 中位 21 ms、观测 79-547 W；
同时抓到 piid1(电量) 在 way=false 时逐样本等于 piid2(功率) —— 所以当日能量只能自己积分。

凭据来源与 nas_off.py 一致：环境变量 PB_PLUG_IP / PB_PLUG_TOKEN，或 ../..  的 plug-secret.env
（也可 --secret 指路）。跑法（服务器上）：
    ~/.venvs/miio/bin/python -I panel/probe/plug_probe.py --seconds 60
"""
import argparse
import json
import os
import statistics
import sys
import time

DEFAULT_PROPS = [{"siid": 11, "piid": 2},   # electric-power, watt, [0,10000,1], read+notify
                 {"siid": 11, "piid": 1},   # power-consumption, kWh（way=false 时是功率镜像）
                 {"siid": 11, "piid": 3},   # accumulation-way, bool
                 {"siid": 2, "piid": 1},    # switch on
                 {"siid": 4, "piid": 4},    # off-load-sec（米家 App 的"关闭延时"，实测 2 s）
                 {"siid": 4, "piid": 6}]    # on-delay remaining


def load_secret(paths):
    for p in paths:
        if p and os.path.exists(p):
            d = {}
            with open(p, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    d[k.strip()] = v.split(" #", 1)[0].strip()
            return d
    return {}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="read-only probe of the supply plug")
    ap.add_argument("--secret", default=None)
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--interval", type=float, default=1.0)
    a = ap.parse_args()

    env = load_secret([a.secret, os.path.join(here, "../../nas/plug-secret.env"),
                       os.path.join(here, "plug-secret.env")])
    ip = os.environ.get("PB_PLUG_IP") or env.get("PB_PLUG_IP")
    tok = os.environ.get("PB_PLUG_TOKEN") or env.get("PB_PLUG_TOKEN")
    if not ip or not tok:
        print("missing PB_PLUG_IP / PB_PLUG_TOKEN", file=sys.stderr)
        return 3

    from miio import Device
    d = Device(ip, tok)

    def rd():
        r = d.send("get_properties", DEFAULT_PROPS)
        return {("%s.%s" % (x["siid"], x["piid"])): x for x in r}

    t0 = time.time()
    try:
        first = rd()
        print("FIRST_OK %.3fs" % (time.time() - t0))
        print("FIRST_RAW", json.dumps(first, ensure_ascii=False, default=str))
    except Exception as e:
        print("FIRST_EXC", repr(e)[:300])
        return 2

    s = []
    end = time.time() + a.seconds
    while time.time() < end:
        st = time.time()
        try:
            r = rd()
            s.append((round(st, 3), r["11.2"].get("value"), r["11.2"].get("code"),
                      r["11.1"].get("value"), r["11.3"].get("value"),
                      r["2.1"].get("value"), r["4.4"].get("value"), round(time.time() - st, 3)))
        except Exception as e:
            s.append((round(st, 3), None, "EXC:" + repr(e)[:90], None, None, None, None,
                      round(time.time() - st, 3)))
        time.sleep(max(0.0, a.interval - (time.time() - st)))

    ok = [x for x in s if isinstance(x[1], (int, float)) and x[2] == 0]
    print("N=%d OK=%d BAD=%d" % (len(s), len(ok), len(s) - len(ok)))
    if not ok:
        return 3
    vals = [x[1] for x in ok]
    ch = [ok[0]]
    for x in ok[1:]:
        if x[1] != ch[-1][1]:
            ch.append(x)
    gaps = [round(ch[i + 1][0] - ch[i][0], 2) for i in range(len(ch) - 1)]
    print("W_MIN=%s W_MAX=%s DISTINCT=%d CHANGES=%d" % (min(vals), max(vals), len(set(vals)), len(ch)))
    if gaps:
        print("GAP_MIN=%.2f GAP_MED=%.2f GAP_MAX=%.2f" % (min(gaps), statistics.median(gaps), max(gaps)))
    mirror = sum(1 for x in ok if x[3] is not None and float(x[3]) == float(x[1]))
    print("ENERGY_MIRROR piid1==piid2 in %d/%d samples (way=%s) → 用不了日差分，只能自己积分"
          % (mirror, len(ok), ok[-1][4]))
    print("OUTLET_OFF_DELAY sec(siid4/piid4)=%s  ← 米家 App 侧的『关闭延时』，不是我们要的 180 s（那在 NAS 判决器里）"
          % ok[-1][6])
    print("RTT med=%.3fs max=%.3fs" % (statistics.median([x[7] for x in ok]), max(x[7] for x in ok)))
    print("RAW_TAIL", json.dumps(s[-3:]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
