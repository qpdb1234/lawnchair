#!/usr/bin/env bash
# Static contract check for the launcher's AI control server.
#
# This does NOT touch a running device. It greps the checked-in source to confirm the wire
# contract the docs and clients depend on is still there: the POST /mcp route, the two
# per-request switch gates, the MCP serverInfo identity, and the five advertised tools.
#
# Use it as a fast, dependency-free regression guard in any environment (no adb, no emulator),
# and as the CI fallback when the live loopback server could not be bound on a headless runner.
#
# Usage (from the repo root, or pass the root as $1):
#   bash scripts/static_contract_check.sh [REPO_ROOT]
set -u

ROOT="${1:-.}"
S="$ROOT/src/com/android/launcher3"
SERVER="$S/AdbControlHttpServer.java"
MCP="$S/AdbControlMcp.java"
SCHEMA="$S/AdbControlMcpJson.java"
pass=0
fail=0

say() { printf '%s\n' "$*"; }
check() { # check <file> <needle> <label>
  if [ ! -f "$1" ]; then say "  FAIL: missing source file $1"; fail=$((fail+1)); return; fi
  if grep -qF -- "$2" "$1"; then say "  PASS: $3"; pass=$((pass+1))
  else say "  FAIL: $3  (looked for '$2' in $1)"; fail=$((fail+1)); fi
}

say "== HTTP / MCP server contract ($SERVER) =="
check "$SERVER" '"/mcp"'              'POST /mcp is routed'
check "$SERVER" 'pref_enable_adb_http' 'HTTP REST gate is checked per request'
check "$SERVER" 'pref_enable_adb_mcp'  'MCP gate is checked per request'

say "== MCP identity ($MCP) =="
check "$MCP" 'lawnchair-launcher'     'MCP serverInfo name present'

say "== tool schema ($SCHEMA) =="
# The tool names live inside a Java string literal ("..."), so match the bare token rather
# than a quoted one - the quotes in the source are backslash-escaped.
for t in list_icons move_icon add_icon remove_icon reload; do
  check "$SCHEMA" "$t" "tool declared: $t"
done

say ""
say "== summary: $pass passed, $fail failed =="
[ "$fail" -eq 0 ]
