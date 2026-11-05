#!/bin/bash
# 20063 重连验证。核心判据只有两条：
#   1) 出现 "has not acknowledged 5702 ... retrying" → 静默不退场生效
#   2) 之后不再出现 launcher receiver stopCast / releaseCastCore → 会话没被拆
export MSYS_NO_PATHCONV=1
ADB=/c/Users/Administrator/dev-tools/android-sdk/platform-tools/adb.exe
PKG=com.baidu.carlife.xiaomi

dsize(){ $ADB shell "run-as $PKG wc -c files/diagnostic.log" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
lsize(){ $ADB shell "wc -c /data/local/tmp/live-20063.txt" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

D_OFF=$(dsize); D_OFF=${D_OFF:-0}
L_OFF=$(lsize); L_OFF=${L_OFF:-0}
echo "== watch 20063 start diag=$D_OFF logcat=$L_OFF =="

while true; do
  sleep 3
  D=$(dsize)
  if [ -n "$D" ] && [ "$D" -lt "$D_OFF" ]; then D_OFF=0; fi
  if [ -n "$D" ] && [ "$D" -gt "$D_OFF" ]; then
    $ADB shell "run-as $PKG tail -c +$((D_OFF+1)) files/diagnostic.log" 2>/dev/null \
      | head -c $((D-D_OFF)) | tr -d '\r' \
      | grep -a -E "entry\]| stage [A-Z]+$|reusing the Wi-Fi|failure:|re-opened IDPS|multicast lock held|Bonjour candidate found|not acknowledged 5702|retrying in|Staying connected|budget [0-9]+s|silentMs|stopped acknowledging" \
      | sed 's/^/D /'
    D_OFF=$D
  fi
  L=$(lsize)
  if [ -n "$L" ] && [ "$L" -lt "$L_OFF" ]; then L_OFF=0; fi
  if [ -n "$L" ] && [ "$L" -gt "$L_OFF" ]; then
    $ADB shell "tail -c +$((L_OFF+1)) /data/local/tmp/live-20063.txt" 2>/dev/null \
      | head -c $((L-L_OFF)) | tr -d '\r' \
      | grep -a -E "releaseCastCore|carlinkDisconnected|launcher receiver stopCast|forceStopApp:com.baidu.carlife|am_proc_start.*carlife|DISCONNECT_CAST" \
      | grep -a -v adbd | grep -a -v ShellService | sed 's/^/L /'
    L_OFF=$L
  fi
done
