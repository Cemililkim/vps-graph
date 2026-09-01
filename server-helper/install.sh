#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer explicitly with sudo." >&2
  exit 1
fi
if [ "$(uname -s)" != "Linux" ]; then
  echo "VPS Graph Docker helper is supported only on Linux." >&2
  exit 1
fi
if [ "$#" -ne 1 ]; then
  echo "Usage: sudo ./install.sh <username>" >&2
  exit 1
fi

USER_NAME=$1
if ! printf '%s\n' "$USER_NAME" | grep -Eq '^[a-z_][a-z0-9_-]*[$]?$'; then
  echo "Invalid target username." >&2
  exit 1
fi
if ! id "$USER_NAME" >/dev/null 2>&1; then
  echo "Target user does not exist." >&2
  exit 1
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HELPER_SOURCE="$SCRIPT_DIR/vpsgraph_docker_scan.py"
HELPER_TARGET=/usr/local/libexec/vpsgraph-docker-scan
SUDOERS_TARGET="/etc/sudoers.d/vpsgraph-docker-scan-$USER_NAME"
PYTHON=/usr/bin/python3
VISUDO=$(command -v visudo || true)

if [ ! -x "$PYTHON" ] || [ ! -f "$HELPER_SOURCE" ]; then
  echo "/usr/bin/python3 and the helper source are required." >&2
  exit 1
fi
if [ -z "$VISUDO" ]; then
  echo "visudo is required to validate the sudoers policy." >&2
  exit 1
fi
"$PYTHON" - "$HELPER_SOURCE" <<'PY'
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_text(encoding="utf-8")
compile(source, sys.argv[1], "exec")
PY

TRUSTED_DOCKER=
for candidate in /usr/bin/docker /usr/local/bin/docker; do
  if [ -e "$candidate" ]; then
    if "$PYTHON" - "$candidate" <<'PY'
import os
import stat
import sys
p = sys.argv[1]
s = os.stat(p)
sys.exit(0 if stat.S_ISREG(s.st_mode) and s.st_uid == 0 and not (s.st_mode & (stat.S_IWGRP | stat.S_IWOTH)) and (s.st_mode & 0o111) and os.access(p, os.X_OK) else 1)
PY
    then
      TRUSTED_DOCKER=$candidate
      break
    fi
  fi
done
if [ -z "$TRUSTED_DOCKER" ]; then
  echo "No trusted Docker executable was found." >&2
  exit 1
fi

TEMP_SUDOERS=$(mktemp /etc/sudoers.d/.vpsgraph-docker-scan.XXXXXX)
cleanup() { rm -f "$TEMP_SUDOERS"; }
trap cleanup EXIT HUP INT TERM
printf '%s ALL=(root) NOPASSWD: %s ""\n' "$USER_NAME" "$HELPER_TARGET" > "$TEMP_SUDOERS"
chown root:root "$TEMP_SUDOERS"
chmod 0440 "$TEMP_SUDOERS"
"$VISUDO" -cf "$TEMP_SUDOERS"
if [ ! -d /usr/local/libexec ]; then
  install -d -o root -g root -m 0755 /usr/local/libexec
fi
install -o root -g root -m 0755 "$HELPER_SOURCE" "$HELPER_TARGET"
mv -f "$TEMP_SUDOERS" "$SUDOERS_TARGET"
trap - EXIT HUP INT TERM

echo "Installed $HELPER_TARGET and the single-command sudoers policy for $USER_NAME."
