#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

JAVA="$SCRIPT_DIR/deps/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
  echo "No JDK found at $JAVA. Run bootstrap.sh first."
  exit 1
fi

FAT_JAR="$SCRIPT_DIR/build/mrcpdf.jar"
if [ ! -f "$FAT_JAR" ]; then
  JAVA_HOME="$SCRIPT_DIR/deps/jdk" "$SCRIPT_DIR/gradlew" build
fi

echo "Using JDK: $JAVA"

# Run from the repo root so bundled deps (jbig2enc, fonts, settings.jsonc)
# resolve correctly regardless of the calling directory.
cd "$SCRIPT_DIR"

exec "$JAVA" -Xmx${MRCPDF_HEAP:-2g} -jar "$FAT_JAR" \
  "$@"
