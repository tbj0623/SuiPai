#!/usr/bin/env bash
# 跑「随拍 ↔ 独立 Python 实现」的互通测试：一条命令把对端、端口、断言全包了。
#
#   tools/run_sync_interop.sh [serial]        # 默认 emulator-5554
#
# 为什么需要它：这些测试要宿主机上真有一个按 SYNC.md 写的对端在听，
# 少了任何一步（adb reverse / serve / journal）都会退化成六个「连接被拒」，
# 看不出是没测到还是测挂了。
set -u

# Windows 上 python 输出重定向到文件会按 GBK 编码，中文断言就成乱码了。
export PYTHONIOENCODING=utf-8

SERIAL="${1:-emulator-5554}"
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
PEER_PORT=47481
LIB="${SUIPAI_PEER_LIB:-/tmp/peerlib}"      # 放若干 .jpg 的目录，充当对端的相册
JOURNAL="$(mktemp -t suipai-journal.XXXXXX)"
CLASS=com.suipai.app.sync.SyncPeerInteropTest

fail() { echo "!! $*" >&2; exit 1; }

[ -x "$ADB" ] || command -v "$ADB" >/dev/null 2>&1 || fail "找不到 adb（设 ANDROID_HOME）"
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || fail "$SERIAL 不在线"
ls "$LIB"/*.jpg >/dev/null 2>&1 || fail "对端相册 $LIB 里没有 .jpg（放几张再跑）"

echo "[1/4] 清掉旧的 peer 进程"
# 只筛 python 进程：不过滤名字的话，powershell 自身的命令行里也有 "sync_peer"，会把自己杀掉。
powershell -NoProfile -Command \
  "Get-CimInstance Win32_Process -Filter \"Name='python.exe' OR Name='python3.exe'\" | Where-Object { \$_.CommandLine -match 'sync_peer' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" \
  >/dev/null 2>&1
sleep 1

echo "[2/4] 起 Python 对端（相册 $LIB，端口 $PEER_PORT）"
PEER_LOG="$(mktemp -t suipai-peer.XXXXXX)"
# 必须把后台对端的 stdout/stderr 接走：不然它持着本脚本的输出管道，
# 调用方 `run_sync_interop.sh | tail` 会永远等不到 EOF（真踩过）。
python3 "$REPO/tools/sync_peer.py" serve --port "$PEER_PORT" --dir "$LIB" --journal "$JOURNAL" >"$PEER_LOG" 2>&1 &
PEER_PID=$!
REVERSE_SPEC="tcp:$PEER_PORT"
# 陷阱串里别再拼引号变量：'A'"$V"' B' 里那个空格会把词断开，尾巴上的单引号就变成未闭合了。
trap 'kill $PEER_PID 2>/dev/null; "$ADB" -s "$SERIAL" reverse --remove "$REVERSE_SPEC" >/dev/null 2>&1' EXIT

for _ in 1 2 3 4 5 6 7 8 9 10; do
  python3 -c "import socket;socket.create_connection(('127.0.0.1',$PEER_PORT),1).close()" >/dev/null 2>&1 && break
  sleep 1
done
python3 -c "import socket;socket.create_connection(('127.0.0.1',$PEER_PORT),1).close()" 2>/dev/null \
  || { echo "!! 对端没起来，日志：" >&2; cat "$PEER_LOG" >&2; exit 1; }

echo "[3/4] adb reverse 设备:$PEER_PORT -> 宿主机:$PEER_PORT"
"$ADB" -s "$SERIAL" reverse "tcp:$PEER_PORT" "tcp:$PEER_PORT" >/dev/null || fail "adb reverse 失败"

echo "[4/4] gradle connectedDebugAndroidTest -Dclass=$CLASS"
( cd "$REPO" && ./gradlew.bat :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CLASS" --console=plain ) || fail "互通测试失败（见上面 gradle 输出）"

sleep 2
echo
echo "=== 宿主机侧断言（设备看不见的部分）==="
python3 - "$JOURNAL" <<'PY'
import collections, json, sys
j = json.load(open(sys.argv[1], encoding="utf-8"))
reqs = collections.Counter(j.get("requests", []))
conns = j.get("connections", 0)
per = j.get("perConnection", [])
thumbs_total = reqs.get("get-thumb", 0)
# 一条连接上最多服务了多少张缩略图 —— 「批量取图只用一条连接」只能这么量：
# 总 connections 里还混着别的用例（每个用例各自探活/列相册）。
best = max((c.get("thumb", 0) for c in per), default=0)
print(f"对端相册 {j.get('librarySize')} 张 | 连接 {conns} 条 | 请求 {dict(reqs)}")
print(f"缩略图共 {thumbs_total} 张，单条连接最多一次服务 {best} 张")
print("收到的 Android hello:", j.get("hello_fields") or {})
ok = True
if (j.get("hello_fields") or {}).get("platform") != "android":
    print("!! hello 的 platform 不是 android —— 线格式没对上"); ok = False
if thumbs_total == 0:
    print("!! 一次缩略图都没服务到"); ok = False
elif best != thumbs_total:
    print(f"!! 缩略图散在 {sum(1 for c in per if c.get('thumb'))} 条连接上：批量取图退化成一图一连了"); ok = False
if not j.get("received"):
    print("!! 没收到 Android 发来的照片"); ok = False
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
PY
