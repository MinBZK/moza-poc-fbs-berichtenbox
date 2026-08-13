#!/usr/bin/env bash
# Zet de contracten op die het uitvraag-systeem nodig heeft: één ServiceConnectionGrant per
# magazijn, zodat de uitvraag-outway `berichtenmagazijn` bij elk van hen mag afnemen.
#
# Wie meedoet staat in peers.env (`UITVRAAG`, `MAGAZIJNEN`, `MAGAZIJN_DIENST`); dit script vertaalt
# dat naar de component-adressen en cert-paden en roept per magazijn `bootstrap.sh` aan. Een magazijn
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

fsc_errlog_init
# Zet $HAVE_JQ, waar fsc_grant_actief/fsc_grant_hash op leunen. bootstrap.sh doet dit voor zichzelf;
# sinds dit script zélf het grant-hash afleidt, heeft het de vlag ook nodig.
fsc_have_jq

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

# De demo-stack moet het grant-hash kennen om `Fsc-Grant-Hash` te kunnen zetten, en compose kan dat
# niet uit een draaiende manager halen. Vandaar een gegenereerd env-bestand dat de uitvraag inleest.
# Naar `.tmp` en pas aan het eind verplaatsen: een half geschreven bestand zou de uitvraag met een
# grant-hash van het ene magazijn en niets voor het andere laten starten.
GRANTS="$(cd "${ENVDIR}/.." && pwd)/generated/fsc-grants.env"
mkdir -p "$(dirname "$GRANTS")"
: > "$GRANTS.tmp"
# Eén trap voor beide tijdelijke bestanden: fsc_errlog_init zette er al een op $ERRLOG, en een
# tweede `trap ... EXIT` vervangt die in plaats van hem aan te vullen.
trap 'rm -f "$GRANTS.tmp" "$ERRLOG"' EXIT

# Het grant-hash is NIET het contract-hash: de outway routeert op de grant uit `content.grants[]`,
# en wie het contract-hash op de header zet krijgt structureel 400 UNKNOWN_GRANT_HASH_IN_HEADER.
CONS_THUMB="$(fsc_outway_thumbprint "${ENVDIR}/${UITVRAAG}/pki/out/${UITVRAAG}/outway/cert.pem")" || {
  echo "FAIL: kon de outway-thumbprint van '${UITVRAAG}' niet berekenen: $(fsc_last_error)" >&2
  exit 1
}

# grant_regel_voor <magazijn> <oin>: één `<PEER>_GRANT_HASH=<hash>`-regel op stdout.
#
# De env-naam is de peernaam in hoofdletters: `magazijn-a` -> `MAGAZIJN_A_GRANT_HASH`. Dat is
# precies de naam die application.properties van de uitvraag leest voor het magazijn met die OIN.
# Hernoem je een peer in peers.env, dan moet die property mee.
#
# De contracten komen van de manager van de CONSUMER: dat is de kant waar de outway zijn grant
# vandaan haalt. Bij de provider staat hetzelfde contract, maar de uitvraag praat daar niet mee.
grant_regel_voor() {
  local magazijn="$1" oin="$2" json contract grant naam

  json="$(fsc_manager_contracts "$ENVDIR" "$UITVRAAG" "$(fsc_component_adres "$CONS_NET" manager)")" || return 1
  contract="$(fsc_grant_actief "$json" "$MAGAZIJN_DIENST" "$oin" "$CONS_OIN" "$CONS_THUMB" | sort | head -n1)"
  [ -n "$contract" ] || return 1

  grant="$(fsc_grant_hash "$json" "$contract" "$MAGAZIJN_DIENST" "$CONS_THUMB")"
  fsc_grant_bruikbaar "$grant" || return 1

  naam="$(printf '%s' "$magazijn" | tr '[:lower:]-' '[:upper:]_')"
  printf '%s_GRANT_HASH=%s\n' "$naam" "$grant"
}

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
    grant_regel_voor "$magazijn" "$PROV_OIN" >> "$GRANTS.tmp" || {
      echo "FAIL: contract staat, maar het grant-hash van ${magazijn} is niet af te leiden." >&2
      FOUTEN=$((FOUTEN + 1))
    }
  else
    echo "FAIL: contract ${UITVRAAG} -> ${magazijn} niet opgezet." >&2
    FOUTEN=$((FOUTEN + 1))
  fi

  echo
done

# Nul magazijnen is geen succes maar een lege configuratie.
if [ "$GEDAAN" -eq 0 ] && [ "$FOUTEN" -eq 0 ]; then
  # Ook hier het oude bestand weg, om dezelfde reden als bij de foutuitgang hieronder: een
  # grant-hash van een vorige federatie zou anders blijven staan en de demo-stack een dode grant
  # laten sturen. `MAGAZIJNEN` met alleen spaties komt langs de `:?`-controle hierboven.
  rm -f "$GRANTS"
  echo "FAIL: MAGAZIJNEN is leeg; er is geen enkel contract opgezet." >&2
  exit 1
fi

if [ "$FOUTEN" -eq 0 ]; then
  # Overschrijven en niet aanvullen: hashes uit een eerdere run zouden anders blijven staan nadat
  # een contract is ingetrokken of opnieuw uitgegeven, en de uitvraag zou dan een dode grant sturen.
  mv "$GRANTS.tmp" "$GRANTS"
  echo "grant-hashes weggeschreven naar ${GRANTS}."
  echo "FBS-CONTRACTEN OK (${GEDAAN} magazijn(en))."
else
  # Ook het BESTAANDE bestand weg. Anders overleeft een grant-hash uit een eerdere federatie een
  # mislukte run: na `federatie.sh down` (die de volumes wist) en een nieuwe `up` bestaat die grant
  # niet meer, maar zou de demo-stack er wél mee starten en op elke magazijn-call een
  # 400 UNKNOWN_GRANT_HASH_IN_HEADER krijgen. Geen bestand = geen FSC-headers = rechtstreeks
  # verkeer, en dat is een eerlijke uitkomst van een mislukte contract-run.
  if [ -e "$GRANTS" ]; then
    rm -f "$GRANTS"
    echo "grant-hashes uit een eerdere run verwijderd (${GRANTS})." >&2
    echo "  Let op: compose leest env_file bij het AANMAKEN van een container, dus een al draaiende" >&2
    echo "  uitvraag houdt de oude grant-hash tot je hem hercreëert (demo/podman-up.sh)." >&2
  fi

  echo "FBS-CONTRACTEN ROOD: ${FOUTEN} van $((GEDAAN + FOUTEN)) mislukt." >&2
  exit 1
fi
