#!/usr/bin/env bash
# Helpers for testing on the Windows box over SSH.
#   tools/win.sh run  '<cmd.exe command>'        run a command in the SSH (non-interactive) session
#   tools/win.sh ps   '<powershell command>'     run PowerShell
#   tools/win.sh put  <local> <remote-path>      copy a file (remote path with forward slashes)
#   tools/win.sh get  <remote-path> <local>
#   tools/win.sh gui  <name> '<cmd.exe command>' start a command in the logged-on user's desktop session
set -euo pipefail
HOST="${LAYERCAST_WIN_HOST:-delvin@192.168.3.241}"
PORT="${LAYERCAST_WIN_PORT:-22}"
SSH=(ssh -o BatchMode=yes -o LogLevel=ERROR -p "$PORT" "$HOST")
filter() { grep -v -i "post-quantum\|store now\|server may need" || true; }
case "$1" in
  run) "${SSH[@]}" "chcp 65001 >nul & $2" 2>&1 | tr -d '\r' | filter ;;
  ps)
    # -EncodedCommand avoids every cmd.exe quoting problem.
    enc=$(printf '%s' "[Console]::OutputEncoding=[Text.Encoding]::UTF8; \$ProgressPreference='SilentlyContinue'; $2" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
    "${SSH[@]}" "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand $enc" 2>&1 | tr -d '\r' | filter ;;
  put) scp -O -T -q -o LogLevel=ERROR -P "$PORT" "$2" "$HOST:\"$3\"" 2>&1 | filter ;;
  get) scp -O -T -q -o LogLevel=ERROR -P "$PORT" "$HOST:\"$2\"" "$3" 2>&1 | filter ;;
  gui)
    # /IT: only runs while the user is logged on, inside their interactive desktop session.
    # The task is deleted right after it started (the launched process keeps running), so nothing is left
    # scheduled on the machine.
    "${SSH[@]}" "schtasks /create /tn \"LayerCast\\$2\" /tr \"cmd /c $3\" /sc once /st 23:59 /it /f >nul && schtasks /run /tn \"LayerCast\\$2\" >nul && schtasks /delete /tn \"LayerCast\\$2\" /f >nul && echo started $2" 2>&1 | tr -d '\r' | filter ;;
  *) echo "unknown command $1" >&2; exit 2 ;;
esac
