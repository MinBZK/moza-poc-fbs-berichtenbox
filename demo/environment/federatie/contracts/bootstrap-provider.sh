#!/usr/bin/env bash
# De provider-helft van de contract-bootstrap: teken de binnengekomen ServiceConnectionGrants op de
# EIGEN manager. Raakt de manager van de consumer niet aan — daar is geen adres, cert of CA voor,
# dus dat kan ook niet per ongeluk terugsluipen.
#
# Welk contract getekend mag worden, besluit `fsc_contract_beoordeling()`; lees daar waarom "precies
# één grant" de eis is en waarom van de thumbprint wel het type maar niet de waarde telt.
#
# Uitgangen:
#   0  klaar — er is iets getekend, of alles wat ons aangaat stond er al
#   1  fout — een call mislukte, of een contract kon niet getekend worden
#   2  configuratie deugt niet
#   3  er is nog niets van een toegelaten consumer binnengekomen — wachten op de overkant
#   4  er is niets getekend én er ligt iets dat aandacht vraagt: een contract dat de toets niet
#      haalt, of een contract dat wij ooit tekenden en dat de toets nu niet meer haalt
#
# De 3 en de 4 zijn er omdat "niets gedaan" drie heel verschillende dingen kan betekenen. Zonder
# die twee meldt een lege lijst hetzelfde als een gezonde ronde, en een verkeerd gezette allowlist
# ook — waarna de aanroeper een uur gaat slapen terwijl de consumer elke vijftien seconden wacht.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBDIR="${FSC_LIBDIR:-$(cd "${HERE}/../../lib" && pwd)}"

# shellcheck source=../../lib/fsc-harness.sh
. "${LIBDIR}/fsc-harness.sh"
# shellcheck source=../../lib/fsc-contract.sh
. "${LIBDIR}/fsc-contract.sh"

fsc_errlog_init
fsc_have_jq

PROVIDER_OIN="$(fsc_env_vereist FSC_PROVIDER_OIN 'de OIN van deze peer')"

# Welke diensten wij aanbieden en welke consumers een contract van ons mogen krijgen. Dit is de
# autorisatiegrens: alles wat er niet in staat, tekenen we niet.
DIENSTEN="$(fsc_env_vereist FSC_DIENSTEN 'dienstnamen die deze peer aanbiedt, gescheiden door witruimte')"
CONSUMERS="$(fsc_env_vereist FSC_CONSUMERS 'consumer-OINs die een contract mogen krijgen, gescheiden door witruimte')"

GROUP_ID="${FSC_GROUP_ID:-moza-fbs-test}"

# Bovengrens op de RESTERENDE geldigheid (`not_after` min nu), niet op de vensterlengte: de vraag is
# hoe lang een tegenpartij een claim op ons kan houden. De consumer-helft vraagt tien jaar; deze
# grens ligt er ongeveer tien dagen boven, zodat een gewone aanvraag doorkomt maar een contract van
# honderd jaar niet. Die tien dagen zijn tegelijk het volledige budget voor klokverschil tussen de
# twee peers — wie deze waarde richting 315360000 bijstelt, haalt dat weg.
MAX_GELDIGHEID="$(fsc_getal_vereist FSC_MAX_GELDIGHEID_SECONDEN "${FSC_MAX_GELDIGHEID_SECONDEN:-316224000}")"

MANAGER="$(fsc_env_vereist FSC_PROVIDER_MANAGER 'https://<eigen-manager>:9443')"
CERT="$(fsc_env_vereist FSC_PROVIDER_CERT)"
KEY="$(fsc_env_vereist FSC_PROVIDER_KEY)"
CA="$(fsc_env_vereist FSC_PROVIDER_CA)"
ADRES="${FSC_PROVIDER_ADRES:-}"

# Her-distributie van de accept-handtekening ook forceren voor contracten die we eerder al tekenden.
# Uit by default: die push lukt normaal, en elke ronde opnieuw pushen is een call per contract per
# ronde voor niets. Aan zetten is de knop voor een gestrande push (zie `distribueer`).
FORCEER_DISTRIBUTIE="${FSC_FORCEER_DISTRIBUTIE:-0}"

fsc_contract_manager_ok "$MANAGER" || exit 2

[ "$HAVE_JQ" -eq 1 ] || {
  echo "FAIL: jq is vereist. Zonder jq is niet vast te stellen wélk contract getekend mag worden," >&2
  echo "  en blind tekenen zou elke aangeboden grant ondertekenen." >&2
  exit 2
}

api() { fsc_contract_api "$MANAGER" "$CERT" "$KEY" "$CA" "$ADRES" "$@"; }

# De lijst is cursor-gepagineerd met een default van honderd en een maximum van duizend, en bevat
# álles: publicatiecontracten, ingetrokken en afgewezen contracten, van alle peers. In een langlevend
# deployment groeit dat monotoon, en zodra ons eigen contract van pagina 1 valt zou de consumer elke
# ronde een nieuw indienen. fsc_contracten_paginas leest daarom door tot de cursor leeg is; een
# ruime limiet scheelt daarbij rondjes. Boven duizend geeft de manager een 400.
CONTRACT_LIMIET="$(fsc_getal_hoogstens FSC_CONTRACT_LIMIET "${FSC_CONTRACT_LIMIET:-1000}" 1000)"

DIENSTEN_JSON="$(fsc_lijst_naar_json FSC_DIENSTEN "$DIENSTEN")" || exit 2
CONSUMERS_JSON="$(fsc_lijst_naar_json FSC_CONSUMERS "$CONSUMERS")" || exit 2

# --- De contracten ophalen en beoordelen ---------------------------------------------------------
JSON="$(fsc_contracten_paginas api "${MANAGER}/v1/contracts?limit=${CONTRACT_LIMIET}")" || {
  echo "FAIL: kon de eigen contractenlijst niet ophalen: $(fsc_last_error)" >&2
  exit 1
}

BEOORDELING="$(fsc_contract_beoordeling \
  "$JSON" "$PROVIDER_OIN" "$DIENSTEN_JSON" "$CONSUMERS_JSON" "$GROUP_ID" "$MAX_GELDIGHEID" \
  "$(date -u +%s)")" || {
  echo "FAIL: de contractenlijst is niet te beoordelen: $(fsc_last_error)" >&2
  exit 1
}

AFGEWEZEN="$(fsc_contract_regels "$BEOORDELING" WEIGER)"

# Contracten die wij ooit tekenden en die nu de toets niet meer halen. Geen besluit meer — het staat
# er al — maar wel iets waar een operator van moet weten: doorgaans een gecorrigeerde allowlist of
# een looptijd die inmiddels buiten de grens valt.
AANDACHT="$(fsc_contract_regels "$BEOORDELING" AANDACHT)"

if [ -n "$AANDACHT" ]; then
  echo "provider: eerder getekende contracten die de toets nu niet meer halen:" >&2
  printf '%s\n' "$AANDACHT" | sed 's/^/  /' >&2
fi

if [ -n "$AFGEWEZEN" ]; then
  # Niet-tekenen is de stille uitkomst: zonder deze regels ziet een operator alleen dat er niets
  # gebeurde, niet waarom.
  echo "provider: contracten die de toets niet halen en dus niet getekend worden:" >&2
  printf '%s\n' "$AFGEWEZEN" | sed 's/^/  /' >&2
fi

# --- Tekenen -------------------------------------------------------------------------------------
# `distribueer <hash> <consumer_oin>`: laat de accept-handtekening (opnieuw) naar de consumer sturen.
#
# De manager pusht die na de accept zelf, maar best-effort: begrensde backoff, geen cron-retry.
# Strandt die push, dan blijft het contract bij de consumer `proposed` en ziet de outway de grant
# nooit, terwijl het hier geldig heet. Vóór de splitsing merkte het script dat doordat het bij de
# consumer keek; die kant is nu een ander proces op een andere manager. Deze helft kan het dus niet
# waarnemen en stuurt daarom één keer na. Strandt hij later alsnog, dan is FSC_FORCEER_DISTRIBUTIE=1
# de knop.
#
# De uitkomst telt mee in FOUTEN: deze call ís het herstelmechanisme voor een gestrande push, dus
# een mislukking hier stil laten verdwijnen zou juist het geval verbergen waarvoor hij bestaat.
distribueer() {
  local hash="$1" consumer="$2" uit

  uit="$(api -X PUT \
    "${MANAGER}/v1/contracts/${hash}/distributions/${consumer}/DISTRIBUTION_ACTION_SUBMIT_ACCEPT_SIGNATURE/retry" \
    -H 'Content-Type: application/json')" || {
    echo "  FAIL: her-distributie van ${hash} naar ${consumer} geweigerd: ${uit:-<leeg>} $(fsc_last_error)" >&2
    return 1
  }
}

GETEKEND=0
FOUTEN=0

# Verwerk één beoordelingsregel. `hash` en `consumer` komen uit de respons en gaan een URL-pad in;
# een waarde met een schuine streep of witruimte zou dat pad verleggen, dus eerst de vorm toetsen.
verwerk() {
  local soort="$1" hash="$2" consumer="$3" uit

  if ! fsc_hash_ok "$hash"; then
    echo "  FAIL: contract-hash met een onverwachte vorm overgeslagen: '${hash}'" >&2
    FOUTEN=$((FOUTEN + 1))
    return
  fi

  case "$consumer" in
    ""|*[!0-9]*)
      echo "  FAIL: consumer-OIN met een onverwachte vorm overgeslagen: '${consumer}'" >&2
      FOUTEN=$((FOUTEN + 1))
      return
      ;;
  esac

  if [ "$soort" = GETEKEND ]; then
    echo "provider: her-distributie forceren voor ${hash} (consumer ${consumer})..."
    distribueer "$hash" "$consumer" || FOUTEN=$((FOUTEN + 1))
    return
  fi

  echo "provider: tekenen ${hash} (consumer ${consumer})..."

  if uit="$(api -X PUT "${MANAGER}/v1/contracts/${hash}/accept" -H 'Content-Type: application/json')"; then
    GETEKEND=$((GETEKEND + 1))
    distribueer "$hash" "$consumer" || FOUTEN=$((FOUTEN + 1))
  else
    echo "  FAIL: accept van ${hash} geweigerd: ${uit:-<leeg>} $(fsc_last_error)" >&2
    FOUTEN=$((FOUTEN + 1))
  fi
}

# Geen `| while`: dat draait in een subshell en dan komen GETEKEND/FOUTEN niet terug.
while IFS=' ' read -r hash consumer; do
  [ -n "$hash" ] || continue

  verwerk TEKEN "$hash" "$consumer"
done <<EOF
$(fsc_contract_regels "$BEOORDELING" TEKEN)
EOF

if [ "$FORCEER_DISTRIBUTIE" = 1 ]; then
  while IFS=' ' read -r hash consumer; do
    [ -n "$hash" ] || continue

    verwerk GETEKEND "$hash" "$consumer"
  done <<EOF
$(fsc_contract_regels "$BEOORDELING" GETEKEND)
EOF
fi

if [ "$FOUTEN" -gt 0 ]; then
  echo "PROVIDER ROOD: ${FOUTEN} handeling(en) mislukt, ${GETEKEND} contract(en) getekend." >&2
  exit 1
fi

# Uitgang 4 alleen als er níéts getekend is. Anders was deze ronde gewoon productief en zou de
# aanroeper hem onterecht als "beslissing, niets meer te doen" lezen — waarna één blijvend
# onbetekenbaar contract van een oude consumer de lus permanent op het lange interval zet, en elke
# nieuwe consumer daardoor tot een uur op zijn handtekening wacht.
if [ -n "$AFGEWEZEN" ] && [ "$GETEKEND" -eq 0 ]; then
  echo "PROVIDER GEWEIGERD ($(printf '%s\n' "$AFGEWEZEN" | grep -c .) contract(en) haalden de toets niet)." >&2
  exit 4
fi

# Ook een uitsluitend-AANDACHT-ronde is geen succes. Er ligt dan een contract dat wij ooit tekenden
# en dat nu de toets niet meer haalt: het datapad is stuk terwijl de consumer zijn contract geldig
# ziet. Zou dit 0 opleveren, dan zet de lus de wachtteller op nul en blijft het component eeuwig
# groen, met één stderr-regel per uur als enige spoor.
if [ -n "$AANDACHT" ] && [ "$GETEKEND" -eq 0 ]; then
  echo "PROVIDER GEWEIGERD ($(printf '%s\n' "$AANDACHT" | grep -c .) eerder getekend contract(en) halen de toets niet meer)." >&2
  exit 4
fi

if [ "$GETEKEND" -gt 0 ]; then
  echo "PROVIDER OK (${GETEKEND} contract(en) getekend${AFGEWEZEN:+, $(printf '%s\n' "$AFGEWEZEN" | grep -c .) geweigerd}${AANDACHT:+, $(printf '%s\n' "$AANDACHT" | grep -c .) met aandacht})."
  exit 0
fi

# Niets getekend en niets dat ons aangaat in de lijst: het contract van de consumer is hier nog niet.
# Dat is wachten op de overkant en geen eindtoestand — de aanroeper hoort er kort op terug te komen
# in plaats van naar het lange interval te gaan, anders duurt een verse bootstrap tot een uur.
# Alleen echt "nog niets binnen": geen getekend contract van ons, en ook geen contract dat ooit van
# ons was. Zonder die tweede voorwaarde meldt een provider met een afgekeurd eigen contract elke
# ronde dat er niets binnen is, terwijl het er wel ligt — en stuurt de lus de operator naar
# WEIGER-regels die er niet zijn.
if [ -z "$(fsc_contract_regels "$BEOORDELING" GETEKEND)" ] && [ -z "$AANDACHT" ]; then
  echo "PROVIDER WACHT (nog geen contract van een toegelaten consumer binnen)."
  exit 3
fi

echo "PROVIDER OK (niets te tekenen)."
