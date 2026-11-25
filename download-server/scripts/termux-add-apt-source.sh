#!/usr/bin/env sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
REPO="${DOWNLOAD_SERVER_REPO:-NihilDigit/piko}"
CHANNEL="${1:-stable}"
INSTALL=1

usage() {
  cat <<'EOF'
Usage: termux-add-apt-source.sh [stable|test] [--repo owner/repo] [--no-install]

Configure Piko's Termux apt source, then run pkg update and install
piko-download-server. Use --no-install to only write the source file.

  stable  https://github.com/<repo>/releases/latest/download
  test    https://github.com/<repo>/releases/download/termux-test

The source uses [trusted=yes]; only add it for a repository you trust.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    stable|test)
      CHANNEL="$1"
      shift
      ;;
    --repo)
      REPO="$2"
      shift 2
      ;;
    --no-install)
      INSTALL=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ ! -d "$PREFIX" ]; then
  echo "Error: Termux prefix '$PREFIX' not found. Please run this inside Termux." >&2
  exit 1
fi

case "$CHANNEL" in
  stable)
    BASE_URL="https://github.com/$REPO/releases/latest/download"
    ;;
  test)
    BASE_URL="https://github.com/$REPO/releases/download/termux-test"
    ;;
  *)
    echo "Error: channel must be stable or test" >&2
    exit 2
    ;;
esac

SOURCE_DIR="$PREFIX/etc/apt/sources.list.d"
SOURCE_FILE="$SOURCE_DIR/piko-download-server.list"
mkdir -p "$SOURCE_DIR"
cat > "$SOURCE_FILE" <<EOF
# Piko download-server ($CHANNEL), repo: $REPO
deb [trusted=yes] $BASE_URL ./
EOF

echo "==> Wrote $SOURCE_FILE"
echo "    deb [trusted=yes] $BASE_URL ./"
echo ""
echo "Next:"
echo "  pkg update"
echo "  pkg install piko-download-server"
echo "  pkg upgrade piko-download-server    # 以后升级"

if [ "$INSTALL" = "1" ]; then
  if ! command -v pkg >/dev/null 2>&1; then
    echo "Error: pkg not found. Run this inside Termux." >&2
    exit 1
  fi
  pkg update
  pkg install -y piko-download-server
fi
