#!/usr/bin/env bash
# Onboarding: maakt de dienst profiel-service aan op de controller Administration-API en
# publiceert 'm via een servicePublication-contract op de eigen manager Internal-API.
# Idempotent: slaat create/publish over als ze er al zijn. Manager hasht+signt het
# contract server-side; de directory (AUTO_SIGN_GRANTS=servicePublication) auto-accept.
set -euo pipefail

COMPOSE=(docker compose -f "$(dirname "$0")/docker-compose.yaml")
SERVICE_NAME="profiel-service"
PROVIDER_OIN="00000000000000001000"
DIR_OIN="00000000000000000010"
GROUP_ID="moza-fbs-test"
STUB_URL="http://stub-upstream:8080"

CERT=/pki/internal/logius/manager/cert.pem
KEY=/pki/internal/logius/manager/key.pem
CA=/pki/internal/logius/ca/root.pem
CONTROLLER=https://controller.logius.fsc-test.local:9444
MANAGER=https://manager.logius.fsc-test.local:9443

# Vang curl-/toolbox-stderr op i.p.v. weg te gooien: een mTLS-/netwerk-/dode-container-fout
# mag niet als "nog niet klaar" maskeren (spiegelt smoke-announce.sh). Surface 'm in de loop.
ERRLOG=$(mktemp)
trap 'rm -f "$ERRLOG"' EXIT

# curl binnen de toolbox, met de internal client-cert. Stderr -> ERRLOG.
tb() { "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
         --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"; }

# Onder podman schrijft de external-compose-provider-wrapper zelf een bannerregel naar stderr
# bij ELKE aanroep; dat is geen curl-fout. Filteren voorkomt vals-alarm-WARN's en een
# misleidende "laatste fout" op de FAIL-paden hieronder.
strip_wrapper_noise() {
  # De banner draagt SGR-ANSI-codes (bv. ESC[4m vóór de tekst), dus een anker op regelbegin mist
  # 'm; ANSI eerst strippen (portable-vorm i.p.v. \x1b, een GNU-sed-extensie die BSD-sed/macOS
  # niet kent), dan zonder anker filteren, en de lege regel weggooien die overblijft na het
  # strippen van de losse ESC[0m-regel.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$ERRLOG" \
    | grep -v 'Executing external compose provider' \
    | grep -v '^[[:space:]]*$' > "${ERRLOG}.f" 2>/dev/null || :
  mv -f "${ERRLOG}.f" "$ERRLOG"
}

echo "publish: wachten op inway-registratie bij de controller..."
# inway->controller-registratie is asynchroon na boot; poll (spiegelt smoke-announce.sh)
# i.p.v. één harde fetch, anders racet een koude start de eerste publish-run.
INWAY_ADDR=""
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  # CreateService verwacht het inway-ADRES (https://...:443, = SELF_ADDRESS), niet de naam.
  INWAY_ADDR=$(tb "$CONTROLLER/v1/inways" | grep -o 'https://inway\.logius\.fsc-test\.local:443' | head -1 || true)
  strip_wrapper_noise
  [ -n "$INWAY_ADDR" ] && break
  # Persistente fout (verkeerd cert-pad, dode toolbox, DNS) mag niet als "traag boot" maskeren.
  [ -s "$ERRLOG" ] && { echo "  WARN: controller-fout: $(tail -n1 "$ERRLOG")" >&2; : >"$ERRLOG"; }
  sleep 5; elapsed=$((elapsed + 5))
  echo "  ...inway nog niet geregistreerd (${elapsed}s)"
done
[ -n "$INWAY_ADDR" ] || { echo "FAIL: geen geregistreerde inway op de controller binnen 60s." >&2; exit 1; }
echo "  inway_address=$INWAY_ADDR"

echo "publish: $SERVICE_NAME aanmaken (idempotent)..."
if tb "$CONTROLLER/v1/services" | grep -q "\"$SERVICE_NAME\""; then
  echo "  bestaat al, skip create."
else
  tb -X POST "$CONTROLLER/v1/services" -H 'Content-Type: application/json' \
     -d "{\"name\":\"$SERVICE_NAME\",\"endpoint_url\":\"$STUB_URL\",\"inway_address\":\"$INWAY_ADDR\"}"
  echo "  aangemaakt."
fi

echo "publish: servicePublication-contract indienen (idempotent)..."
if tb "$MANAGER/v1/services/publications" | grep -q "\"$SERVICE_NAME\""; then
  echo "  al gepubliceerd, skip contract."
else
  # UUID + timestamp zijn host-lokaal (geen container-context nodig) -> host-builtins i.p.v. toolbox-exec.
  # UUID v4 (36 tekens): /proc is Linux-only, op macOS valt 'ie terug op uuidgen (lowercase).
  # Als de manager 400 geeft op het iv-formaat, genereer UUID v7.
  IV=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]')
  # Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan achterlopen; de manager
  # weigert dan created_at "in the future" (HTTP 500). Backdate met een skew-marge — op Linux is de
  # skew ~0, dus onschadelijk. Blijft persistent falen? Herstart de Docker-VM (klok resynct).
  NBF=$(( $(date -u +%s) - 60 ))
  NAF=$((NBF + 315360000))                 # +10 jaar
  # --fail-with-body laat curl bij 4xx/5xx non-zero exiten MAAR print de body; vang beide zodat
  # `set -e` ons niet vóór de diagnostiek killt en de manager-respons zichtbaar is.
  RESP=$(tb -X POST "$MANAGER/v1/contracts" -H 'Content-Type: application/json' -d "{
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
  }") || { echo "FAIL: POST /v1/contracts geweigerd (iv=$IV): ${RESP:-<leeg>} $(strip_wrapper_noise; tail -n1 "$ERRLOG" 2>/dev/null)" >&2; exit 1; }
  # Een 2xx zónder content_hash duidt op een geweigerd formaat (iv/group_id).
  printf '%s' "$RESP" | grep -q '"content_hash"' \
    || { echo "FAIL: contract-respons zonder content_hash (mogelijk geweigerd iv/group_id-formaat): $RESP" >&2; exit 1; }
  echo "  contract ingediend (manager signt; directory auto-accept): $RESP"
fi
echo "publish: klaar."
