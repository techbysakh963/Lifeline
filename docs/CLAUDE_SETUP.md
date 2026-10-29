# Using a subscription you already pay for (Claude, ChatGPT, Google AI)

No API key and no Termux. Lifeline runs the **official** CLI inside its own small Ubuntu (PRoot, in the app's private
storage) and you sign in to it yourself.

1. Lifeline → **AI brain** → pick **Claude**, **ChatGPT** or **Gemini** (subscription row).
2. Tap **Install**. The first time this downloads Ubuntu 24.04 (about 30 MB) and then the CLI:
   Claude Code (~280 MB, from downloads.claude.ai), Codex (~100 MB, OpenAI's GitHub releases) or
   Antigravity `agy` (~40 MB, Google). Every download is checked against the publisher's own checksum.
3. Tap **Sign in** and use your own account:
   - **Claude:** open the sign-in page, sign in, copy the code it shows, paste it back into Lifeline.
   - **ChatGPT:** Lifeline shows a one-time code; open the page and type that code there. Nothing to paste back.
   - **Gemini (agy):** open the Google sign-in page, sign in, copy the code, paste it back.
   Lifeline passes the code straight to the CLI; it never stores or logs it, and never sees your password.
   Only subscription sign-ins are used (an API-key login is refused, so nothing is billed per token).
4. **Save & test connection.**

How it works: the CLI's own tools are off; it only asks, and Lifeline runs every action with your approval. Usage counts
against your plan's limits (shared with that CLI elsewhere).
- **Long session (on by default, each CLI's card):** one session for the whole rescue (Claude and agy: one process;
  Codex: each message resumes the same thread). Lifeline offers its tools to it over
  MCP on the phone's loopback (127.0.0.1, a fresh secret token each session), so follow-ups are faster and use less of the
  plan. Approvals work the same. Save & test connection checks it with one real tool call. Switch it off on the card to
  go back to one run per step.
- Off: each step is one non-interactive CLI run.

Update a CLI with **Update** on its card. **Remove built-in Ubuntu** (AI brain, or Settings › Storage) deletes all
three CLIs and their sign-ins.

Code: `app/src/main/java/com/rodinrescue/runtime/` (Ubuntu runner, installer, sign-in) and
`app/src/main/java/com/rodinrescue/ai/CliProviders.kt` (the three brains).
