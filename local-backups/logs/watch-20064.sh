#!/bin/bash
# 20064 验证：重试期间不再通知宿主停投屏。
# 核心判据（和上一版对比）：
#   1) 不再出现 `launcher receiver stopCast` / `releaseCastCore` / `forceStopApp`
#   2) 出现同一进程内的多次 attempt（多个不同 [xxxxxxxx] 会话号，且进程号不变）
export MSYS_NO_PATHCONV=1
ADB=/c/Users/Administrator/dev-tools/android-sdk/platform-tools/adb.exe
PKG=com.baidu.carlife.xiaomi

dsize(){ $ADB shell "run-as $PKG wc -c files/diagnostic.log" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
lsize(){ $ADB shell "wc -c /data/local/tmp/live-20064.txt" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

D_OFF=$(dsize); D_OFF=${D_OFF:-0}
L_OFF=$(lsize); L_OFF=${L_OFF:-0}
echo "== watch 20064 start diag=$D_OFF logcat=$L_OFF =="

while true; do
  sleep 3
  D=$(dsize)
  if [ -n "$D" ] && [ "$D" -lt "$D_OFF" ]; then D_OFF=0; fi
  if [ -n "$D" ] && [ "$D" -gt "$D_OFF" ]; then
    $ADB shell "run-as $PKG tail -c +$((D_OFF+1)) files/diagnostic.log" 2>/dev/null \
      | head -c $((D-D_OFF)) | tr -d '\r' \
      | grep -a -E "\[entry\]|stage [A-Z]+$|failure:|Bonjour candidate|multicast lock held|attempt|NOT a dead link|not acknowledged|retrying in|Staying connected|budget" \
      | sed 's/^/D /'
    D_OFF=$D
  fi
  L=$(lsize)
  if [ -n "$L" ] && [ "$L" -lt "$L_OFF" ]; then L_OFF=0; fi
  if [ -n "$L" ] && [ "$L" -gt "$L_OFF" ]; then
    $ADB shell "tail -c +$((L_OFF+1)) /data/local/tmp/live-20064.txt" 2>/dev/null \
      | head -c $((L-L_OFF)) | tr -d '\r' \
      | grep -a -E "releaseCastCore|launcher receiver stopCast|forceStopApp:com.baidu.carlife|Start proc [0-9]+:com.baidu.carlife.xiaomi" \
      | sed 's/^/L /'
    L_OFF=$L
  fi
done
