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

# Automatic rollback is only safe for schema-compatible updates. The running JAR
# is authoritative: manual releases may omit the duplicate source directory.
# Compare names AND bytes; missing/corrupt/empty JARs must never bypass the gate.
"$python_bin" - "$previous" "$release" <<'PY'
import hashlib
from pathlib import Path, PurePosixPath
import sys
import zipfile

def migrations(release):
    prefix = 'BOOT-INF/classes/db/mysql/'
    manifest = {}
    with zipfile.ZipFile(release / 'server/target/pixelflow-server-0.1.0.jar') as jar:
        for entry in jar.infolist():
            if not entry.filename.startswith(prefix) or entry.is_dir():
                continue
            name = entry.filename[len(prefix):]
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or str(path) != name or name in manifest:
                raise ValueError('invalid or duplicate migration entry')
            manifest[name] = hashlib.sha256(jar.read(entry)).hexdigest()
    if not manifest or not any(name.endswith('.sql') for name in manifest):
        raise ValueError('JAR has no MySQL migrations')
    directory = release / 'server/src/main/resources/db/mysql'
    if directory.exists():
        if not directory.is_dir():
            raise ValueError('migration source path is not a directory')
        sources = {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in directory.rglob('*') if p.is_file()}
        if sources != manifest:
            raise ValueError('migration source directory differs from packaged JAR')
    return manifest

try:
    previous, current = (migrations(Path(value)) for value in sys.argv[1:])
except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
    print(f'Cannot verify Java migrations: {error}', file=sys.stderr)
    sys.exit(4)
if previous != current:
    print('Java migration changed: manual migration/backup review required', file=sys.stderr)
    for name in sorted(previous.keys() | current.keys()):
        if previous.get(name) != current.get(name):
            print(f'Changed migration: {name}', file=sys.stderr)
    sys.exit(4)
print(f'Java migration check passed: {len(current)} packaged resources match')
PY
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
