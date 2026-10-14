#!/usr/bin/env bash
# Ephemeral CI only. Never point this harness at an installed runtime/profile.
set -euo pipefail
umask 077
client="${1:?built client required}"
if [[ "$#" -gt 2 || ( "${2:-}" != "" && "${2:-}" != --preflight ) ]]; then
  printf 'unexpected protocol harness option\n' >&2
  exit 2
fi
here="$(cd -- "$(dirname -- "$0")" && pwd)"
scratch="$(mktemp -d "${RUNNER_TEMP:?}/session-peer.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

openssl req -x509 -newkey rsa:2048 -nodes -days 1 -keyout "$scratch/ca.key" \
  -out "$scratch/ca.pem" -subj '/CN=Private test authority' \
  -addext 'basicConstraints=critical,CA:TRUE' >/dev/null 2>&1
for name in peer wrong; do
  host=login.example.test
  if [[ "$name" == wrong ]]; then host=wrong.example.test; fi
  openssl req -new -newkey rsa:2048 -nodes -keyout "$scratch/$name.key" \
    -out "$scratch/$name.csr" -subj "/CN=$host" \
    -addext "subjectAltName=DNS:$host" >/dev/null 2>&1
  openssl x509 -req -in "$scratch/$name.csr" -CA "$scratch/ca.pem" \
    -CAkey "$scratch/ca.key" -set_serial "$([[ "$name" == peer ]] && printf 1 || printf 2)" \
    -days 1 -copy_extensions copy -out "$scratch/$name.pem" >/dev/null 2>&1
done
sudo -n chown 65534:65534 "$scratch/peer.key" "$scratch/wrong.key"
chmod 644 "$scratch/ca.pem" "$scratch/peer.pem" "$scratch/wrong.pem"
printf '127.0.0.1 login.example.test\n' > "$scratch/hosts"
printf 'hosts: files\n' > "$scratch/nsswitch.conf"
chmod 644 "$scratch/hosts" "$scratch/nsswitch.conf"
host_net="$(readlink /proc/self/ns/net)"
check_args=("$host_net")
if [[ "${2:-}" == --preflight ]]; then check_args+=(--preflight); fi
check_command=(/usr/bin/python3 /work/protocol_smoke.py "${check_args[@]}")
if [[ "${2:-}" == --preflight ]]; then
  check_command=(/usr/bin/strace -ff -qq -s 256 -o /tmp/tls-preflight.trace -e trace=execve,connect "${check_command[@]}")
fi
timeout --signal=TERM --kill-after=5s 100s sudo -n setpriv --no-new-privs bwrap \
  --unshare-pid --unshare-net --unshare-ipc --unshare-uts --die-with-parent --new-session \
  --cap-drop ALL --cap-add CAP_SETUID --cap-add CAP_SETGID --cap-add CAP_SETPCAP --cap-add CAP_NET_ADMIN \
  --ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/lib /lib --symlink usr/lib64 /lib64 \
  --proc /proc --dev /dev --perms 1777 --size 134217728 --tmpfs /tmp \
  --dir /etc --ro-bind "$scratch/hosts" /etc/hosts --ro-bind "$scratch/nsswitch.conf" /etc/nsswitch.conf \
  --symlink /usr/lib/os-release /etc/os-release \
  --dir /work --dir /work/empty --ro-bind "$client" /work/client \
  --ro-bind "$here/protocol_smoke.py" /work/protocol_smoke.py \
  --ro-bind "$here/offline_smoke.py" /work/offline_smoke.py \
  --ro-bind "$scratch/ca.pem" /work/ca.pem \
  --ro-bind "$scratch/peer.pem" /work/peer.pem --ro-bind "$scratch/peer.key" /work/peer.key \
  --ro-bind "$scratch/wrong.pem" /work/wrong.pem --ro-bind "$scratch/wrong.key" /work/wrong.key \
  --chdir /work --clearenv --setenv PATH /usr/bin:/bin \
  -- /bin/sh -eu -c 'ip link set lo up; exec setpriv --reuid=65534 --regid=65534 --clear-groups \
     --bounding-set=-all --inh-caps=-all --ambient-caps=-all --no-new-privs \
     "$@"' session-setup "${check_command[@]}" > "$scratch/receipt.json" || {
  cat "$scratch/receipt.json"
  exit 1
}

# Re-read kernel namespace references after Bubblewrap has reaped its PID namespace.
sudo -n python3 - "$scratch/receipt.json" <<'PY'
import json
import os
from pathlib import Path
import sys

receipt = json.loads(Path(sys.argv[1]).read_text())
namespace = receipt["private_namespace"]
for path in Path("/proc").glob("[0-9]*/ns/net"):
    try:
        if os.readlink(path) == namespace:
            raise SystemExit("private network namespace survived teardown")
    except FileNotFoundError:
        pass
receipt["namespace_reaped"] = True
print(json.dumps(receipt))
PY
