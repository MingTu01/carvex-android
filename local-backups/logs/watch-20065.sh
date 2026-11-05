#!/bin/bash
# 20065 验证。只看两件事：
#   Q = 是否走了"安静期"等待（the car turned us down ... staying quiet）
#   R = 是否还会因 Bootstrap 层链路移动被终止（iAP link changed during bootstrap）
export MSYS_NO_PATHCONV=1
ADB=/c/Users/Administrator/dev-tools/android-sdk/platform-tools/adb.exe
PKG=com.baidu.carlife.xiaomi

dsize(){ $ADB shell "run-as $PKG wc -c files/diagnostic.log" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
lsize(){ $ADB shell "wc -c /data/local/tmp/live-20065.txt" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

D_OFF=$(dsize); D_OFF=${D_OFF:-0}
L_OFF=$(lsize); L_OFF=${L_OFF:-0}
echo "== watch 20065 start diag=$D_OFF logcat=$L_OFF =="

while true; do
  sleep 3
  D=$(dsize)
  if [ -n "$D" ] && [ "$D" -lt "$D_OFF" ]; then D_OFF=0; fi
  if [ -n "$D" ] && [ "$D" -gt "$D_OFF" ]; then
    $ADB shell "run-as $PKG tail -c +$((D_OFF+1)) files/diagnostic.log" 2>/dev/null \
      | head -c $((D-D_OFF)) | tr -d '\r' \
      | grep -a -E "\[entry\]|stage [A-Z]+$|failure:|turned us down|staying quiet|re-identifying|iAP link changed|hb video|Bonjour candidate" \
      | sed 's/^/D /'
    D_OFF=$D
  fi
  L=$(lsize)
  if [ -n "$L" ] && [ "$L" -lt "$L_OFF" ]; then L_OFF=0; fi
  if [ -n "$L" ] && [ "$L" -gt "$L_OFF" ]; then
    $ADB shell "tail -c +$((L_OFF+1)) /data/local/tmp/live-20065.txt" 2>/dev/null \
      | head -c $((L-L_OFF)) | tr -d '\r' \
      | grep -a -E "releaseCastCore|forceStopApp:com.baidu.carlife|Start proc [0-9]+:com.baidu.carlife.xiaomi|unbindService" \
      | sed 's/^/L /'
    L_OFF=$L
  fi
done
