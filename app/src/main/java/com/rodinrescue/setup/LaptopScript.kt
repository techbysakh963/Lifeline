package com.rodinrescue.setup

/** Generates the one-line laptop bootstrap script the phone serves over the USB link. */
object LaptopScript {
    /** Only [A-Za-z0-9] survive: the phone's USB serial number goes into a udev rule. */
    fun safeSerial(s: String?) = s.orEmpty().filter { it.isLetterOrDigit() && it.code < 128 }.take(64)

    /**
     * [phoneSerial]: the phone's USB serial number (gadget strings/0x409/serialnumber). With it, the script also adds
     * a login prompt on the phone's USB serial port, for THIS phone only (USB tools › USB serial).
     */
    fun build(phoneIp: String, port: Int, token: String, pubKeyLine: String, phoneSerial: String? = null): String = """#!/bin/sh
# Lifeline — one-time laptop setup (served by YOUR phone over the USB cable).
# What it does: installs/enables OpenSSH server if missing, authorizes the phone's key for your user,
# allows SSH on the USB interface if ufw is active, then tells the phone the laptop's address and SSH host-key fingerprints.
# It also adds a login prompt (your normal password) on the serial port THIS phone can add (USB serial), so the phone
# still reaches a shell when the laptop's network or SSH is broken. Other devices don't get it.
# It records the laptop's ORIGINAL state in /var/lib/lifeline/state so "Remove from this laptop" in the app
# undoes only what Lifeline changed (key, ufw rule, and the SSH server only if Lifeline installed/enabled it).
# What it does NOT do: change passwords, open anything to other networks, or run anything after it exits.
PHONE_IP='$phoneIp'; PHONE_PORT='$port'; TOKEN='$token'; PHONE_SERIAL='${safeSerial(phoneSerial)}'
PUBKEY='$pubKeyLine'
say()  { printf '\033[1;36m[lifeline]\033[0m %s\n' "${'$'}1"; }
fail() { printf '\033[1;31m[lifeline] %s\033[0m\n' "${'$'}1" >&2; exit 1; }

if [ -z "${'$'}RODIN_SKIP_INSTALL" ] && [ "${'$'}(id -u)" != 0 ]; then
  fail "Needs root. Run:  curl -fsSL http://${'$'}PHONE_IP:${'$'}PHONE_PORT/s/${'$'}TOKEN | sudo sh"
fi
TARGET_USER="${'$'}{SUDO_USER:-${'$'}(id -un)}"
SSH_PORT="${'$'}{RODIN_SSH_PORT:-22}"
say "Setting up for user: ${'$'}TARGET_USER"
STATE_DIR="${'$'}{RODIN_STATE_DIR:-/var/lib/lifeline}"; STATE="${'$'}STATE_DIR/state"
svc_name() { for s in ssh sshd; do systemctl cat "${'$'}s.service" >/dev/null 2>&1 && { echo "${'$'}s"; return; }; done; echo ssh; }
on() { if "${'$'}@" >/dev/null 2>&1; then echo 1; else echo 0; fi; }
WAS_INSTALLED=1; { command -v sshd || [ -x /usr/sbin/sshd ]; } >/dev/null 2>&1 || WAS_INSTALLED=0

if [ -z "${'$'}RODIN_SKIP_INSTALL" ]; then
  if ! command -v sshd >/dev/null 2>&1 && [ ! -x /usr/sbin/sshd ]; then
    say "Installing OpenSSH server (needs internet: your phone's tether provides it)…"
    if   command -v apt-get >/dev/null 2>&1; then apt-get update -qq >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server
    elif command -v dnf     >/dev/null 2>&1; then dnf install -y openssh-server
    elif command -v yum     >/dev/null 2>&1; then yum install -y openssh-server
    elif command -v pacman  >/dev/null 2>&1; then pacman -Sy --noconfirm openssh
    elif command -v zypper  >/dev/null 2>&1; then zypper -n install openssh
    elif command -v apk     >/dev/null 2>&1; then apk add openssh
    else fail "Unknown package manager: install an OpenSSH server manually, then re-run."; fi
  fi
  SVC="${'$'}(svc_name)"
  if [ "${'$'}WAS_INSTALLED" = 1 ]; then
    WAS_ENABLED="${'$'}(on sh -c "systemctl is-enabled ${'$'}SVC 2>/dev/null | grep -qx enabled")"
    WAS_ACTIVE="${'$'}(on systemctl is-active "${'$'}SVC")"
    SOCK_WAS_ENABLED="${'$'}(on sh -c "systemctl is-enabled ssh.socket 2>/dev/null | grep -qx enabled")"
  else WAS_ENABLED=0; WAS_ACTIVE=0; SOCK_WAS_ENABLED=0; fi   # the package's own post-install enable counts as Lifeline's change
  say "Enabling SSH server…"
  systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || service ssh start 2>/dev/null || service sshd start 2>/dev/null \
    || fail "Could not start the SSH service."
fi

HOME_DIR="${'$'}(getent passwd "${'$'}TARGET_USER" 2>/dev/null | cut -d: -f6)"; [ -n "${'$'}HOME_DIR" ] || HOME_DIR="${'$'}HOME"
AK="${'$'}{RODIN_AUTHKEYS_FILE:-${'$'}HOME_DIR/.ssh/authorized_keys}"
mkdir -p "${'$'}(dirname "${'$'}AK")" && chmod 700 "${'$'}(dirname "${'$'}AK")"
grep -qF "${'$'}PUBKEY" "${'$'}AK" 2>/dev/null || printf '%s\n' "${'$'}PUBKEY" >> "${'$'}AK"
chmod 600 "${'$'}AK"
[ "${'$'}(id -u)" = 0 ] && chown -R "${'$'}TARGET_USER:" "${'$'}(dirname "${'$'}AK")" 2>/dev/null
say "Phone key authorized in ${'$'}AK"

IFACE="${'$'}(ip -4 route get "${'$'}PHONE_IP" 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -1)"
LAPTOP_IP="${'$'}(ip -4 route get "${'$'}PHONE_IP" 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)"
if [ -z "${'$'}RODIN_SKIP_INSTALL" ] && command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active" && [ -n "${'$'}IFACE" ]; then
  ufw allow in on "${'$'}IFACE" to any port "${'$'}SSH_PORT" proto tcp >/dev/null && say "ufw: SSH allowed on ${'$'}IFACE only" && UFW_ADDED="${'$'}IFACE"
fi

# Login prompt on the phone's USB serial port (/dev/ttyACMn), for this phone only (matched by its USB serial number).
# systemd's serial-getty is bound to the device: it starts when the phone adds the port and stops when it goes away.
UDEV_DIR="${'$'}{RODIN_UDEV_DIR:-/etc/udev/rules.d}"; SERIAL_RULE=""
if [ -n "${'$'}PHONE_SERIAL" ] && { [ -z "${'$'}RODIN_SKIP_INSTALL" ] || [ -n "${'$'}RODIN_UDEV_DIR" ]; } && [ -d "${'$'}UDEV_DIR" ] \
   && { [ -n "${'$'}RODIN_UDEV_DIR" ] || systemctl cat serial-getty@.service >/dev/null 2>&1; }; then
  printf '%s\n' "# Lifeline: a login prompt on this phone's USB serial port (removed by Lifeline's Remove from this laptop)." \
    "ACTION==\"add\", SUBSYSTEM==\"tty\", KERNEL==\"ttyACM[0-9]*\", ENV{ID_SERIAL_SHORT}==\"${'$'}PHONE_SERIAL\", TAG+=\"systemd\", ENV{SYSTEMD_WANTS}+=\"serial-getty@%k.service\"" \
    > "${'$'}UDEV_DIR/90-lifeline-serial.rules" && SERIAL_RULE=1
  [ -z "${'$'}RODIN_UDEV_DIR" ] && udevadm control --reload 2>/dev/null
  [ -n "${'$'}SERIAL_RULE" ] && say "Serial login prompt added for this phone (USB tools › USB serial)"
fi

# Remember the ORIGINAL state once (a re-run keeps the first record, so "remove" restores what was there before Lifeline).
if [ "${'$'}(id -u)" = 0 ] || [ -n "${'$'}RODIN_STATE_DIR" ]; then
  if [ ! -f "${'$'}STATE" ]; then
    mkdir -p "${'$'}STATE_DIR" && chmod 700 "${'$'}STATE_DIR"
    printf 'SVC=%s\nINSTALLED_BY_LIFELINE=%s\nWAS_ENABLED=%s\nWAS_ACTIVE=%s\nSOCK_WAS_ENABLED=%s\nSSH_PORT=%s\n' \
      "${'$'}{SVC:-ssh}" "${'$'}(( 1 - WAS_INSTALLED ))" "${'$'}{WAS_ENABLED:-1}" "${'$'}{WAS_ACTIVE:-1}" "${'$'}{SOCK_WAS_ENABLED:-1}" "${'$'}SSH_PORT" > "${'$'}STATE"
    chmod 600 "${'$'}STATE"
  fi
  [ -n "${'$'}UFW_ADDED" ] && { grep -qx "UFW=${'$'}UFW_ADDED" "${'$'}STATE" || echo "UFW=${'$'}UFW_ADDED" >> "${'$'}STATE"; }
  [ -n "${'$'}SERIAL_RULE" ] && { grep -qx "SERIAL_RULE=1" "${'$'}STATE" || echo "SERIAL_RULE=1" >> "${'$'}STATE"; }
fi
st() { sed -n "s/^${'$'}1=//p" "${'$'}STATE" 2>/dev/null | head -1; }

FPS=""
for k in ${'$'}{RODIN_HOSTKEY_GLOB:-/etc/ssh/ssh_host_*_key.pub}; do
  [ -f "${'$'}k" ] || continue
  fp="${'$'}(ssh-keygen -lf "${'$'}k" 2>/dev/null | awk '{print ${'$'}2}')"
  [ -n "${'$'}fp" ] && FPS="${'$'}FPS${'$'}{FPS:+,}${'$'}fp"
done
BODY="user=${'$'}TARGET_USER&ip=${'$'}LAPTOP_IP&port=${'$'}SSH_PORT&fps=${'$'}FPS&host=${'$'}(hostname)&inst=${'$'}(st INSTALLED_BY_LIFELINE)&en=${'$'}(st WAS_ENABLED)&act=${'$'}(st WAS_ACTIVE)&ufw=${'$'}(sed -n 's/^UFW=//p' "${'$'}STATE" 2>/dev/null | head -1)&state=${'$'}([ -f "${'$'}STATE" ] && echo 1 || echo 0)"
URL="http://${'$'}PHONE_IP:${'$'}PHONE_PORT/r/${'$'}TOKEN"
if   command -v curl >/dev/null 2>&1; then curl -fsS -m 10 -d "${'$'}BODY" "${'$'}URL" >/dev/null || fail "Could not reach the phone at ${'$'}URL"
elif command -v wget >/dev/null 2>&1; then wget -q -O /dev/null --timeout=10 --post-data="${'$'}BODY" "${'$'}URL" || fail "Could not reach the phone at ${'$'}URL"
else fail "Neither curl nor wget found."; fi
say "Done. Laptop ${'$'}LAPTOP_IP is registered with your phone. You can close this terminal."
"""
}
