#!/usr/bin/env bash
# Copies mod sources (+ tools) to the Windows test box and, with --plugin, redeploys the OBS plugin
# (OBS must not be running for the DLL to be replaced).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cd "$ROOT"
COPYFILE_DISABLE=1 tar --exclude='./mod/.gradle' --exclude='./mod/build' --exclude='./mod/run' \
  --exclude='./mod/versions/*/build' --exclude='./mod/versions/*/.gradle' --exclude='./mod/buildSrc/build' \
  --exclude='./mod/buildSrc/.gradle' --exclude='./mod/.kotlin' -cf "$TMP/mod.tar" ./mod ./tools
# Relative remote paths are relative to the SSH user's home directory.
tools/win.sh run 'if not exist "%USERPROFILE%\layercast-dev" mkdir "%USERPROFILE%\layercast-dev"'
tools/win.sh put "$TMP/mod.tar" layercast-dev/mod.tar
tools/win.sh run 'cd /d "%USERPROFILE%\layercast-dev" && tar -xf mod.tar && del mod.tar && echo synced sources'
if [ "${1:-}" = "--plugin" ]; then
  tools/win.sh put obs-plugin/build-windows/obs-layercast.dll C:/ProgramData/obs-studio/plugins/obs-layercast/bin/64bit/obs-layercast.dll
  tar -C obs-plugin/data -cf "$TMP/data.tar" .
  tools/win.sh put "$TMP/data.tar" C:/ProgramData/obs-studio/plugins/obs-layercast/data.tar
  tools/win.sh run 'cd /d C:\ProgramData\obs-studio\plugins\obs-layercast\data && tar -xf ..\data.tar && del ..\data.tar && echo deployed plugin'
fi
