#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "No SHA-256 utility found (sha256sum or shasum required)." >&2
    exit 1
  fi
}

if [ -f "$TARGET" ]; then
  ACTUAL=$(checksum "$TARGET")
  [ "$ACTUAL" = "$EXPECTED" ] || {
    echo "Existing Gradle wrapper JAR has an unexpected checksum." >&2
    exit 1
  }
  exit 0
fi

mkdir -p "$(dirname "$TARGET")"
TMP="$TARGET.tmp"
trap 'rm -f "$TMP"' EXIT HUP INT TERM

if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 --connect-timeout 20 "$URL" -o "$TMP"
elif command -v wget >/dev/null 2>&1; then
  wget --https-only --tries=3 -O "$TMP" "$URL"
else
  echo "curl or wget is required to bootstrap the Gradle wrapper." >&2
  exit 1
fi

ACTUAL=$(checksum "$TMP")
[ "$ACTUAL" = "$EXPECTED" ] || {
  echo "Downloaded Gradle wrapper checksum mismatch." >&2
  exit 1
}

mv "$TMP" "$TARGET"
trap - EXIT HUP INT TERM
printf '%s\n' "Verified Gradle wrapper installed at $TARGET"
