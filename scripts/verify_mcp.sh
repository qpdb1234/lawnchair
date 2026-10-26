#!/usr/bin/env bash
# End-to-end smoke test for the launcher's MCP + HTTP control server.
#
# What it checks, in order:
#   1. GET  /help          -> the always-on usage page (proves the port is reachable)
#   2. POST /mcp initialize-> the MCP handshake answers with serverInfo lawnchair-launcher
#   3. POST /mcp tools/list-> the five tools are advertised
#   4. POST /mcp tools/call-> a read-only list_icons call comes back without isError
#
# It only ever READS; it never moves/adds/removes anything, so it is safe to run against a
# real home screen. Requires curl. Run on the PC after `adb forward`, or in Termux on-device.
#
# Usage:
#   ./verify_mcp.sh [BASE_URL]      # BASE_URL default: http://127.0.0.1:9091
set -u

BASE="${1:-http://127.0.0.1:9091}"
MCP="$BASE/mcp"
pass=0
fail=0

say()  { printf '%s\n' "$*"; }
ok()   { say "  PASS: $*"; pass=$((pass+1)); }
bad()  { say "  FAIL: $*"; fail=$((fail+1)); }

# json_extract helper: prefers jq, falls back to grep -o so the script still runs without jq.
has() { # has "<needle>" "<haystack>"
  case "$2" in *"$1"*) return 0 ;; *) return 1 ;; esac
}

say "== 0. reachability (GET /help) =="
help_body="$(curl -fsS --max-time 10 "$BASE/help" 2>&1)" || {
  say "  cannot reach $BASE/help -> $help_body"
  say "  is a channel enabled (Settings -> General -> AI access channels) and 'adb forward tcp:9091 tcp:9091' running?"
  exit 2
}
has "control API" "$help_body" && ok "/help served the usage page" || bad "/help unexpected body"

say "== 1. MCP initialize =="
r="$(curl -fsS --max-time 15 -X POST "$MCP" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' 2>&1)" \
  && has "lawnchair-launcher" "$r" && ok "serverInfo present" || bad "initialize -> $r"

say "== 2. MCP tools/list =="
r="$(curl -fsS --max-time 15 -X POST "$MCP" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' 2>&1)" || { bad "tools/list -> $r"; r=""; }
for t in list_icons move_icon add_icon remove_icon reload; do
  has "\"$t\"" "$r" && ok "tool advertised: $t" || bad "missing tool: $t"
done

say "== 3. MCP tools/call list_icons (read-only) =="
r="$(curl -fsS --max-time 20 -X POST "$MCP" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_icons","arguments":{}}}' 2>&1)" || { bad "tools/call -> $r"; r=""; }
if has '"isError":true' "$r"; then bad "list_icons returned an error"; else ok "list_icons ok"; fi
say "  response: $(printf '%s' "$r" | cut -c1-200)"

say ""
say "== summary: $pass passed, $fail failed =="
[ "$fail" -eq 0 ]
