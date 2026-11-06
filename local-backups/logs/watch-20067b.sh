#!/bin/bash
# 20067 复测（车机冷启动）。这一轮只回答四个问题：
#   Q1 安静期有没有生效        → "turned us down ... staying quiet"
#   Q2 5702 静默是否还退避重发 → "Staying connected and retrying"
#   Q3 收尾后是否还被拆        → launcher receiver stopCast / releaseCastCore / forceStopApp
#   Q4 静默取消有没有留痕      → "attempt cancelled"
export MSYS_NO_PATHCONV=1
ADB=/c/Users/Administrator/dev-tools/android-sdk/platform-tools/adb.exe
PKG=com.baidu.carlife.xiaomi

dsize(){ $ADB shell "run-as $PKG wc -c files/diagnostic.log" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
lsize(){ $ADB shell "wc -c /data/local/tmp/live-20067b.txt" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

D_OFF=$(dsize); D_OFF=${D_OFF:-0}
L_OFF=$(lsize); L_OFF=${L_OFF:-0}
echo "== watch 20067b start diag=$D_OFF logcat=$L_OFF =="

while true; do
  sleep 3
  D=$(dsize)
  if [ -n "$D" ] && [ "$D" -lt "$D_OFF" ]; then D_OFF=0; fi
  if [ -n "$D" ] && [ "$D" -gt "$D_OFF" ]; then
    $ADB shell "run-as $PKG tail -c +$((D_OFF+1)) files/diagnostic.log" 2>/dev/null \
      | head -c $((D-D_OFF)) | tr -d '\r' \
      | grep -a -E "\[entry\]|stage [A-Z]+$|failure:|turned us down|staying quiet|re-identifying|iAP link changed|hb video|Bonjour candidate|Staying connected|not acknowledged 5702|attempt cancelled" \
      | sed 's/^/D /'
    D_OFF=$D
  fi
  L=$(lsize)
  if [ -n "$L" ] && [ "$L" -lt "$L_OFF" ]; then L_OFF=0; fi
  if [ -n "$L" ] && [ "$L" -gt "$L_OFF" ]; then
    $ADB shell "tail -c +$((L_OFF+1)) /data/local/tmp/live-20067b.txt" 2>/dev/null \
      | head -c $((L-L_OFF)) | tr -d '\r' \
      | grep -a -E "releaseCastCore|launcher receiver stopCast|forceStopApp:com.baidu.carlife|Start proc [0-9]+:com.baidu.carlife.xiaomi|unbindService|attempt cancelled" \
      | sed 's/^/L /'
    L_OFF=$L
  fi
done
