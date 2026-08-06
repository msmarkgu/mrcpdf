#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Platform detection ──────────────────────────────────────────────────────
case "$(uname -s)" in
  Darwin) OS="mac"  ;;
  Linux)  OS="linux" ;;
  *)      echo "Unsupported OS: $(uname -s)"; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64)  ARCH="x64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
esac

echo "Detected: $OS / $ARCH"

# ── Flag parsing ────────────────────────────────────────────────────────────
FORCE_DOWNLOAD=false
for arg in "$@"; do
  case "$arg" in
    --force) FORCE_DOWNLOAD=true ;;
  esac
done
if [ "$FORCE_DOWNLOAD" = true ]; then
  echo "Forcing re-download of all dependencies..."
fi

# ── 1. Download OpenJDK 21 LTS ──────────────────────────────────────────────

JDK_DIR="$SCRIPT_DIR/deps/jdk"
mkdir -p "$JDK_DIR"

if [ "$FORCE_DOWNLOAD" = true ] || [ -z "$(ls -A "$JDK_DIR" 2>/dev/null)" ]; then
  echo "Downloading OpenJDK 21 LTS for $OS/$ARCH..."

  API_URL="https://api.adoptium.net/v3/binary/latest/21/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  case "$OS" in
    mac)  JDK_ARCHIVE="openjdk21-mac.tar.gz" ;;
    linux) JDK_ARCHIVE="openjdk21-linux.tar.gz" ;;
  esac

  curl -fsSL -o "$SCRIPT_DIR/$JDK_ARCHIVE" "$API_URL"
  tar -xzf "$SCRIPT_DIR/$JDK_ARCHIVE" -C "$JDK_DIR" --strip-components=1 2>/dev/null || \
    tar -xzf "$SCRIPT_DIR/$JDK_ARCHIVE" -C "$JDK_DIR"
  rm -f "$SCRIPT_DIR/$JDK_ARCHIVE"
  echo "OpenJDK 21 downloaded to $JDK_DIR"
else
  echo "OpenJDK already present in $JDK_DIR"
fi

# Find actual JDK home (flat extraction with --strip-components=1)
if [ "$OS" = "mac" ]; then
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-*/Contents/Home 2>/dev/null | head -1 || true)
else
  JAVA_HOME_PATH=$(ls -d "$JDK_DIR"/jdk-* 2>/dev/null | head -1 || true)
fi
if [ -z "$JAVA_HOME_PATH" ]; then
  if [ -x "$JDK_DIR/bin/java" ]; then
    JAVA_HOME_PATH="$JDK_DIR"
  else
    echo "Cannot find JDK installation in $JDK_DIR"
    exit 1
  fi
fi
echo "JDK home: $JAVA_HOME_PATH"

# Set MRCPDF_JAVA_HOME for subsequent steps
export MRCPDF_JAVA_HOME="$JAVA_HOME_PATH"

# ── 2. Download Gradle 8.0.1 ─────────────────────────────────────────────────
GRADLE_DIR="$SCRIPT_DIR/deps/gradle"
GRADLE_VERSION="8.0.1"
if [ "$FORCE_DOWNLOAD" = true ] || [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  echo "Downloading Gradle $GRADLE_VERSION..."
  rm -rf "$GRADLE_DIR"
  mkdir -p "$GRADLE_DIR"
  curl -fsSL -o "$SCRIPT_DIR/gradle-bin.zip" \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -qo "$SCRIPT_DIR/gradle-bin.zip" -d "$SCRIPT_DIR/deps"
  # Move contents from versioned folder to deps/gradle
  if [ -d "$SCRIPT_DIR/deps/gradle-${GRADLE_VERSION}" ]; then
    mv "$SCRIPT_DIR/deps/gradle-${GRADLE_VERSION}"/* "$GRADLE_DIR/"
    rmdir "$SCRIPT_DIR/deps/gradle-${GRADLE_VERSION}"
  fi
  rm -f "$SCRIPT_DIR/gradle-bin.zip"
  chmod +x "$GRADLE_DIR/bin/gradle"
  echo "Gradle $GRADLE_VERSION downloaded to $GRADLE_DIR"
else
  echo "Gradle already present in $GRADLE_DIR"
fi

# ── 3. Download jbig2enc (Linux) ─────────────────────────────────────────────
# Linux/amd64: prebuilt Debian 11 (glibc 2.31) jbig2enc + libjbig2enc0 from
# SourceForge — runs on Ubuntu 20.04+ and Debian 11+ (backward-compatible).
# leptonica (liblept5) is NOT bundled: it's provided by the host on all target
# distros, and Debian 11's liblept pulls old-SONAME deps (libjpeg.so.62 etc.)
# missing on Ubuntu 24.04+, which would break the binary there.
JBIG2ENC_DIR="$SCRIPT_DIR/deps/jbig2enc/$OS"
JBIG2_BIN="$JBIG2ENC_DIR/jbig2.bin"

case "$OS" in
  linux)
    if [ "$FORCE_DOWNLOAD" = true ] || [ ! -x "$JBIG2_BIN" ]; then
      if [ "$ARCH" != "x64" ]; then
        echo "WARNING: No prebuilt jbig2enc for $ARCH; using CCITT G4 fallback."
      else
        echo "Downloading jbig2enc (Debian 11 prebuilt) for $OS/$ARCH..."
        TMPDIR=$(mktemp -d)
        if curl -fSL -o "$TMPDIR/jbig2enc.deb" \
              "https://sourceforge.net/projects/jbig2enc/files/deb/jbig2enc_0.29-deb11_amd64.deb/download" \
            && curl -fSL -o "$TMPDIR/libjbig2enc0.deb" \
              "https://sourceforge.net/projects/jbig2enc/files/deb/libjbig2enc0_0.29-deb11_amd64.deb/download"; then
          mkdir -p "$TMPDIR/extract"
          for deb in "$TMPDIR"/*.deb; do
            dpkg-deb -x "$deb" "$TMPDIR/extract" 2>/dev/null || true
          done
          if [ -f "$TMPDIR/extract/usr/bin/jbig2" ]; then
            mkdir -p "$JBIG2ENC_DIR/lib"
            cp "$TMPDIR/extract/usr/bin/jbig2" "$JBIG2_BIN"
            chmod +x "$JBIG2_BIN"
            # Bundle only libjbig2enc (not on host); leptonica comes from the host
            cp -a "$TMPDIR/extract/usr/lib/x86_64-linux-gnu/libjbig2enc"*.so* "$JBIG2ENC_DIR/lib/" 2>/dev/null || true
            echo "jbig2enc installed to $JBIG2ENC_DIR"
          else
            echo "WARNING: Could not extract jbig2enc; using CCITT G4 fallback."
          fi
        else
          echo "WARNING: Failed to download jbig2enc; using CCITT G4 fallback."
        fi
        rm -rf "$TMPDIR"
      fi
    fi
    ;;
esac

# Create the jbig2enc wrapper (Java looks for 'jbig2enc', binary is 'jbig2.bin')
if [ ! -f "$JBIG2ENC_DIR/jbig2enc" ] && [ -x "$JBIG2ENC_DIR/jbig2.bin" ]; then
  cat > "$JBIG2ENC_DIR/jbig2enc" <<WRAPPER
#!/bin/bash
SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
export LD_LIBRARY_PATH="\$SCRIPT_DIR/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "\$SCRIPT_DIR/jbig2.bin" "\$@"
WRAPPER
  chmod +x "$JBIG2ENC_DIR/jbig2enc"
  echo "Created jbig2enc wrapper at $JBIG2ENC_DIR/jbig2enc"
fi

# Runtime verification: if the wrapper can't run, treat as uninstalled (CCITT G4)
if [ -x "$JBIG2_BIN" ] && ! "$JBIG2ENC_DIR/jbig2enc" -h >/dev/null 2>&1; then
  echo "WARNING: jbig2enc failed to run; removing it and using CCITT G4 fallback."
  rm -rf "$JBIG2ENC_DIR"
fi

# ── 4. Download bundled CJK font for invisible text layer ────────────────────
# Noto Sans SC (SIL OFL 1.1) — covers Latin + CJK with TrueType outlines
# Required for PDFBox text subsetting (OTF/CFF fonts cause save errors).
FONTS_DIR="$SCRIPT_DIR/deps/fonts"
CJK_FONT="$FONTS_DIR/NotoSansSC-Regular.ttf"
CJK_FONT_URL="https://github.com/google/fonts/raw/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"
mkdir -p "$FONTS_DIR"
if [ "$FORCE_DOWNLOAD" = true ] || [ ! -f "$CJK_FONT" ]; then
  echo "Downloading Noto Sans SC CJK font (~34 MB)..."
  curl -fsSL -o "$CJK_FONT" "$CJK_FONT_URL"
  echo "Noto Sans SC downloaded to $CJK_FONT ($(du -h "$CJK_FONT" | cut -f1))"
else
  echo "Noto Sans SC already present in $FONTS_DIR"
fi

# ── 5. Build project ────────────────────────────────────────────────────────
echo ""
echo "── Bootstrap complete ──"
echo "Run:  ./run.sh input.pdf -o output.pdf"
echo "Build: ./gradlew build"
echo ""
echo "Flags: --force  force re-download of all dependencies"
