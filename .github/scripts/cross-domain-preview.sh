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
#   ZAD_API_KEY=... cross-domain-preview.sh zet       <project> <deployment> inbound|outbound <regel>...
#   ZAD_API_KEY=... cross-domain-preview.sh verwijder <project> <deployment> inbound|outbound <regel>...
#
# Meerdere regelnamen gaan in één patch. De API neemt een lijst, en een deployment die vier regels
# nodig heeft zou anders vier keer de projectcontrole doen en vier keer op een taak wachten.
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
  local actie=$1 deployment=$2 richting=$3
  shift 3

  local regel items=""

  case "$actie" in
    verwijder)
      for regel in "$@"; do
        items="${items:+$items,}$(printf '"%s"' "$regel")"
      done

      printf '{"remove":[%s]}' "$items"
      ;;
    zet)
      local zijde
      case "$richting" in
        inbound) zijde=from ;;
        outbound) zijde=to ;;
        *) fout "Richting moet inbound of outbound zijn, was '$richting'." ;;
      esac

      for regel in "$@"; do
        items="${items:+$items,}$(printf '{"name":"%s","%s":{"deployment":"%s"}}' "$regel" "$zijde" "$deployment")"
      done

      printf '{"add":[%s]}' "$items"
      ;;
    *)
      fout "Actie moet zet of verwijder zijn, was '$actie'."
      ;;
  esac
}

# De projectregel moet bestaan vóór een deployment hem invult. Operations Manager laat het
# genereren nooit falen op een kapotte regel: een deployment-patch zonder projectregel wordt een
# regel op zichzelf, mist dan component en poort, en wordt met een waarschuwing in de log
# overgeslagen. De API accepteert de patch wél. Zonder deze controle meldt de stap dus groen over
# een netwerkregel die er nooit komt, en valt dat pas op wanneer iemand tijdens een demo op de knop
# drukt.
vereis_projectregel() {
  local api_url=$1 api_key=$2 project=$3 richting=$4
  shift 4

  local config regel

  # Eén keer ophalen voor alle regels: de configuratie van een project is er één, en per regel
  # opnieuw vragen zou alleen de API belasten.
  if ! config=$("$CURL" -sf -H "X-API-Key: $api_key" \
    "$api_url/v2/projects/$project/services/cross-domain-access/config"); then
    fout "Cross-domain-configuratie van $project niet op te vragen; kan de projectregels niet controleren."
  fi

  # De naam moet in dít antwoord staan; welke richting is niet uit de platte tekst te halen zonder
  # JSON-parser, en een regelnaam is uniek per project in de praktijk. Een naam die alleen in de
  # andere richting bestaat is daarmee niet te onderscheiden — de melding zegt dat.
  for regel in "$@"; do
    case "$config" in
      *"\"$regel\""*) continue ;;
    esac

    fout "Geen $richting-regel '$regel' op projectniveau in $project. Een deployment-patch vult een bestaande regel aan; zonder die regel wordt hij bij het genereren overgeslagen en komt er geen netwerkregel. Zie demo/environment/zad-demo/README.md."
  done
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
  local actie=${1:-} project=${2:-} deployment=${3:-} richting=${4:-}
  [ "$#" -ge 4 ] && shift 4 || shift "$#"

  case "$actie" in
    zet | verwijder) ;;
    *) fout "Gebruik: $0 zet|verwijder <project> <deployment> inbound|outbound <regel>..." ;;
  esac

  case "$richting" in
    inbound | outbound) ;;
    *) fout "Richting moet inbound of outbound zijn, was '$richting'." ;;
  esac

  [ -n "$project" ] || fout "Geen project opgegeven."
  [ -n "$deployment" ] || fout "Geen deployment opgegeven."
  [ "$#" -gt 0 ] || fout "Geen regelnaam opgegeven."

  local regel
  for regel in "$@"; do
    [ -n "$regel" ] || fout "Een lege regelnaam is geen regelnaam."
  done

  local api_key=${ZAD_API_KEY:-}
  [ -n "$api_key" ] || fout "ZAD_API_KEY ontbreekt; zonder sleutel valt er niets te zetten."

  local api_url=${ZAD_API_URL:-$STANDAARD_API_URL}
  if [ "$actie" = zet ]; then
    vereis_projectregel "$api_url" "$api_key" "$project" "$richting" "$@"
  fi

  local body antwoord taak
  body=$(patch_body "$actie" "$deployment" "$richting" "$@")

  if ! antwoord=$("$CURL" -sf -X PATCH \
    -H "X-API-Key: $api_key" -H 'Content-Type: application/json' \
    "$api_url/v2/projects/$project/services/cross-domain-access/config/deployment/$deployment/$richting" \
    -d "$body"); then
    fout "Patch van de $richting-regels ($*) op $project/$deployment is geweigerd."
  fi

  taak=$(printf '%s' "$antwoord" | sed -n 's/.*"task_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)

  # Geen taak-id betekent dat het antwoord niet de vorm had die we verwachten — bijvoorbeeld een
  # foutmelding met HTTP 200. Doorgaan zou "gelukt" melden over iets wat niet gebeurd is.
  [ -n "$taak" ] || fout "Geen taak-id in het antwoord van de API: $antwoord"

  wacht_op_taak "$api_url" "$api_key" "$taak"

  echo "$actie: $richting-regels ($*) op $project/$deployment (taak $taak)"
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
