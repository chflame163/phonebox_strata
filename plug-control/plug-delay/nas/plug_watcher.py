#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 群晖常驻判决器:监听服务器心跳(HB)与关机握手(SHUTDOWN),
# 两条触发路共用一个防抖闸门,满足任一且过 grace 后调用"关插座命令"。
# 仅用标准库,群晖有 python3 即可。发送层与判决层解耦:PLUG_OFF_CMD 可任意替换。

import os, sys, time, socket, threading, subprocess, argparse, signal
from datetime import datetime

def log(msg):
    print(datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)

def load_env(path):
    d = {}
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                v = v.split(" #", 1)[0]   # 剥离行尾 " #..." 注释(对齐空格式),否则 int(...) 会失败而误用默认值
                d[k.strip()] = v.strip()
    return d

def get(env, key, default, cast=str):
    v = env.get(key)
    if v is None:
        v = os.environ.get(key)
    if v is None:
        return default
    try:
        return cast(v)
    except Exception:
        return default

class Watcher:
    def __init__(self, env):
        self.port          = get(env, "LISTEN_PORT", 12345, int)
        self.stale         = get(env, "STALE_TIMEOUT", 180, int)
        self.shutdown_delay= get(env, "SHUTDOWN_DELAY", 180, int)
        self.grace         = get(env, "REBOOT_GRACE", 90, int)
        self.retry         = get(env, "FIRE_RETRY", 30, int)
        self.alive         = get(env, "HB_ALIVE_LOG", 30, int)   # 心跳存活日志间隔(秒)
        self.off_cmd       = get(env, "PLUG_OFF_CMD", 'echo "no PLUG_OFF_CMD set"')
        self.last_hb_log   = 0.0                                 # 上次打 alive 行的时刻

        self.lock          = threading.Lock()
        self.last_hb       = time.time()   # 初始=启动时刻,NAS 重启后天然需重新收心跳
        self.boot          = time.time()
        self.shutdown_at   = None          # 收到计划关机握手的时刻(单向,置位即计时)
        self.fired         = False         # 本轮已下发关闭(幂等)
        self.next_try      = 0.0           # 失败重试节流

    # ---- UDP 接收线程 ----
    def listener(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", self.port))
        log("监听 UDP 0.0.0.0:%d  (stale=%ds shutdown_delay=%ds grace=%ds)"
            % (self.port, self.stale, self.shutdown_delay, self.grace))
        log("PLUG_OFF_CMD = %s" % self.off_cmd)
        while True:
            data, addr = s.recvfrom(1024)
            now = time.time()
            payload = data.decode("utf-8", "replace").strip()
            if payload.startswith("SHUTDOWN"):
                with self.lock:
                    if not self.fired and self.shutdown_at is None:
                        self.shutdown_at = now
                        log("<-- %s 收到计划关机握手,开始 %ds 待关倒计时"
                            % (addr[0], self.shutdown_delay))
            elif payload.startswith("HB"):
                with self.lock:
                    fresh = (now - self.last_hb) > 2  # 只在心跳明显停过后又回来才算 rearm
                    self.last_hb = now
                    if self.fired and fresh:
                        # 服务器重新在线(插座已被重新上电、服务器已开机) -> 重新武装
                        self.fired = False
                        self.shutdown_at = None
                        self.next_try = 0.0
                        log("<-- %s 心跳恢复,重新武装监听(本轮关闭已复位)" % addr[0])
                    elif now - self.last_hb_log >= self.alive:
                        # 低频存活日志:证明心跳确实在到达(生产默认每 30s 一条)
                        self.last_hb_log = now
                        log("<-- %s 心跳正常 (alive)" % addr[0])

    # ---- 主判决循环 ----
    def loop(self):
        while True:
            time.sleep(1)
            now = time.time()
            with self.lock:
                if self.fired:
                    continue
                if now - self.boot < self.grace:
                    continue  # NAS 刚重启,先等一个刷新窗口,不误判
                reason = None
                if self.shutdown_at is not None:
                    # 计划关机已确立:严格以握手时刻为准、忽略 stale,绝不在 delay 未满前提前断
                    if (now - self.shutdown_at) >= self.shutdown_delay:
                        reason = "计划关机(握手后已满 %ds)" % self.shutdown_delay
                elif (now - self.last_hb) >= self.stale:
                    reason = "心跳失联(已满 %ds)" % self.stale
                if reason is None or now < self.next_try:
                    continue
            self.fire(reason)

    def fire(self, reason):
        with self.lock:
            cmd = self.off_cmd
        log("--> FIRE: %s  |  执行: %s" % (reason, cmd))
        try:
            r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=20)
            out = (r.stdout or "").strip()
            err = (r.stderr or "").strip()
            if r.returncode == 0:
                log("--> 关闭成功 (rc=0)%s,本轮已锁定不再重复发" % (("  | " + out) if out else ""))
                with self.lock:
                    self.fired = True
            else:
                backoff = self.retry
                with self.lock:
                    self.next_try = time.time() + backoff
                log("--> 关闭失败 rc=%d,%.0fs 后重试  |  %s"
                    % (r.returncode, backoff, err or out))
        except Exception as e:
            with self.lock:
                self.next_try = time.time() + self.retry
            log("--> 关闭异常: %r,%.0fs 后重试" % (e, self.retry))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "plug-watcher.env"))
    args = ap.parse_args()
    env = load_env(args.env)
    w = Watcher(env)

    t = threading.Thread(target=w.listener, daemon=True)
    t.start()

    def bye(sig, frm):
        log("收到信号 %s,退出" % sig); sys.exit(0)
    signal.signal(signal.SIGTERM, bye)
    signal.signal(signal.SIGINT, bye)

    w.loop()

if __name__ == "__main__":
    main()
