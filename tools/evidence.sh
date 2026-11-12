#!/usr/bin/env bash
# Re-shoots the ui_gates evidence after a build (all later than the APK): first-screen / s00, the main loop with real
# panel clicks (newgame-confirm, settings-firstscreen, exit-recents), English cold start (default-view), input replay.
#   DEV=emulator-5560 tools/evidence.sh "<factory>/apps/puzzle-box/build"
set -u
B="$1"; DEV="${DEV:-emulator-5560}"; export DEV
ADB="$HOME/Library/Android/sdk/platform-tools/adb"; K="$(dirname "$0")/pb.sh"; PKG=com.kejing.puzzlebox
P() { pico-cli interact act --package $PKG --device "$DEV" --action click --format json --text "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print('click', d.get('target',{}).get('name'), d.get('execution'))"; }
L() { "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
mkdir -p "$B"
"$ADB" -s "$DEV" shell pm clear $PKG >/dev/null; "$ADB" -s "$DEV" logcat -c
L; sleep 2; "$K" shot "$B/s00-loading.png"; sleep 8; "$K" shot "$B/first-screen.png"
P "开始装"; sleep 2
"$K" cmd tap pc:0; sleep 1.5; "$K" cmd rot pitch; sleep 1; "$K" cmd drag "0 0"; sleep 3
P "提示 关"; sleep 2; P "悔一步"; sleep 2; P "帮我放一块"; sleep 3
P "重来"; sleep 2; "$K" shot "$B/newgame-confirm.png"; P "算了"; sleep 1
P "设置"; sleep 2; "$K" shot "$B/settings-firstscreen.png"
P "退出应用"; sleep 2; P "退出"; sleep 4
"$ADB" -s "$DEV" shell dumpsys activity recents > "$B/exit-recents.txt"
echo "recents lines with package (Recent #): $(grep 'Recent #' "$B/exit-recents.txt" | grep -c $PKG)"
L; sleep 8; "$K" cmd hint off; "$K" cmd level M4; sleep 1; "$K" cmd place 2; "$K" cmd lang en; sleep 2
"$ADB" -s "$DEV" shell am force-stop $PKG; sleep 2; L; sleep 10; "$K" shot "$B/default-view.png"
"$K" cmd level W2; sleep 2
for src in hand-ray eye-hand controller; do
  "$K" cmd replay "pc:0 $src"; sleep 1.5; "$K" cmd replay "tab:yaw $src"; sleep 1; "$K" cmd replay "pad:0:0 $src"; sleep 2; "$K" cmd undo; sleep 1.5
done
"$ADB" -s "$DEV" shell run-as $PKG cat files/input-log.txt | grep -E "REPLAY|INPUT" > "$B/input-replay.txt"
echo "replay lines $(wc -l < "$B/input-replay.txt"), square=- $(grep -c 'square=-' "$B/input-replay.txt")"
"$K" cmd lang zh
"$ADB" -s "$DEV" logcat -d | grep -c OVERFLOW
