#!/usr/bin/env python3
# 向 NAS 发一条 UDP 报文。用法: plug-send.py <ip> <port> <payload>
import sys, socket

def main():
    if len(sys.argv) < 4:
        sys.exit("usage: plug-send.py <ip> <port> <payload>")
    ip = sys.argv[1]
    port = int(sys.argv[2])
    payload = " ".join(sys.argv[3:]).encode("utf-8")
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.sendto(payload, (ip, port))
    finally:
        s.close()

if __name__ == "__main__":
    main()
