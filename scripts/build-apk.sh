#!/usr/bin/env bash
# Builds the debug APK, picking a JDK that actually ships javac. Gradle needs a
# full JDK (a JRE-only install fails at compileDebugJavaWithJavac), and on many
# machines java's default points at a JRE.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

find_jdk() {
  local candidates=(
    "${JAVA_HOME:-}"
    /opt/android-studio/jbr
    /usr/lib/jvm/java-21-openjdk-amd64
    /usr/lib/jvm/java-25-openjdk-amd64
    /usr/lib/jvm/default-java
  )
  local jdk
  for jdk in "${candidates[@]}"; do
    if [ -n "$jdk" ] && [ -x "$jdk/bin/javac" ]; then
      printf '%s\n' "$jdk"
      return 0
    fi
  done
  if command -v javac >/dev/null 2>&1; then
    dirname "$(dirname "$(command -v javac)")"
    return 0
  fi
  return 1
}

if ! JDK="$(find_jdk)"; then
  echo "No JDK with javac found. Install one (for example openjdk-21-jdk) or set JAVA_HOME." >&2
  exit 1
fi

echo "==> JDK: $JDK ($("$JDK/bin/javac" -version 2>&1))"
echo "==> Building debug APK (this also builds the dashboard into the APK)"
(cd "$ROOT/android" && JAVA_HOME="$JDK" ./gradlew assembleDebug "$@")

echo "==> APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
