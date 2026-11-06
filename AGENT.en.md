# AGENT.md

[中文](AGENT.md) | English

Rules for this repository only: **CarVeX**, the Android implementation of multi-protocol vehicle connectivity, currently CarPlay-first, including the protocol stack, session orchestration and vendor host adapters. If this repository sits inside the larger research workspace and [`../AGENT.en.md`](../AGENT.en.md) exists, that workspace file also applies and remains the authority for firmware evidence and research process.

Protocol research, connection analysis and firmware evidence live in: [`iamr0s/carvex-carplay-research`](https://github.com/iamr0s/carvex-carplay-research).

## 1. Scope and precedence

Read in this order:

1. `../AGENT.en.md` when present — workspace evidence rules, firmware baseline, debugging and
   field-test process.
2. This file — Android module boundaries, build, signing, flavors and test rules.
3. `README.en.md` — user-facing behavior, supported methods and current limits.
4. The affected module's code and tests.

Firmware is the only protocol authority. This repository is an implementation, not
evidence. Existing code, old commits, string names, selectors and decompiler output are
indexes only.

## 2. Module boundaries

Dependency direction is strictly downward. Do not create cycles.

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

Hard rules:

- `:bridge` must stay host-agnostic. Hosts are injected by `:app` through
  `SessionController`; never add a host dependency to `:bridge`.
- `:protocol-carplay`, `:protocol-api` and `:pcm-core` must stay testable on the JVM.
  Do not add Android UI, Koin, Compose or Service lifecycles to them.
- Only `:app` is an assembly layer. Koin starts there; library modules expose plain
  constructors.
- Vendor package names, services and plugins live in `:host-carlife-mixing`; selecting a
  brand host belongs to `:app` flavors.
- Do not put protocol behavior in the UI layer. The UI observes `BridgeStore` and calls
  `DeviceTransport`; it must not invent handshake steps, delays or retries.

### Adding protocols and hosts

To add HiCar, CarLife, ICCOA or another protocol:

1. Create a new `:protocol-<name>` module implementing the `:protocol-api` contracts.
2. Keep platform differences in `:platform-android` or a separate platform module; keep
   host entry points in `:host-*`.
3. `:bridge` must orchestrate only through contracts and must not branch on protocol names.
4. Do not hardcode protocol-name branches in `:protocol-carplay` or `:app`.

To add a mirroring/host ecosystem such as Xiaomi CarWith or OPPO CAR+, create or extend a
`:host-*` adapter and integrate through host services, permissions and Surface APIs without
modifying `:protocol-*` state machines. `:protocol-carlife` is a reserved skeleton, and
`:host-carlife-mixing` covers the CarLife mixing hosts for Xiaomi/OPPO/vivo/Samsung; this
does not mean every CarWith/CAR+ capability is already supported.

## 3. Change rules

Before editing:

1. Run `git status` and inspect uncommitted work.
2. Back up every file you will change:
   - inside the workspace: `../local-backups/<YYYY-MM-DD>-carvex-<topic>/`
   - standalone clone: `local-backups/<YYYY-MM-DD>-<topic>/`
3. Never revert or overwrite unrelated user changes.

Every production change must state:

1. The firmware address, branch or docset requirement it follows.
2. Why the current code differs.
3. How the new code aligns with that evidence.
4. Which tests were run.
5. Whether it was verified on a phone and a real head unit.

Forbidden:

- Per-vehicle or per-model hardcoded branches.
- Blind fixed delays or unconditional retransmission.
- Skipping declaration, authentication or version checks.
- Closing an `OPEN` research item without evidence.
- Treating a third-party implementation as protocol authority.
- Committing `local.properties`, device-private logs or real user data. The signing keystore and passwords are intentionally public in this repository and are the exception.
- Adding an automatic flavor or host fallback.
- Weakening lint, tests or signing checks to make a build pass.

## 4. Build, signing and versions

Prerequisites: JDK 17+, Android SDK platform 37, build-tools 36.0.0, and the repository's
Gradle Wrapper. `compileSdk`/`targetSdk` are 37; `minSdk` is 29.

Signing has exactly one source: `.signing/keystore.properties`, containing `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. All four values plus the keystore file must
exist. Missing material is a hard configuration failure by design; do not add a silent
debug-signing fallback.

Rules:

- `.signing/keystore.properties` and `.signing/release.jks` are intentionally public and
  tracked. `local.properties`, build logs and device-private data stay ignored.
- Debug and release deliberately share one signing key so they can replace each other on
  a device. Do not split them without an explicit decision.
- `versionCode` is derived as `20000 + git commit count`; `versionName` is
  `<major>.<minor>.<commit count>`. Both positions are derived — the only hand-written
  values are `versionMajor` / `versionMinor` in `app/build.gradle.kts`. Do not hand-edit
  any other version constant, and do not build distributable artifacts from an archive
  without git history.
- Artifact names come from the variant: `CarVex_<brand>_<buildType>_<version>.apk` (and
  `.aab`). Do not rename them manually.
- Keep R8, resource shrinking and `lint { abortOnError = true }` enabled. If lint is wrong,
  fix the code or add a narrowly justified suppression.

## 5. Flavors and hosts

| Flavor | Application ID | Host status |
|---|---|---|
| `xiaomi` | `com.baidu.carlife.xiaomi` | Xiaomi CarWith endpoint |
| `oppo` | `com.baidu.carlife.oppo` | OPPO Car+ endpoint |
| `vivo` | `com.baidu.carlife.vivo` | vivo endpoint |
| `samsung` | `com.baidu.carlife.samsung` | Samsung endpoint with one fallback service |
| `honor` | `com.baidu.carlife.honor` | Packaging only; host plugin unavailable |

- An unknown flavor must fail, not silently become Xiaomi.
- The Honor plugin returns unavailable because no confirmed public mixing endpoint exists.
  Do not turn "host app installed" into "service is bindable".
- Changing an application ID, service class, action or plugin ID is a compatibility change:
  require evidence, update the host README and this table, and test the affected flavor.
- `:host-carlife-mixing` also owns `CarlifeConnectService`, its foreground lifecycle,
  KeepAlive binding and manifest declarations. Do not duplicate that service in `:app`.

## 6. Tests and verification

Minimum local check:

```powershell
.\gradlew.bat :protocol-carplay:testDebugUnitTest :app:compileXiaomiDebugKotlin
```

Additional modules when touched:

```powershell
.\gradlew.bat :pcm-core:test :host-api:test :bridge:testDebugUnitTest
.\gradlew.bat :host-carlife-mixing:testDebugUnitTest
.\gradlew.bat :app:testXiaomiDebugUnitTest
```

All flavors and release checks:

```powershell
.\gradlew.bat :app:testXiaomiDebugUnitTest :app:testOppoDebugUnitTest `
  :app:testVivoDebugUnitTest :app:testSamsungDebugUnitTest :app:testHonorDebugUnitTest `
  :app:lintXiaomiRelease :app:lintOppoRelease :app:lintVivoRelease `
  :app:lintSamsungRelease :app:lintHonorRelease :app:assembleRelease --no-daemon
```

With flavors, AGP generates `lint<Brand>Release` only — there is no aggregate
`lintRelease`, and naming it fails at task selection before any task runs. Add the new
`lint<Brand>Release` here and in CI whenever a flavor is added.

CI runs the same set and verifies the APKs. Each flavor is a separate parallel job, so a
new flavor also needs a new row in the workflow matrix or that brand is never validated.
A passing JVM test only proves the checked condition; it does not prove Bluetooth
behavior, Wi-Fi handoff, first frame, or head-unit interoperability.

## 7. Protocol implementation limits

- State machines must be derived from the firmware protocol stack and framework: message
  dispatch, callbacks, timers and error paths. Do not build a per-vehicle or
  configuration-table state machine. Configuration only supplies identity, addresses,
  certificates, codec and transport parameters; it must not change transitions, skip
  events or alter timeouts.
- Only the Bluetooth/iAP2 path is backed by the bridge today
  (`ConnectionMode.CARPLAY_BLUETOOTH`). The Wi-Fi and USB entries in the UI are not
  separate bridge modes; do not describe them as working transports.
- Authentication is experimental. Challenge signatures, pairing signatures and encrypted
  data are checked, but production certificate-chain trust is not established. Do not
  claim otherwise in code, UI or documentation.
- Preserve field ordering, timeout ownership and state transitions from the docsets. A
  cosmetic refactor that changes ordering is a protocol change and needs the same evidence.
- Reconnect work must isolate generations and clean up stale sessions; do not mask a leak
  with a retry.

## 8. Debugging and field tests

Logs must identify the build and session: APK versionCode, build type, commit,
session ID, target Bluetooth address, initiation direction, negotiated link/control
version, session table, SYN/SYNACK/ACK numbers, last sent message, next expected message,
first timeout or close point, and the actual Network/interface/address source.

```powershell
adb logcat -c
adb logcat -v time > carvex-log.txt
adb shell dumpsys bluetooth_manager > bluetooth-state.txt
adb shell dumpsys connectivity > connectivity-state.txt
adb shell ip addr
adb shell ip route
```

Field-test rules:

1. Commit before building a test artifact.
2. The user packages and installs unless they explicitly ask otherwise.
3. Test one hypothesis at a time.
4. Keep the log from connection start, not only the trailing exception.
5. Record manual disconnects, stale sessions and head-unit restarts.
6. Classify results as `PASS`, `PARTIAL`, `FAIL` or `OPEN`; one success does not close a
   compatibility issue.

## 9. Documentation and assets

- The entry documents are four pairs: `../README.md` / `../README.en.md`, `../AGENT.md` /
  `../AGENT.en.md`, `README.md` / `README.en.md`, and `AGENT.md` / `AGENT.en.md`. The
  Chinese file is primary; keep both languages in sync in the same change.
- Update both README files whenever a build command, signing rule, module, flavor,
  application ID, supported connection method or status changes.
- Do not put a full protocol specification in this repository's README. Link to the
  research docset when the workspace is present.
- Durable architecture decisions belong in `../docs/decisions/NNNN-title.md` when the
  workspace exists.
- `assets/donation/` holds the three images used by the READMEs; `app/src/main/assets/`
  holds the app copies loaded by `LocalVideoDisplay.kt` (`DonationCodes`). Keep both sets
  identical. Renaming an image requires updating both folders and every README. Donations
  must never gate a feature, change a build, or affect diagnostics.
