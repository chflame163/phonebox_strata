# 手机替代 NAS watcher —— App 侧开发资产（2026-11-24）

这个目录装的是「判决逻辑在手机上」这套方案的**协议验证工具**，代码本体在 `app/app/src/main/java/.../deskpanel/`：
`Miio.kt`（插座协议客户端）、`PlugWatcher.kt`（状态机）、`PlugWatchService.kt`（前台服务+push 桥）。
开发计划全文见 NAS 仓库 `docs/手机替代NASWatcher开发计划_20261124.md`。

## 文件清单

- `miio_hex_golden.py` —— 在**服务器**上用 python-miio 0.5.12 现跑现抓的“标准答案”生成器：
  key/iv 配方、body/reply 加密向量、以及一条**真机 get_properties 整帧**（子进程 socket 钩子抓包，
  CHILD_PID 握手防伪捕获）。输出 `/tmp/golden.json`。
- `MiioHexTest.kt` —— VM 里编译运行的比对器（kotlinc + json.jar），拿 golden.json 逐项核对 Kotlin 实现：
  key / iv / body / payload_shape / reply_json / **fullframe**（整帧逐字节）。全绿输出 `HEXDIFF_PASS`。
- `miio_live_check.py` —— python-miio 直连真插座的只读体检（get_properties，永不写），协议侧“真机绿灯”。

## 跑法（都已实测）

```bash
# 服务器：生成 golden（会向插座发一条只读 get_properties）
~/.venvs/miio/bin/python3 miio_hex_golden.py > /tmp/golden.json

# VM（需 kotlinc + json.jar，源码软链/拷贝 App 的 Miio.kt 同目录）：
./kotlinc/bin/kotlinc -cp json.jar Miio.kt MiioHexTest.kt -include-runtime -d hextest.jar
java -cp "hextest.jar:json.jar" MiioHexTestKt golden.json      # → HEXDIFF_PASS

# 服务器：真机只读体检
~/.venvs/miio/bin/python3 miio_live_check.py                    # → live_check_ok + result
```

## 协议结论（2026-11-24 深夜定稿，别再翻旧账）

写这条线之前踩过的坑全部收敛成一句话——**照 Miio.kt 里 buildFrame 的注释实现，别凭记忆改**。要点：

1. **载荷文本是单层编码**：`{"id": N, "method": …, "params": […]}` 原样 + EncryptionAdapter 的 `\0`。
   “双重编码（quote 后再 quote）”是 folklore——真机字节差分抓包证明密文长度对不上（128 vs 112）。
2. **len 字段 = python-miio send() 路径下等于整帧长**（get_properties 类，golden 的 `0x90`=144）；
   info() 路径的帧是另一种基准。两类帧在 python-miio 内部走不同代码路径，别拿一种去套另一种。
3. **checksum = MD5(header[:16] ‖ token.bin ‖ cipher全文)**，摘要写在 [16..32)（覆盖 data 头部槽位），
   cipher 从 32 起完整上链。ts = hello 回报的设备时钟 **+1 秒**（实测 devts+1 应答、devts 超时）。
4. **设备对旧版 data@36 帧“收而不答”**——只看 socket 层会误判成功，必须等 result json 回来才算通。
5. 验收双保险 = `HEXDIFF_PASS`（协议栈自己一致）+ 真机 dry fire 后日志出现
   `CHECK_OK [{"siid":2,"piid":1,…}]`（与 python-miio 同一条路走通）。2026-11-24 两项全绿。

## App 侧行为速记（现场调试用）

- 配置在 agent `display.plug*`（plugWatcher/plugDryRun/plugDelaySec/bootConfirm/Clear/critLevel/udpPort/ip/token），
  手机每 ≤30s 拉一次快照；**cfg 缓存最长 30s，改完立刻测要等一拍**。
- dry=true 全程不写插座；fire 后自动跑只读 `CHECK_*` 验据。判决历史双通道：
  本机 `files/plug_watch.log`（拔电也在）+ push key `plug_watch_log`/`plug_delay_state`（面板 System 页两卡）。
- 手动臂测：`adb shell am broadcast -a net.oxog.phonebox.PLUGW_DRY_ARM`；
  UDP 握手：`python3 /usr/local/bin/plugdelay/plug-send.py <手机IP> 12345 "SHUTDOWN t"`。
- T5（电量下限）语义：**插电=永不拒臂**（FULL_TRICK_LEVEL 哨兵短路），critLevel 只对真电池生效；
  演示拒臂需拔掉充电器再测。
