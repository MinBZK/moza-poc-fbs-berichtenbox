#!/usr/bin/env bash
# Onboarding: maakt de dienst berichtenmagazijn aan op de controller Administration-API en
# publiceert 'm via een servicePublication-contract op de eigen manager Internal-API.
# Idempotent: slaat create/publish over als ze er al zijn. Manager hasht+signt het
# contract server-side; de directory (AUTO_SIGN_GRANTS=servicePublication) auto-accept.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../../../lib/fsc-harness.sh
source "$HERE/../../../lib/fsc-harness.sh"

# shellcheck disable=SC2034  # gelezen door fsc_tb() uit de caller-scope (lib/fsc-harness.sh).
COMPOSE=(docker compose -f "$HERE/docker-compose.yaml")
SERVICE_NAME="berichtenmagazijn"
PROVIDER_OIN="00000000000000100000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"                 # = GROUP_ID env-var op de manager; als de manager een directory-adres verwacht, gebruik DIRECTORY_MANAGER_ADDRESS
# De federatie-opstelling (../../../federatie/) geeft elke component een eigen adres; vandaar
# overrulebaar, met de standalone-waarde als default. Geprefixt met FSC_ omdat een kale `MANAGER=`
# in iemands shell anders stilzwijgend een mTLS-call mét het internal-cert van deze peer naar een
# vreemd endpoint zou sturen.
STUB_URL="${FSC_STUB_URL:-http://stub-upstream:8080}"

# shellcheck disable=SC2034  # gelezen door fsc_tb() uit de caller-scope (lib/fsc-harness.sh).
CERT=/pki/internal/magazijn-a/manager/cert.pem
# shellcheck disable=SC2034  # gelezen door fsc_tb() uit de caller-scope (lib/fsc-harness.sh).
KEY=/pki/internal/magazijn-a/manager/key.pem
# shellcheck disable=SC2034  # gelezen door fsc_tb() uit de caller-scope (lib/fsc-harness.sh).
CA=/pki/internal/magazijn-a/ca/root.pem
CONTROLLER="${FSC_CONTROLLER:-https://controller.magazijn-a.fsc-test.local:9444}"
MANAGER="${FSC_MANAGER:-https://manager.magazijn-a.fsc-test.local:9443}"

# De adressen worden geconcateneerd tot curl's URL-argument. Een waarde die met `-` begint leest
# curl als OPTIE — `-K/pad` maakt er een config-file-lees van — dus eisen we het https-schema.
case "$CONTROLLER" in
  https://*) ;;
  *) echo "FAIL: FSC_CONTROLLER moet met https:// beginnen: '${CONTROLLER}'" >&2; exit 2 ;;
esac
case "$MANAGER" in
  https://*) ;;
  *) echo "FAIL: FSC_MANAGER moet met https:// beginnen: '${MANAGER}'" >&2; exit 2 ;;
esac

# STUB_URL gaat ongeëscaped een handgebouwde JSON-body in (CreateService, hieronder). Een
# aanhalingsteken zou daar uit de string breken en velden kunnen toevoegen.
case "$STUB_URL" in
  http://*|https://*) ;;
  *) echo "FAIL: FSC_STUB_URL moet met http:// of https:// beginnen: '${STUB_URL}'" >&2; exit 2 ;;
esac
case "$STUB_URL" in
  *'"'*|*'\'*) echo "FAIL: FSC_STUB_URL bevat een aanhalingsteken of backslash." >&2; exit 2 ;;
esac

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
fsc_errlog_init

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(fsc_tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.magazijn-a\.fsc-test\.local:443' | head -1 || true)
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  fsc_warn_errlog "controller-fout"
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if fsc_tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  fsc_tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if fsc_tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else
  # UUID + timestamp zijn host-lokaal (geen container-context nodig) -> host-builtins i.p.v. toolbox-exec.
  # UUID v4 (36 tekens): /proc is Linux-only, op macOS valt 'ie terug op uuidgen (lowercase).
  # Als de manager 400 geeft op het iv-formaat, genereer UUID v7.
  IV=$(fsc_new_iv)
  # Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan achterlopen; de manager
  # weigert dan created_at "in the future" (HTTP 500). Backdate met een skew-marge — op Linux is de
  # skew ~0, dus onschadelijk. Blijft persistent falen? Herstart de Docker-VM (klok resynct).
  fsc_validity
  # --fail-with-body laat curl bij 4xx/5xx non-zero exiten MAAR print de body; vang beide zodat
  # `set -e` ons niet vóór de diagnostiek killt en de manager-respons zichtbaar is.
  RESP=$(fsc_tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
    \"contract_content\": {
      \"iv\": \"$IV\",
      \"group_id\": \"$GROUP_ID\",
      \"hash_algorithm\": \"HASH_ALGORITHM_SHA3_512\",
      \"created_at\": $NBF,
      \"validity\": { \"not_before\": $((NBF - 60)), \"not_after\": $NAF },
      \"grants\": [ {
        \"type\": \"GRANT_TYPE_SERVICE_PUBLICATION\",
        \"directory\": { \"peer_id\": \"$DIR_OIN\" },
        \"service\": { \"peer_id\": \"$PROVIDER_OIN\", \"name\": \"$SERVICE_NAME\", \"protocol\": \"PROTOCOL_TCP_HTTP_1.1\" }
      } ]
    }
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }
  # Een 2xx zónder content_hash duidt op een geweigerd formaat (iv/group_id).
  printf '%s' "$RESP" | grep -q '"content_hash"' \
    || { echo "FAIL: contract-respons zonder content_hash (mogelijk geweigerd iv/group_id-formaat): $RESP" >&2; exit 1; }
  echo "  contract ingediend (manager signt; directory auto-accept): $RESP"
fi
echo "publish: klaar."
