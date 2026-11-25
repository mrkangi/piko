#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: make-apt-repo.sh --out <dir> <package.deb> [<package.deb> ...]

Generate a flat apt repository for Termux (aarch64):
  Packages
  Packages.gz
  Release

The .deb files must already be present in <dir>; the generated metadata
references them by filename, so the whole directory can be published as-is.
EOF
}

OUT=""
DEBS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      OUT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      DEBS+=("$@")
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      DEBS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "$OUT" || ${#DEBS[@]} -eq 0 ]]; then
  usage >&2
  exit 2
fi
if ! command -v dpkg-deb >/dev/null 2>&1; then
  echo "Error: dpkg-deb is required to generate the apt repository." >&2
  exit 1
fi

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
PACKAGES="$OUT/Packages"
PACKAGES_GZ="$OUT/Packages.gz"
RELEASE="$OUT/Release"
rm -f "$PACKAGES" "$PACKAGES_GZ" "$RELEASE"

: > "$PACKAGES"
for deb in "${DEBS[@]}"; do
  if [[ ! -f "$deb" ]]; then
    echo "Error: package not found: $deb" >&2
    exit 1
  fi
  arch="$(dpkg-deb -f "$deb" Architecture)"
  if [[ "$arch" != "aarch64" ]]; then
    echo "Error: $deb is not a Termux aarch64 package (Architecture: $arch)." >&2
    exit 1
  fi
  name="$(basename "$deb")"
  if [[ "$(dirname "$deb")" != "$OUT" ]]; then
    echo "Error: $name must be stored directly in $OUT before generating metadata." >&2
    exit 1
  fi

  dpkg-deb -f "$deb" >> "$PACKAGES"
  printf 'Filename: ./%s\n' "$name" >> "$PACKAGES"
  printf 'Size: %s\n' "$(stat -c %s "$deb")" >> "$PACKAGES"
  printf 'SHA256: %s\n\n' "$(sha256sum "$deb" | awk '{print $1}')" >> "$PACKAGES"
done

gzip -9 -n -c "$PACKAGES" > "$PACKAGES_GZ"

package_md5="$(md5sum "$PACKAGES" | awk '{print $1}')"
package_size="$(stat -c %s "$PACKAGES")"
gz_md5="$(md5sum "$PACKAGES_GZ" | awk '{print $1}')"
gz_size="$(stat -c %s "$PACKAGES_GZ")"
package_sha="$(sha256sum "$PACKAGES" | awk '{print $1}')"
gz_sha="$(sha256sum "$PACKAGES_GZ" | awk '{print $1}')"

cat > "$RELEASE" <<EOF
Origin: Piko
Label: Piko
Suite: termux
Codename: termux
Architectures: aarch64
Components: main
Description: Piko download server packages for Termux
Date: $(LC_ALL=C date -Ru)
MD5Sum:
 $package_md5 $package_size Packages
 $gz_md5 $gz_size Packages.gz
SHA256:
 $package_sha $package_size Packages
 $gz_sha $gz_size Packages.gz
EOF

echo "apt repository metadata written to $OUT"
