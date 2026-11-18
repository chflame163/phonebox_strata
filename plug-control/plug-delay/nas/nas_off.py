#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""控制给 Strata 服务器供电的那只小米智能插座（xiaomi.plug.04，开关 = siid 2 / piid 1）。

群晖 watcher 的 fire 命令就是调本脚本（`PLUG_OFF_CMD`）：断电成功 exit 0，失败 exit 1
（watcher 会按 FIRE_RETRY 每 30s 重试）。旧版本把 IP 与 token 写死在源码里，那份真值现在已经
不进了 git —— 见同目录 `plug-secret.env.example` 与 ../DESENSITIZATION.md。

⚠️ 这是一条**会切掉服务器自己电源**的路径。只想验证凭据通不通、网络到不到，请用 `--check`：
   它只发 `get_properties`，一个写操作都没有。

用法：
    nas_off.py            断电（set siid2/piid1 = false）并回读确认
    nas_off.py --on       送电（true）——手动恢复服务器供电的那一把扳机
    nas_off.py --check    只读：回读开关状态 + 实时功率，绝不写

凭据来源（按优先级）：环境变量 PB_PLUG_IP / PB_PLUG_TOKEN → `--secret <文件>` →
脚本同目录 `plug-secret.env` → 同目录 `nas_off.env`（兼容旧名）。
运行环境：群晖上的 python-miio venv（示例 `/volume2/homes/<nas-user>/pdvenv/bin/python3 -I`）。

属性表与只读红线见 docs/plug-04-properties.md（siid11/piid2 是实时瓦数，本脚本从不碰它的写）。
"""
import argparse
import os
import sys

SECRET_KEYS = ("PB_PLUG_IP", "PB_PLUG_TOKEN")


def load_secret(paths):
    """返回 (dict, 命中的文件路径或 None)。格式与本项目所有 env 一致：KEY=value，允许行尾 ' #' 注释。"""
    for p in paths:
        if p and os.path.exists(p):
            d = {}
            with open(p, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    v = v.split(" #", 1)[0]
                    d[k.strip()] = v.strip()
            return d, p
    return {}, None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="control / check the supply plug over LAN miio")
    ap.add_argument("--secret", default=None, help="KEY=value file with PB_PLUG_IP / PB_PLUG_TOKEN")
    ap.add_argument("--check", action="store_true", help="read-only: never writes, safe to run anytime")
    ap.add_argument("--on", action="store_true", help="power the outlet back ON instead of off")
    args = ap.parse_args()

    env, src = load_secret([args.secret,
                            os.environ.get("PB_PLUG_SECRET"),
                            os.path.join(here, "plug-secret.env"),
                            os.path.join(here, "nas_off.env")])
    ip = os.environ.get("PB_PLUG_IP") or env.get("PB_PLUG_IP")
    tok = os.environ.get("PB_PLUG_TOKEN") or env.get("PB_PLUG_TOKEN")
    if not ip or not tok:
        print("ERR 凭据缺失：设 PB_PLUG_IP / PB_PLUG_TOKEN，或在 %s/plug-secret.env 里填"
              "（照 plug-secret.env.example 抄一份即可，真值不进 git）" % here, flush=True)
        return 3
    if "<" in ip or "<" in tok:
        print("ERR 还是占位符（%s），把真值填进凭据文件再跑：来自 %s" % (ip, src or "env"), flush=True)
        return 4
    # 一眼看去像 token 却长度不对的，多半是复制漏了字符——早报错比晚报错好
    if len(tok) != 32:
        print("ERR PB_PLUG_TOKEN 长度 %d，应为 32 位十六进制" % len(tok), flush=True)
        return 5

    from miio import Device
    d = Device(ip, tok)
    SWITCH = [{"siid": 2, "piid": 1}]
    PROPS = SWITCH + [{"siid": 11, "piid": 2}, {"siid": 11, "piid": 1}]   # 开关 + 实时瓦数 + 电量(非累加模式下是功率镜像)
    try:
        if args.check:
            r = d.send("get_properties", PROPS)
            m = {("%s.%s" % (x["siid"], x["piid"])): x for x in r}
            print("CHECK_OK switch=%s  watts=%s  energy(mirror)=%s  way-dependent，见 docs/plug-04-properties.md"
                  % (m.get("2.1", {}).get("value"), m.get("11.2", {}).get("value"), m.get("11.1", {}).get("value")),
                  flush=True)
            return 0
        want = bool(args.on)
        d.send("set_properties", [{"siid": 2, "piid": 1, "value": want}])
        back = d.send("get_properties", SWITCH)
        print("%s_OK verify %s" % ("ON" if want else "OFF", back), flush=True)
        return 0
    except Exception as e:
        print("ERR ip=%s 不可达或写入失败: %r" % (ip, repr(e)[:120]), flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
