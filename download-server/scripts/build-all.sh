#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/bin"
cd "$ROOT"
mkdir -p "$OUT"
PREFIX="${ARTIFACT_PREFIX:-download-server}"

if [[ "${SKIP_WEB:-0}" != "1" ]]; then
  npm --prefix "$ROOT/web" ci
  npm --prefix "$ROOT/web" run build
fi

LDFLAGS="-s -w"
BUILD_FLAGS=(-tags embed_ui -trimpath -ldflags "$LDFLAGS")
ARTIFACTS=()

build_target() {
  local goos="$1"
  local goarch="$2"
  local label="$3"
  local goarm="${4:-}"
  local name="${PREFIX}-${label}"

  echo "==> ${goos}/${goarch} -> ${name}"
  if [[ "$goos" == "windows" ]]; then
    GOOS="$goos" GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=0 \
      go build "${BUILD_FLAGS[@]}" -o "$OUT/${name}.exe" "$ROOT/cmd/server"
    ARTIFACTS+=("${name}.exe")
  else
    GOOS="$goos" GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=0 \
      go build "${BUILD_FLAGS[@]}" -o "$OUT/${name}" "$ROOT/cmd/server"
    ARTIFACTS+=("${name}")
  fi
}

build_target linux amd64 linux-amd64
build_target linux arm64 linux-arm64
build_target linux arm linux-armv7 7
build_target linux 386 linux-386
build_target darwin amd64 darwin-amd64
build_target darwin arm64 darwin-arm64
build_target windows amd64 windows-amd64
build_target windows arm64 windows-arm64
build_target freebsd amd64 freebsd-amd64
build_target android arm64 android-arm64

DEB_NAME="${PREFIX}-termux-aarch64.deb"
echo "==> termux/aarch64 (.deb) -> ${DEB_NAME}"
go run "$ROOT/cmd/termuxdeb" \
  -binary "$OUT/${PREFIX}-android-arm64" \
  -output "$OUT/${DEB_NAME}" \
  -version "${PACKAGE_VERSION:-$PREFIX}"
ARTIFACTS+=("${DEB_NAME}")

INSTALLER_NAME="${PREFIX}-install-termux.sh"
echo "==> termux installer -> ${INSTALLER_NAME}"
cp "$ROOT/scripts/install-termux.sh" "$OUT/${INSTALLER_NAME}"
chmod +x "$OUT/${INSTALLER_NAME}"
ARTIFACTS+=("${INSTALLER_NAME}")

echo "==> checksums"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUT" && sha256sum "${ARTIFACTS[@]}" > SHA256SUMS.txt)
else
  (cd "$OUT" && shasum -a 256 "${ARTIFACTS[@]}" > SHA256SUMS.txt)
fi

{
  echo "generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "go_version=$(go version)"
  echo "git_commit=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "frontend_embedded=true"
  printf 'artifact=%s\n' "${ARTIFACTS[@]}"
} > "$OUT/BUILD-INFO.txt"

echo "Artifacts: $OUT"
