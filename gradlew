#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for this project (uses the distribution in gradle-wrapper.properties).
set -eu
VERSION="8.9"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/irstudiocam-bootstrap"
DIST="$BASE/gradle-$VERSION"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$VERSION-bin.zip"
  echo "Downloading Gradle $VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  else
    echo "curl is required to bootstrap Gradle." >&2
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$BASE"
  else
    echo "unzip is required to bootstrap Gradle." >&2
    exit 1
  fi
fi
exec "$DIST/bin/gradle" "$@"
