# artifacts —— 当前在装的手机 APK（发布产物）

| 文件 | 说明 |
|---|---|
| `PhoneBox-0.9.4+fork-vc31.apk` | 面板手机现装版本。**md5 `2558313ed6a0a438d84849976f20395d`**；包名 `net.oxog.phonebox.deskpanel`；versionCode **31**，versionName **0.9.4+fork**（minSdk 28 / target 35） |

从零部署时可以直接装这个 APK 完成“Step 4”，也可以按 `../app/README.md` 的编译路径从源码构建
（两者产物应逐字节一致，构建配方见 `../tools/build-android.sh`；编译路径是正解，本 APK 只是
免编译的现场快照）。

## 安装与签名注意

- 构建时没有 keystore（`PHONEBOX_STORE_FILE` 未设）→ gradle **release 配置自动回落 debug key
  签名**，所以同 key 的 `adb install -r` 覆盖升级**不丢**应用数据与配对信息。
- 覆盖官方 release 版（不同签名）时需先卸载；`adb install -r -t -d` 三个开关分别对应
  覆盖/允许 test 属性/允许降级。
- 装后要点：`adb shell cmd package set-home-activity net.oxog.phonebox.deskpanel/.HomeAlias`
  设为 kiosk 桌面；开“Root 身份调试”（充电策略要用 adb root）。见 `../phone-setup/README.md`。

## 历史版本指纹（不入库，需要时回服务器 temp 归档取）

vc24 powerfix `9f8cc3be` → vc25 数据判据 `25ee74ab`/终 `429618cc` → vc26 事件快醒 `e861e79d` →
vc27 深睡 `0c6dc633` → vc28 pushItems+delay5 `bda89ec7` → vc29 断流地板 `f0f68d01` →
vc30 stat 卡地板 `4842f32e` → vc31 进程列表地板 `2a30a5aa` → 0.9.4+fork 整合构建 `de714ead` →
现装（同 vc31，含 big value 字号改动）`2558313e`。
