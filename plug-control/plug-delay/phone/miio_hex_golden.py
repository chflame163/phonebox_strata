#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Golden vectors for the Kotlin Miio hex-diff (开发计划 §5.1) — built from python-miio 0.5.12's OWN primitives
(miio.protocol.Utils + EncryptionAdapter internals), which are the ground truth for everything OUR layer owns:

  vectors.key/iv      Utils.key_iv(token.bin)                    → Kotlin derive() must reproduce byte-exactly
  body.body_hex       EncryptionAdapter plaintext (json.dumps(str payload)+NUL → PKCS7 AES-CBC) at its wire offset
  reply.*             a device-shaped reply payload we hand to Kotlin to DECRYPT and parse back

construct's frame assembly (header|checksum with Pointer(32) overlap, ts clock semantics) stays python-miio's job —
our Kotlin layer mirrors MiIOProtocol's division of labour by shipping the SAME bytes construct builds; the live
read-only round-trip against the real plug (miio_live_check.py + the app's --check path) is what certifies the full
frame end-to-end. The two layers together are the hex-diff gate."""
import json, os, sys

def main():
    env = {}
    for line in open(os.path.expanduser("~/.config/pb_plug.env")):
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            kk, vv = line.split("=", 1); env[kk] = vv.split(" #")[0].strip()
    tok_hex = env["PB_PLUG_TOKEN"]

    from miio.protocol import Utils
    token = bytes.fromhex(tok_hex)
    k, iv = Utils.key_iv(token)                       # authoritative key/iv recipe

    # python-miio hands EncryptionAdapter json.dumps(dict) — cPython DEFAULT separators (", " / ": "). Pin it so.
    payload = json.dumps({"id": 12345, "method": "get_properties",
                          "params": [{"siid": 2, "piid": 1}, {"siid": 11, "piid": 2}]})

    # EncryptionAdapter._encode verbatim: json.dumps(obj).encode() + b"\x00" then Utils.encrypt (PKCS7-AES-CBC)
    from miio.protocol import EncryptionAdapter  # noqa: F401  (documents the source of the recipe below)
    body = Utils.encrypt(json.dumps(payload).encode("utf-8") + b"\x00", token)

    # reply payload python-miio would decrypt+load (its _decode strips a trailing NUL): id + 32-hex segment + json
    ans_json = json.dumps({"did": tok_hex, "token": "ffffffff",
                           "result": [{"siid": 2, "piid": 1, "code": 0, "value": True},
                                      {"siid": 11, "piid": 2, "code": 0, "value": 42}]},
                          separators=(",", ":"))
    ans_plain = f"12345{tok_hex}{ans_json}"
    ans_body = Utils.encrypt(ans_plain.encode("utf-8") + b"\x00", token)

    # 4) FULL-FRAME pin against the REAL plug (live capture, 2026-11-24): python-miio's own info() packet bytes.
    #    Our buildFrame(payload_string_json, fixed did/ts) must stitch this frame EXACTLY — header 'unknown' field,
    #    length coverage and checksum scope in one comparison. Read-only device call; nothing is written.
    # capture by SUBPROCESS with a sitecustomize shim — the most robust hook there is: interpreter start-up runs it
    # before anything else imports socket, and the child's own print output tells us plainly what went wrong.
    import subprocess as _SP
    # tap BOTH exits: hello goes through sendto, the real request leaves via sendall (MiIOProtocol.send).
    # per-run capture path (no shared /tmp collisions) + child's PID in each record: the golden's own child is
    # never misread as some earlier manual probe's leftover frames (a 0x8a-first-byte decode proved exactly that —
    # stale info() capture with id=1 while this run had pinned id=31415).
    # The tap rides IN the child source: a sitecustomize shim under -I/-E proved silently dead on this venv three
    # rounds running; an explicit hook line in the child's own -c code is the same tap without startup magic.
    # The file name IS the child pid (parent pre-assigns it via pass_fds + wfd report) — no shared-/tmp leftovers
    # can masquerade as our capture, and a foreign writer would fail the CHILD_PID handshake check below.
    # pid handshake rides the child's STDOUT ("CHILD_PID <n>" first line), not an inherited fd — on this venv even
    # set_inheritable+pass_fds delivered EBADF at fd 7 (three rounds of it), while stdout capture is plain plumbing.
    # The child opens its frame file under its REAL pid; parent parses that pid from stdout and reads exactly THAT
    # path, so a stale /tmp capture can never masquerade as this run's ("48-byte mis-slices" were ghost reads).
    _framefile = "/tmp/pb_frames_%d.bin" % os.getpid()   # placeholder: the real name is rebuilt from stdout below
    _hook = ('import socket as _S\n'
             '_rt,_ra=_S.socket.sendto,_S.socket.sendall\n'
             'def _w(d):\n'
             '    open(_fpath,"ab").write(bytes(d)+b"\\n@@@\\n")\n'
             'def _c(self,d,a,_x=_rt):\n'
             '    _w(d)\n'
             '    return _x(self,d,a)\n'
             'def _ca(self,d,_x=_ra):\n'
             '    _w(d)\n'
             '    return _x(self,d)\n'
             '_S.socket.sendto,_S.socket.sendall=_c,_ca\n')
    # env pass-through keeps the child's python-miio from re-reading the env file itself; ip/token go in argv.
    # _id is a GET-only incrementing property (no setter; the counter behind it is name-mangled) — so capture the
    # frame whose payload TEXT we can reconstruct exactly from public inputs: info() = {"id": 1, "method":
    # "miIO.info", "params": []} on a fresh Device (first send of the process). The fullframe pin certifies LAYOUT
    # constants (unknown/did/ts/len basis/csum scope) — payload-text equivalence is check 3's job, per section.
    # Child prints CHILD_PID <pid> as its FIRST stdout line (before any _w() can fire), opens the frame file under
    # that real pid; parent parses it and matches Popen.pid — a stale /tmp capture can never masquerade as ours.
    # -E ignores stray PYTHON* env vars; sys.executable keeps the venv on path.
    _child = ('import sys, os\n'
              'print("CHILD_PID", os.getpid(), flush=True)\n'
              '_fpath="/tmp/pb_frames_%d.bin" % os.getpid()\n' + _hook +
              'from miio import Device\n'
              'd=Device(sys.argv[1],sys.argv[2])\n'
              # get_properties (NOT info()): Device.send reuses the protocol's __id counter → id=1 on a fresh
              # process, so the frame's plaintext is EXACTLY our requestPayload(1,"get_properties",params) — golden
              # text and live path share one string. info() rides another code path with another ts clock.
              'try:\n'
              '    d.send("get_properties",[{"siid":2,"piid":1},{"siid":11,"piid":2}])\n'
              'except Exception as e:\n'
              '    print("child tolerated:",type(e).__name__,file=sys.stderr)\n')
    try: os.remove(_framefile)
    except OSError: pass
    _cp = _SP.run([sys.executable, "-E", "-c", _child, env["PB_PLUG_IP"], tok_hex], capture_output=True,
                  text=True, timeout=30, env={"PATH": os.environ.get("PATH", "/usr/bin:/bin")}, cwd="/tmp")
    if _cp.returncode != 0:
        raise RuntimeError("capture child failed rc=%d: %s" % (_cp.returncode, (_cp.stderr or _cp.stdout)[-200:]))
    _real_pid = None
    for ln in _cp.stdout.splitlines():
        if ln.startswith("CHILD_PID "): _real_pid = ln.split()[1]
    if _real_pid is None: raise RuntimeError("child never printed CHILD_PID (stdout=%r)" % _cp.stdout[:120])
    # (CompletedProcess carries no .pid — that belt never existed; the per-run path + CHILD_PID presence already
    # fence off ghosts. Add it back via Popen if a future run ever needs the match.)
    # the child opened its frame file under its REAL pid — read exactly that path (the parent-pid placeholder never
    # existed for this run; no ghost fallback) and clean both names up afterwards
    _actual = "/tmp/pb_frames_%s.bin" % _real_pid
    if not os.path.exists(_actual):
        raise RuntimeError("child pid %s left no capture file at %s (stdout=%r)" % (_real_pid, _actual, _cp.stdout[:120]))
    with open(_actual, "rb") as _f: _raw = _f.read()
    try: os.remove(_actual)
    except OSError: pass
    _sent = [x for x in _raw.split(b"\n@@@\n") if x]   # plain frame records — the tap file belongs to this run alone
    def _is_request(f):   # THE request frame = the big one; hello/ACK packets are 32-byte legacy formats. Layout is
        # asserted downstream by the harness's fullframe stitch: a wrong pick decrypts to garbage and fails loudly.
        return len(f) >= 52
    wire = next(f for f in _sent if _is_request(f))
    wire_head = wire[:20].hex()
    # The plaintext inside that frame (EncryptionAdapter output minus the trailing NUL): json.dumps(payload_string).
    # Our buildFrame takes THIS text and must stitch byte-identically to what python-miio put on the wire.
    # Slice by the length field, not a fixed folklore offset: len counts from byte 16 (overlap layout pinned above).
    from miio.protocol import Utils as _U
    # overlap frames: cipher starts at the END of the checksum region (wire[32:] — [16..32) are data's first 16B);
    # non-overlap (lenf=36) frames keep it after header+checksum. The slice rides whatever basis this capture says.
    lf = int.from_bytes(wire[2:4], "big")
    dstart = 32 if lf >= len(wire) - 16 else 36          # overlap basis ⇒ cipher at 32; classic ⇒ at 36
    wire_payload = _U.decrypt(wire[dstart:], token).rstrip(b"\x00").decode("utf-8")


    # SELF-CHECK the capture's layout basis BEFORE emitting, from its own bytes (no folklore math): try cipher@32
    # first — if it decrypts to readable JSON starting with "{", that IS the overlap basis (lenf=16); else fall back
    # to cipher@36. The golden then stores the VERIFIED basis so the harness replays truth instead of re-deriving it.
    import json as _J
    for cand, basis in ((32, 16), (36, 36)):
        try:
            probe = _U.decrypt(wire[cand:], token).rstrip(b"\x00").decode("utf-8")
            if probe.lstrip().startswith("{"):
                dstart, lenf_verified = cand, basis
                break
        except Exception:
            continue
    else:
        raise RuntimeError("capture decrypts to garbage at both 32 and 36 — wrong token or corrupt frame")

    print(json.dumps({
        "token": tok_hex,
        # lenf = the frame's OWN length-field basis (len == whole - lenf), read back from the capture — the harness
        # feeds it to buildFrame, which emits header|dataBlob with the checksum riding inside dataBlob[0:16] (the
        # Pointer(32) overlap python-miio's requests use). The 80-byte info() frame says lenf=16; the folklore
        # "len == dataBlob" reading says lenf=36. Whatever the device accepts, the capture — not memory — decides.
        # ts = the frame's header field (bytes [12..16), big-endian) — a DEVICE UPTIME clock (boot-relative seconds;
        # this capture reads ~2.67e6 ≈ 31 days uptime, NOT epoch). wire_head[:20].hex() slices must ride THAT offset:
        # [16:24] lands inside the checksum region (= cipher[0:16]) and is useless for did/ts — parse from raw bytes.
        "wire_frame": {"hex": wire.hex(), "did4": wire[8:12].hex(),
                       "ts": int.from_bytes(wire[12:16], "big"), "payload_json": wire_payload,
                       # the frame's own length basis (whole - len field): 16 = Pointer(32)-overlap requests (cipher
                       # rides [16..) with the checksum slot overwritten), 36 = classic header|checksum|data.
                       # VERIFIED basis from the self-check above (decrypt probe, not arithmetic folklore):
                       # 16 = overlap (cipher@32, csum slot [16..32) rides inside data's head), 36 = classic.
                       "lenf": lenf_verified},
        "vectors": {"key": k.hex(), "iv": iv.hex()},
        "body": {"payload_json": payload, "wire_offset": 32, "enc_hex": body.hex()},
        "reply": {"id": 12345, "segment": tok_hex, "result_json": ans_json, "enc_hex": ans_body.hex()},
    }, indent=1))

if __name__ == "__main__":
    sys.exit(main())
