package com.rodinrescue.setup

/**
 * Undo for the laptop setup ("Remove from this laptop"). Two parts, run in this order over the live SSH session:
 *  1. [userScript] (as the login user): removes the phone's key from ~/.ssh/authorized_keys, matched by its base64 body
 *     so keys with older comments ("rodin-rescue") are removed too.
 *  2. [rootScript] (via sudo): reads /var/lib/lifeline/state written by the setup script and reverts ONLY Lifeline's
 *     changes: the ufw rule(s), and the SSH server's enabled/running state if it was off before Lifeline. The stop is
 *     deferred a few seconds so this session can finish; OpenSSH stays installed (just disabled).
 * Output lines are machine-readable markers parsed by [parse].
 */
object LaptopCleanup {
    data class Result(val keyRemoved: Boolean, val keyWasAbsent: Boolean, val stateFound: Boolean, val ufwRemoved: List<String>,
                      val sshdDisabled: Boolean, val sshdStopping: Boolean, val done: Boolean, val serialRemoved: Boolean = false)

    /** Base64 body of an OpenSSH public key line ("ssh-rsa AAAA… comment" → "AAAA…"). */
    fun keyBody(publicLine: String): String = publicLine.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()

    fun userScript(publicLine: String): String {
        val body = keyBody(publicLine)
        require(Regex("^[A-Za-z0-9+/=]{40,}$").matches(body)) { "not a public key line" }
        return """
AK="${'$'}{LIFELINE_AUTHKEYS_FILE:-${'$'}HOME/.ssh/authorized_keys}"
if [ -f "${'$'}AK" ] && grep -qF '$body' "${'$'}AK"; then
  grep -vF '$body' "${'$'}AK" > "${'$'}AK.lifeline-tmp"; cat "${'$'}AK.lifeline-tmp" > "${'$'}AK"; rm -f "${'$'}AK.lifeline-tmp"; echo KEY_REMOVED
else echo KEY_ABSENT; fi
""".trim()
    }

    fun rootScript(): String = """
STATE="${'$'}{LIFELINE_STATE_DIR:-/var/lib/lifeline}/state"
if [ ! -f "${'$'}STATE" ]; then
  echo NO_STATE
  # Older setups kept no record but may have added "SSH on the USB interface" ufw rules; remove only those.
  if command -v ufw >/dev/null 2>&1; then
    for i in ${'$'}(ufw status 2>/dev/null | sed -n 's#^22/tcp on \(usb[0-9]*\|enx[0-9a-f]*\|rndis[0-9]*\|ncm[0-9]*\) .*ALLOW.*#\1#p' | sort -u); do
      ufw delete allow in on "${'$'}i" to any port 22 proto tcp >/dev/null 2>&1 && echo "UFW_REMOVED ${'$'}i"
    done
  fi
  echo DONE; exit 0
fi
get() { sed -n "s/^${'$'}1=//p" "${'$'}STATE" | head -1; }
SVC="${'$'}(get SVC)"; SVC="${'$'}{SVC:-ssh}"; PORT="${'$'}(get SSH_PORT)"
echo STATE_FOUND
for i in ${'$'}(sed -n 's/^UFW=//p' "${'$'}STATE"); do
  command -v ufw >/dev/null 2>&1 && ufw delete allow in on "${'$'}i" to any port "${'$'}{PORT:-22}" proto tcp >/dev/null 2>&1 && echo "UFW_REMOVED ${'$'}i"
done
if [ "${'$'}(get SERIAL_RULE)" = 1 ]; then
  rm -f "${'$'}{LIFELINE_UDEV_DIR:-/etc/udev/rules.d}/90-lifeline-serial.rules"
  if [ -z "${'$'}LIFELINE_UDEV_DIR" ]; then udevadm control --reload 2>/dev/null; systemctl stop 'serial-getty@ttyACM*.service' 2>/dev/null; fi
  echo SERIAL_REMOVED
fi
if [ "${'$'}(get WAS_ENABLED)" = 0 ]; then systemctl disable "${'$'}SVC" >/dev/null 2>&1; echo SSHD_DISABLED; fi
[ "${'$'}(get SOCK_WAS_ENABLED)" = 0 ] && systemctl disable ssh.socket >/dev/null 2>&1
STOP=0; [ "${'$'}(get WAS_ACTIVE)" = 0 ] && STOP=1
rm -f "${'$'}STATE"; rmdir "${'$'}(dirname "${'$'}STATE")" 2>/dev/null
if [ "${'$'}STOP" = 1 ] && [ -z "${'$'}LIFELINE_NO_STOP" ]; then
  nohup sh -c "sleep 4; systemctl stop ssh.socket ${'$'}SVC" >/dev/null 2>&1 &
  echo SSHD_STOPPING
fi
echo DONE
""".trim()

    /** Same undo as one paste-able block, for when the phone can't connect any more. */
    fun manualCommand(publicLine: String): String =
        "sh <<'LIFELINE_USER'\n${userScript(publicLine)}\nLIFELINE_USER\nsudo sh <<'LIFELINE_ROOT'\n${rootScript()}\nLIFELINE_ROOT"

    fun parse(userOut: String, rootOut: String): Result {
        val r = rootOut.lines().map { it.trim() }
        return Result(
            keyRemoved = userOut.lines().any { it.trim() == "KEY_REMOVED" },
            keyWasAbsent = userOut.lines().any { it.trim() == "KEY_ABSENT" },
            stateFound = "STATE_FOUND" in r,
            ufwRemoved = r.filter { it.startsWith("UFW_REMOVED ") }.map { it.removePrefix("UFW_REMOVED ") },
            sshdDisabled = "SSHD_DISABLED" in r, sshdStopping = "SSHD_STOPPING" in r, done = "DONE" in r,
            serialRemoved = "SERIAL_REMOVED" in r)
    }
}
