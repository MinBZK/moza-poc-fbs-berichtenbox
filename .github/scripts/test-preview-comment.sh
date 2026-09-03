#!/usr/bin/env bash
# Unittests voor preview-comment.sh. Geen netwerk: elke test zet een gh-stub op het pad die de
# aanroepen en de body wegschrijft en een geregisseerd antwoord teruggeeft.
#
# Wat hier bewaakt wordt is vooral stil misplaatsen. De comment is voor veel lezers het enige spoor
# naar een preview: staat er een project niet in, dan bestaat dat deel van de omgeving voor hen
# niet. En een verkeerd PR-nummer of een gemiste bestaande comment valt niemand op — er staat dan
# gewoon een comment, alleen op de verkeerde plek of twee keer.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/preview-comment.sh"

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

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) fout "$wat — '$naald' staat wél in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Een gh-stub die elke aanroep wegschrijft (argumenten in `aanroepen`, de body in `body`) en op de
# lijst-aanroep `$bestaand` teruggeeft — de JSON zoals `gh api --paginate` hem levert. Met
# `schrijf_rc` doet de stub alsof de POST/PATCH werd geweigerd.
maak_stub() {
  local map=$1 bestaand=$2 schrijf_rc=${3:-0} lijst_rc=${4:-0}

  cat >"$map/gh" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$map/aanroepen"

for arg in "\$@"; do
  case "\$arg" in
    body=*) printf '%s' "\${arg#body=}" >"$map/body" ;;
  esac
done

if printf '%s' "\$*" | grep -q -- '--paginate'; then
  printf '%s' '$bestaand'
  exit $lijst_rc
fi

exit $schrijf_rc
STUB
  chmod +x "$map/gh"
}

# Draait het script met de stub op het pad in een eigen map, zodat een test niet op de aanroepen
# van een vorige leunt. Zet $RC, $UITVOER, $AANROEPEN en $BODY.
draai() {
  local bestaand=$1 schrijf_rc=$2 lijst_rc=$3
  shift 3

  MAP=$(mktemp -d "$WERKMAP/geval.XXXXXX")
  maak_stub "$MAP" "$bestaand" "$schrijf_rc" "$lijst_rc"

  RC=0
  UITVOER=$(PATH="$MAP:$PATH" GITHUB_REPOSITORY="${REPO_OVERRIDE-MinBZK/moza-poc-fbs-berichtenbox}" \
    bash "$SCRIPT" "$@" 2>&1) || RC=$?

  AANROEPEN=$(cat "$MAP/aanroepen" 2>/dev/null || true)
  BODY=$(cat "$MAP/body" 2>/dev/null || true)
}

readonly GEEN='[]'
readonly UITVRAAG_URLS='{"uitvraag":"https://uitvraag-pr-9.example","redis":"https://redis-pr-9.example"}'
readonly DEMO_URLS='{"democonsole":"https://democonsole-pr-9.example","proeftuin":"https://proeftuin-pr-9.example"}'

# --- de body ------------------------------------------------------------------------------------

draai "$GEEN" 0 0 9 "Berichtenuitvraag=$UITVRAAG_URLS" "Demo=$DEMO_URLS"

gelijk "een geslaagde plaatsing eindigt met exit 0" 0 "$RC"

# De header staat vooraan, want cleanup-preview.yml zoekt de comment op `startswith`.
gelijk "de body begint met de header" \
  '## 🚀 Preview Deployment' \
  "$(printf '%s' "$BODY" | head -1)"

bevat "de sectie van de uitvraag staat erin" '### Berichtenuitvraag' "$BODY"

# Waar deze suite om begonnen is: zonder deze assertie mist de comment het deel van de preview waar
# een demo op draait, en is dat aan niets te zien.
bevat "de sectie van de demo staat erin" '### Demo' "$BODY"

bevat "de console van de demo staat er met URL in" \
  '- **democonsole:** https://democonsole-pr-9.example' "$BODY"

bevat "de berichtenbox van de demo staat er met URL in" \
  '- **proeftuin:** https://proeftuin-pr-9.example' "$BODY"

bevat "een component van de uitvraag staat er met URL in" \
  '- **uitvraag:** https://uitvraag-pr-9.example' "$BODY"

bevat "de body noemt het opruimen bij het sluiten van de PR" \
  'opgeruimd zodra de PR sluit' "$BODY"

# De volgorde van de secties is die van de argumenten; anders zou de comment per run kunnen
# wisselen van indeling.
if [ "$(printf '%s' "$BODY" | grep -n '^### ' | head -1 | cut -d: -f2-)" = '### Berichtenuitvraag' ]; then
  ok "de eerste sectie is die van het eerste argument"
else
  fout "de eerste sectie is die van het eerste argument — body: $BODY"
fi

# --- de cardinaliteiten van een sectie ------------------------------------------------------------

draai "$GEEN" 0 0 9 "Demo=$DEMO_URLS"

gelijk "één sectie volstaat" 0 "$RC"
bevat_niet "dan staat de andere sectie er niet in" '### Berichtenuitvraag' "$BODY"

draai "$GEEN" 0 0 9 'Demo={"proeftuin":"https://proeftuin-pr-9.example"}'

gelijk "een sectie met één component" 0 "$RC"
bevat "die ene component staat erin" '- **proeftuin:** https://proeftuin-pr-9.example' "$BODY"

# Een lege map betekent dat de deploy-action de URL's niet uit het OM-antwoord kon halen. Stil
# weglaten zou lezen als "dat project hoort niet bij deze preview".
draai "$GEEN" 0 0 9 'Demo={}'

gelijk "een lege URL-map faalt" 1 "$RC"
bevat "en zegt bij welke sectie" "voor 'Demo'" "$UITVOER"

draai "$GEEN" 0 0 9 'Demo=geen-json'

gelijk "een sectie die geen JSON is, faalt" 1 "$RC"

draai "$GEEN" 0 0 9 'Demo=["proeftuin"]'

gelijk "een JSON-lijst in plaats van een map faalt" 1 "$RC"

# --- de argumenten -------------------------------------------------------------------------------

draai "$GEEN" 0 0 9

gelijk "zonder secties faalt het" 1 "$RC"
bevat "en zegt dat een comment zonder URL's niets zegt" 'zegt niets' "$UITVOER"

draai "$GEEN" 0 0 9 'Demo'

gelijk "een sectie zonder '=' faalt" 1 "$RC"

draai "$GEEN" 0 0 9 "=$DEMO_URLS"

gelijk "een lege sectienaam faalt" 1 "$RC"

# Het lege PR-nummer is de gevaarlijke: `/issues//comments` levert bij GitHub de comments van de
# hele repo, en dan zou deze stap de comment van een willekeurige andere PR bijwerken.
draai "$GEEN" 0 0 "" "Demo=$DEMO_URLS"

gelijk "een leeg PR-nummer faalt" 1 "$RC"
bevat_niet "en er gaat geen enkele aanroep uit" 'issues' "$AANROEPEN"

draai "$GEEN" 0 0 "pr-9" "Demo=$DEMO_URLS"

gelijk "een PR-nummer dat geen getal is, faalt" 1 "$RC"

# Als losse toekenning en niet als prefix op de aanroep: een toekenning vóór een functienaam blijft
# in bash staan na afloop, en dan zouden alle volgende gevallen zonder repo draaien.
REPO_OVERRIDE=''
draai "$GEEN" 0 0 9 "Demo=$DEMO_URLS"
unset REPO_OVERRIDE

gelijk "zonder GITHUB_REPOSITORY faalt het" 1 "$RC"

# --- plaatsen versus bijwerken --------------------------------------------------------------------

draai "$GEEN" 0 0 9 "Demo=$DEMO_URLS"

bevat "zonder bestaande comment gaat er een POST naar de PR" \
  'repos/MinBZK/moza-poc-fbs-berichtenbox/issues/9/comments -X POST' "$AANROEPEN"
bevat "en de uitvoer meldt dat er geplaatst is" 'geplaatst op PR 9' "$UITVOER"

readonly EEN_BESTAANDE='[{"id":11,"body":"## 🚀 Preview Deployment\n\noud"}]'

draai "$EEN_BESTAANDE" 0 0 9 "Demo=$DEMO_URLS"

bevat "een bestaande comment wordt bijgewerkt" \
  'repos/MinBZK/moza-poc-fbs-berichtenbox/issues/comments/11 -X PATCH' "$AANROEPEN"
bevat_niet "en er komt er geen tweede bij" 'issues/9/comments -X POST' "$AANROEPEN"

# Een comment van iemand anders draagt de header niet en mag niet overschreven worden.
draai '[{"id":7,"body":"Ziet er goed uit"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "een vreemde comment wordt met rust gelaten" 'issues/9/comments -X POST' "$AANROEPEN"
bevat_niet "en niet bijgewerkt" 'comments/7 -X PATCH' "$AANROEPEN"

# `gh api --paginate` plakt de pagina's als losse arrays achter elkaar; de comment kan op de tweede
# staan. Zonder paginering zou deze stap er bij elke push een nieuwe plaatsen.
draai '[{"id":7,"body":"Ziet er goed uit"}][{"id":12,"body":"## 🚀 Preview Deployment\n\noud"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "een comment op de tweede pagina wordt gevonden" 'comments/12 -X PATCH' "$AANROEPEN"

draai '[{"id":11,"body":"## 🚀 Preview Deployment\n\noud"},{"id":14,"body":"## 🚀 Preview Deployment — demo"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "bij meerdere treffers wordt de oudste bijgewerkt" 'comments/11 -X PATCH' "$AANROEPEN"
bevat "en de rest wordt gemeld" '::warning::' "$UITVOER"

# --- gh die weigert --------------------------------------------------------------------------------

draai "$GEEN" 1 0 9 "Demo=$DEMO_URLS"

gelijk "een geweigerde POST faalt de stap" 1 "$RC"
bevat "en zegt dat er niets geplaatst is" 'niet geplaatst' "$UITVOER"

draai "$EEN_BESTAANDE" 1 0 9 "Demo=$DEMO_URLS"

gelijk "een geweigerde PATCH faalt de stap" 1 "$RC"
bevat "en zegt dat er niets bijgewerkt is" 'niet bijgewerkt' "$UITVOER"

# Een mislukte lijst-aanroep mag niet als "geen bestaande comment" lezen: dan komt er bij elke push
# een comment bij.
draai "$GEEN" 0 1 9 "Demo=$DEMO_URLS"

gelijk "een mislukte lijst-aanroep faalt de stap" 1 "$RC"
bevat_niet "en er wordt niets geplaatst" '-X POST' "$AANROEPEN"

# --- de header ---------------------------------------------------------------------------------

MAP=$(mktemp -d "$WERKMAP/geval.XXXXXX")
maak_stub "$MAP" '[{"id":21,"body":"## Eigen kop\n\noud"}]' 0 0
RC=0
UITVOER=$(PATH="$MAP:$PATH" GITHUB_REPOSITORY=MinBZK/moza-poc-fbs-berichtenbox COMMENT_HEADER='## Eigen kop' \
  bash "$SCRIPT" 9 "Demo=$DEMO_URLS" 2>&1) || RC=$?
AANROEPEN=$(cat "$MAP/aanroepen")
BODY=$(cat "$MAP/body")

gelijk "een eigen header wordt gebruikt" '## Eigen kop' "$(printf '%s' "$BODY" | head -1)"
bevat "en de comment met die header wordt bijgewerkt" 'comments/21 -X PATCH' "$AANROEPEN"

# --- de aansluiting op de workflows ---------------------------------------------------------------

DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
CLEANUP_YML="$REPO_ROOT/.github/workflows/cleanup-preview.yml"

# De drie plekken die dezelfde tekst moeten dragen: het script plaatst de comment, cleanup zoekt
# hem op `startswith` van deze tekst en deploy.yml geeft hem mee. Drift laat de comment achter op
# een gesloten PR.
header_uit() { sed -n "s/^ *COMMENT_HEADER: *['\"]\(.*\)['\"] *$/\1/p" "$1" | head -1; }

gelijk "deploy.yml draagt dezelfde header als het script" \
  "$(sed -n "s/^readonly STANDAARD_HEADER='\(.*\)'$/\1/p" "$SCRIPT")" \
  "$(header_uit "$DEPLOY_YML")"

gelijk "cleanup-preview.yml draagt dezelfde header als deploy.yml" \
  "$(header_uit "$DEPLOY_YML")" \
  "$(header_uit "$CLEANUP_YML")"

# Zonder deze twee is dit script dood gewicht: de deploy zou de comment weer door de action laten
# plaatsen, en die kent alleen de URL's van haar eigen project.
if grep -q 'preview-comment.sh' "$DEPLOY_YML"; then
  ok "deploy.yml roept preview-comment.sh aan"
else
  fout "deploy.yml roept preview-comment.sh aan"
fi

if grep -q "comment-on-pr: 'true'" "$DEPLOY_YML"; then
  fout "geen enkele deploy-stap laat de action zelf een comment plaatsen"
else
  ok "geen enkele deploy-stap laat de action zelf een comment plaatsen"
fi

echo "ASSERTIES=$asserties"

if [ "$mislukt" -ne 0 ]; then
  exit 1
fi

echo "Alle tests geslaagd."
