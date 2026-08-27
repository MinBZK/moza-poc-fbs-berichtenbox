#!/usr/bin/env bash
# Vult de peer-deployment van een cross-domain-access-regel in voor één deployment, of haalt hem
# er weer uit.
#
# Waarom dit bestaat: een cross-domain-regel noemt altijd één concrete peer-deployment. Blijft die
# open, dan slaat Operations Manager de regel bij het genereren over — er is geen vorm die
# "dezelfde deployment als de mijne" betekent. De regel zelf staat daarom één keer op
# projectniveau, zonder peer-deployment, en elke deployment krijgt een patch die hem invult. Voor
# `test` doe je dat één keer met de hand; voor een preview hoort het bij het aanmaken en het
# opruimen, en dat is wat dit script doet.
#
# Welk veld ingevuld wordt volgt uit de richting: in een inbound-regel is de tegenpartij `from`,
# in een outbound-regel is dat `to`. De regel-naam is de sleutel — een patch met dezelfde naam
# past de projectregel aan, een andere naam maakt een eigen regel voor die deployment.
#
# Gebruik:
#   ZAD_API_KEY=... cross-domain-preview.sh zet       <project> <deployment> inbound|outbound <regel>
#   ZAD_API_KEY=... cross-domain-preview.sh verwijder <project> <deployment> inbound|outbound <regel>
#
# `verwijder` op een regel die er niet is, is een no-op aan de API-kant; twee keer opruimen mag dus.

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Overschrijfbaar zodat de unittests het HTTP-verkeer kunnen onderscheppen zonder netwerk. In CI
# staat hier niets en is het gewoon curl.
CURL=${ZAD_CURL:-curl}

fout() {
  echo "::error::$*" >&2

  exit 1
}

# De body van de patch. Het veld dat de peer draagt hangt aan de richting; die twee door elkaar
# halen levert een regel op die valideert maar niets openzet, en dat faalt stil.
patch_body() {
  local actie=$1 deployment=$2 richting=$3 regel=$4

  case "$actie" in
    verwijder)
      printf '{"remove":["%s"]}' "$regel"
      ;;
    zet)
      local zijde
      case "$richting" in
        inbound) zijde=from ;;
        outbound) zijde=to ;;
        *) fout "Richting moet inbound of outbound zijn, was '$richting'." ;;
      esac

      printf '{"add":[{"name":"%s","%s":{"deployment":"%s"}}]}' "$regel" "$zijde" "$deployment"
      ;;
    *)
      fout "Actie moet zet of verwijder zijn, was '$actie'."
      ;;
  esac
}

# Wacht tot de taak een eindtoestand heeft. Zonder deze lus meldt de stap groen zodra de API het
# verzoek heeft aangenomen (HTTP 202), en dat zegt niets over de uitkomst.
wacht_op_taak() {
  local api_url=$1 api_key=$2 taak=$3
  local status antwoord

  for _ in $(seq 60); do
    if ! antwoord=$("$CURL" -sf -H "X-API-Key: $api_key" "$api_url/tasks/$taak"); then
      fout "Taak $taak niet op te vragen; de uitkomst van de netwerkregel is onbekend."
    fi

    status=$(printf '%s' "$antwoord" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p' | head -1)

    case "$status" in
      completed)
        return 0
        ;;
      failed | error | cancelled)
        fout "Taak $taak eindigde als '$status': $antwoord"
        ;;
    esac

    sleep 2
  done

  fout "Taak $taak was na twee minuten nog niet klaar; de netwerkregel staat mogelijk niet."
}

main() {
  local actie=${1:-} project=${2:-} deployment=${3:-} richting=${4:-} regel=${5:-}

  case "$actie" in
    zet | verwijder) ;;
    *) fout "Gebruik: $0 zet|verwijder <project> <deployment> inbound|outbound <regel>" ;;
  esac

  case "$richting" in
    inbound | outbound) ;;
    *) fout "Richting moet inbound of outbound zijn, was '$richting'." ;;
  esac

  [ -n "$project" ] || fout "Geen project opgegeven."
  [ -n "$deployment" ] || fout "Geen deployment opgegeven."
  [ -n "$regel" ] || fout "Geen regelnaam opgegeven."

  local api_key=${ZAD_API_KEY:-}
  [ -n "$api_key" ] || fout "ZAD_API_KEY ontbreekt; zonder sleutel valt er niets te zetten."

  local api_url=${ZAD_API_URL:-$STANDAARD_API_URL}
  local body antwoord taak
  body=$(patch_body "$actie" "$deployment" "$richting" "$regel")

  if ! antwoord=$("$CURL" -sf -X PATCH \
    -H "X-API-Key: $api_key" -H 'Content-Type: application/json' \
    "$api_url/v2/projects/$project/services/cross-domain-access/config/deployment/$deployment/$richting" \
    -d "$body"); then
    fout "Patch van de $richting-regel '$regel' op $project/$deployment is geweigerd."
  fi

  taak=$(printf '%s' "$antwoord" | sed -n 's/.*"task_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)

  # Geen taak-id betekent dat het antwoord niet de vorm had die we verwachten — bijvoorbeeld een
  # foutmelding met HTTP 200. Doorgaan zou "gelukt" melden over iets wat niet gebeurd is.
  [ -n "$taak" ] || fout "Geen taak-id in het antwoord van de API: $antwoord"

  wacht_op_taak "$api_url" "$api_key" "$taak"

  echo "$actie: $richting-regel '$regel' op $project/$deployment (taak $taak)"
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
