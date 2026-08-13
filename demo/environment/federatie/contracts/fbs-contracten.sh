#!/usr/bin/env bash
# Zet de contracten op die het uitvraag-systeem nodig heeft: één ServiceConnectionGrant per
# magazijn, zodat de uitvraag-outway `berichtenmagazijn` bij elk van hen mag afnemen.
#
# Wie meedoet staat in peers.env (`UITVRAAG`, `MAGAZIJNEN`, `MAGAZIJN_DIENST`); dit script vertaalt
# dat naar de peer-blokken en cert-paden en roept per magazijn `bootstrap.sh` aan. Een magazijn
# toevoegen is daarmee één naam in `MAGAZIJNEN`.
#
# Idempotent, ook zonder lokale state: `bootstrap.sh` leidt uit de contracten zelf af of er al een
# geldig contract voor die combinatie bestaat. Twee keer draaien levert dus geen tweede contract op,
# en dat geldt net zo goed in een deploy-job met een lege schijf.
#
# Voorwaarden: de federatie draait (`../federatie.sh up`) en elk magazijn heeft zijn dienst
# gepubliceerd (`<magazijn>/deploy/local/publish-service.sh`). `../smoke-federatie.sh` doet dat
# laatste voor de peers die het toetst.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
FEDDIR="$(cd "${HERE}/.." && pwd)"
ENVDIR="$(cd "${FEDDIR}/.." && pwd)"

# shellcheck source=../../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=../peers.env
. "${FEDDIR}/peers.env"

: "${UITVRAAG:?geen UITVRAAG in peers.env}"
: "${MAGAZIJNEN:?geen MAGAZIJNEN in peers.env}"
: "${MAGAZIJN_DIENST:?geen MAGAZIJN_DIENST in peers.env}"

CONS_NET="$(fsc_peer_waarde NET "$UITVRAAG")"
CONS_OIN="$(fsc_peer_waarde OIN "$UITVRAAG")"
[ -n "$CONS_NET" ] && [ -n "$CONS_OIN" ] || {
  echo "FAIL: NET_/OIN_ ontbreekt voor de uitvraag-peer '${UITVRAAG}' in peers.env." >&2
  exit 1
}

FOUTEN=0
GEDAAN=0

for magazijn in $MAGAZIJNEN; do
  PROV_NET="$(fsc_peer_waarde NET "$magazijn")"
  PROV_OIN="$(fsc_peer_waarde OIN "$magazijn")"

  if [ -z "$PROV_NET" ] || [ -z "$PROV_OIN" ]; then
    echo "FAIL: NET_/OIN_ ontbreekt voor magazijn '${magazijn}' in peers.env." >&2
    FOUTEN=$((FOUTEN + 1))
    continue
  fi

  if [ "$PROV_OIN" = "$CONS_OIN" ]; then
    # Een zelfreferentieel contract is geldig FSC (zie logius' consume-service.sh voor de
    # profiel-service), maar hier zou het betekenen dat het magazijn dezelfde identiteit draagt
    # als de uitvraag — dan klopt peers.env niet.
    echo "FAIL: magazijn '${magazijn}' heeft dezelfde OIN als de uitvraag-peer (${PROV_OIN})." >&2
    FOUTEN=$((FOUTEN + 1))
    continue
  fi

  echo "== contract ${UITVRAAG} -> ${magazijn} (${MAGAZIJN_DIENST}) =="

  # De interne manager-API zit op het manager-adres van de peer, op de standaardpoort 9443. De
  # octet-toewijzing staat in federatie/README.md en geldt voor elke peer gelijk.
  if FSC_CONSUMER_OIN="$CONS_OIN" \
     FSC_PROVIDER_OIN="$PROV_OIN" \
     FSC_SERVICE_NAME="$MAGAZIJN_DIENST" \
     FSC_OUTWAY_CERT="${ENVDIR}/${UITVRAAG}/pki/out/${UITVRAAG}/outway/cert.pem" \
     FSC_CONSUMER_MANAGER="https://manager.${UITVRAAG}.fsc-test.local:9443" \
     FSC_CONSUMER_ADRES="$(fsc_component_adres "$CONS_NET" manager)" \
     FSC_CONSUMER_CERT="${ENVDIR}/${UITVRAAG}/pki/internal/${UITVRAAG}/manager/cert.pem" \
     FSC_CONSUMER_KEY="${ENVDIR}/${UITVRAAG}/pki/internal/${UITVRAAG}/manager/key.pem" \
     FSC_CONSUMER_CA="${ENVDIR}/${UITVRAAG}/pki/internal/${UITVRAAG}/ca/root.pem" \
     FSC_PROVIDER_MANAGER="https://manager.${magazijn}.fsc-test.local:9443" \
     FSC_PROVIDER_ADRES="$(fsc_component_adres "$PROV_NET" manager)" \
     FSC_PROVIDER_CERT="${ENVDIR}/${magazijn}/pki/internal/${magazijn}/manager/cert.pem" \
     FSC_PROVIDER_KEY="${ENVDIR}/${magazijn}/pki/internal/${magazijn}/manager/key.pem" \
     FSC_PROVIDER_CA="${ENVDIR}/${magazijn}/pki/internal/${magazijn}/ca/root.pem" \
       "${HERE}/bootstrap.sh"; then
    GEDAAN=$((GEDAAN + 1))
  else
    echo "FAIL: contract ${UITVRAAG} -> ${magazijn} niet opgezet." >&2
    FOUTEN=$((FOUTEN + 1))
  fi

  echo
done

# Nul magazijnen is geen succes maar een lege configuratie.
if [ "$GEDAAN" -eq 0 ] && [ "$FOUTEN" -eq 0 ]; then
  echo "FAIL: MAGAZIJNEN is leeg; er is geen enkel contract opgezet." >&2
  exit 1
fi

if [ "$FOUTEN" -eq 0 ]; then
  echo "FBS-CONTRACTEN OK (${GEDAAN} magazijn(en))."
else
  echo "FBS-CONTRACTEN ROOD: ${FOUTEN} van $((GEDAAN + FOUTEN)) mislukt." >&2
  exit 1
fi
