#!/usr/bin/env python3
"""Resident 1 Hz reader for the supply plug (xiaomi.plug.04 @ PB_PLUG_IP, LAN miio, read-only).

Grabs the outlet's instantaneous wattage (siid 11 / piid 2, "electric-power", unit watt) and keeps
our own running energy integral for the panel's `plug_power` push (pb_strata_push.py reads the cache).

Why we integrate instead of reading the plug's own energy property: piid 1 ("power-consumption",
labelled kWh) answers with a mirror of the live wattage whenever piid 3 ("accumulate way") is false
- measured 2026-11-17, every one of 60 samples had piid1 == piid2 (547 -> 547.0, 79 -> 79.0), and a
60 s window "lost" 468 kWh. So that property is not an energy counter in this mode, and switching it
would be a *write* to a device we may not disturb. Our own integral also dodges the "does the
accumulated reading survive a power cut?" unknown: this plug is cut 180 s after every shutdown by
the plug-delay project.

READ-ONLY ON PURPOSE. Writing siid 2 / piid 1 turns the server's own supply off - never send
set_properties from here (that path belongs to the NAS watcher, nas_off.py).

Cadence is genuinely 1 Hz: the plug refreshes the watt value every second (60/60 code 0 in 60 s,
median UDP RTT 21 ms), so re-reading each tick is not re-reading a stale device cache. A gap longer
than DT_CLAMP seconds is dropped rather than integrated - a dead plug/网络 hole must not invent
energy, and while the server is off nothing is integrated anyway (this process dies with it).

Env: PB_PLUG_IP and PB_PLUG_TOKEN are both **required** (EnvironmentFile), nothing hardcoded -
     PB_PLUG_CACHE, PB_PLUG_STATE, PB_PLUG_LOOP, PB_PLUG_DT_CLAMP, PB_PLUG_SAVE_S, PB_PLUG_BEAT.
Run from the persistent venv: ~/.venvs/miio/bin/python  (pip python-miio 0.5.12, tuna mirror).
"""
import json
import os
import time

from miio import Device

IP = os.environ.get("PB_PLUG_IP", "")        # 现场地址不进 git，见 tools/DESENSITIZATION.md
TOK = os.environ.get("PB_PLUG_TOKEN", "")
LOOP = float(os.environ.get("PB_PLUG_LOOP", "1.0"))
DT_CLAMP = float(os.environ.get("PB_PLUG_DT_CLAMP", "60"))   # longer holes than this are not billed
SAVE_S = float(os.environ.get("PB_PLUG_SAVE_S", "15"))       # state file cadence (the integral itself is live)
BEAT_S = float(os.environ.get("PB_PLUG_BEAT", "30"))
CACHE = os.environ.get("PB_PLUG_CACHE", "/tmp/pb_plug_cache.json")
STATE = os.path.expanduser(os.environ.get("PB_PLUG_STATE", "~/.local/state/pb_plug.state"))

REQ = [{"siid": 11, "piid": 2}, {"siid": 11, "piid": 1}, {"siid": 11, "piid": 3}, {"siid": 2, "piid": 1}]


def local_day():
    return time.strftime("%Y-%m-%d")


def load_state():
    try:
        st = json.load(open(STATE))
        return st if isinstance(st, dict) else {}
    except Exception:
        return {}


def save_state(st):
    try:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        tmp = STATE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(st, f)
        os.replace(tmp, STATE)
    except Exception as e:
        print("state save failed:", e, flush=True)


def write_cache(blob):
    try:
        tmp = CACHE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(blob, f)
        os.replace(tmp, CACHE)
    except Exception as e:
        print("cache write failed:", e, flush=True)


def main():
    if not IP or not TOK:
        print("PB_PLUG_IP / PB_PLUG_TOKEN unset - refusing to start (EnvironmentFile ~/.config/pb_plug.env)",
              flush=True)
        return 4
    dev = Device(IP, TOK)
    st = load_state()
    if st.get("date") != local_day():
        print(f"new day {local_day()} (was {st.get('date')}): energy today starts at 0", flush=True)
        st = {"date": local_day()}
    st.setdefault("wh", 0.0)
    st.setdefault("run_s", 0.0)
    print(f"reader up ip={IP} loop={LOOP}s clamp={DT_CLAMP}s state={STATE} "
          f"today={st['wh'] / 1000.0:.3f}kWh run={st['run_s'] / 3600.0:.2f}h", flush=True)

    bad_run = 0
    n_ok = n_bad = 0
    last_beat = last_save = 0.0
    while True:
        start = time.time()
        try:
            raw = dev.send("get_properties", REQ)
            m = {f"{x['siid']}.{x['piid']}": x for x in raw}
            pw = m.get("11.2") or {}
            if pw.get("code") != 0 or not isinstance(pw.get("value"), (int, float)):
                raise RuntimeError(f"siid11/piid2 code={pw.get('code')} value={pw.get('value')!r}")
            w = float(pw["value"])
            now = time.time()
            gap = now - float(st.get("last_ok") or 0) if st.get("last_ok") else 0.0
            if st.get("last_ok") and 0 < gap <= DT_CLAMP:
                st["wh"] += float(st.get("last_w") or w) * gap / 3600.0     # the gap carried the previous tick's watts
                st["run_s"] += gap
            elif st.get("last_ok"):
                print(f"hole {gap:.0f}s longer than clamp {DT_CLAMP:.0f}s - not billed", flush=True)
            st["last_ok"] = now
            st["last_w"] = w
            st["peak"] = max(float(st.get("peak") or 0), w)
            kwh = m.get("11.1") or {}
            st["dev_kwh"] = kwh.get("value") if kwh.get("code") == 0 else None    # reference only (see docstring)
            st["dev_code"] = kwh.get("code")
            way = m.get("11.3") or {}
            st["way"] = way.get("value") if way.get("code") == 0 else None
            on = m.get("2.1") or {}
            st["on"] = on.get("value") if on.get("code") == 0 else None
            if st.get("date") != local_day():
                print(f"day rolled to {local_day()} mid-run, resetting today", flush=True)
                st = {"date": local_day(), "wh": 0.0, "run_s": 0.0, "last_ok": now, "last_w": w,
                      "dev_kwh": st.get("dev_kwh"), "way": st.get("way"), "on": st.get("on")}
                st["peak"] = w
            bad_run = 0
            n_ok += 1
            avg = st["wh"] * 3600.0 / st["run_s"] if st["run_s"] > 0 else None
            write_cache({"ts": round(now, 3), "w": int(round(w)),
                         "today_wh": round(st["wh"], 1), "today_kwh": round(st["wh"] / 1000.0, 3),
                         "peak_w": int(round(st["peak"])),
                         "avg_w": round(avg, 1) if avg is not None else None,
                         "run_s": round(st["run_s"], 1), "dev_kwh": st.get("dev_kwh"),
                         "dev_code": st.get("dev_code"), "way": st.get("way"), "on": st.get("on"),
                         "day": st["date"], "bad": 0})
            if time.time() - last_save > SAVE_S:
                save_state(st)
                last_save = time.time()
            if time.time() - last_beat > BEAT_S:
                print(f"plug w={w:.0f} today={st['wh'] / 1000.0:.3f}kWh peak={st['peak']:.0f} "
                      f"run={st['run_s'] / 3600.0:.2f}h ok={n_ok} bad={n_bad} dev_kwh={st.get('dev_kwh')} "
                      f"way={st.get('way')}", flush=True)
                last_beat = time.time()
        except Exception as e:
            n_bad += 1
            bad_run += 1
            if bad_run in (1, 5) or bad_run % 60 == 0:
                print(f"plug read failed x{bad_run}: {repr(e)[:120]}", flush=True)
            if bad_run >= 120:                    # device may have rebooted and changed handshake state
                print("rebuilding miio device after 120 straight failures", flush=True)
                try:
                    dev = Device(IP, TOK)
                except Exception:
                    pass
                bad_run = 1
        time.sleep(max(0.05, LOOP - (time.time() - start)))


if __name__ == "__main__":
    import sys
    try:
        sys.exit(main() or 0)
    except KeyboardInterrupt:
        save_state(load_state())
        raise
