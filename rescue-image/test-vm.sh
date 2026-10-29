#!/bin/sh
# Boots a rescue ISO in a UEFI VM WITH SECURE BOOT ON (Microsoft keys, like most PCs) and checks, like the phone would:
#   - the system starts (signed shim/GRUB/kernel accepted),
#   - it finds the key drive (made exactly as the app makes it) and starts SSH,
#   - SSH accepts the given key and presents exactly the host key from the key drive (pinned).
# Both drives are USB storage devices, like the phone's (the key drive named "Lifeline" / "- key" like the app's);
# the network is a USB network device, like the phone's tethering (the system ignores ordinary network cards). Usage: ./test-vm.sh out/lifeline-rescue-YYYYMMDD.iso
set -eu
iso=$(readlink -f "$1")
w=$(mktemp -d); trap 'kill $(cat "$w/qemu.pid" 2>/dev/null) 2>/dev/null; rm -rf "$w"' EXIT
ssh-keygen -q -t ed25519 -N '' -f "$w/phone" -C phone
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$w/host.pem" 2>/dev/null   # PKCS#8 PEM, like the app
python3 -I - "$w" <<'PY'
import base64, sys
w = sys.argv[1]
pub = open(w + "/phone.pub").read().strip() + "\n"; host = open(w + "/host.pem").read()
t = ("LIFELINE-RESCUE-KEY v1\nauthorized_key %s\nhost_key %s\nend\n" % (base64.b64encode(pub.encode()).decode(), base64.b64encode(host.encode()).decode())).encode()
open(w + "/key.img", "wb").write(t + b"\0" * (65536 - len(t)))
PY
ssh-keygen -y -f "$w/host.pem" > "$w/host.pub" 2>/dev/null || openssl ec -in "$w/host.pem" -pubout 2>/dev/null | ssh-keygen -i -m PKCS8 -f /dev/stdin > "$w/host.pub"
echo "[localhost]:2222 $(cat "$w/host.pub")" > "$w/known_hosts"
cp /usr/share/OVMF/OVMF_VARS_4M.ms.fd "$w/vars.fd"
qemu-system-x86_64 -enable-kvm -m 2048 -smp 2 -machine q35,smm=on -global driver=cfi.pflash01,property=secure,value=on \
  -drive if=pflash,format=raw,unit=0,readonly=on,file=/usr/share/OVMF/OVMF_CODE_4M.secboot.fd -drive if=pflash,format=raw,unit=1,file="$w/vars.fd" \
  -device qemu-xhci -drive if=none,id=iso,format=raw,readonly=on,file="$iso" -device usb-storage,drive=iso,bootindex=0 \
  $( [ "${NOKEY:-}" = 1 ] || echo "-drive if=none,id=key,format=raw,readonly=on,file=$w/key.img -device usb-bot,id=kb -device scsi-hd,bus=kb.0,drive=key,vendor=Lifeline,product=-key" ) \
  $( [ "${TWOKEYS:-}" = 1 ] && echo "-drive if=none,id=key2,format=raw,readonly=on,file=$w/key.img -device usb-bot,id=kb2 -device scsi-hd,bus=kb2.0,drive=key2,vendor=Lifeline,product=-key" ) \
  -netdev user,id=n,hostfwd=tcp:127.0.0.1:2222-:22 -device usb-net,netdev=n \
  -display none -serial file:"$w/serial.log" -monitor unix:"$w/mon",server,nowait -daemonize -pidfile "$w/qemu.pid"
echo "VM started (Secure Boot on) from $(basename "$iso"), $(date -r "$iso" '+%F %T'), sha256 $(sha256sum "$iso" | cut -c1-12)…; waiting for SSH…"
if [ "${NOKEY:-}" = 1 ] || [ "${TWOKEYS:-}" = 1 ]; then
  # Without the phone's key drive nothing may listen: wait until the system is up (60 s), then expect no SSH at all.
  # Nothing may answer at all: no SSH greeting, no host key (not just "login refused").
  sleep 75
  if ssh-keyscan -T 8 -p 2222 localhost > "$w/err" 2>/dev/null && [ -s "$w/err" ]; then
    echo "FAIL: an SSH server answered: $(head -c 120 "$w/err")"; exit 1; fi
  echo "PASS: no SSH server at all (${NOKEY:+no key drive}${TWOKEYS:+two key drives})"; exit 0
fi
t0=$(date +%s)
for i in $(seq 1 ${TRIES:-90}); do
  if ssh -q -o BatchMode=yes -o ConnectTimeout=3 -o UserKnownHostsFile="$w/known_hosts" -o StrictHostKeyChecking=yes -o HostKeyAlgorithms=ecdsa-sha2-nistp256 \
       -i "$w/phone" -p 2222 root@localhost 'echo SSH-OK; mokutil --sb-state 2>/dev/null || dmesg | grep -i "secure boot" | head -2; uname -r; free -m | sed -n 2p; lsblk -o NAME,SIZE,TYPE,RM | head; systemctl is-active lifeline-key; echo default=$(systemctl get-default); sleep 15; echo login-errors=$(journalctl -b --no-pager | grep -c "could not identify user\|Authentication failure")' 2>/dev/null | tee "$w/out"; [ -s "$w/out" ]; then
    echo "PASS after $(( $(date +%s) - t0 )) s"
    # 2026-10-29: live-config auto-logged in a missing user "user" every 5 s, and the default target was graphical.
    grep -qx "default=multi-user.target" "$w/out" || { echo "FAIL: default target isn't multi-user.target"; exit 1; }
    grep -qx "login-errors=0" "$w/out" || { echo "FAIL: console login errors (auto-login of a missing user?)"; exit 1; }
    echo "PASS: text console, no login errors"
    ssh-keygen -q -t ed25519 -N '' -f "$w/stranger"
    if ssh -o BatchMode=yes -o ConnectTimeout=5 -o UserKnownHostsFile="$w/known_hosts" -i "$w/stranger" -p 2222 root@localhost true 2>/dev/null; then
      echo "FAIL: another key was accepted"; exit 1; fi
    echo "PASS: another key is refused"; exit 0
  fi
  sleep 5
done
echo "FAIL: no SSH. Serial log tail:"; tail -40 "$w/serial.log"
echo "screendump $PWD/fail-screen.ppm" | socat - unix-connect:"$w/mon" >/dev/null 2>&1 && echo "Screen: $PWD/fail-screen.ppm"
exit 1
