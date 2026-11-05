#!/bin/bash
# 监视 20060 的锁屏复测：同时跟两份日志，只打有信息量的行。
#   D = diagnostic.log（我们自己的发现/连接日志，是唯一能看见 found / screenOn 的地方）
#   L = live-20060.txt（logcat，用来看车机与 CarWith 的动作：屏幕、断开、强杀、重启）
export MSYS_NO_PATHCONV=1
ADB=/c/Users/Administrator/dev-tools/android-sdk/platform-tools/adb.exe
PKG=com.baidu.carlife.xiaomi

size_of() { $ADB shell "run-as $PKG wc -c files/diagnostic.log" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
lsize_of() { $ADB shell "wc -c /data/local/tmp/live-20060.txt" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

D_OFF=$(size_of); D_OFF=${D_OFF:-0}
L_OFF=$(lsize_of); L_OFF=${L_OFF:-0}
echo "== watching from diag=$D_OFF logcat=$L_OFF =="

while true; do
  sleep 4
  D=$(size_of)
  if [ -n "$D" ] && [ "$D" -lt "$D_OFF" ]; then D_OFF=0; fi
  if [ -n "$D" ] && [ "$D" -gt "$D_OFF" ]; then
    $ADB shell "run-as $PKG tail -c +$((D_OFF+1)) files/diagnostic.log" 2>/dev/null \
      | head -c $((D-D_OFF)) \
      | tr -d '\r' \
      | grep -a -E "entry\]|stage |Bonjour|multicast|re-issuing|Resource close type=ReceiverDiscovery|teardown|iAP|CarVexFail|bootstrap|retry|FAILED" \
      | grep -a -v "^$" | sed 's/^/D /'
    D_OFF=$D
  fi
  L=$(lsize_of)
  if [ -n "$L" ] && [ "$L" -lt "$L_OFF" ]; then L_OFF=0; fi
  if [ -n "$L" ] && [ "$L" -gt "$L_OFF" ]; then
    $ADB shell "tail -c +$((L_OFF+1)) /data/local/tmp/live-20060.txt" 2>/dev/null \
      | head -c $((L-L_OFF)) \
      | tr -d '\r' \
      | grep -a -E "setScreenState|DISCONNECT_CAST|forceStop|am_proc_start|am_kill|CarlinkSM|UcarSM|CMD_|ScreenOn:|android.intent.action.SCREEN" \
      | grep -a -v adbd | grep -a -v ShellService | sed 's/^/L /'
    L_OFF=$L
  fi
done
