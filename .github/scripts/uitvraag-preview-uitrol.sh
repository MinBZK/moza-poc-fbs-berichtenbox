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
# `oordeel` geeft bij een groene deploy de adressen door. Een rode laat hij alleen door als de uitrol
# zelf geland is: de gevraagde images staan in de deployment, in het project wacht niets meer op
# uitrol, er loopt geen sync, en geen ander component dan de uitvraag meldt een fout. Dan staan de
# netwerkregels in het cluster en kan de console erbij. Elke andere toestand blijft rood.
#
# `wacht` draait na de magazijnen-uitrol, ook als de deploy groen was: een groene stap kan "succeeded,
# but some components are unhealthy" betekenen. Hij eist twee bronnen tegelijk, want elk alleen laat
# een kapotte preview door. Operations Manager moet de deployment met de gevraagde images en zonder
# wachtende uitrol `Healthy` noemen: bij een bestaande preview blijft de oude pod anders ready, en zijn
# readiness zegt dan `UP` voor een versie die niet meer gevraagd is. En de readiness van de uitvraag
# zelf moet `UP` zijn: een component dat Operations Manager heeft uitgeschakeld, staat daar als gezond.
# Dit is de eigenlijke poort.
#
# Een wachtende uitrol telt voor het hele project. Een wijziging van een andere PR die op dat moment
# wacht, maakt deze run dus ook rood; dat is de veilige kant, en een herhaalde run lost het op.
#
# Gebruik:
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh oordeel <uitkomst> <project> <deployment> <component> <componenten-json>
#     met URLS en URL in de omgeving: de outputs van de deploy-action, doorgegeven als die slaagde.
#     Zet `urls=` en `url=` op stdout, in de vorm van $GITHUB_OUTPUT.
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh wacht <project> <deployment> <component> <componenten-json> <urls-json> [seconden] [interval]

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Een adres gaat via $GITHUB_OUTPUT verder; een regeleinde erin zou daar een eigen regel worden.
readonly ADRES='^https://[^[:space:][:cntrl:]]+$'

# `wacht` slaapt tussen twee opvragingen; de tests vervangen dit door een stub die het interval logt.
SLAAP=${ZAD_SLAAP:-sleep}

# De toets op een deployment, gedeeld door `oordeel` en `wacht`. Geeft de reden waarom hij niet klopt,
# of `gezond` / `nog niet gezond`.
#
# Een melding hoort bij het component als haar naam `<deployment>-<component>` is, eventueel met de
# hash-achtervoegsels van ReplicaSet en pod en het achtervoegsel van een event. Kubernetes gebruikt in
# die hashes geen klinkers, dus een ander component zoals `uitvraag-aanmeld` telt niet mee.
# shellcheck disable=SC2016 # de $-namen zijn jq-variabelen, geen shell-expansie
readonly TOETS='
  def van_component: (.resource // "") | test("^[A-Za-z]+/" + $d + "-" + $c
    + "(-[bcdfghjklmnpqrstvwxz2456789]{5,10}){0,2}(\\.[0-9a-f]+)?$");
  ([.components[]? | {key: .reference, value: .image}] | from_entries) as $staat
  | [$gevraagd[] | select($staat[.name] != .image) | .name] as $afwijkend
  | (.errors // []) as $fouten
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
    elif (.status != "Progressing" and .status != "Degraded") then
      "status \(.status)"
    elif .status == "Degraded" and ($fouten | length) == 0 then
      "Degraded zonder fouten die een component noemen"
    elif all($fouten[]; van_component) then
      "nog niet gezond"
    else
      "\(.status) door meer dan het component \($c): " + ([$fouten[] | select(van_component | not) | .resource] | join(", "))
    end'

fout() {
  echo "::error::$*" >&2

  exit 1
}

# Vraagt de deployment op en zet `antwoord`, of `opvraagfout` met de reden. Exitcode 0 = bruikbaar
# antwoord, 1 = mislukt maar opnieuw proberen heeft zin, 2 = de sleutel wordt geweigerd.
#
# De sleutel gaat via stdin, zodat hij niet in de argv van curl staat. Zonder `-f`, met de statuscode
# achter de body: dan is een 401 van een 503 te onderscheiden.
vraag_deployment_op() {
  local project=$1 deployment=$2 ruw http

  ruw=$(printf 'X-API-Key: %s\n' "$ZAD_API_KEY" | curl -sS --max-time 30 -w '\n%{http_code}' \
    -H @- -H 'Accept: application/json' \
    "${ZAD_API_URL:-$STANDAARD_API_URL}/v2/projects/$project/deployments/$deployment" 2>&1) \
    || { opvraagfout="curl: $(tr '\n' ' ' <<<"${ruw%$'\n'*}" | head -c 200)"; return 1; }

  http=${ruw##*$'\n'}
  antwoord=${ruw%$'\n'*}

  case "$http" in
    200) ;;
    401 | 403) opvraagfout="HTTP $http"; return 2 ;;
    *) opvraagfout="HTTP $http"; return 1 ;;
  esac

  jq -e 'type == "object" and (.status | type) == "string" and has("running_sync")
    and ((.urls // {}) | type) == "object"' <<<"$antwoord" >/dev/null 2>&1 \
    || { opvraagfout="een antwoord zonder de vorm van een deployment"; return 1; }
}

# De reden uit TOETS voor `antwoord`; een fout van jq komt in de reden mee.
toets() {
  local componenten=$1 component=$2 deployment=$3 jqfout

  jqfout=$(mktemp)
  jq -r --argjson gevraagd "$componenten" --arg c "$component" --arg d "$deployment" "$TOETS" \
    <<<"$antwoord" 2>"$jqfout" || echo "onleesbaar antwoord ($(head -c 200 "$jqfout"))"
  rm -f "$jqfout"
}

# Of alle adressen kale https-URL's of leeg zijn. Een leeg adres slaat `kies_url` over, zoals de action.
geldige_adressen() {
  jq -e --arg re "$ADRES" \
    'type == "object" and all(.[]; type == "string" and (length == 0 or test($re)))' <<<"$1" >/dev/null 2>&1
}

# Het eerste opgegeven component met een niet-leeg adres, zoals de deploy-action `url` kiest.
kies_url() {
  jq -r --argjson urls "$1" \
    'map(.name) | map(select(($urls[.] // "") | length > 0)) | first // empty | $urls[.]' <<<"$2"
}

oordeel() {
  local uitkomst=$1 project=$2 deployment=$3 component=$4 componenten=$5

  if [ "$uitkomst" = success ]; then
    geldige_adressen "${URLS:-}" && [[ "${URL:-}" =~ $ADRES ]] \
      || fout "De deploy-action slaagde maar gaf geen bruikbare adressen voor $deployment."

    printf 'urls=%s\nurl=%s\n' "$URLS" "$URL"

    return 0
  fi

  [ "$uitkomst" = failure ] || fout "Onbekende uitkomst van de deploy-stap: '$uitkomst'."

  local antwoord opvraagfout="" reden urls url

  vraag_deployment_op "$project" "$deployment" \
    || fout "$deployment in $project is niet op te vragen ($opvraagfout); of de uitrol landde, is onbekend."

  reden=$(toets "$componenten" "$component" "$deployment")

  case "$reden" in
    gezond | "nog niet gezond") ;;
    *) fout "De uitrol van $deployment in $project is niet geland of faalt op meer dan $component: $reden." ;;
  esac

  urls=$(jq -c '.urls // {}' <<<"$antwoord")

  geldige_adressen "$urls" || fout "$deployment in $project heeft een adres dat geen kale https-URL is."

  url=$(kies_url "$urls" "$componenten")

  [ -n "$url" ] || fout "Geen van de opgegeven componenten van $deployment heeft een publiek adres."

  if [ "$reden" = gezond ]; then
    echo "::notice::De deploy-stap meldde rood, maar $deployment in $project is gezond; de melding was alleen te vroeg." >&2
  else
    echo "::warning::De uitrol van $deployment in $project is geland, maar $component is nog niet gezond. De magazijnen-preview rolt daarom toch uit; daarna wacht de run tot deze preview gezond is." >&2
  fi

  printf 'urls=%s\nurl=%s\n' "$urls" "$url"
}

# Vraagt de readiness op en zet `readiness` op `UP` of op wat er mis is. `-f` niet: dan is een 503
# van de router niet van een onbereikbare host te onderscheiden. Wat curl zelf meldt, komt mee.
vraag_readiness_op() {
  local adres=$1 ruw http body niet_up rc=0

  ruw=$(curl -sS --max-time 10 -w '\n%{http_code}' "$adres" 2>&1) || rc=$?

  if [ "$rc" -ne 0 ]; then
    readiness="curl $rc: $(tr '\n' ' ' <<<"${ruw%$'\n'*}" | head -c 200)"

    return
  fi

  http=${ruw##*$'\n'}
  body=${ruw%$'\n'*}

  if [ "$http" = 200 ] && [ "$(jq -r '.status? // empty' <<<"$body" 2>/dev/null)" = UP ]; then
    readiness=UP

    return
  fi

  niet_up=$(jq -r '[.checks[]? | select(.status != "UP") | .name] | join(", ")' <<<"$body" 2>/dev/null) || niet_up=""
  readiness="HTTP $http${niet_up:+, niet UP: $niet_up}"
}

wacht() {
  local project=$1 deployment=$2 component=$3 componenten=$4 urls=$5 seconden=$6 interval=$7 basis

  basis=$(jq -r --arg c "$component" '.[$c] // empty' <<<"$urls" 2>/dev/null) || basis=""

  [[ "$basis" =~ $ADRES ]] || fout "Geen bruikbaar adres voor $component in de adressen van de uitvraag-preview."

  local einde=$((SECONDS + seconden)) antwoord opvraagfout readiness staat rc

  while :; do
    rc=0
    vraag_deployment_op "$project" "$deployment" || rc=$?

    case "$rc" in
      0) staat=$(toets "$componenten" "$component" "$deployment") ;;
      2) fout "$deployment in $project is niet op te vragen ($opvraagfout); opnieuw proberen verandert dit antwoord niet." ;;
      *) staat="niet op te vragen ($opvraagfout)" ;;
    esac

    vraag_readiness_op "${basis%/}/q/health/ready"

    if [ "$staat" = gezond ] && [ "$readiness" = UP ]; then
      echo "$deployment in $project is gezond, en $component antwoordt UP ($basis)."

      return 0
    fi

    [ "$SECONDS" -lt "$einde" ] || break

    "$SLAAP" "$interval"
  done

  fout "$deployment in $project was na ${seconden}s nog niet gezond (Operations Manager: $staat; readiness van $component: $readiness)."
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
      [ "$#" -ge 6 ] && [ "$#" -le 8 ] \
        || fout "Gebruik: $0 wacht <project> <deployment> <component> <componenten-json> <urls-json> [seconden] [interval]"

      jq -e 'type == "array" and length > 0' <<<"$5" >/dev/null 2>&1 \
        || fout "De componentenlijst is geen niet-lege JSON-lijst."

      [ -n "${ZAD_API_KEY:-}" ] || fout "ZAD_API_KEY ontbreekt."

      wacht "$2" "$3" "$4" "$5" "$6" "${7:-300}" "${8:-10}"
      ;;
    *)
      fout "Onbekende actie '$actie'; kies oordeel of wacht."
      ;;
  esac
}

main "$@"
