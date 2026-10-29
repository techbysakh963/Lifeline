#!/bin/sh
# Builds the Lifeline rescue system ISO in a Debian container (reproducible from this folder):
#   ./build.sh            -> out/lifeline-rescue-<date>.iso + .sha256
# Needs podman (or docker). Secure Boot: Debian's Microsoft-signed shim + signed GRUB + signed kernel.
# nouser noautologin (2026-10-29): live-config made a console auto-login for a user "user" that isn't there, so a
# pam error filled the screen and the journal every few seconds. No user, no auto-login: the phone is the way in.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
run=$(command -v podman || command -v docker)
mkdir -p "$here/out"
# Never leave an older image behind under a name that looks new (2026-10-28: a failed build left the previous ISO and
# the tests ran on it).
rm -f "$here"/out/lifeline-rescue-*.iso "$here"/out/lifeline-rescue-*.iso.sha256
mkdir -p "$here/cache"
"$run" run --rm --privileged -v "$here:/src:ro" -v "$here/out:/out" -v "$here/cache:/b/cache" docker.io/library/debian:trixie sh -euc '
  for t in 1 2 3; do apt-get update -qq && apt-get install -y -qq live-build >/dev/null && break; echo "apt failed (mirror?), retrying in 20 s"; sleep 20; done
  command -v lb >/dev/null || { echo "live-build could not be installed"; exit 1; }
  mkdir -p /b && cd /b
  lb config --distribution trixie --archive-areas "main contrib non-free-firmware" \
    --binary-images iso-hybrid --bootloaders "syslinux,grub-efi" --uefi-secure-boot enable \
    --debian-installer none --apt-recommends false --memtest none --firmware-binary false --firmware-chroot false \
    --iso-volume LIFELINE-RESCUE --iso-application "Lifeline rescue" --iso-publisher "Lifeline" \
    --bootappend-live "boot=live components nouser noautologin toram console=ttyS0,115200 console=tty0"
  cp -r /src/config/. config/
  lb build
  v=$(date -u +%Y%m%d)
  cp live-image-amd64.hybrid.iso /out/lifeline-rescue-$v.iso
  cd /out && sha256sum lifeline-rescue-$v.iso > lifeline-rescue-$v.iso.sha256 && ls -la /out
'
