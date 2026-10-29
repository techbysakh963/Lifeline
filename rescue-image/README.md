# Lifeline rescue system

A small Debian 13 (trixie) live system that a PC boots from the Lifeline phone (Boot tab → Lifeline rescue system).
It copies itself into the PC's memory, gets an address from the phone's USB tethering, reads the phone's key drive
and starts SSH, so Lifeline's SSH AI can repair the PC's disks when its own Linux or Windows won't start.

- **Secure Boot:** Debian's Microsoft-signed shim, signed GRUB and signed kernel (verified in a VM with Microsoft keys).
- **No passwords, no secrets in the image:** SSH starts only with the phone's key drive (a 64 KiB raw USB drive the app
  writes per session: the phone's public key + a host key the phone generates, so it knows the fingerprint). It must be
  the only drive named "Lifeline / - key"; the network comes up only on the phone's USB link.
- **Build:** `./build.sh` (podman/docker, Debian live-build). **Test:** `./test-vm.sh out/lifeline-rescue-*.iso`
  (Secure Boot VM; also `NOKEY=1`, `TWOKEYS=1`).

## Licences and sources
The image is built only from unmodified Debian packages (main, contrib, non-free-firmware) plus the files in `config/`
(Lifeline's, same licence as Lifeline). Each package keeps its own licence (`/usr/share/doc/*/copyright` in the image).
Source for every Debian package is available from Debian: https://sources.debian.org and https://snapshot.debian.org
(the package list and versions are in the build log). Lifeline will provide the exact sources on request.
