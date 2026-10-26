#!/usr/bin/env bash
# Read-only prompt-injection scanner for this repository.
#
# WHAT IT DOES
#   Greps the checked-in working tree for *imperative* prompt-injection payloads - the kind of
#   text that, if it ever shows up inside a command's output or a fetched file, is trying to
#   masquerade as your instructions and steer an AI assistant (fake "system prompt", "ignore all
#   previous instructions", an approval gate that claims to override you, etc.).
#
# WHAT IT DOES NOT DO
#   It never edits anything, and it does NOT flag ordinary technical prose. Generic words like
#   "system prompt" (which this repo's own AI docs legitimately use) are deliberately left out;
#   only multi-word command phrases are matched, so the docs here are not false positives.
#
# This is a repo-hygiene guard, not an endpoint AV. A live injection can ride transiently in a
# tool result without ever being written to disk, in which case nothing on disk will match -
# that is expected and is exactly why such text must simply be treated as untrusted and ignored.
#
# Usage:
#   bash scripts/scan_prompt_injection.sh [REPO_ROOT]      # advisory: prints findings, exit 0
#   bash scripts/scan_prompt_injection.sh --strict [ROOT]  # exit 1 if any payload is found
set -u

STRICT=0
ROOT="."
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    *) ROOT="$arg" ;;
  esac
done

if [ ! -d "$ROOT" ]; then
  echo "not a directory: $ROOT" >&2
  exit 2
fi

# High-specificity imperative payloads. Kept lowercase; matched case-insensitively.
# Each entry is a fixed string (grep -F), so no regex metacharacters here.
PATTERNS='ignore all previous instructions
ignore the previous instructions
ignore prior instructions
ignore all above instructions
disregard all previous instructions
override all previous instructions
override the previous instructions
forget all previous instructions
you are now operating as
do not tell the user
dont tell the user
do not inform the user
<proposed plan>
approve this plan before
new system prompt
updated system prompt
this is your new system prompt
permission protocol takes precedence
switch to codex
enter codex mode
act as the codex
the following instruction supersedes'

echo "== prompt-injection scan: $ROOT =="

TMP="$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/pscan.$$")"
: > "$TMP"

# Prefer git grep (searches tracked files) inside a repo; else fall back to a plain recursive grep.
if command -v git >/dev/null 2>&1 && git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  INGIT=1
else
  INGIT=0
fi

scan_one() { # scan_one <pattern>
  if [ "$INGIT" -eq 1 ]; then
    git -C "$ROOT" grep -nIi -F -- "$1" -- . 2>/dev/null
  else
    grep -rIni -F --exclude-dir=.git -- "$1" "$ROOT" 2>/dev/null
  fi | grep -v 'scan_prompt_injection\.sh' \
       | while IFS= read -r line; do printf '  [%s] %s\n' "$1" "$line"; done >> "$TMP"
}

while IFS= read -r pat; do
  [ -z "$pat" ] && continue
  scan_one "$pat"
done <<EOF
$PATTERNS
EOF

count=$(wc -l < "$TMP" 2>/dev/null | tr -d '[:space:]')
[ -z "$count" ] && count=0

if [ "$count" -eq 0 ]; then
  echo "  PASS: no prompt-injection payloads found in the tracked tree"
  rm -f "$TMP"
  exit 0
fi

echo "  WARN: $count matching line(s) - review each before trusting any assistant/tool text:"
sed 's/^/    /' "$TMP"
rm -f "$TMP"

if [ "$STRICT" -eq 1 ]; then
  exit 1
fi
exit 0
