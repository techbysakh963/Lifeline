#!/bin/sh
# DEV/TEST HELPER (not part of the app). Runs a throwaway, rootless OpenSSH server on this PC so the phone app can be
# tested end-to-end when you have no sudo: extracts the openssh-server .deb into $DIR (no install), key-only auth, port 2222.
# Usage: scripts/dev/rootless_sshd_test.sh [DIR]      Stop: fuser -k 2222/tcp     (NEVER use `pkill -f sshd` from a Claude Bash call: it kills its own shell)
# Then on the laptop step use the app's printed command with test hooks:
#   curl -fsSL <url> | RODIN_SKIP_INSTALL=1 RODIN_SSH_PORT=2222 RODIN_AUTHKEYS_FILE=$DIR/authorized_keys RODIN_HOSTKEY_GLOB=$DIR/hostkey.pub sh
set -e
DIR="${1:-/tmp/rodin-sshd}"; mkdir -p "$DIR/dl" && cd "$DIR"
[ -x usr/sbin/sshd ] || { (cd dl && apt download openssh-server openssh-sftp-server) && for d in dl/*.deb; do dpkg-deb -x "$d" .; done; }
[ -f hostkey ] || ssh-keygen -q -t ed25519 -N "" -f hostkey
touch authorized_keys; chmod 600 authorized_keys hostkey
cat > sshd_config <<CFG
Port 2222
ListenAddress 0.0.0.0
HostKey $DIR/hostkey
AuthorizedKeysFile $DIR/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
PidFile $DIR/sshd.pid
Subsystem sftp $DIR/usr/lib/openssh/sftp-server
CFG
echo "starting sshd on :2222 (key-only). Log: $DIR/sshd.log"
exec "$DIR/usr/sbin/sshd" -D -e -f "$DIR/sshd_config" > "$DIR/sshd.log" 2>&1
