#!/usr/bin/env bash
# Builds the web dashboard straight into the Android app's assets so the APK
# carries its own remote control UI.
set -euo pipefail
cd "$(dirname "$0")/.."

DASHBOARD="dashboard"
ASSETS="android/app/src/main/assets/dashboard"

if [ ! -d "$DASHBOARD/node_modules" ]; then
  echo "==> Installing dashboard dependencies"
  if command -v bun >/dev/null 2>&1; then
    (cd "$DASHBOARD" && bun install)
  else
    (cd "$DASHBOARD" && npm install)
  fi
fi

# Vite 8 ships its Rust bundler as a platform specific optional dependency. npm
# occasionally skips it on a cold cache; install it explicitly when it is missing
# so the build does not fail with "cannot find rolldown binding".
if [ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" = "x86_64" ]; then
  if [ ! -d "$DASHBOARD/node_modules/@rolldown/binding-linux-x64-gnu" ]; then
    echo "==> Adding the missing rolldown native binding"
    (cd "$DASHBOARD" && npm install --no-save --no-audit --no-fund @rolldown/binding-linux-x64-gnu@1.2.14)
  fi
fi

echo "==> Building dashboard"
if command -v bun >/dev/null 2>&1; then
  (cd "$DASHBOARD" && bun run build)
else
  (cd "$DASHBOARD" && npm run build)
fi
if [ ! -f "$ASSETS/index.html" ]; then
  echo "!! Dashboard build produced no index.html in $ASSETS" >&2
  exit 1
fi

echo "==> Dashboard ready in $ASSETS"
