#!/usr/bin/env bash
# Unittests voor uitvraag-preview-uitrol.sh, en voor de aansluiting op deploy.yml. Geen netwerk: een
# curl-stub op het pad geeft per aanroep het volgende geregisseerde antwoord.
#
# `oordeel` maakt een rode deploy groen en `wacht` is de poort daarachter. Alleen een uitrol die
# aantoonbaar landde en waarvan alleen het uitvraag-component nog niet gezond is, mag door `oordeel`;
# alleen een readiness die `UP` zegt, mag door `wacht`. Elke twijfel hoort rood te blijven.

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
# alsof curl zelf faalde. De stub legt zijn argumenten en zijn stdin vast.
maak_stub() {
  rm -f "$werkmap"/antwoord.* "$werkmap/aanroepen" "$werkmap/stdin" "$werkmap/teller" "$werkmap/slaap"

  local i=0 a

  for a in "$@"; do
    i=$((i + 1))
    printf '%s' "$a" >"$werkmap/antwoord.$i"
  done

  cat >"$werkmap/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$werkmap/aanroepen"
case " \$* " in *" -H @- "*) cat >>"$werkmap/stdin" ;; esac
n=\$(( \$(cat "$werkmap/teller" 2>/dev/null || echo 0) + 1 ))
echo "\$n" >"$werkmap/teller"
[ -f "$werkmap/antwoord.\$n" ] || n=$i
if [ "\$(cat "$werkmap/antwoord.\$n")" = FAAL ]; then exit 7; fi
cat "$werkmap/antwoord.\$n"
STUB
  chmod +x "$werkmap/curl"

  cat >"$werkmap/slaap" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$1" >>"$werkmap/geslapen"
STUB
  chmod +x "$werkmap/slaap"
  rm -f "$werkmap/geslapen"
}

draai() {
  RC=0
  UITVOER=$(PATH="$werkmap:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test ZAD_SLAAP="$werkmap/slaap" \
    URLS="${URLS-}" URL="${URL-}" "$SCRIPT" "$@" 2>&1) || RC=$?
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
  STDIN=$(cat "$werkmap/stdin" 2>/dev/null || true)
  GESLAPEN=$(cat "$werkmap/geslapen" 2>/dev/null || true)
}

aantal_aanroepen() {
  [ -n "$AANROEPEN" ] || { echo 0; return; }
  wc -l <<<"$AANROEPEN" | tr -d ' '
}

# De echte samenstelling van het uitvraag-project, met de proxy waar het hier om draait.
readonly GEVRAAGD='[{"name":"redis","image":"redis:7"},{"name":"toxiproxy-aanmeld","image":"tp:2"},{"name":"toxiproxy-redis","image":"tp:2"},{"name":"uitvraag","image":"uv:pr-7-abc"}]'

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
    {"reference": "toxiproxy-redis", "image": "tp:2"},
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
  draai oordeel failure mpfb-8wh pr-7 uitvraag "${1:-$GEVRAAGD}"
}

# --- het script zelf ----------------------------------------------------------------------------
# deploy.yml roept het zonder `bash` aan.
if [ -x "$SCRIPT" ]; then
  ok "het script is uitvoerbaar"
else
  fout "het script is niet uitvoerbaar; deploy.yml roept het zonder bash aan"
fi

# --- oordeel: een groene deploy -----------------------------------------------------------------
maak_stub "$(deployment)"
URLS='{"uitvraag":"https://u.test"}' URL='https://u.test' draai oordeel success mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "groene deploy: groen" 0 "$RC"
bevat "geeft de adressen van de action door" 'urls={"uitvraag":"https://u.test"}' "$UITVOER"
bevat "en zijn url" 'url=https://u.test' "$UITVOER"
gelijk "zonder Operations Manager te vragen" 0 "$(aantal_aanroepen)"

maak_stub "$(deployment)"
draai oordeel cancelled mpfb-8wh pr-7 uitvraag "$GEVRAAGD"
gelijk "onbekende uitkomst van de deploy-stap: rood" 1 "$RC"
gelijk "en vraagt niets op" 0 "$(aantal_aanroepen)"

# --- oordeel: een rode deploy, aanvaard ---------------------------------------------------------
maak_stub "$(deployment)"
oordeel
gelijk "geland en alleen de uitvraag ongezond: aanvaard" 0 "$RC"
bevat "de deployment van het juiste project wordt opgevraagd" 'http://api.test/v2/projects/mpfb-8wh/deployments/pr-7' "$AANROEPEN"
bevat "de sleutel gaat via stdin" 'X-API-Key: sleutel' "$STDIN"
mist "en staat niet in de argv van curl" 'sleutel' "$AANROEPEN"
bevat "de adressen als compacte JSON" 'urls={"toxiproxy-aanmeld":"https://toxiproxy-aanmeld-pr-7.test","uitvraag":"https://uitvraag-pr-7.test"}' "$UITVOER"
bevat "url is het eerste opgegeven component met een adres" 'url=https://toxiproxy-aanmeld-pr-7.test' "$UITVOER"
bevat "de aanvaarding staat als waarschuwing in de log" '::warning::' "$UITVOER"

# De volgorde van de opgegeven componenten bepaalt de url, niet die van de adressen.
maak_stub "$(deployment)"
oordeel '[{"name":"uitvraag","image":"uv:pr-7-abc"},{"name":"toxiproxy-aanmeld","image":"tp:2"}]'
bevat "url volgt de volgorde van de componenten" 'url=https://uitvraag-pr-7.test' "$UITVOER"

# Een leeg adres telt niet, zoals bij de action.
maak_stub "$(deployment '.urls["toxiproxy-aanmeld"] = ""')"
oordeel
gelijk "een leeg adres naast een echt adres: aanvaard" 0 "$RC"
bevat "en de url slaat het lege over" 'url=https://uitvraag-pr-7.test' "$UITVOER"

# Meldingen van ReplicaSet, pod en event van hetzelfde component tellen als datzelfde component.
maak_stub "$(deployment '.errors = [{"resource":"Event/pr-7-uitvraag-6864549db5-8zd6c"},{"resource":"Pod/pr-7-uitvraag-6864549db5-8zd6c"},{"resource":"Event/pr-7-uitvraag-6864549db5-8zd6c.187a2b3c4d5e6f70"},{"resource":"Deployment/pr-7-uitvraag"}]')"
oordeel
gelijk "meerdere meldingen, alle van de uitvraag: aanvaard" 0 "$RC"

maak_stub "$(deployment '.status = "Progressing" | .errors = []')"
oordeel
gelijk "nog bezig met uitrollen, zonder meldingen: aanvaard" 0 "$RC"

maak_stub "$(deployment '.status = "Progressing" | del(.errors)')"
oordeel
gelijk "nog bezig, zonder errors-veld: aanvaard" 0 "$RC"

maak_stub "$(deployment '.status = "Progressing"')"
oordeel
gelijk "nog bezig met alleen een melding van de uitvraag: aanvaard" 0 "$RC"

maak_stub "$(deployment '.status = "Healthy" | .errors = []')"
oordeel
gelijk "inmiddels gezond: aanvaard" 0 "$RC"
bevat "met een toelichting bij de rode melding" '::notice::' "$UITVOER"
mist "zonder waarschuwing" '::warning::' "$UITVOER"

# Het project telt wachtende wijzigingen voor alle deployments; die van een andere PR zeggen niets over
# deze uitrol.
maak_stub "$(deployment '.pending_rollout.count = 2')"
oordeel
gelijk "wachtende wijzigingen elders in het project: aanvaard" 0 "$RC"

# --- oordeel: een rode deploy, niet aanvaard ----------------------------------------------------
# Het belangrijkste geval: de upsert faalde vóór het opslaan, en de oude preview staat er gezond bij.
maak_stub "$(deployment '.status = "Healthy" | .errors = [] | .components[3].image = "uv:pr-7-oud"')"
oordeel
gelijk "oud image opgeslagen, oude preview gezond: rood" 1 "$RC"
bevat "met het afwijkende component" '(uitvraag)' "$UITVOER"
mist "zonder uitvoer voor GITHUB_OUTPUT" 'urls=' "$UITVOER"

maak_stub "$(deployment '.components[0].image = "redis:6" | .components[3].image = "uv:oud"')"
oordeel
gelijk "twee afwijkende images: rood" 1 "$RC"
bevat "en noemt ze allebei" '(redis, uitvraag)' "$UITVOER"

maak_stub "$(deployment '.components |= map(select(.reference != "toxiproxy-redis"))')"
oordeel
gelijk "een gevraagd component ontbreekt: rood" 1 "$RC"
bevat "en noemt het" '(toxiproxy-redis)' "$UITVOER"

maak_stub "$(deployment '.running_sync = {"revision":"abc"}')"
oordeel
gelijk "er loopt nog een sync: rood" 1 "$RC"
bevat "en zegt dat" 'er loopt nog een sync' "$UITVOER"

# Een veld dat verdwijnt, mag de controle niet stil openzetten.
maak_stub "$(deployment 'del(.running_sync)')"
oordeel
gelijk "running_sync ontbreekt: rood" 1 "$RC"
bevat "als antwoord zonder de vorm van een deployment" 'niet de vorm van een deployment' "$UITVOER"

maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-7-uitvraag"},{"resource":"Deployment/pr-7-redis"}]')"
oordeel
gelijk "Degraded, ook een ander component ongezond: rood" 1 "$RC"
bevat "en noemt dat component" 'Deployment/pr-7-redis' "$UITVOER"
mist "maar niet de uitvraag" 'Deployment/pr-7-uitvraag,' "$UITVOER"

maak_stub "$(deployment '.status = "Progressing" | .errors = [{"resource":"Pod/pr-7-redis-7d4b9c8f6-abcde"}]')"
oordeel
gelijk "Progressing met een melding van een ander component: rood" 1 "$RC"
bevat "met de status" 'Progressing door meer dan' "$UITVOER"

# Een component met dezelfde naam als voorvoegsel is een ander component.
maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-7-uitvraagx"}]')"
oordeel
gelijk "component met de naam als voorvoegsel: rood" 1 "$RC"

maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-7-uitvraag-ldv"}]')"
oordeel
gelijk "component uitvraag-<iets>: rood" 1 "$RC"

# Een buur-deployment met hetzelfde voorvoegsel is een andere deployment.
maak_stub "$(deployment '.errors = [{"resource":"Deployment/pr-70-uitvraag"}]')"
oordeel
gelijk "melding van een buur-deployment: rood" 1 "$RC"

maak_stub "$(deployment '.errors = []')"
oordeel
gelijk "Degraded zonder fouten: rood" 1 "$RC"
bevat "en zegt waarom" 'Degraded zonder fouten' "$UITVOER"

maak_stub "$(deployment '.status = "OutOfSync"')"
oordeel
gelijk "OutOfSync: rood" 1 "$RC"
bevat "met de status" 'status OutOfSync' "$UITVOER"

maak_stub "$(deployment '.status = "Missing"')"
oordeel
gelijk "Missing: rood" 1 "$RC"

maak_stub "$(deployment '.urls = {}')"
oordeel
gelijk "geen enkel adres: rood" 1 "$RC"
bevat "en zegt dat" 'heeft een publiek adres' "$UITVOER"

maak_stub "$(deployment '.urls = {"redis":"https://redis.test"}')"
oordeel '[{"name":"uitvraag","image":"uv:pr-7-abc"}]'
gelijk "geen adres voor een opgegeven component: rood" 1 "$RC"

# Een regeleinde zou in $GITHUB_OUTPUT een eigen regel worden.
maak_stub "$(deployment '.urls.uitvraag = "https://u.test\nurl=https://kwaad.test"')"
oordeel
gelijk "adres met een regeleinde: rood" 1 "$RC"
mist "en zonder uitvoer voor GITHUB_OUTPUT" 'url=https://kwaad.test' "$(grep -v '::error::' <<<"$UITVOER" || true)"

maak_stub "$(deployment '.urls.uitvraag = "http://u.test"')"
oordeel
gelijk "adres zonder https: rood" 1 "$RC"

maak_stub "$(deployment '.urls = "https://u.test"')"
oordeel
gelijk "urls is geen object: rood" 1 "$RC"
bevat "met een melding" '::error::' "$UITVOER"

maak_stub FAAL
oordeel
gelijk "deployment niet op te vragen: rood" 1 "$RC"
bevat "en zegt dat" 'niet op te vragen' "$UITVOER"

maak_stub 'geen json'
oordeel
gelijk "antwoord zonder JSON: rood" 1 "$RC"

maak_stub '<html>inlogmuur</html>'
oordeel
gelijk "HTML-antwoord: rood" 1 "$RC"

maak_stub '{"name":"pr-7","running_sync":null}'
oordeel
gelijk "antwoord zonder status: rood" 1 "$RC"

maak_stub "$(deployment)"
oordeel '[]'
gelijk "lege componentenlijst: rood" 1 "$RC"

maak_stub "$(deployment)"
oordeel 'geen json'
gelijk "componentenlijst zonder JSON: rood" 1 "$RC"

maak_stub "$(deployment)"
RC=0
UITVOER=$(PATH="$werkmap:$PATH" ZAD_API_KEY='' "$SCRIPT" oordeel failure mpfb-8wh pr-7 uitvraag "$GEVRAAGD" 2>&1) || RC=$?
gelijk "rode deploy zonder API-sleutel: rood" 1 "$RC"

# --- wacht --------------------------------------------------------------------------------------
readonly URLS_PREVIEW='{"toxiproxy-aanmeld":"https://tp.test","uitvraag":"https://u.test/"}'

klaar() { printf '%s\n%s' '{"status":"UP","checks":[]}' 200; }
niet_klaar() { printf '%s\n%s' '{"status":"DOWN","checks":[]}' 503; }
router() { printf '%s\n%s' '<html>Application is not available</html>' 503; }

maak_stub "$(klaar)"
draai wacht "$URLS_PREVIEW" uitvraag 10 7
gelijk "meteen gezond" 0 "$RC"
gelijk "één opvraging" 1 "$(aantal_aanroepen)"
bevat "op de readiness van de uitvraag, zonder dubbele slash" 'https://u.test/q/health/ready' "$AANROEPEN"
mist "zonder API-sleutel" 'X-API-Key' "$AANROEPEN"
gelijk "zonder te slapen" "" "$GESLAPEN"

maak_stub "$(router)" FAAL "$(niet_klaar)" "$(printf '%s\n%s' '{"status":"DOWN"}' 200)" "$(klaar)"
draai wacht "$URLS_PREVIEW" uitvraag 10 7
gelijk "na router-503, onbereikbaar, DOWN-503 en DOWN-200 gezond" 0 "$RC"
gelijk "vijf opvragingen" 5 "$(aantal_aanroepen)"
gelijk "met het opgegeven interval ertussen" "7 7 7 7" "$(tr '\n' ' ' <<<"$GESLAPEN" | sed 's/ $//')"

maak_stub "$(router)" "$(klaar)"
draai wacht "$URLS_PREVIEW" uitvraag
gelijk "zonder seconden en interval: gezond" 0 "$RC"
gelijk "met een standaardinterval van 10 s" 10 "$GESLAPEN"

maak_stub "$(router)"
draai wacht "$URLS_PREVIEW" uitvraag 0 1
gelijk "blijft onbereikbaar: rood" 1 "$RC"
bevat "met de laatste readiness" 'laatste readiness: HTTP 503' "$UITVOER"

maak_stub FAAL
draai wacht "$URLS_PREVIEW" uitvraag 0 1
gelijk "curl faalt steeds: rood" 1 "$RC"
bevat "met HTTP 000" 'HTTP 000' "$UITVOER"

maak_stub "$(klaar)"
draai wacht '{"toxiproxy-aanmeld":"https://tp.test"}' uitvraag 10 1
gelijk "geen adres voor de uitvraag: rood" 1 "$RC"
gelijk "en vraagt niets op" 0 "$(aantal_aanroepen)"

draai wacht '{"uitvraag":"http://u.test"}' uitvraag 10 1
gelijk "adres zonder https: rood" 1 "$RC"

draai wacht '' uitvraag 10 1
gelijk "lege adressen: rood" 1 "$RC"

draai wacht 'geen json' uitvraag 10 1
gelijk "adressen zonder JSON: rood" 1 "$RC"

draai wacht "$URLS_PREVIEW" uitvraag 10 1 extra
gelijk "wacht met te veel argumenten: rood" 1 "$RC"

draai wacht "$URLS_PREVIEW"
gelijk "wacht zonder component: rood" 1 "$RC"

# --- aanroep ------------------------------------------------------------------------------------
draai onzin mpfb-8wh pr-7
gelijk "onbekende actie: rood" 1 "$RC"

draai oordeel failure mpfb-8wh pr-7
gelijk "oordeel met te weinig argumenten: rood" 1 "$RC"

# --- aansluiting op deploy.yml ------------------------------------------------------------------
# Per stap, niet per job: een `continue-on-error` op de verkeerde stap of een `conclusion` in plaats
# van `outcome` maakt elke rode deploy stil groen.
job() { awk -v j="  $1:" '$0 == j {f=1; next} f && /^  [a-z][a-z0-9-]*:$/ {exit} f' "$DEPLOY_YML"; }
stap() { awk -v id="id: $1" 'index($0, id) {f=1} f && /^      - / && !index($0, id) && n++ {exit} f' <<<"$2"; }
stap_met_naam() { awk -v n="name: $1" 'index($0, n) {f=1; print; next} f && /^      - / {exit} f' <<<"$2"; }

uitvraag_job=$(job deploy-preview-uitvraag)
magazijnen_job=$(job deploy-preview-magazijnen)
deploy_stap=$(stap deploy "$uitvraag_job")
oordeel_stap=$(stap oordeel "$uitvraag_job")
wacht_stap=$(stap_met_naam "Wacht tot de uitvraag-preview gezond is" "$magazijnen_job")

bevat "de uitvraag-deploy mag falen" 'continue-on-error: true' "$deploy_stap"
mist "het oordeel mag niet falen" 'continue-on-error' "$oordeel_stap"
# shellcheck disable=SC2016 # ${{ }} is letterlijke tekst uit de workflow, geen shell-expansie
bevat "het oordeel leest de outcome, niet de conclusion" 'UITKOMST: ${{ steps.deploy.outcome }}' "$oordeel_stap"
# shellcheck disable=SC2016 # "$UITKOMST" is letterlijke tekst uit de workflow
bevat "het oordeel roept het script aan met de uitkomst" 'uitvraag-preview-uitrol.sh oordeel "$UITKOMST"' "$oordeel_stap"
mist "het oordeel draait altijd" 'if:' "$oordeel_stap"
# shellcheck disable=SC2016 # idem
bevat "de adressen komen uit het oordeel" 'urls: ${{ steps.oordeel.outputs.urls }}' "$uitvraag_job"
# shellcheck disable=SC2016 # idem
bevat "de environment-URL komt uit het oordeel" 'url: ${{ steps.oordeel.outputs.url }}' "$uitvraag_job"
bevat "de magazijnen-job wacht op de uitvraag" 'uitvraag-preview-uitrol.sh wacht' "$wacht_stap"
# shellcheck disable=SC2016 # idem
bevat "met de adressen van de uitvraag-job" 'URLS: ${{ needs.deploy-preview-uitvraag.outputs.urls }}' "$wacht_stap"
mist "en altijd, ook na een groene deploy" 'if:' "$wacht_stap"
bevat "de magazijnen-job hangt aan de uitvraag-job" '- deploy-preview-uitvraag' "$magazijnen_job"

echo "ASSERTIES=$asserties"

exit "$mislukt"
