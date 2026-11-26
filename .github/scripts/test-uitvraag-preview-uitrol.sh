#!/usr/bin/env bash
# Unittests voor uitvraag-preview-uitrol.sh, en voor de aansluiting op deploy.yml. Geen netwerk: een
# curl-stub op het pad geeft per aanroep het volgende geregisseerde antwoord.
#
# `oordeel` maakt een rode deploy groen. Alleen een uitrol die aantoonbaar landde en waarvan alleen het
# uitvraag-component nog niet gezond is, mag dat; elke twijfel hoort rood te blijven.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/uitvraag-preview-uitrol.sh"
readonly DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

fout() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    fout "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

mist() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) fout "$wat — '$naald' hoort er niet in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# Elk argument is één antwoord, in volgorde; het laatste blijft daarna staan. `FAAL` als antwoord doet
# alsof curl zelf faalde.
maak_stub() {
  rm -f "$werkmap"/antwoord.* "$werkmap/aanroepen" "$werkmap/teller"

  local i=0 a

  for a in "$@"; do
    i=$((i + 1))
    printf '%s' "$a" >"$werkmap/antwoord.$i"
  done

  cat >"$werkmap/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$werkmap/aanroepen"
n=\$(( \$(cat "$werkmap/teller" 2>/dev/null || echo 0) + 1 ))
echo "\$n" >"$werkmap/teller"
[ -f "$werkmap/antwoord.\$n" ] || n=$i
if [ "\$(cat "$werkmap/antwoord.\$n")" = FAAL ]; then exit 22; fi
cat "$werkmap/antwoord.\$n"
STUB
  chmod +x "$werkmap/curl"
}

draai() {
  RC=0
  UITVOER=$(PATH="$werkmap:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test ZAD_SLAAP=true \
    bash "$SCRIPT" "$@" 2>&1) || RC=$?
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
}

readonly GEVRAAGD='[{"name":"redis","image":"redis:7"},{"name":"toxiproxy-aanmeld","image":"tp:2"},{"name":"uitvraag","image":"uv:pr-7-abc"}]'

# Een deployment zoals Operations Manager hem geeft; de jq-filter erachter past één veld aan.
deployment() {
  jq -c "${1:-.}" <<'JSON'
{
  "status": "Degraded",
  "running_sync": null,
  "pending_rollout": {"project": "mpfb-8wh", "count": 0},
  "components": [
    {"reference": "redis", "image": "redis:7"},
    {"reference": "toxiproxy-aanmeld", "image": "tp:2"},
    {"reference": "uitvraag", "image": "uv:pr-7-abc"}
  ],
  "urls": {
    "toxiproxy-aanmeld": "https://toxiproxy-aanmeld-pr-7.test",
    "uitvraag": "https://uitvraag-pr-7.test"
  },
  "errors": [
    {"resource": "Deployment/pr-7-uitvraag", "message": "Deployment \"pr-7-uitvraag\" exceeded its progress deadline"}
  ]
}
JSON
}

oordeel() {
  draai oordeel mpfb-8wh pr-7 uitvraag "${1:-$GEVRAAGD}"
}

# --- oordeel: aanvaard --------------------------------------------------------------------------
maak_stub "$(deployment)"
oordeel
gelijk "geland en alleen de uitvraag ongezond: aanvaard" 0 "$RC"
bevat "de deployment van het juiste project wordt opgevraagd" 'http://api.test/v2/projects/mpfb-8wh/deployments/pr-7' "$AANROEPEN"
bevat "met de API-sleutel" 'X-API-Key: sleutel' "$AANROEPEN"
bevat "nog niet gezond" 'gezond=false' "$UITVOER"
bevat "de adressen als compacte JSON" 'urls={"toxiproxy-aanmeld":"https://toxiproxy-aanmeld-pr-7.test","uitvraag":"https://uitvraag-pr-7.test"}' "$UITVOER"
# Zoals de deploy-action: het eerste opgegeven component mét adres, niet het eerste in de lijst.
bevat "url is het eerste opgegeven component met een adres" 'url=https://toxiproxy-aanmeld-pr-7.test' "$UITVOER"
bevat "de aanvaarding staat als waarschuwing in de log" '::warning::' "$UITVOER"

# Een pod- of event-melding van hetzelfde component telt als datzelfde component.
maak_stub "$(deployment '.errors = [{"resource":"Event/pr-7-uitvraag-6864549db5-8zd6c"},{"resource":"Deployment/pr-7-uitvraag"}]')"
oordeel
gelijk "meerdere meldingen, alle van de uitvraag: aanvaard" 0 "$RC"

maak_stub "$(deployment '.status = "Progressing" | .errors = []')"
oordeel
gelijk "nog bezig met uitrollen: aanvaard" 0 "$RC"
bevat "en nog niet gezond" 'gezond=false' "$UITVOER"

maak_stub "$(deployment '.status = "Healthy" | .errors = []')"
oordeel
gelijk "inmiddels gezond: aanvaard" 0 "$RC"
bevat "als gezond" 'gezond=true' "$UITVOER"
mist "zonder waarschuwing" '::warning::' "$UITVOER"

# --- oordeel: niet aanvaard ---------------------------------------------------------------------
# Het belangrijkste geval: de upsert faalde vóór het opslaan, en de oude preview staat er gezond bij.
maak_stub "$(deployment '.status = "Healthy" | .errors = [] | .components[2].image = "uv:pr-7-oud"')"
oordeel
gelijk "oud image opgeslagen, oude preview gezond: rood" 1 "$RC"
bevat "met het afwijkende component" 'uitvraag' "$UITVOER"
mist "zonder uitvoer voor GITHUB_OUTPUT" 'gezond=' "$UITVOER"

maak_stub "$(deployment '.components |= map(select(.reference != "redis"))')"
oordeel
gelijk "een gevraagd component ontbreekt: rood" 1 "$RC"
bevat "en noemt het" '(redis)' "$UITVOER"

maak_stub "$(deployment '.pending_rollout.count = 2')"
oordeel
gelijk "wachtende wijzigingen in het project: rood" 1 "$RC"
bevat "met het aantal" '2 wijziging' "$UITVOER"

maak_stub "$(deployment '.pending_rollout = null')"
oordeel
gelijk "aantal wachtende uitrollen onbekend: rood" 1 "$RC"

maak_stub "$(deployment '.running_sync = {"revision":"abc"}')"
oordeel
gelijk "er loopt nog een sync: rood" 1 "$RC"

maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-7-uitvraag"},{"resource":"Deployment/pr-7-redis"}]')"
oordeel
gelijk "ook een ander component ongezond: rood" 1 "$RC"
bevat "en noemt dat component" 'Deployment/pr-7-redis' "$UITVOER"

# Een component met dezelfde naam als voorvoegsel is een ander component.
maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-7-uitvraagx"}]')"
oordeel
gelijk "component met de naam als voorvoegsel: rood" 1 "$RC"

# Een buur-deployment met hetzelfde voorvoegsel is een andere deployment.
maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-70-uitvraag"}]')"
oordeel
gelijk "melding van een buur-deployment: rood" 1 "$RC"

maak_stub "$(deployment '.errors = []')"
oordeel
gelijk "Degraded zonder fouten: rood" 1 "$RC"

maak_stub "$(deployment '.status = "OutOfSync"')"
oordeel
gelijk "OutOfSync: rood" 1 "$RC"
bevat "met de status" 'OutOfSync' "$UITVOER"

maak_stub "$(deployment '.urls = {}')"
oordeel
gelijk "geen enkel adres: rood" 1 "$RC"

maak_stub "$(deployment '.urls = {"redis":"https://redis.test"}')"
oordeel '[{"name":"uitvraag","image":"uv:pr-7-abc"}]'
gelijk "geen adres voor een opgegeven component: rood" 1 "$RC"

maak_stub FAAL
oordeel
gelijk "deployment niet op te vragen: rood" 1 "$RC"

maak_stub 'geen json'
oordeel
gelijk "antwoord zonder JSON: rood" 1 "$RC"

maak_stub '{"name":"pr-7"}'
oordeel
gelijk "antwoord zonder status: rood" 1 "$RC"

maak_stub "$(deployment)"
oordeel '[]'
gelijk "lege componentenlijst: rood" 1 "$RC"

maak_stub "$(deployment)"
oordeel 'geen json'
gelijk "componentenlijst zonder JSON: rood" 1 "$RC"

# --- wacht --------------------------------------------------------------------------------------
maak_stub "$(deployment '.status = "Healthy" | .errors = []')"
draai wacht mpfb-8wh pr-7 10 1
gelijk "meteen gezond" 0 "$RC"
gelijk "één opvraging" 1 "$(wc -l <<<"$AANROEPEN" | tr -d ' ')"

maak_stub "$(deployment)" "$(deployment '.status = "Progressing"')" FAAL "$(deployment '.status = "Healthy" | .errors = []')"
draai wacht mpfb-8wh pr-7 10 0
gelijk "na Degraded, Progressing en een mislukte opvraging gezond" 0 "$RC"
gelijk "vier opvragingen" 4 "$(wc -l <<<"$AANROEPEN" | tr -d ' ')"

# Gezond, maar met een sync die nog loopt, is nog niet klaar.
maak_stub "$(deployment '.status = "Healthy" | .errors = [] | .running_sync = {"revision":"abc"}')" \
  "$(deployment '.status = "Healthy" | .errors = []')"
draai wacht mpfb-8wh pr-7 10 0
gelijk "wacht tot de lopende sync klaar is" 0 "$RC"
gelijk "twee opvragingen" 2 "$(wc -l <<<"$AANROEPEN" | tr -d ' ')"

maak_stub "$(deployment)"
draai wacht mpfb-8wh pr-7 0 0
gelijk "blijft Degraded: rood" 1 "$RC"
bevat "met de laatste status" 'laatste status: Degraded' "$UITVOER"
bevat "en de foutmelding" 'exceeded its progress deadline' "$UITVOER"

maak_stub FAAL
draai wacht mpfb-8wh pr-7 0 0
gelijk "niet op te vragen: rood" 1 "$RC"
bevat "en zegt dat" 'niet op te vragen' "$UITVOER"

# --- aanroep ------------------------------------------------------------------------------------
RC=0
UITVOER=$(PATH="$werkmap:$PATH" ZAD_API_KEY='' bash "$SCRIPT" wacht mpfb-8wh pr-7 2>&1) || RC=$?
gelijk "zonder API-sleutel: rood" 1 "$RC"

draai onzin mpfb-8wh pr-7
gelijk "onbekende actie: rood" 1 "$RC"

draai oordeel mpfb-8wh pr-7
gelijk "oordeel met te weinig argumenten: rood" 1 "$RC"

# --- aansluiting op deploy.yml ------------------------------------------------------------------
# Zonder `continue-on-error` komt het oordeel nooit aan de beurt; zonder de wachtstap blijft een
# preview die nooit gezond werd groen.
uitvraag_job=$(awk '/^  deploy-preview-uitvraag:/{f=1; next} f && /^  [a-z][a-z0-9-]*:$/{exit} f' "$DEPLOY_YML")
magazijnen_job=$(awk '/^  deploy-preview-magazijnen:/{f=1; next} f && /^  [a-z][a-z0-9-]*:$/{exit} f' "$DEPLOY_YML")

bevat "de uitvraag-deploy mag falen" 'continue-on-error: true' "$uitvraag_job"
bevat "de uitvraag-job roept het oordeel aan" 'uitvraag-preview-uitrol.sh oordeel' "$uitvraag_job"
# shellcheck disable=SC2016 # ${{ }} is letterlijke tekst uit de workflow, geen shell-expansie
bevat "de adressen komen uit het oordeel" 'urls: ${{ steps.oordeel.outputs.urls }}' "$uitvraag_job"
# shellcheck disable=SC2016 # idem
bevat "de environment-URL komt uit het oordeel" 'url: ${{ steps.oordeel.outputs.url }}' "$uitvraag_job"
bevat "de magazijnen-job wacht op de uitvraag" 'uitvraag-preview-uitrol.sh wacht' "$magazijnen_job"
bevat "alleen als die nog niet gezond was" "needs.deploy-preview-uitvraag.outputs.gezond == 'false'" "$magazijnen_job"

echo "ASSERTIES=$asserties"

exit "$mislukt"
