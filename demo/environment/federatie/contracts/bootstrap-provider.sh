#!/usr/bin/env bash
# De provider-helft van de contract-bootstrap: teken de binnengekomen ServiceConnectionGrants op de
# EIGEN manager. Raakt de manager van de consumer niet aan — daar is geen adres, cert of CA voor,
# dus dat kan ook niet per ongeluk terugsluipen.
#
# Vóór de splitsing was de accept een gerichte handeling: het script kende het hash dat het zelf net
# had laten indienen. Hier kennen we dat hash niet en besluiten we per contract of we tekenen. Die
# toets staat in fsc_contract_beoordeling(); lees daar waarom "precies één grant" de eis is en
# waarom de thumbprint niet getoetst wordt.
#
# Uitgangen: 0 = niets meer te doen (alles getekend, of er was niets), 1 = fout, 2 = configuratie.
# Geen aparte "nog niets binnen"-uitgang: een lege lijst is geen tussenstand maar gewoon niets te
# doen. Of de consumer al ingediend heeft, is aan de consumer-helft om te melden.
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

# Welke diensten wij aanbieden en welke consumers een contract van ons mogen krijgen. Beide
# spaties-gescheiden. Dit is de autorisatiegrens: alles wat er niet in staat, tekenen we niet.
DIENSTEN="$(fsc_env_vereist FSC_DIENSTEN 'spaties-gescheiden dienstnamen die deze peer aanbiedt')"
CONSUMERS="$(fsc_env_vereist FSC_CONSUMERS 'spaties-gescheiden consumer-OINs die een contract mogen krijgen')"

MANAGER="$(fsc_env_vereist FSC_PROVIDER_MANAGER 'https://<eigen-manager>:9443')"
CERT="$(fsc_env_vereist FSC_PROVIDER_CERT)"
KEY="$(fsc_env_vereist FSC_PROVIDER_KEY)"
CA="$(fsc_env_vereist FSC_PROVIDER_CA)"
ADRES="${FSC_PROVIDER_ADRES:-}"

# Her-distributie van de accept-handtekening ook forceren voor contracten die we eerder al tekenden.
# Uit by default: die push lukt normaal en elke ronde opnieuw pushen is een call per contract per
# ronde, voor niets. Aan zetten is de knop voor een gestrande push (zie hieronder).
FORCEER_DISTRIBUTIE="${FSC_FORCEER_DISTRIBUTIE:-0}"

fsc_contract_manager_ok "$MANAGER" || exit 2

[ "$HAVE_JQ" -eq 1 ] || {
  echo "FAIL: jq is vereist. Zonder jq is niet vast te stellen wélk contract getekend mag worden," >&2
  echo "  en blind tekenen zou elke aangeboden grant ondertekenen." >&2
  exit 1
}

api() { fsc_contract_api "$MANAGER" "$CERT" "$KEY" "$CA" "$ADRES" "$@"; }

# Woordsplitsing één keer, expliciet: `FSC_DIENSTEN=" "` komt langs de aanwezigheidscontrole
# hierboven maar levert een lege lijst op, en een lege allowlist zou betekenen dat we niets meer
# tekenen zonder dat iemand dat bedoeld heeft.
read -r -a DIENSTEN_LIJST <<< "$DIENSTEN"
read -r -a CONSUMERS_LIJST <<< "$CONSUMERS"

[ "${#DIENSTEN_LIJST[@]}" -gt 0 ] || { echo "FAIL: FSC_DIENSTEN bevat geen enkele dienstnaam." >&2; exit 2; }
[ "${#CONSUMERS_LIJST[@]}" -gt 0 ] || { echo "FAIL: FSC_CONSUMERS bevat geen enkele OIN." >&2; exit 2; }

DIENSTEN_JSON="$(fsc_json_lijst "${DIENSTEN_LIJST[@]}")"
CONSUMERS_JSON="$(fsc_json_lijst "${CONSUMERS_LIJST[@]}")"

# --- De contracten ophalen en beoordelen ---------------------------------------------------------
JSON="$(api "${MANAGER}/v1/contracts")" || {
  echo "FAIL: kon de eigen contractenlijst niet ophalen: $(fsc_last_error)" >&2
  exit 1
}

# Met eigen melding: `fsc_contract_beoordeling` eindigt op `jq … 2>/dev/null`. Antwoordt de manager
# met 200 maar zonder geldige JSON (een proxy-foutpagina, een afgekapt antwoord), dan faalt deze
# toekenning onder `pipefail` en zou het script zonder deze tak zwijgend afbreken.
BEOORDELING="$(fsc_contract_beoordeling "$JSON" "$PROVIDER_OIN" "$DIENSTEN_JSON" "$CONSUMERS_JSON")" || {
  echo "FAIL: de eigen contractenlijst is niet te lezen — geen geldige JSON?" >&2
  exit 1
}

AFGEWEZEN="$(fsc_contract_regels "$BEOORDELING" WEIGER)"

if [ -n "$AFGEWEZEN" ]; then
  # Niet-tekenen is de stille uitkomst: zonder deze regels ziet een operator alleen dat er niets
  # gebeurde, niet waarom. Geen fout — een contract dat wij niet tekenen, werkt niet, en dat is
  # precies de bedoeling.
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
# waarnemen en stuurt daarom één keer na — goedkoop, en het dekt het geval dat de push tijdens de
# accept mislukt. Strandt hij later alsnog, dan is FSC_FORCEER_DISTRIBUTIE=1 de knop.
distribueer() {
  local hash="$1" consumer="$2"
  api -X PUT \
    "${MANAGER}/v1/contracts/${hash}/distributions/${consumer}/DISTRIBUTION_ACTION_SUBMIT_ACCEPT_SIGNATURE/retry" \
    -H 'Content-Type: application/json' >/dev/null \
    || echo "  WARN: her-distributie van ${hash} naar ${consumer} gaf een fout: $(fsc_last_error)" >&2
}

GETEKEND=0
FOUTEN=0

while IFS=' ' read -r hash consumer; do
  [ -n "$hash" ] || continue

  echo "provider: tekenen ${hash} (consumer ${consumer})..."

  if api -X PUT "${MANAGER}/v1/contracts/${hash}/accept" -H 'Content-Type: application/json' >/dev/null; then
    GETEKEND=$((GETEKEND + 1))
    distribueer "$hash" "$consumer"
  else
    echo "  FAIL: accept van ${hash} geweigerd: $(fsc_last_error)" >&2
    FOUTEN=$((FOUTEN + 1))
  fi
done <<EOF
$(fsc_contract_regels "$BEOORDELING" TEKEN)
EOF

if [ "$FORCEER_DISTRIBUTIE" = 1 ]; then
  while IFS=' ' read -r hash consumer; do
    [ -n "$hash" ] || continue

    echo "provider: her-distributie forceren voor ${hash} (consumer ${consumer})..."
    distribueer "$hash" "$consumer"
  done <<EOF
$(fsc_contract_regels "$BEOORDELING" GETEKEND)
EOF
fi

if [ "$FOUTEN" -gt 0 ]; then
  echo "PROVIDER ROOD: ${FOUTEN} van $((GETEKEND + FOUTEN)) contract(en) niet getekend." >&2
  exit 1
fi

if [ "$GETEKEND" -gt 0 ]; then
  echo "PROVIDER OK (${GETEKEND} contract(en) getekend)."
else
  echo "PROVIDER OK (niets te tekenen)."
fi
