#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Live ground-truth for the golden's segment choice (开发计划 §5.1):
runs python-miio against the REAL plug with --check-equivalent reads, then replays a fixed-id request
through Device.send to capture what plaintext python-miio actually encrypts (id+segment+json).
Prints the segment hex it observes so golden/harness agree with reality, not with memory.
Read-only: get_properties only — never writes siid2/piid1."""
import json, os, sys

def main():
    env = {}
    for line in open(os.path.expanduser("~/.config/pb_plug.env")):
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            kk, vv = line.split("=", 1); env[kk] = vv.split(" #")[0].strip()
    ip, tok = env["PB_PLUG_IP"], env["PB_PLUG_TOKEN"]
    from miio import Device
    d = Device(ip, tok)
    r = d.send("get_properties", [{"siid": 2, "piid": 1}, {"siid": 11, "piid": 2}])
    print(json.dumps({"live_check_ok": True, "result": r}))

if __name__ == "__main__":
    sys.exit(main())
