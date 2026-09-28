#!/usr/bin/env bash
# Helpers for testing on the Linux (Ubuntu, X11) box over SSH.
#   tools/linux.sh sync                       copy mod/, obs-plugin/ and tools/ to ~/layercast-dev
#   tools/linux.sh run '<shell command>'      run a command (in ~/layercast-dev)
#   tools/linux.sh gui <name> '<command>'     start a command detached in the logged-on user's X11 session;
#                                             output goes to ~/layercast-dev/<name>.log, "EXIT n" is appended
#   tools/linux.sh get <remote-path> <local>
set -euo pipefail
HOST="${LAYERCAST_LINUX_HOST:-meteor@192.168.101.237}"
SSH=(ssh -o BatchMode=yes -o LogLevel=ERROR "$HOST")
DIR='~/layercast-dev'
case "$1" in
  sync)
    ROOT="$(cd "$(dirname "$0")/.." && pwd)"
    "${SSH[@]}" "mkdir -p $DIR"
    rsync -az --delete -e "ssh -o BatchMode=yes -o LogLevel=ERROR" \
      --exclude '.gradle/' --exclude 'build/' --exclude '/mod/run/' --exclude '.kotlin/' --exclude 'build-*/' --exclude '.DS_Store' \
      "$ROOT/mod" "$ROOT/obs-plugin" "$ROOT/tools" "$HOST:layercast-dev/"
    echo synced ;;
  run) "${SSH[@]}" "cd $DIR && $2" ;;
  gui)
    # The session's X server and authority file (GNOME on X11 via GDM).
    # Fully detached (own session, no inherited descriptors), so the SSH connection returns right away.
    ssh -n -o BatchMode=yes -o LogLevel=ERROR "$HOST" "cd $DIR && DISPLAY=:0 XAUTHORITY=/run/user/\$(id -u)/gdm/Xauthority \
      XDG_RUNTIME_DIR=/run/user/\$(id -u) DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/\$(id -u)/bus \
      setsid -f bash -c '$3; echo EXIT \$? >> $2.log' > $2.log 2>&1 < /dev/null; echo started $2" ;;
  get) scp -q -o BatchMode=yes -o LogLevel=ERROR "$HOST:$2" "$3" ;;
  *) echo "unknown command $1" >&2; exit 2 ;;
esac
