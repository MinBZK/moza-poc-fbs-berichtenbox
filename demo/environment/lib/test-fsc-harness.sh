#!/usr/bin/env bash
# Fixture-test voor fsc_scrub_errlog: toetst tegen de exacte ANSI-bytes uit de PR-166-bug-report
# (ericwout-overheid, 2026-08-12) — reproductie van de podman-external-compose-provider-banner.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fsc-harness.sh
source "$HERE/fsc-harness.sh"

fails=0

assert_empty_after_scrub() {
  local desc="$1" content="$2"
  ERRLOG=$(mktemp)
  printf '%s' "$content" > "$ERRLOG"
  fsc_scrub_errlog
  if [ -s "$ERRLOG" ]; then
    echo "FAIL: $desc — verwacht leeg na scrub, kreeg: $(cat "$ERRLOG")" >&2
    fails=$((fails + 1))
  else
    echo "OK: $desc"
  fi
  rm -f "$ERRLOG"
}

assert_survives_scrub() {
  local desc="$1" content="$2" expect_substr="$3"
  ERRLOG=$(mktemp)
  printf '%s' "$content" > "$ERRLOG"
  fsc_scrub_errlog
  if grep -qF "$expect_substr" "$ERRLOG"; then
    echo "OK: $desc"
  else
    echo "FAIL: $desc — verwachtte '$expect_substr' te overleven, kreeg: $(cat "$ERRLOG")" >&2
    fails=$((fails + 1))
  fi
  rm -f "$ERRLOG"
}

# Exacte bytes uit de bug-report: ESC[4m vóór de banner, lege regel, losse ESC[0m.
BANNER=$'\033[4m>>>> Executing external compose provider "/home/claude/.local/bin/docker-compose". Please note this can fail with unexpected errors.\n\n\033[0m\n'

assert_empty_after_scrub "banner-only wordt leeg (geen vals alarm)" "$BANNER"

REAL_ERROR="${BANNER}curl: (7) Failed to connect to manager.logius.fsc-test.local port 9443: Connection refused
"
assert_survives_scrub "echte curl-fout blijft zichtbaar na scrub" "$REAL_ERROR" "Connection refused"

if [ "$fails" -eq 0 ]; then
  echo "ALLE ASSERTS GROEN"
  exit 0
else
  echo "FAIL: $fails assert(s) gefaald" >&2
  exit 1
fi
