#!/usr/bin/env sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
REPO="${DOWNLOAD_SERVER_REPO:-NihilDigit/piko}"
TAG="${DOWNLOAD_SERVER_TAG:-latest}"
HOST="${DOWNLOAD_SERVER_HOST:-0.0.0.0}"
PORT="${DOWNLOAD_SERVER_PORT:-7788}"
DATA_DIR="${DOWNLOAD_SERVER_DATA_DIR:-$HOME_DIR/.pikpak/downloader}"
DEB_PATH=""
BIN_PATH=""
ENABLE_BOOT=0

usage() {
  cat <<'EOF'
Usage: install-termux.sh [options]

Options:
  --deb <path>       Install from a local Termux .deb package
  --bin <path>       Install from a local android-arm64 binary
  --tag <tag>        GitHub Release tag to download (default: latest)
  --repo <owner/repo> GitHub repository (default: NihilDigit/piko)
  --host <addr>      Listen host written to $PREFIX/etc/default/download-server (default: 0.0.0.0)
  --port <port>      Listen port written to $PREFIX/etc/default/download-server (default: 7788)
  --data-dir <path>  Data directory written to $PREFIX/etc/default/download-server
  --boot             Also create ~/.termux/boot/start-download-server.sh for Termux:Boot
  -h, --help         Show this help
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --deb)
      DEB_PATH="$2"
      shift 2
      ;;
    --bin)
      BIN_PATH="$2"
      shift 2
      ;;
    --tag)
      TAG="$2"
      shift 2
      ;;
    --repo)
      REPO="$2"
      shift 2
      ;;
    --host)
      HOST="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --data-dir)
      DATA_DIR="$2"
      shift 2
      ;;
    --boot)
      ENABLE_BOOT=1
      shift 1
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
  echo "Error: Termux prefix '$PREFIX' not found. Please run this script inside Termux." >&2
  exit 1
fi

find_local_artifact() {
  pattern="$1"
  for dir in "." "$(dirname "$0")"; do
    for candidate in "$dir"/$pattern; do
      if [ -f "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done
  done
  return 1
}

if [ -z "$DEB_PATH" ] && [ -z "$BIN_PATH" ]; then
  if found_deb="$(find_local_artifact 'download-server*termux-aarch64.deb' 2>/dev/null)"; then
    DEB_PATH="$found_deb"
  elif found_bin="$(find_local_artifact 'download-server*android-arm64' 2>/dev/null)"; then
    BIN_PATH="$found_bin"
  fi
fi

write_env_config() {
  mkdir -p "$PREFIX/etc/default"
  cat > "$PREFIX/etc/default/download-server" <<EOF
# PikPak Download Server environment configuration for Termux
DOWNLOAD_SERVER_HOST="$HOST"
DOWNLOAD_SERVER_PORT="$PORT"
DOWNLOAD_SERVER_DATA_DIR="$DATA_DIR"
EOF
}

install_service_files() {
  SVC_DIR="$PREFIX/var/service/download-server"
  LOG_DIR="$PREFIX/var/log/sv/download-server"
  mkdir -p "$SVC_DIR/log" "$LOG_DIR"

  cat > "$SVC_DIR/run" <<EOF
#!$PREFIX/bin/sh
exec 2>&1
export PREFIX="$PREFIX"
export HOME="$HOME_DIR"
if [ -f "\$PREFIX/etc/default/download-server" ]; then
  . "\$PREFIX/etc/default/download-server"
fi
export DOWNLOAD_SERVER_HOST="\${DOWNLOAD_SERVER_HOST:-0.0.0.0}"
export DOWNLOAD_SERVER_PORT="\${DOWNLOAD_SERVER_PORT:-7788}"
export DOWNLOAD_SERVER_DATA_DIR="\${DOWNLOAD_SERVER_DATA_DIR:-\$HOME/.pikpak/downloader}"
exec "\$PREFIX/bin/download-server"
EOF
  chmod 755 "$SVC_DIR/run"

  cat > "$SVC_DIR/log/run" <<EOF
#!$PREFIX/bin/sh
svlogger="$PREFIX/share/termux-services/svlogger"
exec "\${svlogger}" "\$@"
EOF
  chmod 755 "$SVC_DIR/log/run"
}

ensure_termux_services() {
  if [ ! -f "$PREFIX/share/termux-services/svlogger" ]; then
    echo "==> Installing termux-services..."
    pkg update -y
    pkg install -y termux-services
  fi
}

download_from_release() {
  if ! command -v curl >/dev/null 2>&1; then
    pkg install -y curl
  fi
  TMP_DIR="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/piko-dl.XXXXXX")"
  trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

  if [ "$TAG" = "latest" ]; then
    API_URL="https://api.github.com/repos/$REPO/releases/latest"
  else
    API_URL="https://api.github.com/repos/$REPO/releases/tags/$TAG"
  fi

  echo "==> Querying release from $API_URL..."
  RELEASE_JSON="$(curl -fsSL "$API_URL")"

  DEB_URL="$(printf '%s\n' "$RELEASE_JSON" | grep -oE 'https://[^"]+download-server-[^"]*termux-aarch64\.deb' | head -n 1 || true)"
  if [ -n "$DEB_URL" ]; then
    DEB_PATH="$TMP_DIR/download-server-termux-aarch64.deb"
    echo "==> Downloading $DEB_URL..."
    curl -fL --progress-bar -o "$DEB_PATH" "$DEB_URL"
    return 0
  fi

  BIN_URL="$(printf '%s\n' "$RELEASE_JSON" | grep -oE 'https://[^"]+download-server-[^"]*android-arm64' | head -n 1 || true)"
  if [ -n "$BIN_URL" ]; then
    BIN_PATH="$TMP_DIR/download-server-android-arm64"
    echo "==> Downloading $BIN_URL..."
    curl -fL --progress-bar -o "$BIN_PATH" "$BIN_URL"
    return 0
  fi

  echo "Error: Could not find Termux .deb or android-arm64 binary in release ($TAG)." >&2
  exit 1
}

if [ -z "$DEB_PATH" ] && [ -z "$BIN_PATH" ]; then
  download_from_release
fi

if [ -n "$DEB_PATH" ]; then
  echo "==> Installing package $DEB_PATH..."
  ensure_termux_services
  dpkg -i "$DEB_PATH" || pkg install -y -f
  write_env_config
else
  echo "==> Installing binary $BIN_PATH..."
  ensure_termux_services
  mkdir -p "$PREFIX/bin"
  cp "$BIN_PATH" "$PREFIX/bin/download-server"
  chmod 755 "$PREFIX/bin/download-server"
  write_env_config
  install_service_files
fi

if [ "$ENABLE_BOOT" = "1" ] || [ -d "$HOME_DIR/.termux/boot" ]; then
  mkdir -p "$HOME_DIR/.termux/boot"
  BOOT_SCRIPT="$HOME_DIR/.termux/boot/start-download-server.sh"
  cat > "$BOOT_SCRIPT" <<EOF
#!$PREFIX/bin/sh
termux-wake-lock >/dev/null 2>&1 || true
. "$PREFIX/etc/profile"
sv up download-server >/dev/null 2>&1 || true
EOF
  chmod 755 "$BOOT_SCRIPT"
  echo "==> Configured Termux:Boot script at $BOOT_SCRIPT"
fi

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock || true
fi

export SVDIR="${SVDIR:-$PREFIX/var/service}"
if [ -f "$PREFIX/etc/profile.d/start-services.sh" ]; then
  # Start runit service-daemon in current session without requiring Termux restart
  . "$PREFIX/etc/profile.d/start-services.sh" >/dev/null 2>&1 || true
fi

echo "==> Enabling and starting download-server service..."
sv-enable download-server || true
sleep 1
sv restart download-server >/dev/null 2>&1 || sv up download-server || true

LOG_FILE="$PREFIX/var/log/sv/download-server/current"
i=0
while [ $i -lt 6 ]; do
  if [ -f "$LOG_FILE" ] && grep -q "Setup Token:" "$LOG_FILE" 2>/dev/null; then
    break
  fi
  sleep 1
  i=$((i + 1))
done

echo ""
echo "============================================================"
echo " PikPak Download Server is installed and enabled!"
echo "============================================================"
echo "  Status:   sv status download-server"
echo "  Restart:  sv restart download-server"
echo "  Stop:     sv down download-server"
echo "  Config:   $PREFIX/etc/default/download-server"
echo "  Logs:     cat $LOG_FILE"
echo "  Web UI:   http://127.0.0.1:$PORT"
if [ -f "$LOG_FILE" ]; then
  TOKEN_LINE="$(grep "Setup Token:" "$LOG_FILE" 2>/dev/null | tail -n 1 || true)"
  if [ -n "$TOKEN_LINE" ]; then
    echo "  Setup:    http://127.0.0.1:$PORT/setup"
    echo "  $TOKEN_LINE"
  fi
fi
echo "============================================================"
