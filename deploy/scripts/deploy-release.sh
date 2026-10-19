#!/usr/bin/env bash
# Run as the unprivileged deployment user, never root. Arguments are supplied by CI.
set -Eeuo pipefail
umask 027

sha=${1:-}
run=${2:-}
digest=${3:-}
[[ "$sha" =~ ^[a-f0-9]{40}$ && "$run" =~ ^[0-9]+-[0-9]+$ && "$digest" =~ ^[a-f0-9]{64}$ ]] || {
  echo 'Invalid deployment arguments' >&2; exit 2;
}
root=${PIXELFLOW_DEPLOY_ROOT:-/opt/pixelflow}
python_bin=${PIXELFLOW_DEPLOY_PYTHON:-/usr/bin/python3.11}
attempts=${PIXELFLOW_HEALTH_ATTEMPTS:-30}
[[ "$root" == /* && "$root" != / && "$root" != /opt && "$root" != /tmp && "$attempts" =~ ^[1-9][0-9]*$ ]]
[[ "$(realpath "$root")" == "$root" ]]
[[ -d "$root/incoming" && -d "$root/releases" && -L "$root/current" ]]
command -v flock >/dev/null
"$python_bin" -c 'import sys; assert sys.version_info[:2] == (3, 11), "Python 3.11 required"'

# Server-side lock also protects against manual deployment and other runners.
exec 9> "$root/.deploy.lock"
flock -n 9 || { echo 'Another deployment is running' >&2; exit 3; }
previous=$(realpath "$root/current")
[[ "$previous" == "$root/releases/"* && -d "$previous" ]]
release="$root/releases/$sha-$run"
archive="$root/incoming/$sha-$run.tar.gz"
[[ -f "$archive" && ! -L "$archive" && ! -e "$release" ]]
printf '%s  %s\n' "$digest" "$archive" | sha256sum --check --status

# Only regular files/directories within the expected package roots are accepted.
"$python_bin" - "$archive" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for item in archive.getmembers():
        path = PurePosixPath(item.name)
        if (path.is_absolute() or '..' in path.parts or not path.parts
                or path.parts[0] not in {'RELEASE_COMMIT', 'server', 'intelligence'}
                or not (item.isfile() or item.isdir())):
            raise SystemExit('Unsafe archive member rejected')
PY
mkdir "$release"
tar --no-same-owner --no-same-permissions -xzf "$archive" -C "$release"
[[ "$(cat "$release/RELEASE_COMMIT")" == "$sha" ]]
[[ -s "$release/server/target/pixelflow-server-0.1.0.jar" ]]

# Automatic rollback is only safe for schema-compatible updates. Fail before restart.
diff -qr "$previous/server/src/main/resources/db/mysql" "$release/server/src/main/resources/db/mysql" || {
  echo 'Java migration changed: manual migration/backup review required' >&2; exit 4;
}
cmp "$previous/intelligence/src/pixelflow_intelligence/analytics/mysql_schema.py" \
    "$release/intelligence/src/pixelflow_intelligence/analytics/mysql_schema.py" || {
  echo 'Python schema changed: manual migration/backup review required' >&2; exit 4;
}

# Create the venv at its final path: moving a venv breaks absolute interpreter paths.
"$python_bin" -m venv "$release/intelligence/.venv"
"$release/intelligence/.venv/bin/python" -m pip install --disable-pip-version-check \
  -c "$release/intelligence/constraints-ci.txt" "$release/intelligence"
"$release/intelligence/.venv/bin/python" -c 'import pixelflow_intelligence.api.app'
chmod -R g+rX "$release"

restart_needed=0
timer_paused=0
switch_link() {
  local target=$1 label=$2
  local link="$root/.current-$run-$label"
  ln -s "$target" "$link" || return
  mv -Tf "$link" "$root/current"
}
stop_apps() {
  sudo -n /usr/bin/systemctl stop pixelflow-python.service pixelflow-java.service
}
start_apps() {
  sudo -n /usr/bin/systemctl start pixelflow-java.service || return
  sudo -n /usr/bin/systemctl start pixelflow-python.service
}
health() {
  local port payload
  for port in 8080 8001; do
    payload=$(curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:$port/health") || return 1
    printf '%s' "$payload" | "$python_bin" -c 'import json,sys; assert json.load(sys.stdin).get("status") == "UP"' || return 1
  done
  # Query Feed as well: /health alone does not prove that the Java database is readable.
  curl --fail --silent --show-error --max-time 5 'http://127.0.0.1:8080/feed/?count=1' >/dev/null
}
wait_health() {
  local i
  for ((i=0; i<attempts; i++)); do
    if health; then return 0; fi
    sleep 2
  done
  return 1
}
cleanup() {
  local code=$?
  trap - EXIT INT TERM HUP
  set +e
  if (( code != 0 && restart_needed )); then
    echo 'Deployment failed; restoring previous application release' >&2
    stop_apps
    if switch_link "$previous" rollback && start_apps && wait_health; then
      echo "Application rollback healthy: $previous" >&2
    else
      echo 'ROLLBACK FAILED: administrator intervention required' >&2
    fi
  fi
  if (( timer_paused )); then
    if ! sudo -n /usr/bin/systemctl start pixelflow-analysis.timer; then
      echo 'Failed to restore analysis timer' >&2
      code=1
    fi
  fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

if systemctl is-active --quiet pixelflow-analysis.timer; then
  timer_paused=1
  sudo -n /usr/bin/systemctl stop pixelflow-analysis.timer
fi
if systemctl is-active --quiet pixelflow-analysis.service; then
  sudo -n /usr/bin/systemctl stop pixelflow-analysis.service
fi
restart_needed=1
stop_apps
switch_link "$release" deploy
start_apps
wait_health
restart_needed=0
echo "Deployment healthy: $sha ($run)"
# Releases and the uploaded archive are retained; no automatic destructive cleanup.
