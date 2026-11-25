#!/bin/sh

PKG_NAME="${SYNOPKG_PKGNAME:-piko-download-server}"
PKG_DEST="${SYNOPKG_PKGDEST:-/var/packages/${PKG_NAME}/target}"
PKG_VAR="${SYNOPKG_PKGVAR:-/var/packages/${PKG_NAME}/var}"
PKG_SHARE_ROOT="/var/packages/${PKG_NAME}/shares"

ENV_DIR="${PKG_VAR}/etc"
ENV_FILE="${ENV_DIR}/download-server.env"
DATA_DIR="${PKG_VAR}/data"
RUN_DIR="${PKG_VAR}/run"
LOG_DIR="${PKG_VAR}/logs"
PID_FILE="${RUN_DIR}/download-server.pid"
SERVICE_LOG="${LOG_DIR}/service.log"
BIN="${PKG_DEST}/bin/download-server"

resolve_path() {
  if command -v realpath >/dev/null 2>&1; then
    realpath "$1" 2>/dev/null && return 0
  fi
  (cd "$1" 2>/dev/null && pwd -P) || printf '%s\n' "$1"
}

ensure_dirs() {
  umask 077
  mkdir -p "${ENV_DIR}" "${DATA_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${PKG_VAR}/PikoDownloads"
  chmod 700 "${ENV_DIR}" "${DATA_DIR}" "${RUN_DIR}" 2>/dev/null || true
  chmod 755 "${PKG_VAR}/PikoDownloads" 2>/dev/null || true
}

ensure_env() {
  if [ -f "${ENV_FILE}" ]; then
    chmod 600 "${ENV_FILE}" 2>/dev/null || true
    return 0
  fi

  share_name="${wizard_download_share:-}"
  home_dir=""
  if [ -n "${share_name}" ] && [ -d "${PKG_SHARE_ROOT}/${share_name}" ]; then
    home_dir="$(resolve_path "${PKG_SHARE_ROOT}/${share_name}")"
  fi
  if [ -z "${home_dir}" ]; then
    home_dir="${PKG_VAR}"
  fi

  mkdir -p "${home_dir}/PikoDownloads" 2>/dev/null || true
  umask 077
  cat > "${ENV_FILE}" <<EOF
# Piko Download Server environment configuration
DOWNLOAD_SERVER_HOST="0.0.0.0"
DOWNLOAD_SERVER_PORT="7788"
DOWNLOAD_SERVER_DATA_DIR="${DATA_DIR}"
DOWNLOAD_SERVER_HOME="${home_dir}"
DOWNLOAD_SERVER_SHARE_NAME="${share_name}"
DOWNLOAD_SERVER_PACKAGE_MODE="synology"
EOF
  chmod 600 "${ENV_FILE}" 2>/dev/null || true
}

load_env() {
  if [ -f "${ENV_FILE}" ]; then
    . "${ENV_FILE}"
  fi
}

service_home() {
  if [ -n "${DOWNLOAD_SERVER_SHARE_NAME:-}" ] && [ -d "${PKG_SHARE_ROOT}/${DOWNLOAD_SERVER_SHARE_NAME}" ]; then
    resolve_path "${PKG_SHARE_ROOT}/${DOWNLOAD_SERVER_SHARE_NAME}"
    return 0
  fi
  printf '%s\n' "${DOWNLOAD_SERVER_HOME:-${PKG_VAR}}"
}

is_running() {
  [ -f "${PID_FILE}" ] || return 1
  pid="$(cat "${PID_FILE}" 2>/dev/null)" || return 1
  case "${pid}" in
    ''|*[!0-9]*)
      rm -f "${PID_FILE}"
      return 1
      ;;
  esac
  if kill -0 "${pid}" 2>/dev/null; then
    return 0
  fi
  rm -f "${PID_FILE}"
  return 1
}

rotate_log() {
  [ -f "${SERVICE_LOG}" ] || return 0
  size="$(wc -c < "${SERVICE_LOG}" 2>/dev/null | tr -d ' ')"
  case "${size}" in
    ''|*[!0-9]*) size=0 ;;
  esac
  [ "${size}" -ge 10485760 ] || return 0
  i=4
  while [ "${i}" -gt 1 ]; do
    previous=$((i - 1))
    [ -f "${SERVICE_LOG}.${previous}" ] && mv -f "${SERVICE_LOG}.${previous}" "${SERVICE_LOG}.${i}"
    i="${previous}"
  done
  mv -f "${SERVICE_LOG}" "${SERVICE_LOG}.1"
}

start_service() {
  ensure_dirs
  ensure_env
  load_env

  if is_running; then
    echo "${PKG_NAME} is already running"
    return 0
  fi
  [ -x "${BIN}" ] || {
    echo "${PKG_NAME}: missing executable ${BIN}" >&2
    return 1
  }

  home_dir="$(service_home)"
  mkdir -p "${home_dir}/PikoDownloads" 2>/dev/null || true
  export HOME="${home_dir}"
  export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
  export DOWNLOAD_SERVER_HOST="${DOWNLOAD_SERVER_HOST:-0.0.0.0}"
  export DOWNLOAD_SERVER_PORT="${DOWNLOAD_SERVER_PORT:-7788}"
  export DOWNLOAD_SERVER_DATA_DIR="${DOWNLOAD_SERVER_DATA_DIR:-${DATA_DIR}}"
  export DOWNLOAD_SERVER_PACKAGE_MODE="${DOWNLOAD_SERVER_PACKAGE_MODE:-synology}"

  rotate_log
  cd "${PKG_VAR}" || return 1
  nohup "${BIN}" >> "${SERVICE_LOG}" 2>&1 &
  pid=$!
  printf '%s' "${pid}" > "${PID_FILE}"
  sleep 1
  if ! kill -0 "${pid}" 2>/dev/null; then
    echo "${PKG_NAME}: failed to start" >&2
    tail -n 40 "${SERVICE_LOG}" 2>/dev/null || true
    rm -f "${PID_FILE}"
    return 1
  fi
  echo "${PKG_NAME} started (pid ${pid})"
}

stop_service() {
  if ! is_running; then
    rm -f "${PID_FILE}"
    echo "${PKG_NAME} is not running"
    return 0
  fi
  pid="$(cat "${PID_FILE}")"
  kill -TERM "${pid}" 2>/dev/null || true
  i=0
  while [ "${i}" -lt 30 ]; do
    kill -0 "${pid}" 2>/dev/null || break
    sleep 1
    i=$((i + 1))
  done
  if kill -0 "${pid}" 2>/dev/null; then
    kill -KILL "${pid}" 2>/dev/null || true
    sleep 1
  fi
  rm -f "${PID_FILE}"
  echo "${PKG_NAME} stopped"
}
