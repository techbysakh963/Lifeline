# Roadmap

Lifeline is in beta. Here's what's in the app today and what's coming next.

## Available now · v0.6

**Three ways into a broken PC**
- **SSH rescue** for Linux PCs: one-tap setup over the USB cable, works with a VPN on the phone, and
  *Remove from this laptop* undoes everything Lifeline changed.
- **Keyboard rescue** for any PC: the phone's camera shows the AI the screen and the phone types as a USB keyboard.
  Works in boot menus, consoles and Windows recovery; hold the phone or put it on a stand. **Take control** lets you
  type passwords the AI never sees.
- **Boot tab:** the phone becomes a USB drive. Boot the Lifeline rescue system (Secure Boot supported) or any live
  Linux or installer, with optional saved space that survives reboots.

**AI brains**
- Claude, ChatGPT and Gemini subscriptions, signed in with your own account. Their official tools run in Lifeline's
  built-in Linux, with nothing else to install.
- API keys: Claude, OpenAI, Gemini, DeepSeek.

**Safety**
- Read-only first; every change is an approval card with the evidence, the exact command and whether it can be undone.
- Destructive steps need a typed `DELETE`; formatting, partitioning, bootloader and firmware writes are never run.
- Command output is treated as untrusted, secrets are redacted before they reach the AI, and every step is logged.
- Optional app lock (fingerprint or screen lock), and *Remove access when I disconnect* for one-time repairs.

**And more**
- **Type on PC:** write, paste or dictate on the phone, typed on the PC.
- **USB serial:** a terminal (and the AI) over the PC's serial login, with no network.
- Saved devices and conversations, Storage & cleanup.

## Next
- More boot-menu and BIOS/UEFI setup coverage in keyboard rescue, and fewer AI turns per fix.
- Mouse control and more keyboard layouts in keyboard rescue.
- Simpler, tidier screens.

## Later
- Windows-native repair beyond the recovery environment.
- An offline AI that runs on the phone.

Have an idea or found a bug? [Open an issue](https://github.com/techbysakh963/Lifeline/issues).
