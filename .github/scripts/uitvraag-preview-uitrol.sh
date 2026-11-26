#!/usr/bin/env bash
# Beoordeelt de uitrol van een uitvraag-preview en wacht later tot die preview echt gezond is.
#
# Een nieuwe preview van het uitvraag-project wordt pas gezond als de demo-console in het
# magazijnen-project de proxy van `toxiproxy-redis` heeft aangemaakt: Toxiproxy houdt zijn proxies
# alleen in het geheugen, en zonder proxy weigert hij de verbinding naar Redis, dus faalt de
# readiness van de uitvraag. Die console rolt pas uit ná de uitvraag, omdat hij de netwerkregels nodig
# heeft die met de uitvraag meekomen. Operations Manager wacht na de uitrol vijf minuten op een gezonde
# deployment en geeft het dan op; de deploy-action meldt dat als een rode stap. Zonder deze tussenstap
# rolt de magazijnen-preview dus nooit uit, en wordt de uitvraag dus nooit gezond.
#
# `oordeel` geeft bij een groene deploy de adressen door. Een rode neemt hij alleen over als de uitrol
# zelf geland is: de gevraagde images staan in de deployment, er loopt geen sync, en wat ontbreekt is
# alleen de gezondheid van het uitvraag-component. Dan staan de netwerkregels in het cluster en kan de
# console erbij. Elke andere toestand blijft rood.
#
# `wacht` draait na de magazijnen-uitrol, ook als de deploy groen was: een groene stap kan "succeeded,
# but some components are unhealthy" betekenen. Hij vraagt de readiness van de uitvraag zelf op en
# niet de status in Operations Manager, want een component dat Operations Manager heeft uitgeschakeld,
# staat daar als gezond. Dit is de eigenlijke poort.
#
# Gebruik:
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh oordeel <uitkomst> <project> <deployment> <component> <componenten-json>
#     met URLS en URL in de omgeving: de outputs van de deploy-action, doorgegeven als die slaagde.
#     Zet `urls=` en `url=` op stdout, in de vorm van $GITHUB_OUTPUT.
#   uitvraag-preview-uitrol.sh wacht <urls-json> <component> [seconden] [interval]

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Een adres gaat via $GITHUB_OUTPUT verder; een regeleinde erin zou daar een eigen regel worden.
readonly ADRES='^https://[^[:space:][:cntrl:]]+$'

# `wacht` slaapt tussen twee opvragingen; de tests vervangen dit door een stub die het interval logt.
SLAAP=${ZAD_SLAAP:-sleep}

fout() {
  echo "::error::$*" >&2

  exit 1
}

# Vraagt de deployment op en zet `antwoord`. De sleutel gaat via stdin, zodat hij niet in de argv van
# curl staat.
vraag_deployment_op() {
  local project=$1 deployment=$2

  antwoord=$(printf 'X-API-Key: %s\n' "$ZAD_API_KEY" | curl -sSf --max-time 30 --retry 3 --retry-connrefused \
    -H @- -H 'Accept: application/json' \
    "${ZAD_API_URL:-$STANDAARD_API_URL}/v2/projects/$project/deployments/$deployment") \
    || fout "$deployment in $project is niet op te vragen; of de uitrol landde, is onbekend."

  jq -e 'type == "object" and (.status | type) == "string" and has("running_sync")
    and ((.urls // {}) | type) == "object"' <<<"$antwoord" >/dev/null 2>&1 \
    || fout "Het antwoord over $deployment in $project heeft niet de vorm van een deployment."
}

# Het eerste opgegeven component met een niet-leeg adres, zoals de deploy-action `url` kiest.
kies_url() {
  local urls=$1 componenten=$2

  jq -r --argjson urls "$urls" \
    'map(.name) | map(select(($urls[.] // "") | length > 0)) | first // empty | $urls[.]' <<<"$componenten"
}

oordeel() {
  local uitkomst=$1 project=$2 deployment=$3 component=$4 componenten=$5

  if [ "$uitkomst" = success ]; then
    printf 'urls=%s\nurl=%s\n' "${URLS:-}" "${URL:-}"

    return 0
  fi

  [ "$uitkomst" = failure ] || fout "Onbekende uitkomst van de deploy-stap: '$uitkomst'."

  local antwoord reden

  vraag_deployment_op "$project" "$deployment"

  # De reden als tekst en niet als exitcode van `jq -e`: zo zegt de melding wát er niet klopt.
  #
  # Een melding hoort bij het component als haar naam `<deployment>-<component>` is, eventueel met de
  # hash-achtervoegsels van ReplicaSet en pod. Zo telt een ander component waarvan de naam met die van
  # dit component begint, niet mee.
  reden=$(jq -r --argjson gevraagd "$componenten" --arg c "$component" --arg d "$deployment" '
    def van_component: (.resource // "") | test("^[A-Za-z]+/" + $d + "-" + $c + "(-[a-z0-9]{5,10}){0,2}(\\.[a-z0-9]+)?$");
    ([.components[]? | {key: .reference, value: .image}] | from_entries) as $staat
    | [$gevraagd[] | select($staat[.name] != .image) | .name] as $afwijkend
    | if ($afwijkend | length) > 0 then
        "de opgeslagen images wijken af van de gevraagde (" + ($afwijkend | join(", ")) + ")"
      elif .running_sync != null then
        "er loopt nog een sync"
      elif .status == "Healthy" then
        "gezond"
      elif (.status != "Progressing" and .status != "Degraded") then
        "status \(.status)"
      elif .status == "Degraded" and ((.errors // []) | length) == 0 then
        "Degraded zonder fouten die een component noemen"
      elif all((.errors // [])[]; van_component) then
        "nog niet gezond"
      else
        "\(.status) door meer dan het component \($c): " + ([.errors[] | select(van_component | not) | .resource] | join(", "))
      end' <<<"$antwoord" 2>/dev/null) || reden="onleesbaar antwoord"

  case "$reden" in
    gezond)
      echo "::notice::De deploy-stap meldde rood, maar $deployment in $project is gezond; de melding was alleen te vroeg." >&2
      ;;
    "nog niet gezond")
      echo "::warning::De uitrol van $deployment in $project is geland, maar $component is nog niet gezond. De magazijnen-preview rolt daarom toch uit; daarna wacht de run tot deze preview gezond is." >&2
      ;;
    *)
      fout "De uitrol van $deployment in $project is niet geland of faalt op meer dan $component: $reden."
      ;;
  esac

  local urls url

  urls=$(jq -c '.urls // {}' <<<"$antwoord")
  url=$(kies_url "$urls" "$componenten")

  [ -n "$url" ] || fout "Geen van de opgegeven componenten van $deployment heeft een publiek adres."

  # Een leeg adres slaat `kies_url` over, zoals de action; het telt hier dus niet als ongeldig.
  jq -e --arg re "$ADRES" 'all(.[]; type == "string" and (length == 0 or test($re)))' <<<"$urls" >/dev/null \
    || fout "$deployment in $project heeft een adres dat geen kale https-URL is."

  printf 'urls=%s\nurl=%s\n' "$urls" "$url"
}

wacht() {
  local urls=$1 component=$2 seconden=$3 interval=$4 basis

  basis=$(jq -r --arg c "$component" '.[$c] // empty' <<<"$urls" 2>/dev/null) || basis=""

  [[ "$basis" =~ $ADRES ]] || fout "Geen bruikbaar adres voor $component in de adressen van de uitvraag-preview."

  local einde=$((SECONDS + seconden)) http status=onbekend lichaam

  while :; do
    # `-f` niet: dan is een 503 van de router niet van een onbereikbare host te onderscheiden.
    lichaam=$(curl -sS --max-time 10 -w '\n%{http_code}' "${basis%/}/q/health/ready" 2>/dev/null) || lichaam=$'\n000'
    http=${lichaam##*$'\n'}

    if [ "$http" = 200 ] && [ "$(jq -r '.status? // empty' <<<"${lichaam%$'\n'*}" 2>/dev/null)" = UP ]; then
      echo "$component van de preview is gezond ($basis)."

      return 0
    fi

    status="HTTP $http"

    [ "$SECONDS" -lt "$einde" ] || break

    "$SLAAP" "$interval"
  done

  fout "$component van de preview was na ${seconden}s nog niet gezond (laatste readiness: $status op $basis)."
}

main() {
  local actie=${1:-}

  case "$actie" in
    oordeel)
      [ "$#" -eq 6 ] || fout "Gebruik: $0 oordeel <uitkomst> <project> <deployment> <component> <componenten-json>"

      jq -e 'type == "array" and length > 0' <<<"$6" >/dev/null 2>&1 \
        || fout "De componentenlijst is geen niet-lege JSON-lijst."

      if [ "$2" != success ]; then
        [ -n "${ZAD_API_KEY:-}" ] || fout "ZAD_API_KEY ontbreekt."
      fi

      oordeel "$2" "$3" "$4" "$5" "$6"
      ;;
    wacht)
      [ "$#" -ge 3 ] && [ "$#" -le 5 ] || fout "Gebruik: $0 wacht <urls-json> <component> [seconden] [interval]"

      wacht "$2" "$3" "${4:-300}" "${5:-10}"
      ;;
    *)
      fout "Onbekende actie '$actie'; kies oordeel of wacht."
      ;;
  esac
}

main "$@"
