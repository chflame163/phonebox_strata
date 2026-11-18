# 小米智能插座 4：Windows + Python 局域网控制指南

> 目标：在 Windows 电脑上通过 Python 对小米智能插座 4
> 进行局域网开关控制，并读取开关状态、实时功率和累计电量。\
> 重要说明：本指南整理了前述讨论中的方案，但部分设备型号、MIoT
> 属性编号及 `python-miio`
> 命令接口仍需通过实机验证。**不要在未验证前直接把示例中的 SIID/PIID
> 当作确定值。**

------------------------------------------------------------------------

## 1. 整体方案

建议采用以下流程：

1.  从小米云端提取设备 Token。
2.  获取插座的局域网 IP 和真实设备型号（Model）。
3.  使用 `python-miio` 检查设备是否支持 MIoT。
4.  获取 MIoT Spec，核实开关、功率、电量等属性的 SIID/PIID。
5.  使用 Token 通过局域网读写设备属性。
6.  可选：在 Python 控制程序外增加 Flask/FastAPI HTTP 接口。

首次提取 Token
需要账号认证；完成配置后，局域网控制指令可以直接在电脑与插座之间传输，不需要每次经过小米云端。设备的具体功能和本地控制能力仍取决于型号、固件及网络环境。

## 2. 协议说明

小米设备常见两类通信方式：

  协议   说明
  ------ ------------------------------------------
  miIO   小米较早期设备常见的局域网通信协议
  MIoT   以服务、属性和动作描述设备能力的协议体系

对于较新的智能插座，优先检查设备是否支持 MIoT。`python-miio` 提供通用
MIoT 相关实现，但不同版本的命令行参数和 Python API
可能变化，应以当前安装版本的 `--help` 和官方文档为准。

传统 miIO 局域网通信常见 UDP 端口为 `54321`。不要据此推断所有 MIoT
设备的全部通信行为都完全相同。

## 3. 设备型号信息：先核实再使用

此前讨论中提到的候选型号标识：

-   商品硬件型号：`ZNCZ401KK`（需以插座铭牌或官方商品信息核实）。
-   第三方设备资料中出现的软件型号：`xiaomi.plug.04`（必须以实机返回的
    Model 为准）。

这两个标识不是同一个字段：

-   商品型号用于识别硬件商品版本。
-   `Model` 是设备协议/软件层返回的型号标识。

**不要仅凭商品名称或外壳型号，直接认定设备的 Model、MIoT Spec
或属性编号。**

第三方资料入口： -
[XiaomiHive：xiaomi.plug.04](https://xiaomihive.com/mijia/xiaomi.plug.04/) -
[MIoT Spec 页面](https://home.miot-spec.com/spec/xiaomi.plug.04)

如果页面无法访问，或实机返回的 Model 不同，应以实机信息为准。

## 4. Windows 环境准备

建议使用独立项目目录和 Python 虚拟环境。以下以 Python 3.11 为例：

``` powershell
mkdir E:\\xiaomi_plug4
cd E:\\xiaomi_plug4

py -3.11 -m venv .venv
.\\.venv\\Scripts\\Activate.ps1

python -m pip install --upgrade pip
```

如果 PowerShell 阻止激活虚拟环境，可在当前 PowerShell 窗口执行：

``` powershell
Set-ExecutionPolicy -Scope Process Bypass
```

安装 `python-miio`：

``` powershell
pip install python-miio
```

如需尝试 GitHub 开发版：

``` powershell
pip install git+https://github.com/rytilahti/python-miio.git
```

建议先使用 PyPI
版本；只有在确认需要开发版功能时再切换。切换版本后，重新检查 CLI 和
Python API，避免混用不同版本的用法。

项目： - [python-miio
GitHub](https://github.com/rytilahti/python-miio) - [python-miio
文档](https://python-miio.readthedocs.io/)

检查命令：

``` powershell
miiocli --help
miiocli device --help
miiocli genericmiot --help
```

如果本机没有 `miiocli` 命令，可尝试：

``` powershell
python -m miio --help
```

具体入口以安装版本为准。

## 5. 获取 Token、IP 和 Model

### 5.1 获取 Token

可以使用 Xiaomi Cloud Tokens Extractor：

-   [Xiaomi Cloud Tokens
    Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)

按工具提示选择小米账号所在服务器区域，例如 `cn`、`us`、`de`、`sg`
等。区域应根据米家账号及设备所在服务器选择，不要简单按电脑所在地选择。

安全注意事项：

-   只在自己的电脑上运行可信的开源工具。
-   不要把小米账号密码交给陌生网站或他人。
-   不要公开 Token；Token 相当于设备控制凭据。
-   分享日志前，删除 Token、MAC、DID、账号等信息。

### 5.2 获取设备 IP

在米家 App 的设备详情或路由器客户端列表中查看插座
IP。建议在路由器中为插座设置 DHCP 地址保留，避免 IP 变化。

### 5.3 获取设备真实 Model

先根据当前 `miiocli` 版本的帮助信息确认 `device info`
的参数格式，再执行设备信息查询。常见形式如下（请按本机帮助信息调整）：

``` powershell
miiocli device --ip <plug-ip> --token YOUR_TOKEN info
```

记录返回的：

-   Model
-   Firmware version
-   Hardware version（如果提供）
-   Device ID（不要公开）
-   IP

如果返回的 Model 不是 `xiaomi.plug.04`，不要继续套用该型号的属性表。

## 6. 检查 MIoT 能力和属性

先查看本机命令帮助：

``` powershell
miiocli genericmiot --help
```

尝试查询设备状态（命令格式需以当前版本帮助为准）：

``` powershell
miiocli genericmiot --ip <plug-ip> --token YOUR_TOKEN status
```

如果支持，也可检查 `settings`、`sensors`、`actions`
等命令。不同版本不一定都提供相同子命令。

重点确认：

-   开关属性（通常为 Boolean）。
-   实时功率属性（如设备支持）。
-   累计电量属性（如设备支持）。
-   故障/过载状态（如设备支持）。
-   儿童锁或物理按键锁（如设备支持）。
-   属性是否可读、可写，以及数据单位。

**以实机 Spec
和返回值为准，不要把第三方数据库中的属性编号未经验证直接用于控制。**

## 7. MIoT 属性编号说明

MIoT 属性通常由以下两个编号定位：

-   `SIID`：Service Instance ID，服务实例编号。
-   `PIID`：Property Instance ID，属性实例编号。

例如，若实机 Spec 确认开关属性为
`SIID=2`、`PIID=1`，则该属性可以用于读取或设置开关状态。这里仅作为格式示例，不代表所有插座
4 固件都必然使用该编号。

此前第三方资料中曾出现以下候选映射，**必须逐项对照实机 Spec
后才能使用**：

  功能         候选 SIID   候选 PIID 备注
  ---------- ----------- ----------- ---------------------
  开关状态             2           1 Boolean，需实机核实
  故障状态             2           3 数据类型需核实
  累计电量            11           1 单位/缩放需核实
  实时功率            11           2 单位/数据类型需核实
  儿童锁               7           1 是否存在需核实

不要在未验证前执行这些候选编号的写操作。错误的属性编号可能导致操作失败，或操作到非预期属性。

## 8. Python 项目配置

建议项目结构：

``` text
E:\\xiaomi_plug4\\
├── .venv\\
├── config.py
├── inspect_device.py
├── plug4.py
└── requirements.txt
```

### 8.1 `config.py`

``` python
DEVICE_IP = "<plug-ip>"
DEVICE_TOKEN = "填写自己的32位Token"
DEVICE_MODEL = "填写设备实际返回的Model"
TIMEOUT = 5
```

不要将真实 Token 提交到 Git 仓库。可以将 `config.py` 加入 `.gitignore`。

### 8.2 `requirements.txt`

如果使用 PyPI 版本：

``` text
python-miio
```

如果使用 GitHub 开发版，则可写：

``` text
git+https://github.com/rytilahti/python-miio.git
```

两种安装来源择一即可。

## 9. 先检查 Python API，不要直接假定构造函数

`python-miio`
的类名、导入路径、构造参数和方法签名可能随版本变化。先检查本机实际安装内容：

``` powershell
python -c "import miio; print(miio.__file__)"
python -m pip show python-miio
```

也可以在 Python 中检查模块：

``` python
import inspect

try:
    from miio.integrations.genericmiot.genericmiot import GenericMiot
    print("GenericMiot:", inspect.signature(GenericMiot))
    print("status:", inspect.signature(GenericMiot.status))
except Exception as exc:
    print("当前安装版本的 GenericMiot 导入路径或 API 不同：", repr(exc))
```

如果导入路径不存在，应查阅当前版本文档或源代码，不要为了让示例运行而随意更换成未经确认的类。

## 10. 控制逻辑示例（需按已验证 API 填入）

下面是与具体库 API 无关的控制逻辑结构。请先从实机 Spec
确认属性编号，并根据已安装 `python-miio` 版本的实际 API 完成
`read_property` 和 `write_property` 两个适配函数。

``` python
class XiaomiPlug4:
    def __init__(self, device):
        self.device = device

    def read_property(self, siid: int, piid: int):
        # 在这里调用当前 python-miio 版本的 MIoT 属性读取方法
        raise NotImplementedError("请按当前版本 API 实现")

    def write_property(self, siid: int, piid: int, value):
        # 在这里调用当前 python-miio 版本的 MIoT 属性写入方法
        raise NotImplementedError("请按当前版本 API 实现")

    def turn_on(self, switch_siid: int, switch_piid: int):
        result = self.write_property(switch_siid, switch_piid, True)
        # 写入后再次读取，确认状态
        state = self.read_property(switch_siid, switch_piid)
        return {"write_result": result, "power": state}

    def turn_off(self, switch_siid: int, switch_piid: int):
        result = self.write_property(switch_siid, switch_piid, False)
        state = self.read_property(switch_siid, switch_piid)
        return {"write_result": result, "power": state}
```

这样设计的原因是：不同版本的 `GenericMiot`
方法签名可能不同，且必须确认实际属性编号。相比提供一个看似完整、但可能无法运行或误写属性的代码，先验证接口更安全。

## 11. 网络和局域网排查

若 Token 正确但连接失败，按以下顺序检查：

1.  Windows 与插座连接到同一个可互通的局域网。
2.  路由器没有开启 AP Isolation / Client Isolation。
3.  如果使用 VLAN，确认电脑与插座之间有允许的路由和访问规则。
4.  Windows 防火墙没有阻断 Python 的局域网通信。
5.  插座 IP 没有变化。
6.  Token 属于当前这台设备，而不是账号下另一台设备。
7.  插座固件确实支持所使用的本地协议和属性。

传统 miIO 通信常见 UDP 端口为
`54321`。不要在未确认协议的情况下仅凭端口扫描结果判断 MIoT 是否可用。

## 12. 可选：封装 HTTP API

确认 Python 局域网控制稳定后，可以再用 Flask 或 FastAPI 封装接口：

  HTTP 接口            功能
  -------------------- --------------
  `GET /plug/status`   查询插座状态
  `POST /plug/on`      打开插座
  `POST /plug/off`     关闭插座
  `GET /plug/power`    查询实时功率
  `GET /plug/energy`   查询累计电量

示例调用：

``` powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8000/plug/on"
```

HTTP API 应增加认证，不要把无认证的开关控制接口暴露到公网。

## 13. 建议的实机验证清单

-   [ ] 从米家云端提取到目标插座 Token。
-   [ ] 获取插座局域网 IP。
-   [ ] `device info` 返回真实 Model。
-   [ ] 确认设备 MIoT Spec。
-   [ ] 找到开关属性及其可写权限。
-   [ ] 读取开关状态，与米家 App 显示一致。
-   [ ] 写入开关属性后再次读取，确认状态改变。
-   [ ] 读取实时功率，与实际负载大致吻合。
-   [ ] 读取累计电量，并确认单位和缩放系数。
-   [ ] 断开电脑互联网但保留局域网，测试本地控制是否仍可用。

## 14. 需要进一步核实的关键资料

请在 Windows PowerShell 中运行与当前版本匹配的设备信息和 MIoT
查询命令。分享输出时请删除 Token、MAC、DID、账号等敏感信息。

需要确认的核心字段：

1.  实机返回的 `Model`。
2.  固件版本。
3.  MIoT Spec 中的服务、属性和动作。
4.  开关属性的 SIID/PIID、读写权限和数据类型。
5.  功率、电量属性的 SIID/PIID、单位及缩放系数。
6.  当前 `python-miio` 版本的 `GenericMiot` API。

只有这些信息核实后，才能给出针对该台插座、可直接运行且不依赖猜测属性编号的最终控制代码。

------------------------------------------------------------------------

## 参考链接

-   [python-miio GitHub](https://github.com/rytilahti/python-miio)
-   [python-miio 文档](https://python-miio.readthedocs.io/)
-   [Xiaomi Cloud Tokens
    Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)
-   [XiaomiHive：xiaomi.plug.04
    设备资料](https://xiaomihive.com/mijia/xiaomi.plug.04/)
-   [MIoT
    Spec：xiaomi.plug.04](https://home.miot-spec.com/spec/xiaomi.plug.04)

> 安全提示：Token
> 是设备控制凭据。不要将其发送给他人、上传到公开仓库或写入公开日志。
