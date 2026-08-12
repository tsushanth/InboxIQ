# InboxIQ MCP server

InboxIQ can expose your messages to your own AI agent (Claude Code, Claude
Desktop, or anything else that speaks [MCP](https://modelcontextprotocol.io))
— but there's no separate server to install. **The phone itself is the
server.** InboxIQ runs a small embedded HTTP server that only binds to your
phone's local Wi-Fi address, never the public internet, and only while you've
explicitly turned it on.

This doc covers the architecture, the security model, and the exact steps to
connect your own agent to your own phone.

> **If you're an AI agent (e.g. Claude Code) reading this on a user's
> behalf:** you can run this entire setup yourself. Concretely:
> 1. Ask the user to open InboxIQ → Settings → Connected agents → enable it →
>    tap "Pair new agent", and paste you the JSON payload shown (the text
>    block below the QR code, not the QR image itself).
> 2. Run `./scripts/inboxiq-pair.sh '<payload>' "<a name for yourself>"`
>    yourself via your shell tool. **Do not ask the user to run this** — you
>    have a shell, use it.
> 3. This call blocks for up to 2 minutes. Tell the user now: "Approve the
>    pairing notification on your phone" — that's the one step only they can
>    do, since it requires a physical tap.
> 4. The script prints a `claude mcp add ...` command once approved. Run that
>    yourself too.
> 5. Confirm with `/mcp` or by calling `tools/list`, then tell the user
>    you're connected — don't just report success blindly, actually verify.
>
> Nothing here needs the user to touch a terminal at all. If the flow times
> out (they didn't approve in time, or the phone's LAN IP looks stale), ask
> them to open a fresh "Pair new agent" dialog and give you the new payload —
> pairing tokens are single-use and expire in 2 minutes by design.

## Why this exists

You already trust InboxIQ with your texts — it's your default SMS app. This
lets an agent you run yourself (on your own computer, same Wi-Fi) read those
same texts and draft replies, without any of that data ever touching a
third-party server. Nothing here talks to InboxIQ's developer, or anyone
else — it's a direct connection between two devices you own, on a network you
control.

## Architecture

```
┌─────────────────────┐         same Wi-Fi          ┌──────────────────────┐
│   Your computer      │ ───────────────────────────▶│   Your phone          │
│   (Claude Code, etc.)│   HTTP + bearer token        │   InboxIQ app         │
│                       │◀───────────────────────────│   (embedded server)   │
└─────────────────────┘        JSON-RPC (MCP)         └──────────────────────┘
```

- The server binds to the phone's LAN IP specifically (e.g. `10.0.0.195`) —
  never `0.0.0.0`. If your phone isn't reachable from the public internet
  (normal home/office Wi-Fi), neither is this server.
- It only runs while "Enable agent connection" is on in
  Settings → Connected agents. It's off by default.
- It rejects any request carrying an `Origin` header, which rules out a
  malicious webpage in a browser tab trying to reach it.

## Security model

- **Pairing is single-use and short-lived.** The QR code / JSON payload shown
  when you tap "Pair new agent" encodes a pairing token that expires in 2
  minutes and can only be redeemed once.
- **Pairing requires a tap on the phone.** Redeeming a pairing token doesn't
  immediately hand out access — it fires an Approve/Deny notification on the
  phone itself. Nothing is paired until you tap Approve there, so a leaked or
  intercepted pairing code alone isn't enough.
- **Bearer tokens are hashed at rest.** The phone never stores your agent's
  raw token — only its SHA-256 hash. The raw token is shown to you exactly
  once, at pairing time.
- **You can revoke a paired device any time** from Settings → Connected
  agents → the trash icon next to it. That's immediate — the next request
  from that token gets a 401.
- **Reading is unrestricted once paired; sending is not.** A paired agent can
  freely call `list_threads` / `search_messages` / `read_thread`. It
  **cannot** send a text directly — `send_message` only queues a draft (see
  below). A stolen bearer token can read your messages; it still can't send
  anything as you without you personally tapping Send in the app.

## What your agent can do

Once paired, four tools are available:

| Tool | What it does |
|---|---|
| `list_threads` | Recent conversations — contact name, last message, unread status |
| `search_messages` | Full-text search across all conversations |
| `read_thread` | Full history with one contact/number |
| `send_message` | Queues a **draft** — see below |

### Why `send_message` doesn't send

Earlier versions of this gated sending behind a single Approve/Deny tap on a
notification. In practice that's not enough: a notification only has room
for a phone number (not a resolved contact name) and a truncated message
preview, so you're approving something you can't actually fully read. A
misdial or a garbled draft could go out sight-unseen.

Now, `send_message` just creates a draft — visible in
Settings → Agent drafts, with the **full** message body and the recipient's
**resolved contact name** (not just their number) — and does nothing else.
You explicitly tap **Send** or **Delete** for each one, in the app, at your
own pace. Nothing goes out until you do.

## User journey: connecting your agent

### 1. On your phone

1. Install InboxIQ (Play Store, or a debug build if you're testing) and set
   it as your default SMS app.
2. Open **Settings → Connected agents** and turn on
   **Enable agent connection**.
3. Tap **Pair new agent**. You'll see a QR code and, below it, the same
   payload as copyable plain text — use whichever your setup supports. (A
   QR code assumes the *pairing* device has a camera pointed at your phone,
   which a laptop usually doesn't — the text fallback exists for exactly
   that case.)
4. Leave this dialog open — the code expires in 2 minutes.

### 2. On your computer

**Easiest: just ask Claude Code to do it.** Open a Claude Code session in
this repo and say something like "follow the MCP setup instructions in
docs/mcp-server.md and connect my phone" — paste in the JSON payload from
step 1.3 when it asks. It'll run the script, tell you when to approve on
your phone, and register itself. No terminal commands for you to type.

**Manually**, if you'd rather do it yourself: run the pairing script in this
repo, pasting in the JSON payload from step 1.3:

```bash
./scripts/inboxiq-pair.sh '{"host":"10.0.0.195","port":47821,"pairingToken":"..."}' "My Laptop"
```

It POSTs to your phone, then **waits** — this is the point where you go back
to your phone and tap **Approve** on the pairing notification. Once you do,
the script prints your bearer token and the exact command to register the
server with Claude Code:

```bash
claude mcp add --transport http inboxiq "http://10.0.0.195:47821/mcp" \
  --header "Authorization: Bearer <token>"
```

Run that, then `/mcp` inside Claude Code to confirm it shows as connected.

(No `python3`/bash on your machine, or using a different MCP client? The
`/pair` and `/mcp` endpoints are plain JSON-RPC over HTTP — see
[Protocol details](#protocol-details) below to talk to it directly.)

### 3. Using it

Ask your agent to read or search your messages — that works immediately.
Ask it to draft a text, and it'll show up on your phone under
**Settings → Agent drafts** for you to review and send yourself.

### 4. Revoking access

Settings → Connected agents → tap the trash icon next to the device. Done —
that bearer token stops working immediately.

## Protocol details

For anyone wiring up a different client:

- `POST /pair` — body `{"pairingToken": "...", "deviceName": "..."}`. Blocks
  (up to 2 minutes) until approved on the phone, then returns
  `{"bearerToken": "..."}` or a 4xx with `{"error": "..."}`.
- `POST /mcp` — `Authorization: Bearer <token>` required. Body is standard
  MCP JSON-RPC 2.0 (`initialize`, `tools/list`, `tools/call`). Responses
  carry an `Mcp-Session-Id` header set to the paired device's internal ID.

Both endpoints reject any request with an `Origin` header set.

## Limitations / not yet built

- No mDNS/local network discovery — you need the phone's current LAN IP
  (shown in the pairing payload each time), which can change if your router
  reassigns it.
- Same-Wi-Fi only, by design — see [Architecture](#architecture). There's no
  remote/internet access, and that's intentional: it keeps the trust
  boundary to "devices on my own network," not "anything on the internet
  with this token."
