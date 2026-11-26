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
# `nulmeting` telt vóór de uitrol hoeveel wijzigingen er in het project op uitrol wachten. Die telling
# geldt voor het hele project, en `preview-klaarzetten` laat er bij een nieuwe preview zelf een paar
# achter die blijven meetellen tot iemand het hele project herverwerkt. Een absolute eis van nul zou dus
# elke nieuwe preview rood maken.
#
# `oordeel` geeft bij een groene deploy de adressen door. Een rode laat hij alleen door als de uitrol
# zelf geland is: de gevraagde images staan in de deployment, er wacht niet meer op uitrol dan bij de
# nulmeting, er loopt geen sync, en geen ander component dan de uitvraag meldt een fout of afwijking.
# Dan staan de netwerkregels in het cluster en kan de console erbij. Elke andere toestand blijft rood.
# De telling vangt het enige pad waarlangs deze uitrol iets laat wachten: een overname door een andere
# taak die de wijziging opslaat maar niet uitrolt. Zet een andere PR in die paar minuten iets in de
# wacht, dan wordt deze run ook rood; dat is de veilige kant, en alleen een herhaling van de hele run
# doet een nieuwe nulmeting.
#
# `wacht` is de eigenlijke poort. Hij draait na de magazijnen-uitrol, ook als de deploy groen was: een
# groene stap kan "succeeded, but some components are unhealthy" betekenen. Hij eist twee bronnen
# tegelijk, want elk alleen laat een kapotte preview door. Operations Manager moet de deployment met de
# gevraagde images en zonder sync `Healthy` noemen: bij een bestaande preview blijft de oude pod anders
# ready, en zijn readiness zegt dan `UP` voor een versie die niet meer gevraagd is. En de readiness van
# de uitvraag zelf moet `UP` zijn: een component dat Operations Manager heeft uitgeschakeld, staat daar
# als gezond. Die tweede bron dekt alleen de uitvraag en wat zijn readiness raakt (Redis en
# `toxiproxy-redis`), niet `toxiproxy-aanmeld`. De telling van wachtende uitrollen telt hier niet mee:
# deze stap schrijft niets, en een andere PR zou hem anders minutenlang rood kunnen houden.
#
# Gebruik:
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh nulmeting <project> <deployment>
#     Zet `wachtend=` op stdout.
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh oordeel <uitkomst> <project> <deployment> <component> <componenten-json> <wachtend>
#     met URLS en URL in de omgeving: de outputs van de deploy-action, doorgegeven als die slaagde.
#     Zet `urls=` en `url=` op stdout.
#   ZAD_API_KEY=... uitvraag-preview-uitrol.sh wacht <project> <deployment> <component> <componenten-json> <urls-json> [seconden] [interval]

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Een adres gaat via $GITHUB_OUTPUT verder; een regeleinde erin zou daar een eigen regel worden.
readonly ADRES='^https://[^[:space:][:cntrl:]]+$'

# Tussen twee opvragingen; de tests vervangen dit door een stub die het interval logt.
SLAAP=${ZAD_SLAAP:-sleep}

# De toets op een deployment, gedeeld door `oordeel` en `wacht`. Geeft de reden waarom hij niet klopt,
# of `gezond` / `nog niet gezond`. Met `$max` null telt het aantal wachtende uitrollen niet mee.
#
# Een melding hoort bij het component als haar naam `<deployment>-<component>` is, eventueel met de
# hash-achtervoegsels van ReplicaSet en pod en het achtervoegsel van een event. Kubernetes gebruikt in
# die hashes geen klinkers, dus een melding van een ander component zoals `uitvraag-aanmeld` telt niet
# als een melding van de uitvraag.
#
# shellcheck disable=SC2016 # de $-namen zijn jq-variabelen, geen shell-expansie
readonly TOETS='
  def van_component: (.resource // "") | test("^[A-Za-z]+/" + $d + "-" + $c
    + "(-[bcdfghjklmnpqrstvwxz2456789]{5,10}){0,2}(\\.[0-9a-f]+)?$");
  ([.components[]? | {key: .reference, value: .image}] | from_entries) as $staat
  | [$gevraagd[] | select($staat[.name] != .image) | .name] as $afwijkend
  | (.errors // []) as $fouten
  | ($fouten + (.deviations // [])) as $meldingen
  | if ($afwijkend | length) > 0 then
      "de opgeslagen images wijken af van de gevraagde (" + ($afwijkend | join(", ")) + ")"
    elif $max != null and (.pending_rollout.count | type) != "number" then
      "het aantal wachtende uitrollen ontbreekt"
    elif $max != null and .pending_rollout.count > $max then
      "er wachten \(.pending_rollout.count) wijziging(en) in het project op uitrol, \($max) bij de nulmeting (de telling geldt voor het hele project; komt de stijging van een andere PR, herhaal dan de hele run, niet alleen de mislukte jobs)"
    elif .running_sync != null then
      "er loopt nog een sync"
    elif .status == "Healthy" then
      "gezond"
    elif (.status != "Progressing" and .status != "Degraded") then
      "status \(.status)"
    elif .status == "Degraded" and ($fouten | length) == 0 then
      "Degraded zonder fouten die een component noemen"
    elif all($meldingen[]; van_component) then
      "nog niet gezond"
    else
      "\(.status) door meer dan het component \($c): "
        + ([$meldingen[] | select(van_component | not) | (.resource // "(zonder resource)")] | join(", "))
    end'

fout() {
  echo "::error::$*" >&2

  exit 1
}

# Een stderr-bestand op één regel, ingekort tot 200 tekens. Uit een eigen bestand: met `2>&1` raakt de
# melding van curl vermengd met de body en de statuscode die `-w` erachter zet.
stderr_melding() {
  tr '\n' ' ' <"$1" | head -c 200
}

# Vraagt de deployment op en zet `antwoord`, of `opvraagfout` met de reden. Exitcode 0 = bruikbaar
# antwoord, 1 = mislukt maar opnieuw proberen heeft zin, 2 = de sleutel wordt geweigerd, 3 = de
# deployment bestaat niet.
#
# Met `telling` als derde argument hoeft het antwoord alleen een object te zijn: vlak na
# `preview-klaarzetten` is de deployment nog niet uitgerold, en wat er dan nog aan velden ontbreekt, is
# niet vastgelegd.
#
# De sleutel gaat via stdin, zodat hij niet in de argv van curl staat. Zonder `-f`, zodat een
# HTTP-fout geen curl-fout wordt; de statuscode achter de body scheidt een geweigerde sleutel en een
# verdwenen deployment van een storing.
vraag_deployment_op() {
  local project=$1 deployment=$2 vorm=${3:-deployment} ruw http fouten rc=0

  fouten=$(mktemp)
  ruw=$(printf 'X-API-Key: %s\n' "$ZAD_API_KEY" | curl -sS --max-time 30 -w '\n%{http_code}' \
    -H @- -H 'Accept: application/json' \
    "${ZAD_API_URL:-$STANDAARD_API_URL}/v2/projects/$project/deployments/$deployment" 2>"$fouten") || rc=$?

  if [ "$rc" -ne 0 ]; then
    opvraagfout="curl $rc: $(stderr_melding "$fouten")"
    rm -f "$fouten"

    return 1
  fi

  rm -f "$fouten"
  http=${ruw##*$'\n'}
  antwoord=${ruw%$'\n'*}

  case "$http" in
    200) ;;
    401 | 403) opvraagfout="HTTP $http"; return 2 ;;
    404) opvraagfout="HTTP 404"; return 3 ;;
    *) opvraagfout="HTTP $http"; return 1 ;;
  esac

  if [ "$vorm" = telling ]; then
    jq -e 'type == "object"' <<<"$antwoord" >/dev/null 2>&1 \
      || { opvraagfout="een antwoord zonder de vorm van een deployment"; return 1; }

    return 0
  fi

  jq -e 'type == "object" and (.status | type) == "string" and has("running_sync")
    and ((.urls // {}) | type) == "object"' <<<"$antwoord" >/dev/null 2>&1 \
    || { opvraagfout="een antwoord zonder de vorm van een deployment"; return 1; }
}

# `vraag_deployment_op` met drie pogingen, voor de stappen die één keer kijken: één hik van Operations
# Manager hoort de uitrol niet rood te maken. Een geweigerde sleutel of verdwenen deployment herhaalt
# niet.
vraag_deployment_op_met_herhaling() {
  local poging rc

  for poging in 1 2 3; do
    rc=0
    vraag_deployment_op "$@" || rc=$?

    [ "$rc" -eq 1 ] || return "$rc"

    [ "$poging" -eq 3 ] || "$SLAAP" 5
  done

  return 1
}

# De reden uit TOETS voor `antwoord`; een fout van jq komt in de reden mee.
toets() {
  local componenten=$1 component=$2 deployment=$3 max=$4 jqfout

  jqfout=$(mktemp)
  jq -r --argjson gevraagd "$componenten" --arg c "$component" --arg d "$deployment" --argjson max "${max:-null}" \
    "$TOETS" <<<"$antwoord" 2>"$jqfout" || echo "onleesbaar antwoord ($(stderr_melding "$jqfout"))"
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

nulmeting() {
  local project=$1 deployment=$2 antwoord opvraagfout="" wachtend

  vraag_deployment_op_met_herhaling "$project" "$deployment" telling \
    || fout "$deployment in $project is vóór de uitrol niet op te vragen ($opvraagfout)."

  wachtend=$(jq -r '.pending_rollout.count | if type == "number" and . >= 0 and floor == . then . else empty end' \
    <<<"$antwoord" 2>/dev/null) || wachtend=""

  [ -n "$wachtend" ] || fout "Het aantal wachtende uitrollen in $project is vóór de uitrol niet te lezen."

  printf 'wachtend=%s\n' "$wachtend"
}

oordeel() {
  local uitkomst=$1 project=$2 deployment=$3 component=$4 componenten=$5 max=$6

  if [ "$uitkomst" = success ]; then
    geldige_adressen "${URLS:-}" && [[ "${URL:-}" =~ $ADRES ]] \
      || fout "De deploy-action slaagde maar gaf geen bruikbare adressen voor $deployment."

    printf 'urls=%s\nurl=%s\n' "$URLS" "$URL"

    return 0
  fi

  [ "$uitkomst" = failure ] || fout "Onbekende uitkomst van de deploy-stap: '$uitkomst'."

  local antwoord opvraagfout="" reden urls url

  vraag_deployment_op_met_herhaling "$project" "$deployment" \
    || fout "$deployment in $project is niet op te vragen ($opvraagfout); of de uitrol landde, is onbekend."

  reden=$(toets "$componenten" "$component" "$deployment" "$max")

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

# Vraagt de readiness op en zet `readiness` op `UP` of op wat er mis is. Zonder `-f`: daarmee gooit
# curl bij een 503 de body weg, en dan zegt de melding niet welke check niet UP is.
vraag_readiness_op() {
  local adres=$1 ruw http body niet_up fouten rc=0

  fouten=$(mktemp)
  ruw=$(curl -sS --max-time 10 -w '\n%{http_code}' "$adres" 2>"$fouten") || rc=$?

  if [ "$rc" -ne 0 ]; then
    readiness="curl $rc: $(stderr_melding "$fouten")"
    rm -f "$fouten"

    return
  fi

  rm -f "$fouten"
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
      0) staat=$(toets "$componenten" "$component" "$deployment" "") ;;
      2) fout "$deployment in $project is niet op te vragen ($opvraagfout); opnieuw proberen verandert dit antwoord niet." ;;
      3) fout "$deployment in $project bestaat niet meer; waarschijnlijk is de preview opgeruimd." ;;
      *) staat="niet op te vragen ($opvraagfout)" ;;
    esac

    # De gevraagde images stonden na deze uitrol in de deployment: de deploy slaagde, of `oordeel` zag
    # ze daar. Wijken ze nu af, dan zette een nieuwere uitrol van deze deployment ze verder, en wordt
    # deze run niet meer gezond.
    case "$staat" in
      "de opgeslagen images wijken af"*)
        fout "$deployment in $project is intussen door een nieuwere uitrol overgenomen: $staat."
        ;;
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

geldige_componenten() {
  jq -e 'type == "array" and length > 0 and all(.[]; type == "object"
    and (.name | type) == "string" and (.name | length) > 0
    and (.image | type) == "string" and (.image | length) > 0)' <<<"$1" >/dev/null 2>&1 \
    || fout "De componentenlijst is geen niet-lege JSON-lijst van componenten met een naam en een image."
}

geldig_aantal() {
  [[ "$1" =~ ^[0-9]+$ ]] || fout "Het aantal wachtende uitrollen bij de nulmeting is geen getal: '$1'."
}

vereis_sleutel() {
  [ -n "${ZAD_API_KEY:-}" ] || fout "ZAD_API_KEY ontbreekt."
}

main() {
  local actie=${1:-}

  case "$actie" in
    nulmeting)
      [ "$#" -eq 3 ] || fout "Gebruik: $0 nulmeting <project> <deployment>"

      vereis_sleutel
      nulmeting "$2" "$3"
      ;;
    oordeel)
      [ "$#" -eq 7 ] || fout "Gebruik: $0 oordeel <uitkomst> <project> <deployment> <component> <componenten-json> <wachtend>"

      geldige_componenten "$6"

      if [ "$2" != success ]; then
        vereis_sleutel
        geldig_aantal "$7"
      fi

      oordeel "$2" "$3" "$4" "$5" "$6" "$7"
      ;;
    wacht)
      [ "$#" -ge 6 ] && [ "$#" -le 8 ] \
        || fout "Gebruik: $0 wacht <project> <deployment> <component> <componenten-json> <urls-json> [seconden] [interval]"

      geldige_componenten "$5"
      vereis_sleutel
      wacht "$2" "$3" "$4" "$5" "$6" "${7:-300}" "${8:-10}"
      ;;
    *)
      fout "Onbekende actie '$actie'; kies nulmeting, oordeel of wacht."
      ;;
  esac
}

main "$@"
