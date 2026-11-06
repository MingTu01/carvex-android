# AGENT.md

[中文](AGENT.md) | [English](AGENT.en.md)

本文件只适用于本仓库：**CarVeX**，多协议车机互联的 Android 实现，当前以 CarPlay 为主，包含协议栈、会话编排和品牌宿主适配。如果本仓库位于更大的研究工作区并存在 [`../AGENT.md`](../AGENT.md)，该工作区规则同样适用，并且仍是固件证据与研究流程的依据。

协议研究、连接分析和固件证据位于：[`iamr0s/carvex-carplay-research`](https://github.com/iamr0s/carvex-carplay-research)。

## 1. 适用范围与优先级

阅读顺序：

1. 存在 `../AGENT.md` 时先读它——工作区证据规则、固件基线、调试与实车流程。
2. 本文件——Android 模块边界、构建、签名、flavor 与测试规则。
3. `README.md`——面向使用者的行为、支持方式与当前限制。
4. 受影响模块的代码与测试。

固件是唯一协议依据。本仓库是实现，不是证据。现有代码、旧提交、字符串名、selector 和
自动反编译输出都只能作为索引。

## 2. 模块边界

依赖方向严格向下，不得出现环。

```text
:app
  -> :bridge, :host-api, :host-carlife-mixing
:bridge
  -> :core, :protocol-carplay, :host-api, :protocol-api, :platform-android
:protocol-carplay
  -> :core, :protocol-api, :pcm-core, :platform-android
:core
  -> :host-api, :protocol-api, :pcm-core, :platform-android
:host-carlife-mixing -> :host-api -> :pcm-core
:protocol-carlife   -> :protocol-api, :platform-android
```

硬性规则：

- `:bridge` 必须与宿主无关。宿主由 `:app` 通过 `SessionController` 注入；不得给
  `:bridge` 增加宿主依赖。
- `:protocol-carplay`、`:protocol-api`、`:pcm-core` 必须能在 JVM 上测试。不得加入
  Android UI、Koin、Compose 或 Service 生命周期。
- 只有 `:app` 是组装层。Koin 在这里启动；库模块只暴露普通构造函数。
- 厂商包名、Service 与插件位于 `:host-carlife-mixing`；选择品牌宿主属于 `:app`
  的 flavor。
- 协议行为不得写进 UI 层。UI 只观察 `BridgeStore`、调用 `DeviceTransport`，不得自行
  发明握手步骤、延迟或重试。

### 新增协议与宿主

新增 HiCar、CarLife、ICCOA 等协议时：

1. 新建 `:protocol-<name>` 模块，实现 `:protocol-api` 契约。
2. 平台差异放 `:platform-android` 或独立 platform 模块；宿主入口放 `:host-*`。
3. `:bridge` 只通过契约编排，不出现协议名分支。
4. 不得在 `:protocol-carplay` 或 `:app` 中按协议名硬编码分支。

新增小米 CarWith、OPPO CAR+ 等投屏/宿主生态时，新建或扩展 `:host-*` 适配器，
通过宿主 Service、权限和 Surface 接口接入，不修改 `:protocol-*` 状态机。现有
`:protocol-carlife` 是预留骨架，现有 `:host-carlife-mixing` 覆盖小米/OPPO/vivo/三星
的 CarLife mixing 宿主，不代表已经支持所有 CarWith/CAR+ 能力。

## 3. 修改规则

修改前：

1. 运行 `git status`，检查未提交改动。
2. 备份将要修改的每个文件：
   - 在工作区内：`../local-backups/<YYYY-MM-DD>-carvex-<主题>/`
   - 独立克隆：`local-backups/<YYYY-MM-DD>-<主题>/`
3. 不回滚、不覆盖用户无关的已有修改。

每次生产修改必须说明：

1. 遵循的固件地址、分支或 docset 要求。
2. 当前代码为什么不同。
3. 新代码如何与该证据对齐。
4. 运行了哪些测试。
5. 是否在手机和真实车机上验证。

禁止：

- 按车型或车机型号硬编码分支。
- 盲试固定延迟或无条件重发。
- 跳过声明、认证或版本检查。
- 在没有证据时关闭 `OPEN` 研究项。
- 把第三方实现当作协议依据。
- 提交 `local.properties`、设备私有日志或真实用户数据；签名密钥库与口令在本仓库公开，属于例外。
- 增加自动 flavor 或宿主回退。
- 为了让构建通过而削弱 lint、测试或签名检查。

## 4. 构建、签名与版本

前置条件：JDK 17+、Android SDK platform 37、build-tools 36.0.0，以及仓库内的 Gradle
Wrapper。`compileSdk`/`targetSdk` 为 37，`minSdk` 为 29。

签名只有一个来源：`.signing/keystore.properties`，包含 `storeFile`、`storePassword`、
`keyAlias`、`keyPassword`。四个值与密钥库文件必须同时存在。缺少任何一项都会在配置
阶段硬失败；不要增加静默的 debug 签名回退。

规则：

- `.signing/keystore.properties` 与 `.signing/release.jks` 按设计公开并随仓库分发。
  `local.properties`、构建日志和设备私有数据仍保持忽略。
- Debug 与 Release 有意共用同一把签名密钥，以便在同一设备上互相覆盖。没有明确决策
  不得拆开。
- `versionCode` 由 `20000 + git 提交数` 派生，`versionName` 是 `<主>.<次>.<提交数>`。
  两个位置都是派生的，手写的只有 `app/build.gradle.kts` 里的 `versionMajor` / `versionMinor`。
  不要手改其余版本常量，也不要用没有 git 历史的压缩包构建可分发包。
- 产物名由 variant 决定：`CarVex_<brand>_<buildType>_<version>.apk`（以及 `.aab`）。
  不要手工改名。
- 保持 R8、资源压缩与 `lint { abortOnError = true }` 开启。若 lint 判断有误，修正
  代码或加入范围明确的抑制，不要整体关闭。

## 5. Flavor 与宿主

| Flavor | 包名 | 宿主状态 |
|---|---|---|
| `xiaomi` | `com.baidu.carlife.xiaomi` | 小米 CarWith endpoint |
| `oppo` | `com.baidu.carlife.oppo` | OPPO Car+ endpoint |
| `vivo` | `com.baidu.carlife.vivo` | vivo endpoint |
| `samsung` | `com.baidu.carlife.samsung` | 三星 endpoint，含一个备用 Service |
| `honor` | `com.baidu.carlife.honor` | 仅打包；宿主插件不可用 |

- 未知 flavor 必须报错，不能静默变成小米。
- 荣耀插件返回不可用，因为没有已确认的公开 mixing endpoint。不得把“宿主应用已安装”
  当成“Service 可绑定”。
- 修改 applicationId、Service 类、action 或插件 ID 属于兼容性变更：必须有证据，
  同步宿主 README 与本表，并测试受影响 flavor。
- `:host-carlife-mixing` 同时拥有 `CarlifeConnectService` 的前台生命周期、KeepAlive
  绑定和 Manifest 声明。不要在 `:app` 中重复实现。

## 6. 测试与验证

最低本地检查：

```powershell
.\gradlew.bat :protocol-carplay:testDebugUnitTest :app:compileXiaomiDebugKotlin
```

改动相关模块时追加：

```powershell
.\gradlew.bat :pcm-core:test :host-api:test :bridge:testDebugUnitTest
.\gradlew.bat :host-carlife-mixing:testDebugUnitTest
.\gradlew.bat :app:testXiaomiDebugUnitTest
```

全部 flavor 与 release 检查：

```powershell
.\gradlew.bat :app:testXiaomiDebugUnitTest :app:testOppoDebugUnitTest `
  :app:testVivoDebugUnitTest :app:testSamsungDebugUnitTest :app:testHonorDebugUnitTest `
  :app:lintXiaomiRelease :app:lintOppoRelease :app:lintVivoRelease `
  :app:lintSamsungRelease :app:lintHonorRelease :app:assembleRelease --no-daemon
```

`:app` 有 flavor 之后，AGP 只生成 `lint<Brand>Release`，没有聚合的 `lintRelease`；
写 `lintRelease` 会在任务选择阶段报 ambiguous，什么都没跑就失败。新增 flavor 时，
这份清单和 CI 都要同步加上对应的 `lint<Brand>Release`。

CI 运行同一组检查并校验 APK。其中每个 flavor 是独立 job 并行执行，新增 flavor 时
要同时往 workflow 的 matrix 里加一项，否则该品牌不会被验证。JVM 测试通过只证明被
检查的条件，不证明蓝牙行为、Wi-Fi handoff、首帧或车机互通。

## 7. 协议实现限制

- 状态机必须由固件协议栈/框架的消息分派、回调、定时器和错误路径推导，不能做成按
  车型或配置表切换的状态机。配置只提供身份、地址、证书、编解码和传输参数；不得改变
  状态转移、跳过事件或超时。
- 当前桥接只支持蓝牙/iAP2 路径（`ConnectionMode.CARPLAY_BLUETOOTH`）。界面中的 Wi-Fi
  与 USB 不是独立的桥接模式，不得描述为可用传输方式。
- 认证仍属实验阶段。challenge 签名、配对签名和加密数据会检查，但生产级证书链信任
  尚未建立。不得在代码、UI 或文档中声称已经建立。
- 保持 docset 中的字段顺序、超时归属与状态转换。看起来只是“整理”的顺序变化也是协议
  变更，需要同样的证据。
- 重连实现必须隔离代际并清理旧会话；不要用重试掩盖泄漏。

## 8. 调试与实车测试

日志必须能定位构建与会话：APK versionCode、构建类型、提交号、session ID、目标蓝牙
地址、发起方向、协商的 link/control 版本、session 表、SYN/SYNACK/ACK 序号、最后发送
消息、下一条预期消息、首个超时或关闭点，以及实际 Network/接口/地址来源。

```powershell
adb logcat -c
adb logcat -v time > carvex-log.txt
adb shell dumpsys bluetooth_manager > bluetooth-state.txt
adb shell dumpsys connectivity > connectivity-state.txt
adb shell ip addr
adb shell ip route
```

实车规则：

1. 先提交，再构建测试包。
2. 用户自行打包安装，除非用户明确要求代打包。
3. 一次只验证一个假设。
4. 保留从连接开始的完整日志，不只保留末尾异常。
5. 记录手动断开、残留会话和车机重启。
6. 结果分为 `PASS`、`PARTIAL`、`FAIL`、`OPEN`；一次成功不能关闭兼容性问题。

## 9. 文档与资源

- 入口文档是四对：`../README.md` / `../README.en.md`、`../AGENT.md` / `../AGENT.en.md`、
  `README.md` / `README.en.md`、`AGENT.md` / `AGENT.en.md`。中文文件为主；任何变化必须
  在同一次修改中同步两种语言。
- 构建命令、签名规则、模块、flavor、包名、支持的连接方式或状态变化时，同步更新两份
  README（中英）。
- 本仓库 README 不承载完整协议规范。工作区存在时，链接到对应研究 docset。
- 工作区存在时，持久架构决策写入 `../docs/decisions/NNNN-title.md`。
- `assets/donation/` 存放 README 使用的三张图片；`app/src/main/assets/` 存放应用内
  由 `LocalVideoDisplay.kt`（`DonationCodes`）加载的副本。两处必须保持一致。改名或删除
  必须同步更新两个目录和所有 README。捐赠不得成为功能门槛、影响构建或影响诊断。
