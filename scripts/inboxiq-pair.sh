#!/usr/bin/env bash
# Redeems an InboxIQ pairing payload (from Settings -> Connected agents -> Pair new
# agent on the phone) into a bearer token, and prints a ready-to-run `claude mcp add`
# command. See ../docs/mcp-server.md for the full walkthrough.
#
# Usage:
#   ./inboxiq-pair.sh '<pairing JSON pasted from the phone>' ["My Laptop"]
#
# Example:
#   ./inboxiq-pair.sh '{"host":"10.0.0.195","port":47821,"pairingToken":"abc123"}' "Sushanth's MacBook"

set -euo pipefail

PAYLOAD="${1:-}"
DEVICE_NAME="${2:-$(hostname)}"

if [ -z "$PAYLOAD" ]; then
  echo "Usage: $0 '<pairing JSON from the phone>' [\"device name\"]" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required (used to parse/build JSON without extra dependencies)." >&2
  exit 1
fi

read -r HOST PORT PAIRING_TOKEN <<EOF
$(python3 -c '
import json, sys
p = json.loads(sys.argv[1])
print(p["host"], p["port"], p["pairingToken"])
' "$PAYLOAD")
EOF

echo "Requesting pairing with InboxIQ at $HOST:$PORT ..."
echo "Check your phone now — approve the notification within 2 minutes."

BODY=$(python3 -c '
import json, sys
print(json.dumps({"pairingToken": sys.argv[1], "deviceName": sys.argv[2]}))
' "$PAIRING_TOKEN" "$DEVICE_NAME")

RESPONSE=$(curl -sS -X POST "http://$HOST:$PORT/pair" \
  -H "Content-Type: application/json" \
  -d "$BODY" \
  -m 130)

BEARER_TOKEN=$(python3 -c '
import json, sys
r = json.loads(sys.argv[1])
if "bearerToken" not in r:
    print("ERROR: " + r.get("error", "unknown error"), file=sys.stderr)
    sys.exit(1)
print(r["bearerToken"])
' "$RESPONSE")

echo ""
echo "Paired. Bearer token: $BEARER_TOKEN"
echo ""
echo "Register this with Claude Code:"
echo ""
echo "  claude mcp add --transport http inboxiq \"http://$HOST:$PORT/mcp\" \\"
echo "    --header \"Authorization: Bearer $BEARER_TOKEN\""
echo ""
echo "Then run /mcp in Claude Code to confirm it's connected."
