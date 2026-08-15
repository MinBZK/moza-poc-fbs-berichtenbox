#!/usr/bin/env bash
# De consumer-helft van de contract-bootstrap: dien een ServiceConnectionGrant in bij de EIGEN
# manager en stel vast of hij geldig is geworden. Raakt de manager van de provider niet aan — daar
# is geen adres, cert of CA voor, dus dat kan ook niet per ongeluk terugsluipen.
#
# Stroom:
#   1. bereken de SPKI-SHA256-thumbprint van het GROUP-cert van de eigen outway (of neem hem uit
#      env). Dat is waarmee de outway zich naar de provider-inway identificeert, en hij is stabiel
#      bij cert-rotatie binnen hetzelfde sleutelpaar;
#   2. staat er al een contract voor deze combinatie? Geldig en door de provider getekend: klaar.
#      Wel ingediend maar nog niet getekend: wachten is aan de provider-helft;
#   3. anders `POST /v1/contracts` op de eigen manager. Die tekent server-side namens ons en synct
#      het contract via de mesh naar de provider.
#
# Uitgangen: 0 = contract geldig, 3 = ingediend maar nog niet getekend, 1 = fout, 2 = configuratie.
# De 3 is geen fout: in de gesplitste opzet draaien de helften onafhankelijk, dus "de provider is
# nog niet langsgeweest" is de normale tussenstand. De aanroeper (lokaal bootstrap.sh, op ZAD de
# lus) draait gewoon opnieuw.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBDIR="${FSC_LIBDIR:-$(cd "${HERE}/../../lib" && pwd)}"

# shellcheck source=../../lib/fsc-harness.sh
. "${LIBDIR}/fsc-harness.sh"
# shellcheck source=../../lib/fsc-contract.sh
. "${LIBDIR}/fsc-contract.sh"

fsc_errlog_init
fsc_have_jq

CONSUMER_OIN="$(fsc_env_vereist FSC_CONSUMER_OIN 'de OIN van deze peer')"
PROVIDER_OIN="$(fsc_env_vereist FSC_PROVIDER_OIN 'de OIN van de aanbiedende peer')"
SERVICE_NAME="$(fsc_env_vereist FSC_SERVICE_NAME 'de dienst die we willen afnemen')"
GROUP_ID="${FSC_GROUP_ID:-moza-fbs-test}"

MANAGER="$(fsc_env_vereist FSC_CONSUMER_MANAGER 'https://<eigen-manager>:9443')"
CERT="$(fsc_env_vereist FSC_CONSUMER_CERT)"
KEY="$(fsc_env_vereist FSC_CONSUMER_KEY)"
CA="$(fsc_env_vereist FSC_CONSUMER_CA)"
ADRES="${FSC_CONSUMER_ADRES:-}"

fsc_contract_manager_ok "$MANAGER" || exit 2

[ "$HAVE_JQ" -eq 1 ] || {
  echo "FAIL: jq is vereist. De idempotentie leunt op het uitlezen van de contract-JSON; zonder jq" >&2
  echo "  zou elke run een nieuw contract aanmaken in plaats van een bestaand te herkennen." >&2
  exit 2
}

api() { fsc_contract_api "$MANAGER" "$CERT" "$KEY" "$CA" "$ADRES" "$@"; }

# De lijst is cursor-gepagineerd met een default van honderd, en bevat álles: publicatiecontracten,
# ingetrokken en afgewezen contracten, van alle peers. In een langlevend deployment groeit dat
# monotoon, en zodra ons eigen contract van pagina 1 valt zou de consumer elke ronde een nieuw
# indienen. Een ruime limiet houdt de guard in fsc-contract.sh een vangrail in plaats van een
# dagelijkse blokkade.
CONTRACT_LIMIET="${FSC_CONTRACT_LIMIET:-1000}"

# --- 1. Outway-thumbprint -----------------------------------------------------------------------
# Uit env óf uit het certificaat. Op ZAD is env de weg: het group-cert van de outway hangt daar aan
# het outway-component, en het aan een tweede component koppelen zou die identiteit verspreiden.
THUMB="${FSC_OUTWAY_THUMBPRINT:-}"

if [ -z "$THUMB" ]; then
  OUTWAY_CERT="$(fsc_env_vereist FSC_OUTWAY_CERT 'of zet FSC_OUTWAY_THUMBPRINT rechtstreeks')"
  command -v openssl >/dev/null 2>&1 || { echo "FAIL: openssl niet gevonden." >&2; exit 2; }

  THUMB="$(fsc_outway_thumbprint "$OUTWAY_CERT")" || {
    echo "FAIL: kon de outway-thumbprint niet berekenen uit ${OUTWAY_CERT}: $(fsc_last_error)" >&2
    exit 1
  }
fi

# Een meegegeven thumbprint gaat ongezien in de grant en bepaalt welke outway de dienst mag afnemen;
# een typefout levert een contract op dat geldig heet en nooit werkt. De waarde wordt bovendien
# ongeëscaped in de JSON-body hieronder geïnterpoleerd, dus een aanhalingsteken erin zou de
# contract-content herschrijven — vandaar een volledige vormcontrole en niet alleen de lengte.
fsc_hex64 "$THUMB" || {
  echo "FAIL: thumbprint is geen 64 lowercase hex-tekens: '${THUMB}'" >&2
  exit 2
}

echo "consumer: outway public-key-thumbprint = ${THUMB}"

# --- 2. Staat er al iets uit? -------------------------------------------------------------------
eigen_contracten() {
  local json
  json="$(api "${MANAGER}/v1/contracts?limit=${CONTRACT_LIMIET}")" || {
    echo "FAIL: kon de eigen contractenlijst niet ophalen: $(fsc_last_error)" >&2
    return 1
  }

  # Met eigen melding, mét de reden erbij: de manager kan met 200 antwoorden en toch iets anders
  # sturen dan een contractenlijst — een ander schema, een proxy-foutpagina, een afgekapt antwoord.
  # "geen geldige JSON" zou daarbij vaak onwaar zijn en de operator de verkeerde kant op sturen.
  fsc_contract_voor_combinatie "$json" "$SERVICE_NAME" "$PROVIDER_OIN" "$CONSUMER_OIN" "$THUMB" | sort || {
    echo "FAIL: de eigen contractenlijst is niet te lezen: $(fsc_last_error)" >&2
    return 1
  }
}

# geldige_hashes <regels>: de hashes uit `<hash> <state> <getekend>`-regels die klaar zijn.
geldige_hashes() {
  printf '%s' "${1:-}" | awk '$2 == "contract_state_valid" && $3 == "ja" { print $1 }'
}

REVOKE_FOUTEN=0
REGELS="$(eigen_contracten)" || exit 1
GELDIG="$(geldige_hashes "$REGELS")"

# `ontbreekt` betekent dat de manager het state-veld niet meestuurde — niet dat het contract
# ongeldig is. Stil als "nog niet klaar" lezen zou hier eeuwig wachten opleveren op iets dat er al
# staat, dus dat geval wordt gemeld.
if printf '%s' "$REGELS" | awk '$2 == "ontbreekt" { gevonden = 1 } END { exit !gevonden }'; then
  echo "WARN: de manager gaf voor minstens één contract geen state terug; die tellen hier niet als geldig." >&2
  printf '%s\n' "$REGELS" | sed 's/^/  /' >&2
fi

if [ -n "$GELDIG" ]; then
  AANTAL="$(printf '%s\n' "$GELDIG" | grep -c .)"
  HASH="$(printf '%s\n' "$GELDIG" | head -n1)"
  echo "OK: er is al een geldig contract voor ${SERVICE_NAME} (${CONSUMER_OIN} -> ${PROVIDER_OIN})."
  printf '%s\n' "$GELDIG" | sed 's/^/  contract: /'

  if [ "$AANTAL" -gt 1 ]; then
    # Kan ontstaan doordat de existence-check en de POST niet atomair zijn: twee gelijktijdige of
    # herhaalde runs kunnen allebei posten. Ze zijn functioneel inwisselbaar — zelfde dienst, zelfde
    # peers, zelfde outway — dus we houden er één aan en trekken de rest in. Welke dat is doet er
    # niet toe zolang de keuze stabiel is over runs heen; vandaar gesorteerd en dan de eerste.
    # `fbs-contracten.sh` leidt het grant-hash voor de `Fsc-Grant-Hash`-header uit datzelfde
    # gesorteerd-eerste contract af, dus beide kanten komen bij hetzelfde uit.
    echo "  ${AANTAL} geldige contracten voor dezelfde combinatie; de overtollige worden ingetrokken." >&2

    while IFS= read -r dup; do
      [ -n "$dup" ] || continue

      # Het hash komt uit de respons en gaat een URL-pad in; een waarde met een schuine streep zou
      # dat pad verleggen naar een ander endpoint.
      if ! fsc_hash_ok "$dup"; then
        echo "  FAIL: contract-hash met een onverwachte vorm overgeslagen: '${dup}'" >&2
        REVOKE_FOUTEN=$((REVOKE_FOUTEN + 1))
        continue
      fi

      if uit="$(api -X PUT "${MANAGER}/v1/contracts/${dup}/revoke" -H 'Content-Type: application/json')"; then
        echo "  ingetrokken: ${dup}" >&2
      else
        # Geteld en niet alleen gemeld: blijft dit mislukken, dan groeit het aantal geldige
        # contracten elke ronde door en is er niets dat dat rood maakt.
        echo "  FAIL: kon duplicaat ${dup} niet intrekken: ${uit:-<leeg>} $(fsc_last_error)" >&2
        REVOKE_FOUTEN=$((REVOKE_FOUTEN + 1))
      fi
    done <<EOF
$(printf '%s\n' "$GELDIG" | tail -n +2)
EOF

    [ "$REVOKE_FOUTEN" -eq 0 ] || {
      echo "FAIL: ${REVOKE_FOUTEN} duplicaat/duplicaten bleven staan." >&2
      exit 1
    }
  fi

  echo "CONSUMER OK (bestaand contract ${HASH})."
  exit 0
fi

if [ -n "$REGELS" ]; then
  # Bewust niet "de provider moet nog tekenen": de derde kolom kan `ja` zijn terwijl het contract
  # hier nog niet geldig is — dan is de handtekening er wel maar de state nog niet, en zou die
  # melding de operator naar de verkeerde helft sturen.
  echo "consumer: contract is ingediend maar hier nog niet geldig:" >&2
  printf '%s\n' "$REGELS" | sed 's/^/  /' >&2
  echo "CONSUMER WACHT (nog niet geldig bij ons)."
  exit 3
fi

# --- 3. Contract opstellen en indienen ----------------------------------------------------------
IV="$(fsc_new_iv)"
fsc_validity

echo "consumer: serviceConnection-contract indienen bij de eigen manager..."
# `service.type` is verplicht op een connection-grant (de publicatie-grant defaultte 'm, deze niet),
# en `outway.identification` is sinds OpenFSC v2.0.0 een union met `type` als discriminator — de
# platte v1-vorm wordt niet meer geaccepteerd. `fsc_version` zetten we niet zelf: de POST neemt
# `createContractContent`, waar dat veld ontbreekt; de manager vult 'm en neemt 'm mee in de hash.
#
# Precies één grant, want dat is wat de provider-helft tekent: een contract met meer grants wordt
# daar geweigerd in plaats van gedeeltelijk geaccepteerd.
RESP="$(api -X POST "${MANAGER}/v1/contracts" -H 'Content-Type: application/json' -d "{
  \"contract_content\": {
    \"iv\": \"${IV}\",
    \"group_id\": \"${GROUP_ID}\",
    \"hash_algorithm\": \"HASH_ALGORITHM_SHA3_512\",
    \"created_at\": ${NBF},
    \"validity\": { \"not_before\": $((NBF - 60)), \"not_after\": ${NAF} },
    \"grants\": [ {
      \"type\": \"GRANT_TYPE_SERVICE_CONNECTION\",
      \"service\": { \"type\": \"SERVICE_TYPE_SERVICE\", \"peer_id\": \"${PROVIDER_OIN}\", \"name\": \"${SERVICE_NAME}\" },
      \"outway\": {
        \"peer_id\": \"${CONSUMER_OIN}\",
        \"identification\": {
          \"type\": \"OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT\",
          \"public_key_thumbprint\": \"${THUMB}\"
        }
      }
    } ]
  }
}")" || { echo "FAIL: POST /v1/contracts geweigerd: ${RESP:-<leeg>} $(fsc_last_error)" >&2; exit 1; }

HASH="$(printf '%s' "$RESP" | jq -r '.content_hash // empty' 2>/dev/null)" || HASH=""
[ -n "$HASH" ] || { echo "FAIL: respons zonder content_hash (formaat geweigerd?): ${RESP}" >&2; exit 1; }

echo "  eigen handtekening gezet; mesh-sync gestart; content_hash=${HASH}"

# Hier niet wachten. De provider-helft is een los proces dat op dit moment nog niet langs is
# geweest, dus elke seconde pollen levert gegarandeerd niets op; de aanroeper draait opnieuw.
echo "CONSUMER WACHT (contract ${HASH} ingediend; provider-helft moet nog tekenen)."
exit 3
