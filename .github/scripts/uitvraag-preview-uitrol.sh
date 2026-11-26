#!/usr/bin/env bash
# Beoordeelt de uitrol van een uitvraag-preview die de deploy-action rood meldde, en wacht later tot
# die preview gezond is.
#
# Een nieuwe preview van het uitvraag-project wordt pas gezond als de demo-console in het
# magazijnen-project de proxy van `toxiproxy-redis` heeft aangemaakt: Toxiproxy houdt zijn proxies
# alleen in het geheugen, en zonder proxy weigert hij de verbinding naar Redis, dus faalt de
# readiness van de uitvraag. Die console rolt pas uit ná de uitvraag, omdat hij de netwerkregels nodig
# heeft die met de uitvraag meekomen. De deploy-action wacht via Operations Manager op een gezonde
# sync en geeft het na vijf minuten op. Zonder deze tussenstap zou de magazijnen-preview dus nooit
# uitrollen, en de uitvraag dus nooit gezond worden.
#
# `oordeel` neemt die rode melding alleen over als vaststaat dat de uitrol zelf geland is: de
# gevraagde images staan in de deployment, er wacht in het project niets op uitrol, er loopt geen
# sync, en het enige dat ontbreekt is de gezondheid van het uitvraag-component. Dan staan de
# netwerkregels in het cluster en kan de console erbij. Elke andere toestand blijft rood. Een preview
# die al gezond is, telt als gezond: die had alleen een trage melding.
#
# `wacht` draait na de magazijnen-uitrol en eist alsnog een gezonde preview; die stap is de eigenlijke
# poort.
#
# Gebruik:
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh oordeel <project> <deployment> <component> <componenten-json>
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh wacht <project> <deployment> [seconden] [interval]
#
# `oordeel` zet bij aanvaarding `urls=`, `url=` en `gezond=` op stdout, in de vorm van $GITHUB_OUTPUT.

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Overschrijfbaar zodat de unittests het HTTP-verkeer kunnen onderscheppen zonder netwerk.
CURL=${ZAD_CURL:-curl}

# `wacht` slaapt tussen twee opvragingen; de tests zetten dit op niets.
SLAAP=${ZAD_SLAAP:-sleep}

fout() {
  echo "::error::$*" >&2

  exit 1
}

# Vraagt de deployment op en zet `antwoord`. Exitcode 0 = antwoord binnen, 1 = niet op te vragen.
vraag_deployment_op() {
  local project=$1 deployment=$2

  antwoord=$("$CURL" -sSf --max-time 30 --retry 3 --retry-connrefused \
    -H "X-API-Key: $ZAD_API_KEY" -H 'Accept: application/json' \
    "${ZAD_API_URL:-$STANDAARD_API_URL}/v2/projects/$project/deployments/$deployment") || return 1

  jq -e 'type == "object" and (.status | type) == "string"' <<<"$antwoord" >/dev/null 2>&1
}

oordeel() {
  local project=$1 deployment=$2 component=$3 componenten=$4 antwoord uitkomst

  vraag_deployment_op "$project" "$deployment" \
    || fout "$deployment in $project is niet op te vragen; of de uitrol landde, is onbekend."

  # De reden als tekst en niet als exitcode van `jq -e`: zo zegt de melding wát er niet klopt. Een
  # antwoord met een andere vorm valt in jq zelf om en komt via `||` op "onleesbaar" uit.
  uitkomst=$(jq -r --argjson gevraagd "$componenten" --arg c "$component" --arg d "$deployment" '
    def opgeslagen: [.components[]? | {key: .reference, value: .image}] | from_entries;
    def van_component: (.resource // "") | test("^[A-Za-z]+/" + $d + "-" + $c + "(-|$)");
    (opgeslagen) as $staat
    | [$gevraagd[] | select($staat[.name] != .image) | .name] as $afwijkend
    | if ($afwijkend | length) > 0 then
        "de opgeslagen images wijken af van de gevraagde (" + ($afwijkend | join(", ")) + ")"
      elif (.pending_rollout.count | type) != "number" then
        "het aantal wachtende uitrollen ontbreekt"
      elif .pending_rollout.count != 0 then
        "er wachten \(.pending_rollout.count) wijziging(en) in het project op uitrol"
      elif .running_sync != null then
        "er loopt nog een sync"
      elif .status == "Healthy" then
        "gezond"
      elif .status == "Progressing" then
        "nog niet gezond"
      elif .status != "Degraded" then
        "status \(.status)"
      elif (.errors | length) == 0 then
        "Degraded zonder fouten die een component noemen"
      elif all(.errors[]; van_component) then
        "nog niet gezond"
      else
        "Degraded door meer dan het component \($c): " + ([.errors[] | select(van_component | not) | .resource] | join(", "))
      end' <<<"$antwoord" 2>/dev/null) || uitkomst="onleesbaar antwoord"

  local gezond

  case "$uitkomst" in
    gezond) gezond=true ;;
    "nog niet gezond") gezond=false ;;
    *) fout "De uitrol van $deployment in $project is niet geland of faalt op meer dan $component: $uitkomst." ;;
  esac

  local urls url

  urls=$(jq -c '.urls // {}' <<<"$antwoord")

  [ "$urls" != "{}" ] || fout "$deployment in $project heeft geen enkel publiek adres."

  # Dezelfde keuze als de deploy-action: het eerste opgegeven component dat een adres heeft.
  url=$(jq -r --argjson urls "$urls" 'map(.name) | map($urls[.] // empty) | first // empty' <<<"$componenten")

  [ -n "$url" ] || fout "Geen van de opgegeven componenten van $deployment heeft een publiek adres."

  if [ "$gezond" = false ]; then
    echo "::warning::De uitrol van $deployment in $project is geland, maar $component is nog niet gezond. De magazijnen-preview rolt daarom toch uit; daarna wacht de run tot deze preview gezond is." >&2
  fi

  printf 'urls=%s\nurl=%s\ngezond=%s\n' "$urls" "$url" "$gezond"
}

wacht() {
  local project=$1 deployment=$2 seconden=${3:-300} interval=${4:-5} antwoord status=onbekend

  local einde=$((SECONDS + seconden))

  while :; do
    if vraag_deployment_op "$project" "$deployment"; then
      status=$(jq -r '.status' <<<"$antwoord")

      if [ "$status" = Healthy ] && [ "$(jq -r '.running_sync' <<<"$antwoord")" = null ]; then
        echo "$deployment in $project is gezond."

        return 0
      fi
    else
      status="niet op te vragen"
    fi

    [ "$SECONDS" -lt "$einde" ] || break

    "$SLAAP" "$interval"
  done

  local fouten=""

  if [ "$status" != "niet op te vragen" ]; then
    fouten=$(jq -r '[.errors[]? | .message] | join("; ")' <<<"$antwoord" 2>/dev/null || true)
  fi

  fout "$deployment in $project was na ${seconden}s nog niet gezond (laatste status: $status${fouten:+; $fouten})."
}

main() {
  local actie=${1:-}

  [ -n "${ZAD_API_KEY:-}" ] || fout "ZAD_API_KEY ontbreekt."

  case "$actie" in
    oordeel)
      [ "$#" -eq 5 ] || fout "Gebruik: $0 oordeel <project> <deployment> <component> <componenten-json>"

      jq -e 'type == "array" and length > 0' <<<"$5" >/dev/null 2>&1 \
        || fout "De componentenlijst is geen niet-lege JSON-lijst."

      oordeel "$2" "$3" "$4" "$5"
      ;;
    wacht)
      [ "$#" -ge 3 ] && [ "$#" -le 5 ] || fout "Gebruik: $0 wacht <project> <deployment> [seconden] [interval]"

      wacht "$2" "$3" "${4:-300}" "${5:-5}"
      ;;
    *)
      fout "Onbekende actie '$actie'; kies oordeel of wacht."
      ;;
  esac
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
