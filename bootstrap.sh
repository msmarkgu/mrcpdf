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

# Derive Debian architecture for dynamic package URLs
DEB_ARCH=$(dpkg --print-architecture 2>/dev/null || echo "amd64")
case "$DEB_ARCH" in
  amd64) LIB_ARCH="x86_64-linux-gnu" ;;
  arm64) LIB_ARCH="aarch64-linux-gnu" ;;
  *)     echo "Warning: unknown Debian arch '$DEB_ARCH', assuming amd64"
         DEB_ARCH="amd64"; LIB_ARCH="x86_64-linux-gnu" ;;
esac

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
# Linux: extracted from Debian/Ubuntu packages (jbig2enc + liblept).
JBIG2ENC_DIR="$SCRIPT_DIR/deps/jbig2enc/$OS"
mkdir -p "$JBIG2ENC_DIR/lib"

if [ "$FORCE_DOWNLOAD" = true ] || [ ! -x "$JBIG2ENC_DIR/jbig2.bin" ]; then
  echo "Downloading jbig2enc for $OS/$ARCH..."
  case "$OS" in
    linux)
      TMPDIR=$(mktemp -d)
      if command -v apt-get &>/dev/null; then
        cd "$TMPDIR"
        apt-get download jbig2 libjbig2enc0t64 liblept5 2>/dev/null || {
          curl -fsSL -o jbig2.deb \
            "http://archive.ubuntu.com/ubuntu/pool/universe/j/jbig2enc/jbig2_0.29-2.1build1_${DEB_ARCH}.deb"
          curl -fsSL -o libjbig2enc0t64.deb \
            "http://archive.ubuntu.com/ubuntu/pool/universe/j/jbig2enc/libjbig2enc0t64_0.29-2.1build1_${DEB_ARCH}.deb"
          curl -fsSL -o liblept5.deb \
            "http://archive.ubuntu.com/ubuntu/pool/main/l/leptonlib/liblept5_1.82.0-3build3_${DEB_ARCH}.deb"
        }
        for deb in "$TMPDIR"/*.deb; do [ -f "$deb" ] && dpkg-deb -x "$deb" "$TMPDIR/extract" 2>/dev/null; done
        if [ -f "$TMPDIR/extract/usr/bin/jbig2" ]; then
          cp "$TMPDIR/extract/usr/bin/jbig2" "$JBIG2ENC_DIR/jbig2.bin"
          cp -a "$TMPDIR/extract/usr/lib/"*.so* "$JBIG2ENC_DIR/lib/" 2>/dev/null || true
          cp -a "$TMPDIR/extract/usr/lib/${LIB_ARCH}/"*.so* "$JBIG2ENC_DIR/lib/" 2>/dev/null || true
          chmod +x "$JBIG2ENC_DIR/jbig2.bin"
          echo "jbig2enc installed to $JBIG2ENC_DIR"
        else
          echo "WARNING: Could not extract jbig2enc."
        fi
      fi
      ;;
  esac
  rm -rf "$TMPDIR"
fi

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
