#!/bin/sh
set -eu
VERSION="9.2.1"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-${VERSION}-bin/manual"
DIST="$BASE/gradle-${VERSION}"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-${VERSION}-bin.zip"
  if [ ! -f "$ZIP" ]; then
    echo "Downloading Gradle ${VERSION}..."
    curl -fL "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" -o "$ZIP"
  fi
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$DIST/bin/gradle" "$@"
