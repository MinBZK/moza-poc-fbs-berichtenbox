#!/usr/bin/env bash
# Zet de contracten op die de FBS-keten nodig heeft, in twee richtingen:
#
#   - ophalen: één ServiceConnectionGrant per magazijn, zodat de uitvraag-outway
#     `berichtenmagazijn` bij elk van hen mag afnemen;
#   - pushen:  één per magazijn, zodat dat magazijn zijn CloudEvents kwijt kan bij de
#     notificatiedienst. Daar is het magazijn zelf de consumer.
#
# Wie meedoet staat in peers.env (`UITVRAAG`/`MAGAZIJNEN`/`MAGAZIJN_DIENST` en
# `NOTIFICATIE`/`PUSHERS`/`NOTIFICATIE_DIENST`); dit script vertaalt dat naar de component-adressen
# en cert-paden en roept per combinatie `bootstrap.sh` aan. Een magazijn toevoegen is daarmee één
# naam in `MAGAZIJNEN`.
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
: "${NOTIFICATIE:?geen NOTIFICATIE in peers.env}"
: "${PUSHERS:?geen PUSHERS in peers.env}"
: "${NOTIFICATIE_DIENST:?geen NOTIFICATIE_DIENST in peers.env}"

# Eén env-naam voor alle pushers werkt zolang er één is; een tweede zou de eerste overschrijven in
# hetzelfde bestand. Dat is een bewuste grens en geen omissie: een tweede pusher is een tweede
# magazijn-deployment met een eigen omgeving, niet twee hashes in één env-bestand.
if [ "$(printf '%s\n' $PUSHERS | grep -c .)" -gt 1 ]; then
  echo "FAIL: PUSHERS bevat meer dan één peer; NOTIFICATIE_GRANT_HASH kan er maar één dragen." >&2
  exit 2
fi

FOUTEN=0
GEDAAN=0

# De demo-stack moet het grant-hash kennen om `Fsc-Grant-Hash` te kunnen zetten, en compose kan dat
# niet uit een draaiende manager halen. Vandaar een gegenereerd env-bestand dat de apps inlezen.
# Naar `.tmp` en pas aan het eind verplaatsen: een half geschreven bestand zou een app met een
# grant-hash voor de ene bestemming en niets voor de andere laten starten.
GRANTS="$(cd "${ENVDIR}/.." && pwd)/generated/fsc-grants.env"
mkdir -p "$(dirname "$GRANTS")"
: > "$GRANTS.tmp"
# Eén trap voor beide tijdelijke bestanden: fsc_errlog_init zette er al een op $ERRLOG, en een
# tweede `trap ... EXIT` vervangt die in plaats van hem aan te vullen.
trap 'rm -f "$GRANTS.tmp" "$ERRLOG"' EXIT

# grant_regel_voor <consumer-peer> <consumer-oin> <thumbprint> <provider-oin> <dienst> <env-naam>:
# één `<ENV-NAAM>=<hash>`-regel op stdout.
#
# Het grant-hash is NIET het contract-hash: de outway routeert op de grant uit `content.grants[]`,
# en wie het contract-hash op de header zet krijgt structureel 400 UNKNOWN_GRANT_HASH_IN_HEADER.
#
# De contracten komen van de manager van de CONSUMER: dat is de kant waar de outway zijn grant
# vandaan haalt. Bij de provider staat hetzelfde contract, maar de consumer praat daar niet mee.
grant_regel_voor() {
  local consumer="$1" cons_oin="$2" thumb="$3" prov_oin="$4" dienst="$5" envnaam="$6"
  local json contract grant cons_net

  cons_net="$(fsc_peer_waarde NET "$consumer")"
  json="$(fsc_manager_contracts "$ENVDIR" "$consumer" "$(fsc_component_adres "$cons_net" manager)")" || return 1
  contract="$(fsc_grant_actief "$json" "$dienst" "$prov_oin" "$cons_oin" "$thumb" | sort | head -n1)"
  [ -n "$contract" ] || return 1

  grant="$(fsc_grant_hash "$json" "$contract" "$dienst" "$thumb")"
  fsc_grant_bruikbaar "$grant" || return 1

  # Escapen voor compose: een kale `$` in dit bestand wordt als variabele-verwijzing gelezen en
  # vreet de rest van het hash op. Zie fsc_compose_env_waarde.
  printf '%s=%s\n' "$envnaam" "$(fsc_compose_env_waarde "$grant")"
}

# contract_op <consumer-peer> <provider-peer> <dienst> <env-naam>: zet het contract op en schrijft
# het grant-hash weg. Werkt in beide richtingen — welke peer de consumer is, staat in peers.env.
contract_op() {
  local consumer="$1" provider="$2" dienst="$3" envnaam="$4"
  local cons_net cons_oin prov_net prov_oin cons_thumb

  cons_net="$(fsc_peer_waarde NET "$consumer")"
  cons_oin="$(fsc_peer_waarde OIN "$consumer")"
  prov_net="$(fsc_peer_waarde NET "$provider")"
  prov_oin="$(fsc_peer_waarde OIN "$provider")"

  if [ -z "$cons_net" ] || [ -z "$cons_oin" ] || [ -z "$prov_net" ] || [ -z "$prov_oin" ]; then
    echo "FAIL: NET_/OIN_ ontbreekt voor '${consumer}' of '${provider}' in peers.env." >&2
    return 1
  fi

  if [ "$prov_oin" = "$cons_oin" ]; then
    # Een zelfreferentieel contract is geldig FSC (zie logius' consume-service.sh voor de
    # profiel-service), maar hier zou het betekenen dat aanbieder en afnemer dezelfde identiteit
    # dragen — dan klopt peers.env niet.
    echo "FAIL: '${consumer}' en '${provider}' hebben dezelfde OIN (${prov_oin})." >&2
    return 1
  fi

  cons_thumb="$(fsc_outway_thumbprint "${ENVDIR}/${consumer}/pki/out/${consumer}/outway/cert.pem")" || {
    echo "FAIL: kon de outway-thumbprint van '${consumer}' niet berekenen: $(fsc_last_error)" >&2
    return 1
  }

  echo "== contract ${consumer} -> ${provider} (${dienst}) =="

  # De interne manager-API zit op het manager-adres van de peer, op de standaardpoort 9443. De
  # octet-toewijzing staat in federatie/README.md en geldt voor elke peer gelijk.
  FSC_CONSUMER_OIN="$cons_oin" \
  FSC_PROVIDER_OIN="$prov_oin" \
  FSC_SERVICE_NAME="$dienst" \
  FSC_OUTWAY_CERT="${ENVDIR}/${consumer}/pki/out/${consumer}/outway/cert.pem" \
  FSC_CONSUMER_MANAGER="https://manager.${consumer}.fsc-test.local:9443" \
  FSC_CONSUMER_ADRES="$(fsc_component_adres "$cons_net" manager)" \
  FSC_CONSUMER_CERT="${ENVDIR}/${consumer}/pki/internal/${consumer}/manager/cert.pem" \
  FSC_CONSUMER_KEY="${ENVDIR}/${consumer}/pki/internal/${consumer}/manager/key.pem" \
  FSC_CONSUMER_CA="${ENVDIR}/${consumer}/pki/internal/${consumer}/ca/root.pem" \
  FSC_PROVIDER_MANAGER="https://manager.${provider}.fsc-test.local:9443" \
  FSC_PROVIDER_ADRES="$(fsc_component_adres "$prov_net" manager)" \
  FSC_PROVIDER_CERT="${ENVDIR}/${provider}/pki/internal/${provider}/manager/cert.pem" \
  FSC_PROVIDER_KEY="${ENVDIR}/${provider}/pki/internal/${provider}/manager/key.pem" \
  FSC_PROVIDER_CA="${ENVDIR}/${provider}/pki/internal/${provider}/ca/root.pem" \
    "${HERE}/bootstrap.sh" || {
      echo "FAIL: contract ${consumer} -> ${provider} niet opgezet." >&2
      return 1
    }

  grant_regel_voor "$consumer" "$cons_oin" "$cons_thumb" "$prov_oin" "$dienst" "$envnaam" >> "$GRANTS.tmp" || {
    echo "FAIL: contract staat, maar het grant-hash van ${provider} (${dienst}) is niet af te leiden." >&2
    return 1
  }
}

# Ophalen: de uitvraag-outway mag `berichtenmagazijn` afnemen bij elk magazijn.
#
# De env-naam is de peernaam in hoofdletters: `magazijn-a` -> `MAGAZIJN_A_GRANT_HASH`. Dat is
# precies de naam die application.properties van de uitvraag leest voor het magazijn met die OIN.
# Hernoem je een peer in peers.env, dan moet die property mee.
for magazijn in $MAGAZIJNEN; do
  ENVNAAM="$(printf '%s' "$magazijn" | tr '[:lower:]-' '[:upper:]_')_GRANT_HASH"

  if contract_op "$UITVRAAG" "$magazijn" "$MAGAZIJN_DIENST" "$ENVNAAM"; then
    GEDAAN=$((GEDAAN + 1))
  else
    FOUTEN=$((FOUTEN + 1))
  fi

  echo
done

# Pushen: elk magazijn mag zijn CloudEvents kwijt bij de notificatiedienst. Andere richting,
# zelfde machinerie — het magazijn is hier de consumer.
for pusher in $PUSHERS; do
  if contract_op "$pusher" "$NOTIFICATIE" "$NOTIFICATIE_DIENST" NOTIFICATIE_GRANT_HASH; then
    GEDAAN=$((GEDAAN + 1))
  else
    FOUTEN=$((FOUTEN + 1))
  fi

  echo
done

# Nul magazijnen is geen succes maar een lege configuratie.
if [ "$GEDAAN" -eq 0 ] && [ "$FOUTEN" -eq 0 ]; then
  # Ook hier het oude bestand weg, om dezelfde reden als bij de foutuitgang hieronder: een
  # grant-hash van een vorige federatie zou anders blijven staan en de demo-stack een dode grant
  # laten sturen. `MAGAZIJNEN`/`PUSHERS` met alleen spaties komt langs de `:?`-controle hierboven.
  rm -f "$GRANTS"
  echo "FAIL: MAGAZIJNEN en PUSHERS zijn leeg; er is geen enkel contract opgezet." >&2
  exit 1
fi

if [ "$FOUTEN" -eq 0 ]; then
  # Overschrijven en niet aanvullen: hashes uit een eerdere run zouden anders blijven staan nadat
  # een contract is ingetrokken of opnieuw uitgegeven, en de uitvraag zou dan een dode grant sturen.
  mv "$GRANTS.tmp" "$GRANTS"
  echo "grant-hashes weggeschreven naar ${GRANTS}."
  echo "FBS-CONTRACTEN OK (${GEDAAN} contract(en))."
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
    echo "  uitvraag of magazijn houdt de oude grant-hash tot je hem hercreëert (demo/podman-up.sh)." >&2
  fi

  echo "FBS-CONTRACTEN ROOD: ${FOUTEN} van $((GEDAAN + FOUTEN)) mislukt." >&2
  exit 1
fi
