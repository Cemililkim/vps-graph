#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this uninstaller explicitly with sudo." >&2
  exit 1
fi
if [ "$#" -gt 1 ]; then
  echo "Usage: sudo ./uninstall.sh [username]" >&2
  exit 1
fi
USER_NAME=${1:-vpsgraph}
if ! printf '%s\n' "$USER_NAME" | grep -Eq '^[a-z_][a-z0-9_-]*[$]?$'; then
  echo "Invalid target username." >&2
  exit 1
fi

HELPER_TARGET=/usr/local/libexec/vpsgraph-docker-scan
SUDOERS_TARGET="/etc/sudoers.d/vpsgraph-docker-scan-$USER_NAME"
EXPECTED_RULE="$USER_NAME ALL=(root) NOPASSWD: $HELPER_TARGET \"\""

if [ -e "$SUDOERS_TARGET" ]; then
  if [ ! -f "$SUDOERS_TARGET" ] || [ "$(cat "$SUDOERS_TARGET")" != "$EXPECTED_RULE" ]; then
    echo "Refusing to remove an unexpected sudoers file." >&2
    exit 1
  fi
fi
if [ -e "$HELPER_TARGET" ]; then
  if ! grep -Fq "Narrow, read-only Docker metadata helper for VPS Graph." "$HELPER_TARGET"; then
    echo "Refusing to remove an unexpected helper file." >&2
    exit 1
  fi
fi
if [ -e "$SUDOERS_TARGET" ]; then
  rm -f "$SUDOERS_TARGET"
fi
if [ -e "$HELPER_TARGET" ]; then
  rm -f "$HELPER_TARGET"
fi

echo "Removed VPS Graph Docker helper artifacts for $USER_NAME."
