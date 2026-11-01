#!/usr/bin/env bash
# 查哪些方法因为太大而根本没被 JIT 编译（这是冷启动 9~20 秒的根因）。
#
#   tools/jit_limit_check.sh [serial]
#
# 做法：强制回到 verify 模式（去掉 AOT 产物），冷启动，再把每个大界面都走一遍，
# 让那些方法真的热起来 —— ART 就会打 `Method exceeds compiler instruction limit: N`。
# 只看 logcat 里的这一句，比数代码行数可靠：它给的是**真实指令数**和**真实判定结果**。
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    ANDROID_HOME="$ANDROID_SDK_ROOT"
  elif [ -n "${LOCALAPPDATA:-}" ] && command -v cygpath >/dev/null 2>&1; then
    ANDROID_HOME="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk"
  else
    echo "请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT 后再运行" >&2
    exit 2
  fi
fi
export ANDROID_HOME
export MSYS2_ARG_CONV_EXCL="*"
DEV="${1:-emulator-5554}"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
PKG=com.suipai.app.debug
ACT="$PKG/com.suipai.app.MainActivity"

$ADB -s "$DEV" shell "cmd package compile -m verify -f $PKG" >/dev/null 2>&1
$ADB -s "$DEV" shell am force-stop $PKG
sleep 2
$ADB -s "$DEV" logcat -c >/dev/null 2>&1
$ADB -s "$DEV" shell am start -n "$ACT" >/dev/null 2>&1
sleep 12

sw() { $ADB -s "$DEV" shell input swipe "$@"; sleep 2; }
tp() { $ADB -s "$DEV" shell input tap "$@"; sleep 3; }

# 相机页
tp 900 2232          # 拍照 tab
sleep 6
# 看图页 + 一瞬：回照片页，点第一张，再点「一瞬」
tp 173 2232
sleep 2
sw 540 1600 540 900 150
$ADB -s "$DEV" shell input tap 180 1300   # 第一张照片
sleep 5
sw 950 2205 150 2205 120                  # 底部工具条滚到「一瞬」
$ADB -s "$DEV" shell input tap 917 2212
sleep 10
# 相册页
tp 540 2232
sleep 4

echo "=== ART 拒绝编译的方法（本 app）==="
$ADB -s "$DEV" logcat -d 2>/dev/null |
  grep -oE "Method exceeds compiler instruction limit: [0-9]+ in void [a-z.]+\.[A-Za-z0-9]+[Kk]t\.[A-Za-z_]+" |
  sed -E 's/.*limit: ([0-9]+) in void [a-z.]+\.([A-Za-z0-9]+Kt\.[A-Za-z_]+)/\1\t\2/' |
  sort -rn -u |
  awk 'BEGIN{printf "%-9s %s\n","instr","method"} {printf "%-9s %s\n",$1,$2}'
echo
echo "=== 主线程卡顿 ==="
$ADB -s "$DEV" logcat -d 2>/dev/null | grep -E "Choreographer.*Skipped" | sed 's/^.*Choreographer/  Choreographer/' | head -8
N=$($ADB -s "$DEV" logcat -d 2>/dev/null | grep -c "Method exceeds compiler instruction limit")
echo
if [ "${N:-0}" -eq 0 ]; then echo "OK: 没有方法被拒绝编译"; else echo "还有 $N 个方法没被 JIT 编译"; fi
