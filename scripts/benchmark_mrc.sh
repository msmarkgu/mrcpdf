#!/usr/bin/env bash
# Benchmark MRC compression effect: report output file size and time
# for MRC-compressed real-world test PDFs.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PDF_DIR="$REPO_DIR/tests/test-files/real-world"
JAR="$REPO_DIR/build/mrcpdf.jar"
JAVA="$REPO_DIR/deps/jdk/bin/java"
if [ ! -x "$JAVA" ]; then
  # Try PATH
  JAVA=$(command -v java 2>/dev/null || echo "")
fi
if [ ! -x "$JAVA" ]; then
  echo "ERROR: No Java 21 found. Run bootstrap.sh or set PATH."
  exit 1
fi
REPORT_DIR="$REPO_DIR/eval"

# The input corpus is not in the repository (it is large and permissively
# licensed per-document, not per-collection), so it has to be pointed at.
# An explicit override beats a hard-coded path that is absent on every clone.
PDF_DIR="${MRCPDF_BENCH_DIR:-$PDF_DIR}"

# Rebuild when the JAR is missing or older than any source file. Checking only
# for existence is not enough: a stale JAR silently produces numbers for code
# that is no longer in the tree, and the resulting "input size / MRC size"
# ratios look entirely plausible.
if [ ! -f "$JAR" ] || [ -n "$(find "$REPO_DIR/src/main" "$REPO_DIR/build.gradle.kts" \
      -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "Building fat JAR ..."
  "$REPO_DIR"/gradlew shadowJar -q
fi

RUN_TS=$(date '+%Y-%m-%d %H:%M')
FILTER="${1:-}"

mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/report-mrc.md"

TMPDIR="$REPO_DIR/temp/benchmark-mrc"
mkdir -p "$TMPDIR"

# Byte size of a file, portably. `stat --format` is GNU-only and fails on
# macOS/BSD, which would silently record a 0-byte size and a bogus ratio.
file_size() {
  local f="$1" n
  n=$(stat -c %s "$f" 2>/dev/null) || n=$(stat -f %z "$f" 2>/dev/null) || n=$(wc -c < "$f" 2>/dev/null)
  echo "${n//[[:space:]]/}"
}

if [ ! -d "$PDF_DIR" ]; then
  echo "ERROR: input corpus not found: $PDF_DIR"
  echo "       It is not part of the repository. Set MRCPDF_BENCH_DIR to the"
  echo "       directory holding your PDFs, or pass a path as the first"
  echo "       argument:"
  echo "         MRCPDF_BENCH_DIR=/path/to/pdfs $0"
  echo "         $0 /path/to/one.pdf"
  exit 1
fi

if [ ! -f "$REPORT" ] || [ ! -s "$REPORT" ]; then
  cat > "$REPORT" <<'EOF'
# MRC Compression Benchmark
EOF
  echo "" >> "$REPORT"
  printf "| %-35s | %-16s | %5s | %10s | %10s | %10s | %7s |\n" \
    "File" "Date" "Pages" "Input size" "MRC size" "MRC time" "Ratio" >> "$REPORT"
  printf "|%s|%s|%s|%s|%s|%s|%s|\n" \
    "-----------------------------------" \
    "------------------" \
    "-------" \
    "----------" \
    "----------" \
    "----------" \
    "-------" >> "$REPORT"
fi

if [ -n "$FILTER" ] && [[ "$FILTER" == */* ]]; then
  PDFS=("$FILTER")
else
  PDFS=("$PDF_DIR"/${FILTER:-*.pdf})
fi

for pdf in "${PDFS[@]}"; do
  if [ ! -f "$pdf" ]; then
    echo "  File not found: $pdf"
    continue
  fi
  name=$(basename "$pdf")
  pages=$(pdfinfo "$pdf" 2>/dev/null | grep 'Pages:' | awk '{print $2}')
  pages=${pages:-?}

  echo "  Processing $name ($pages pages) ..."

  base="${name%.pdf}"
  tmp_mrc="$TMPDIR/mrc-${base}.pdf"

  start=$(date +%s.%N)
  "$JAVA" -jar "$JAR" "$pdf" -o "$tmp_mrc"
  end=$(date +%s.%N)
  mrc_time=$(echo "$end - $start" | bc 2>/dev/null || echo "0")
  mrc_size=$(file_size "$tmp_mrc")
  in_size=$(file_size "$pdf")

  # The ratio the README advertises is measured against the input file. Without
  # the input size there is nothing to compare against, which is why the old
  # report could never substantiate the headline number.
  if [ "$in_size" -gt 0 ] && [ "$mrc_size" -gt 0 ]; then
    ratio=$(awk -v i="$in_size" -v o="$mrc_size" 'BEGIN { printf "%.2fx", i / o }')
  else
    ratio="-"
  fi

  printf "| %-35s | %-16s | %5s | %8d KB | %8d KB | %8.1fs | %7s |\n" \
    "$name" "$RUN_TS" "$pages" \
    "$(( in_size / 1024 ))" \
    "$(( mrc_size / 1024 ))" \
    "$mrc_time" "$ratio" >> "$REPORT"

done

echo "MRC benchmark complete."
cat "$REPORT"
