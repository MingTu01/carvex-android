# CarVeX

[中文](README.md) | [English](README.en.md)

[![Android signed APK](https://github.com/iamr0s/carvex-android/actions/workflows/android.yml/badge.svg)](https://github.com/iamr0s/carvex-android/actions/workflows/android.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

> 面向多协议车机互联的 Android 实现；CarPlay 是当前首个按 iPhone 固件复现的协议。
>
> 状态：研究预览。CarPlay 路径最完整；HiCar、CarLife、ICCOA 等尚未实现。本地测试与编译不代表真实车机互通，没有证据的内容保持 `OPEN`。

## 为什么做这件事

时代仿佛越开越阔，也越收越紧。各家以协议为藩篱，以生态为绳缆，口称开放，实则圈地；用户被高高捧起，又常被用完即弃，如祭祀中的刍狗。

我们想把互通重新落回协议本身：不靠某家的配置表，不把用户拴进某个生态，而是按协议栈、协商字段和能力位，让手机与车机尽可能广泛地连接。

> “这些墙很有意思。起初你恨它，后来你习惯它；日子够久，你会发现自己离不开它。这就叫体制化。”
>
> —— 瑞德，《肖申克的救赎》

## 捐赠

如果这个项目对你有帮助，欢迎自愿捐赠以示感谢。捐赠不构成任何支持承诺。

<p align="center">
  <img src="assets/donation/alipay.jpg" width="200" alt="支付宝">
  <img src="assets/donation/wxpay.jpg" width="200" alt="微信支付">
  <img src="assets/donation/binance.jpg" width="200" alt="Binance Pay">
</p>

<p align="center">支付宝 · 微信支付 · Binance Pay</p>

## 交流群

CarVeX 开发者交流群，欢迎一起讨论协议适配、车机兼容性与实车调试。

<p align="center">
  <a href="https://qm.qq.com/cgi-bin/qm/qr?k=KyWBLDmahaZW2TKDU51x3hs-6irSn6UX&amp;jump_from=webapi&amp;authKey=qFX04xLs8pXluNhr7O18mECpkt8MP3ooZzKf81NPWjq9C4QDf42TixqzTWrEXQG1">
    <img src="https://pub.idqqimg.com/wpa/images/group.png" alt="CarVeX开发者交流群">
  </a>
</p>

## 这是什么

CarVeX 是多协议车机互联的 Android 实现，当前以 CarPlay 为主。项目目标不是只适配 CarPlay，而是在同一套会话、平台和宿主框架下接入 HiCar、CarLife、ICCOA 等协议。当前已展开的 CarPlay 链路与 docset 中记录的一致：

协议研究、连接分析和固件证据位于独立仓库：[`iamr0s/carvex-carplay-research`](https://github.com/iamr0s/carvex-carplay-research)。

```text
Bluetooth RFCOMM
  -> iAP2 link
  -> Identification / Authentication
  -> Wi-Fi handoff
  -> AirPlay control
  -> RTSP SETUP
  -> video / audio / microphone
  -> normal teardown and reconnect
```

工程分层让协议逻辑可以在纯 JVM 上测试，Android 传输能力留在平台模块中。应用同时内置
CarLife mixing 宿主适配器，使同一套会话可以从多个厂商的车机生态中启动。

Android 侧是分模组设计：`:protocol-api` 定义协议契约，`:protocol-carplay` 实现 CarPlay，
`:bridge` 只负责会话编排，`:platform-android` 提供平台能力，`:app` 负责组装和宿主注入。
新增 HiCar、CarLife、ICCOA 等协议时，新增独立 `:protocol-*` 模块并接入 `:bridge`/`:app`，
不需要改动 CarPlay 协议实现；`:protocol-carlife` 目前只是预留骨架。宿主/投屏实现也走独立
`:host-*` 模块，小米 CarWith、OPPO CAR+ 等生态通过宿主插件接入，不改动协议栈。

本仓库也可以放在更大的研究目录 `D:\carvex-research\android` 下；在这种布局中，
父目录的 `AGENT.md` 同样适用。

## 当前 CarPlay 能力

- 已配对设备列表、显式连接/断开、单设备设置与诊断。
- iAP2 链路建立、Identification 流程与控制会话路由。
- Wi-Fi handoff 与 AirPlay control / RTSP SETUP 流程。
- 视频、音频、麦克风、RTP 时序与重传相关的协议与管线模块。车载音频与麦克风传输尚未
  实现；视频、触摸与兼容性仍需实车验收。
- 小米、OPPO、vivo、三星的 CarLife mixing 宿主；荣耀仅用于打包。
- 结构化桥接日志，支持复制与导出。
- 在系统不暴露蓝牙 MAC 时，可通过 Shizuku 读取手机蓝牙 MAC，供身份握手使用。
- 本地投屏页（连接建立页与无宿主页）。
- 浅色/深色/跟随系统主题，以及权限引导流程。

## 连接方式与当前限制

界面列出三种连接方式，但当前活动桥接只支持一种：

| 界面方式 | 桥接状态 |
|---|---|
| `CarPlay-Bluetooth` | 已支持：`ConnectionMode.CARPLAY_BLUETOOTH` |
| `CarPlay-WIFI` | 仅在界面列出；没有独立的桥接模式 |
| `CarPlay-USB` | 仅在界面列出；没有独立的桥接模式 |

认证仍属实验阶段。界面会说明实验连接流程验证 challenge 签名、配对签名和加密数据，
但生产级证书链信任尚未建立。某一个环境连接成功只是一个数据点，不代表通用兼容性。

## 环境要求

- Android 10 及以上（`minSdk 29`）；`compileSdk` 与 `targetSdk` 为 37。
- 具备蓝牙的设备；Wi-Fi handoff 还需要 Wi-Fi 与本地网络权限。
- Android SDK platform 37 与 build-tools 36.0.0。
- JDK 17 及以上用于 Gradle。CI 使用 Temurin 17，Android Studio 自带 JBR 也可。
- 使用仓库内的 Gradle Wrapper；Kotlin 2.4.21，AGP 9.4.1。
- 签名密钥库随仓库公开。Debug 与 Release 有意共用同一把密钥。

Shizuku 是可选项，只用于在系统限制 `Settings.Secure.bluetooth_address` 时读取手机
蓝牙 MAC。

## 构建

1. 配置 SDK 路径。若缺少 `local.properties`，创建并写入：

   ```properties
   sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
   ```

2. 签名材料已随仓库提供：`.signing/keystore.properties` 与 `.signing/release.jks`。
   构建会直接使用它们；只有需要更换密钥时才复制 `.signing/keystore.properties.example`。

   `storeFile` 相对仓库根目录解析，也可以写绝对路径。属性文件、四个值或密钥库缺任何
   一项，构建都会在配置阶段直接失败。这是有意设计：静默产出未签名或 debug 签名的包，
   只会在更晚、更难定位的地方失败。

3. 编译并运行协议测试：

   ```powershell
   .\gradlew.bat :protocol-carplay:testDebugUnitTest :app:compileXiaomiDebugKotlin
   ```

4. 构建单个品牌：

   ```powershell
   .\gradlew.bat :app:assembleXiaomiDebug --no-daemon
   ```

5. 构建五个品牌并运行宿主测试：

   ```powershell
   .\gradlew.bat :host-carlife-mixing:test :app:assembleXiaomiDebug :app:assembleOppoDebug `
     :app:assembleVivoDebug :app:assembleSamsungDebug :app:assembleHonorDebug --no-daemon
   ```

APK 产物：

```text
app/build/outputs/apk/<brand>/<debug|release>/CarVex_<brand>_<buildType>_<version>.apk
```

`versionCode` 是 `20000 + git 提交数`，`versionName` 是同一次构建的 `<主>.<次>.<提交数>`，
因此构建需要完整的 git 历史（`git rev-list --count HEAD`）。没有 `.git` 的源码压缩包会
回退到 `10001` / `1.0.1`，不要用它分发。Debug 与 Release 共用同一把密钥，同一台设备上会
互相覆盖。

Release 构建开启 R8 与资源压缩。Lint 配置为 `abortOnError = true`，导致 lint 失败的
告警会直接令构建失败。

## CI 与签名

`.github/workflows/android.yml` 在推送 `main`、Pull Request 和手动触发时运行，分三部分：

- `protocol-tests`：`:protocol-carplay`、`:pcm-core`、`:host-api`、`:bridge`、
  `:host-carlife-mixing` 的纯 JVM 测试。
- `apk`：按 flavor 展开的矩阵，每个品牌一个 job 并行执行自己的单测、lint 和打包
  （`:app:test<Brand>DebugUnitTest`、`:app:lint<Brand>Release`、
  `:app:assemble<Brand>Release`）。lint 必须点名到 flavor：`:app` 有 flavor 后 AGP
  只生成 `lint<Brand>Release`，没有聚合的 `lintRelease`。`fail-fast: false`，
  一个品牌失败不会取消其它品牌。
- `publish`：收集五个 job 的 APK，打包成 `CarVex_all_flavors_release_<version>.zip`
  （版本从 APK 文件名派生，与散装包同源），生成 `SHA256SUMS` 与 `build-info.txt`，
  非 PR 运行再发预发布版本。

CI 直接使用仓库内公开的 `.signing/keystore.properties` 与 `.signing/release.jks`，
不再依赖 secrets；fork PR 也使用同一份公开签名材料。

每次非 PR 运行都会把散装 APK、`CarVex_all_flavors_release_<version>.zip` 与
`SHA256SUMS` 作为 `build-<run>` 预发布版本上传到 GitHub Releases。这些是实验构建：
车载音频与麦克风传输尚未实现，视频、触摸与兼容性仍需实车验收。

本仓库有意公开 `.signing/keystore.properties` 与 `release.jks`，用于可复现构建；
它们不是安全边界。仍不要提交 `local.properties`、设备私有日志或真实用户数据。

## 模块分层

```text
:protocol-carplay    carplay.core 原语 + Android CarPlay 会话
:pcm-core            纯 JVM PCM 转换与重采样
:platform-android    蓝牙、网络、NSD 和 Surface 适配
:protocol-api        协议契约（纯 JVM）
:protocol-carlife    CarLife 协议预留骨架
:host-api            宿主契约
:core                共享状态、事件与诊断
:bridge              会话编排；不依赖任何宿主
:host-carlife-mixing 各品牌共用的 CarLife mixing 宿主
:app                 应用、UI、Service、flavor 与宿主接线
```

依赖方向严格向下。`:bridge` 不依赖宿主模块，由 `:app` 通过 `SessionController` 注入；
`:protocol-carplay` 不得依赖 `:app`、`:bridge`，也不得依赖 Koin 或 Compose。

## 扩展新协议与宿主

新增 HiCar、CarLife、ICCOA 等协议时：

1. 新建 `:protocol-<name>` 模块，实现 `:protocol-api` 契约。
2. 平台差异放 `:platform-android` 或新增 platform 模块；宿主 Service/权限放 `:host-*`。
3. `:bridge` 只通过契约编排，`:app` 负责组装和 flavor。
4. 不改动 `:protocol-carplay` 的现有状态机。

新增小米 CarWith、OPPO CAR+ 等投屏生态时，扩展或新建 `:host-*` 适配器，不改动
`:protocol-*`。`:protocol-carlife` 目前是预留骨架，不能当作已实现的 CarLife 连接。

## 品牌 flavor

每个 flavor 改变 applicationId 并选择宿主插件。未知 flavor 会报错，不会自动回退到小米。

| Flavor | 包名 | 宿主包 | Service |
|---|---|---|---|
| `xiaomi` | `com.baidu.carlife.xiaomi` | `com.miui.carlink` | `com.xiaomi.ucar.carlife.CarlifeConnService` |
| `oppo` | `com.baidu.carlife.oppo` | `com.oplus.ocar` | `com.oplus.ocar.connect.carlife.CarlifeCastManagerService` |
| `vivo` | `com.baidu.carlife.vivo` | `com.vivo.car.networking` | `com.vivo.vivo_car_service.CarLifeMixingService` |
| `samsung` | `com.baidu.carlife.samsung` | `com.samsung.android.carlink` | `com.samsung.android.carlink.mixing.CarLifeMixingService` |
| `honor` | `com.baidu.carlife.honor` | `com.hihonor.auto` | 尚无已确认的公开 mixing endpoint |

荣耀 flavor 可以构建用于打包，但宿主插件明确返回不可用。“APK 已安装”不能证明第三方
应用可以绑定荣耀系统服务。

## 测试

```powershell
.\gradlew.bat :protocol-carplay:testDebugUnitTest
.\gradlew.bat :pcm-core:test :host-api:test :bridge:testDebugUnitTest
.\gradlew.bat :host-carlife-mixing:testDebugUnitTest
.\gradlew.bat :app:testXiaomiDebugUnitTest
```

测试只证明被检查的条件，不能替代车机抓包或实车测试。

## 状态与非目标

- 本仓库不修改车机固件，也不修改第三方接收端软件。
- 不按车型硬编码；兼容性必须依据协商字段，而不是车型名称。
- 不因一次成功就关闭连接问题。
- 状态机由协议栈框架和协商字段决定，不是按车型或配置表切换的状态机。
- 研究目录中严格区分 `CONFIRMED`、`INFERRED`、`OPEN`。本 README 只是概述，不是协议规范。
- 修改代码前先读 [`AGENT.md`](AGENT.md)，其中包含模块、证据、签名与测试规则。

## 许可

GNU General Public License v3.0，详见 [`LICENSE`](LICENSE)。

本项目与 Apple、百度及任何手机、汽车、车机厂商无隶属关系。CarPlay 是 Apple Inc. 的商标。
