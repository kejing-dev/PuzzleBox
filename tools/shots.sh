#!/usr/bin/env bash
# QA state captures: tools/shots.sh OUTDIR LANG STATE... (each: goto, wait, capture). DEV = the borrowed emulator.
#   special names: S11 S12 S13 (pages), S13exit (exit confirm), S15 (panel closed), S11g<n> (boxes group n)
set -u
OUT="$1"; LANG_="$2"; shift 2
K="$(dirname "$0")/pb.sh"
mkdir -p "$OUT"
"$K" cmd lang "$LANG_"; "$K" cmd welcome
for S in "$@"; do
  "$K" cmd hold off; "$K" cmd page play
  case "$S" in
    S11g*) "$K" cmd group "${S#S11g}" ;;
    S11) "$K" cmd page boxes ;;
    S12) "$K" cmd page rules ;;
    S13) "$K" cmd page settings ;;
    S13exit) "$K" cmd exitask ;;
    S13clear) "$K" cmd clearask ;;
    S15) "$K" cmd goto S02; sleep 3; "$K" cmd closepanel ;;
    *) "$K" cmd goto "$S" ;;
  esac
  sleep "${WAIT:-6}"
  for try in 1 2 3; do "$K" shot "$OUT/$S-$LANG_.png" 2>/dev/null && [ -s "$OUT/$S-$LANG_.png" ] && break; sleep 3; done
  case "$S" in S15) "$K" cmd reopen; sleep 4 ;; S13exit|S13clear) "$K" cmd page play ;; esac
  echo "$S $LANG_"
done
