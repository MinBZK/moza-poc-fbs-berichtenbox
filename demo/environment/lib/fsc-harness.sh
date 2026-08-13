#!/usr/bin/env bash
# Gedeelde helpers voor de lokale FSC-peer-harnessen onder demo/environment/<peer>/deploy/local/.
# Peer-identiteit (OIN's, servicenamen, cert-paden, MANAGER/CONTROLLER-URL's, TIMEOUT/INTERVAL,
# GROUP_ID) staat in elk script zelf — hier alleen het generieke idioom. bash 3.2-compatibel
# (macOS-default, zie smoke-services.sh): geen associative arrays, geen mapfile, geen ${var^^}.

# --- ERRLOG-levenscyclus --------------------------------------------------------------------

# fsc_errlog_init: mktemp + trap, zet de globale $ERRLOG. Aanroepen ná `set -euo pipefail`.
fsc_errlog_init() {
  ERRLOG=$(mktemp)
  trap 'rm -f "$ERRLOG"' EXIT
}

# fsc_scrub_errlog [bestand]: verwijdert de podman-external-compose-provider-banner (met zijn
# SGR-ANSI-omhulsel, bv. ESC[4m vóór de tekst) uit het gegeven bestand (default $ERRLOG), in
# place. ANSI eerst strippen (portable LC_ALL=C-vorm i.p.v. \x1b, een GNU-sed-extensie die
# BSD-sed/macOS niet kent), dan zonder regelanker filteren (de banner start niet op kolom 1
# door de ANSI-prefix), dan lege regels weggooien die overblijven na het strippen van de losse
# ESC[0m-regel.
fsc_scrub_errlog() {
  local file="${1:-$ERRLOG}"
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$file" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${file}.f" 2>/dev/null || :
  mv -f "${file}.f" "$file"
}

# fsc_warn_errlog "<prefix>": het poll-loop-idioom — scrub, en als er dan nog iets overblijft,
# print een WARN met de laatste regel en truncate. Retourneert altijd 0 (mag een poll-lus onder
# `set -e` nooit laten stoppen).
fsc_warn_errlog() {
  local prefix="$1"
  fsc_scrub_errlog
  if [ -s "$ERRLOG" ]; then
    echo "  WARN: ${prefix}: $(tail -n1 "$ERRLOG")" >&2
    : > "$ERRLOG"
  fi
  return 0
}

# fsc_last_error [n]: scrub + print de laatste n regels (default 1) van $ERRLOG. Voor gebruik
# in FAIL-strings (command substitution) of op de FAIL-paden. Print niets als leeg; faalt nooit.
fsc_last_error() {
  local n="${1:-1}"
  fsc_scrub_errlog
  tail -n "$n" "$ERRLOG" 2>/dev/null
  return 0
}

# --- curl-in-toolbox -------------------------------------------------------------------------

# fsc_tb <curl-args...>: curl binnen de toolbox-container, met de internal-client-cert van de
# caller. Leest $COMPOSE/$CERT/$KEY/$CA uit de caller-scope (peer-specifiek, hier niet gezet).
fsc_tb() {
  "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
    --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"
}

# --- contract-bootstrap-helpers (consume-service.sh) ------------------------------------------

# fsc_new_iv: UUID v4. /proc is Linux-only; op macOS valt terug op uuidgen (lowercase).
fsc_new_iv() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]'
}

# fsc_validity: zet de globale $NBF/$NAF (created_at resp. not_after, +10 jaar geldig) met een
# skew-backdate — Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan
# achterlopen; de manager weigert dan created_at "in the future" (HTTP 500). Op Linux is de
# skew ~0, dus onschadelijk.
fsc_validity() {
  NBF=$(( $(date -u +%s) - 60 ))
  NAF=$((NBF + 315360000))
}

# fsc_have_jq: zet de globale $HAVE_JQ (1/0).
fsc_have_jq() {
  HAVE_JQ=0
  command -v jq >/dev/null 2>&1 && HAVE_JQ=1
  return 0
}

# fsc_accept_state <json> <content_hash> <oin>: "yes"/"no"/"unknown" — draagt het contract de
# accept-handtekening van $oin?
fsc_accept_state() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg oin "$3" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | if ($c | length) == 0 then "unknown"
      elif ([ $c[] | .signatures?.accept? | objects ] | length) == 0 then "unknown"
      elif ($c | any((.signatures?.accept? // {}) | has($oin))) then "yes"
      else "no" end' 2>/dev/null || echo unknown
}

# fsc_contract_state <json> <content_hash>: manager-state (bv. "valid"), "unknown" zonder jq/match.
fsc_contract_state() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h) | .state?]
    | map(select(. != null)) | (first // "unknown") | ascii_downcase' 2>/dev/null || echo unknown
}

# fsc_grant_hash <json> <content_hash> <service_name> <outway_thumbprint>: het GRANT-hash uit
# content.grants[] (niet het contract-hash zelf) — matcht op service.name + outway-thumbprint
# zodat dit ook klopt zodra een contract ooit meer dan één grant draagt.
fsc_grant_hash() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg svc "$3" --arg thumb "$4" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | [$c[] | (.content?.grants? // [])[]
         | select(.service?.name == $svc and .outway?.identification?.public_key_thumbprint == $thumb)
         | .hash?] as $g
    | ($g[0] // "unknown")' 2>/dev/null || echo unknown
}
