# 用 Xiaomi-cloud-tokens-extractor 获取设备 token（以及四条被堵的死路）

局域网 miio 的全部凭据 = **IP + 32 位 token**（token 派生 AES key/iv）。插座被重置或重新配网后
token 会换，两处存放（NAS `plug-secret.env`、服务器 `pb_plug.env`）都要同步。

## 工具与本次实测路径

- 工具：**Xiaomi-cloud-tokens-extractor**，本次用的 exe 来自
  <https://github.com/fh489320599/Xiaomi-cloud-tokens-extractor> 的 Windows release
  （19.6 MB，不入库；md5 `a1e867c75fe08881bd334d05502c1d95`、sha256 前缀 `43079f88f7a7ad49`，
  重新下载后先核对指纹。上游原版：<https://github.com/Revivalsss/Xiaomi-cloud-tokens-extractor>，
  Python 版可直接 `python token_extractor.py` 跑，不必用 exe）。
- 用法（Windows 上）：双击 exe → 输米家账号/密码 → 区服选 **cn**（中国大陆）→ 列出家里全部
  设备：每行 `NAME/ID/MAC/IP/TOKEN/MODEL`，同时写一份 `device_list.txt`。
- 找插座：`MODEL` 为 **`xiaomi.plug.04`** 那一行；TOKEN 应是 **32 位十六进制**（长度不对多半是
  复制漏字符）；IP 用同一行或路由器 DHCP 租约核对（`miiocli device --ip <plug-ip> --token
  <plug-token> info` 能通即成）。
- 输出格式样例见同目录 `device_list.masked.txt`（token/MAC/IP/设备名全部打码后的真实导出）。
  若云登录被拦，用米家 App「我的 → 我的设备 → 导出设备备份（区服选中国大陆）」拿到导出件，
  再用本工具的本地备份解析模式提 token——本次最终 token 即走“导出结果里取”的路子。

## 别重踩的四条死路（2026-11-17 全部实测被堵）

1. **python-miio 的 micloud 云登录**：新版小米对 sid=xiaomiio 的应用身份核验——
   `securityStatus=16` → authStart 网页验证流（短信/邮箱），脚本过不去。注意这与账号是否开
   “双重认证”无关（本机该开关本就关着）。fh 系 exe/fork 的登录实现能过，原版 Python 若撞同款
   核验也走 App 导出路。
2. **`miiocli discover` 明文探测**：已配过网的设备不回 token（只能拿没配网的新设备）。
3. **米家 App 设备备份导出**：新版 App 的设置里没有旧版那个导出入口（不同版本 UI 差异，
   找到就可用，找不到别硬找）。
4. **adb `run-as com.xiaomi.smarthome`**：release 包非 debuggable，直接报 not debuggable。

另外：**手搓纯 Python AES 解密 miio 报文的路线别走**——本次写了两版各有 bug（ShiftRows 按列移
之类），群晖/服务器直接装 `python-miio`（0.5.12）最稳。

## 插座 IP 怎么定

路由器 DHCP 列表里按型号/hostname 认领，或固定租约；拿到后
`ping` 通 + `miiocli device ... info` 返回型号串（本只应回 `xiaomi.plug.04`）即为正确目标。
写错 IP 的后果不对称：读侧只是 no live watts；**NAS 侧 fire 时写错则关不掉该关的电**。
