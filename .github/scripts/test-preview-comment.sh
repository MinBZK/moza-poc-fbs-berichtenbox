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

niet_leeg() {
  local wat=$1 gemeten=$2

  if [ -n "$gemeten" ]; then
    ok "$wat"
  else
    fout "$wat — leeg, dus deze controle meet niets"
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

# Een jq-stub die niets doet en met `$rc` eindigt: voor het onderscheid tussen "de URL's deugen
# niet" (jq meldt 5) en "jq zelf kwam er niet doorheen".
maak_jq_stub() {
  local map=$1 rc=$2

  printf '#!/usr/bin/env bash\nexit %s\n' "$rc" >"$map/jq"
  chmod +x "$map/jq"
}

# Draait het script met de stub op het pad in een eigen map, zodat een test niet op de aanroepen
# van een vorige leunt. Zet $MAP, $RC, $UITVOER, $AANROEPEN en $BODY.
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

# De volgorde bínnen een sectie is die van de JSON-map: de componenten staan in de deploy-invoer in
# een gekozen volgorde, en die hoort de comment niet te husselen.
regels_van_sectie() {
  printf '%s\n' "$2" | awk -v kop="### $1" '
    $0 == kop { in_sectie = 1; next }
    in_sectie && /^### / { in_sectie = 0 }
    in_sectie && /^- / { print }
  '
}

gelijk "de componenten staan in de volgorde van de map" \
  '- **democonsole:** https://democonsole-pr-9.example
- **proeftuin:** https://proeftuin-pr-9.example' \
  "$(regels_van_sectie Demo "$BODY")"

# De structuur-assertie die de hele klasse "kop zonder inhoud" dekt: hoe de render later ook wordt
# herschreven, een sectie zonder URL-regel eronder is altijd fout.
kop_zonder_regels() {
  printf '%s\n' "$1" | awk '
    /^### / { kop = 1; next }
    kop && /^- \*\*/ { kop = 0; next }
    kop && NF { print "kop zonder URL-regel: " $0; kop = 1 }
  '
}

gelijk "elke sectiekop wordt gevolgd door minstens één URL-regel" "" "$(kop_zonder_regels "$BODY")"

# --- de cardinaliteiten van een sectie ------------------------------------------------------------

draai "$GEEN" 0 0 9 "Demo=$DEMO_URLS"

gelijk "één sectie volstaat" 0 "$RC"
bevat_niet "dan staat de andere sectie er niet in" '### Berichtenuitvraag' "$BODY"

draai "$GEEN" 0 0 9 'Demo={"proeftuin":"https://proeftuin-pr-9.example"}'

gelijk "een sectie met één component" 0 "$RC"
bevat "die ene component staat erin" '- **proeftuin:** https://proeftuin-pr-9.example' "$BODY"

# Een lege map betekent dat de deploy-action de URL's niet opleverde. Stil weglaten zou lezen als
# "dat project hoort niet bij deze preview".
draai "$GEEN" 0 0 9 'Demo={}'

gelijk "een lege URL-map faalt" 1 "$RC"
bevat "en zegt bij welke sectie" "voor 'Demo'" "$UITVOER"
gelijk "en er wordt niets geplaatst" "" "$AANROEPEN"

draai "$GEEN" 0 0 9 'Demo=geen-json'

gelijk "een sectie die geen JSON is, faalt" 1 "$RC"

draai "$GEEN" 0 0 9 'Demo=["proeftuin"]'

gelijk "een JSON-lijst in plaats van een map faalt" 1 "$RC"

# Het realistische productiegeval: de job-output van de andere deploy is leeg omdat het
# `outputs`-blok wegviel of de action geen URL's opleverde.
draai "$GEEN" 0 0 9 'Berichtenuitvraag=' "Demo=$DEMO_URLS"

gelijk "een lege sectiewaarde faalt" 1 "$RC"
bevat "en wijst de lege sectie aan" "voor 'Berichtenuitvraag'" "$UITVOER"
gelijk "en er gaat geen aanroep uit" "" "$AANROEPEN"

# --- tekens met eigen betekenis --------------------------------------------------------------------

draai "$GEEN" 0 0 9 'Demo={"a%sb":"https://x.example/p%d?q=1&r=2`x`"}'

gelijk "een component-naam of URL met printf- of shell-tekens faalt niet" 0 "$RC"
bevat "en komt ongeschonden in de body" \
  '- **a%sb:** https://x.example/p%d?q=1&r=2`x`' "$BODY"

# --- de argumenten -------------------------------------------------------------------------------

draai "$GEEN" 0 0 9

gelijk "zonder secties faalt het" 1 "$RC"
bevat "en zegt dat een comment zonder URL's niets zegt" 'zegt niets' "$UITVOER"

draai "$GEEN" 0 0 9 'Demo'

gelijk "een sectie zonder '=' faalt" 1 "$RC"

draai "$GEEN" 0 0 9 "=$DEMO_URLS"

gelijk "een lege sectienaam faalt" 1 "$RC"

# Een leeg of niet-numeriek nummer moet afbreken vóór de eerste aanroep: anders komt het pas
# halverwege de stap als 404 uit `gh`, met een melding die de oorzaak niet noemt.
draai "$GEEN" 0 0 "" "Demo=$DEMO_URLS"

gelijk "een leeg PR-nummer faalt" 1 "$RC"
gelijk "en er gaat geen enkele aanroep uit" "" "$AANROEPEN"

draai "$GEEN" 0 0 "pr-9" "Demo=$DEMO_URLS"

gelijk "een PR-nummer dat geen getal is, faalt" 1 "$RC"
gelijk "ook dan gaat er geen aanroep uit" "" "$AANROEPEN"

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

# De lijst-aanroep pagineert en vraagt een volle pagina: zonder allebei valt de comment op een
# drukke PR buiten beeld en komt er bij elke push een tweede bij.
bevat "de lijst-aanroep pagineert" '--paginate' "$AANROEPEN"
bevat "en vraagt honderd comments per pagina" 'per_page=100' "$AANROEPEN"

readonly EEN_BESTAANDE='[{"id":11,"body":"## 🚀 Preview Deployment\n\noud"}]'

draai "$EEN_BESTAANDE" 0 0 9 "Demo=$DEMO_URLS"

bevat "een bestaande comment wordt bijgewerkt" \
  'repos/MinBZK/moza-poc-fbs-berichtenbox/issues/comments/11 -X PATCH' "$AANROEPEN"
bevat_niet "en er komt er geen tweede bij" 'issues/9/comments -X POST' "$AANROEPEN"

# Een comment van iemand anders draagt de header niet en mag niet overschreven worden.
draai '[{"id":7,"body":"Ziet er goed uit"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "een vreemde comment wordt met rust gelaten" 'issues/9/comments -X POST' "$AANROEPEN"
bevat_niet "en niet bijgewerkt" 'comments/7 -X PATCH' "$AANROEPEN"

# `startswith` en niet `contains`: een comment die de header midden in de tekst aanhaalt is van een
# collega, en die overschrijven zou zijn woorden wissen.
draai '[{"id":8,"body":"Kijk hier eens: ## 🚀 Preview Deployment staat er raar bij"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "een header midden in een comment telt niet als treffer" 'issues/9/comments -X POST' "$AANROEPEN"
bevat_niet "en die comment blijft ongemoeid" 'comments/8 -X PATCH' "$AANROEPEN"

# `gh api --paginate` plakt de pagina's als losse arrays achter elkaar; de comment kan op de tweede
# staan. Zonder paginering zou deze stap er bij elke push een nieuwe plaatsen.
draai '[{"id":7,"body":"Ziet er goed uit"}][{"id":12,"body":"## 🚀 Preview Deployment\n\noud"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "een comment op de tweede pagina wordt gevonden" 'comments/12 -X PATCH' "$AANROEPEN"

draai '[{"id":11,"body":"## 🚀 Preview Deployment\n\noud"},{"id":14,"body":"## 🚀 Preview Deployment — demo"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "bij meerdere treffers wordt de eerste bijgewerkt" 'comments/11 -X PATCH' "$AANROEPEN"
bevat "en de rest wordt gemeld" '::warning::' "$UITVOER"

# Omgekeerde fixture, zodat vastligt dát het de eerste uit het antwoord is en niet het laagste id:
# de API levert oplopend op aanmaaktijd, en dáárom is de eerste de oudste.
draai '[{"id":14,"body":"## 🚀 Preview Deployment\n\noud"},{"id":11,"body":"## 🚀 Preview Deployment — demo"}]' 0 0 9 "Demo=$DEMO_URLS"

bevat "de keuze volgt de volgorde van de API, niet de hoogte van het id" 'comments/14 -X PATCH' "$AANROEPEN"

# --- gh en jq die weigeren ---------------------------------------------------------------------------

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

# jq die omvalt is iets anders dan URL's die niet deugen. Zonder dat onderscheid gaat de lezer de
# deploy-uitvoer napluizen terwijl daar niets mis is — en zonder de exitcode-controle zou een halve
# sectie (kop zonder regels) geplaatst worden met exit 0.
MAP=$(mktemp -d "$WERKMAP/geval.XXXXXX")
maak_stub "$MAP" "$GEEN" 0 0
maak_jq_stub "$MAP" 99
RC=0
UITVOER=$(PATH="$MAP:$PATH" GITHUB_REPOSITORY=MinBZK/moza-poc-fbs-berichtenbox \
  bash "$SCRIPT" 9 "Demo=$DEMO_URLS" 2>&1) || RC=$?
AANROEPEN=$(cat "$MAP/aanroepen" 2>/dev/null || true)

gelijk "een jq die omvalt faalt de stap" 1 "$RC"
bevat "en wijst jq aan in plaats van de URL's" 'jq kon de' "$UITVOER"
gelijk "en er wordt niets geplaatst" "" "$AANROEPEN"

# Zonder jq op het pad hoort de melding dat te zeggen, en niet de URL's te verdenken. Het pad draagt
# alleen de stub-map, dus bash wordt hier op zijn volle pad aangeroepen — anders vindt de shell
# zichzelf niet en meet deze test iets anders dan een ontbrekende jq.
MAP=$(mktemp -d "$WERKMAP/geval.XXXXXX")
maak_stub "$MAP" "$GEEN" 0 0
RC=0
UITVOER=$(PATH="$MAP" GITHUB_REPOSITORY=MinBZK/moza-poc-fbs-berichtenbox \
  "$(command -v bash)" "$SCRIPT" 9 "Demo=$DEMO_URLS" 2>&1) || RC=$?
AANROEPEN=$(cat "$MAP/aanroepen" 2>/dev/null || true)

gelijk "een ontbrekende jq faalt de stap" 1 "$RC"
bevat "en meldt dat jq ontbreekt" 'jq ontbreekt' "$UITVOER"
gelijk "en er wordt niets geplaatst" "" "$AANROEPEN"

# --- de header ---------------------------------------------------------------------------------

met_header() {
  local header=$1 bestaand=$2

  MAP=$(mktemp -d "$WERKMAP/geval.XXXXXX")
  maak_stub "$MAP" "$bestaand" 0 0

  RC=0
  UITVOER=$(PATH="$MAP:$PATH" GITHUB_REPOSITORY=MinBZK/moza-poc-fbs-berichtenbox COMMENT_HEADER="$header" \
    bash "$SCRIPT" 9 "Demo=$DEMO_URLS" 2>&1) || RC=$?

  AANROEPEN=$(cat "$MAP/aanroepen" 2>/dev/null || true)
  BODY=$(cat "$MAP/body" 2>/dev/null || true)
}

met_header '## Eigen kop' '[{"id":21,"body":"## Eigen kop\n\noud"}]'

gelijk "een eigen header wordt gebruikt" '## Eigen kop' "$(printf '%s' "$BODY" | head -1)"
bevat "en de comment met die header wordt bijgewerkt" 'comments/21 -X PATCH' "$AANROEPEN"

# De header gaat via `--arg` het jq-programma in. Zou hij geïnterpoleerd worden, dan breekt een
# quote of backslash het programma — of erger, sluit iemand het filter af en schrijft zijn eigen.
met_header '## "a" \b kop' '[{"id":22,"body":"## \"a\" \\b kop\n\noud"}]'

gelijk "een header met een quote en een backslash faalt niet" 0 "$RC"
bevat "en vindt de bestaande comment" 'comments/22 -X PATCH' "$AANROEPEN"

met_header '## kop") | .id) | halt_error(1) # ' '[{"id":23,"body":"## kop\") | .id) | halt_error(1) # \n\noud"}]'

gelijk "een header die het jq-filter probeert af te sluiten faalt niet" 0 "$RC"
bevat "en wordt als gewone tekst behandeld" 'comments/23 -X PATCH' "$AANROEPEN"

# Een leeg gezette header mag niet stil terugvallen op de default: dan zoekt het opruimen straks op
# een andere tekst dan waaronder geplaatst is.
met_header '' "$GEEN"

gelijk "een lege header faalt" 1 "$RC"
bevat "en zegt waarom" 'niet te herkennen' "$UITVOER"

# --- het script als bibliotheek --------------------------------------------------------------------

# De `BASH_SOURCE`-guard onderaan het script: sourcen mag geen comment plaatsen. Zonder deze
# assertie kan die guard sneuvelen zonder dat iets het merkt.
MAP=$(mktemp -d "$WERKMAP/geval.XXXXXX")
maak_stub "$MAP" "$GEEN" 0 0
RC=0
UITVOER=$(PATH="$MAP:$PATH" GITHUB_REPOSITORY=MinBZK/moza-poc-fbs-berichtenbox \
  bash -c "source '$SCRIPT'" 2>&1) || RC=$?
AANROEPEN=$(cat "$MAP/aanroepen" 2>/dev/null || true)

gelijk "sourcen voert main niet uit" 0 "$RC"
gelijk "en plaatst niets" "" "$AANROEPEN"

# --- de aansluiting op de workflows ---------------------------------------------------------------

DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
CLEANUP_YML="$REPO_ROOT/.github/workflows/cleanup-preview.yml"

# De drie plekken die dezelfde tekst moeten dragen: het script plaatst de comment onder deze
# default, cleanup zoekt hem op `startswith` van deze tekst en deploy.yml geeft hem mee. Drift laat
# de comment achter op een gesloten PR.
header_uit() { sed -n "s/^ *COMMENT_HEADER: *['\"]\(.*\)['\"] *$/\1/p" "$1" | head -1; }

SCRIPT_HEADER=$(sed -n "s/^readonly STANDAARD_HEADER='\(.*\)'$/\1/p" "$SCRIPT")
DEPLOY_HEADER=$(header_uit "$DEPLOY_YML")
CLEANUP_HEADER=$(header_uit "$CLEANUP_YML")

# Zonder deze drie zou een gewijzigde schrijfwijze alle extracties leeg maken en zouden de
# vergelijkingen hieronder op niets slagen.
niet_leeg "de header is uit het script te lezen" "$SCRIPT_HEADER"
niet_leeg "de header is uit deploy.yml te lezen" "$DEPLOY_HEADER"
niet_leeg "de header is uit cleanup-preview.yml te lezen" "$CLEANUP_HEADER"

gelijk "deploy.yml draagt dezelfde header als het script" "$SCRIPT_HEADER" "$DEPLOY_HEADER"
gelijk "cleanup-preview.yml draagt dezelfde header als deploy.yml" "$DEPLOY_HEADER" "$CLEANUP_HEADER"

# De aanroep zelf, en niet een comment-regel die het script noemt: zonder het wegfilteren van
# commentaar zou het verwijderen van de `run:`-regel deze controle groen laten.
AANROEP_REGELS=$(grep -v '^[[:space:]]*#' "$DEPLOY_YML" | grep -A3 'preview-comment\.sh')

niet_leeg "deploy.yml roept preview-comment.sh aan" "$AANROEP_REGELS"

# De unittests bewijzen dat het script twee secties rendert als het er twee krijgt; deze twee
# bewijzen dat de workflow ze ook meegeeft. Valt één argument weg, dan staat er nog steeds een
# comment — met een halve preview erin, en groene CI.
bevat "de aanroep geeft de demo mee" 'Demo=$URLS_DEMO' "$AANROEP_REGELS"
bevat "de aanroep geeft de uitvraag mee" 'Berichtenuitvraag=$URLS_UITVRAAG' "$AANROEP_REGELS"

# De demo is de ingang voor wie de PR opent; dat staat zo in de toelichting bij de stap en hoort
# dus ook de eerste sectie te zijn.
gelijk "de demo staat vooraan in de aanroep" \
  'Demo=$URLS_DEMO' \
  "$(printf '%s\n' "$AANROEP_REGELS" | sed -n 's/.*"\([A-Za-z]*=\$URLS_[A-Z]*\)".*/\1/p' | head -1)"

# De env-namen in de aanroep moeten in het `env:`-blok van de stap staan; anders komen ze leeg
# binnen en faalt de stap pas op de sectie-controle.
for env_naam in URLS_DEMO URLS_UITVRAAG PR_NUMMER; do
  if grep -q "^ *$env_naam: " "$DEPLOY_YML"; then
    ok "$env_naam is in deploy.yml gedefinieerd"
  else
    fout "$env_naam is in deploy.yml gedefinieerd"
  fi
done

# Zet iemand de comment van de action weer aan, dan schrijft die de body opnieuw met alleen de
# URL's van haar eigen project. Het patroon dekt de drie schrijfwijzen van `true` in YAML.
if grep -Eq "comment-on-pr:[[:space:]]*['\"]?true" "$DEPLOY_YML"; then
  fout "geen enkele deploy-stap laat de action zelf een comment plaatsen"
else
  ok "geen enkele deploy-stap laat de action zelf een comment plaatsen"
fi

echo "ASSERTIES=$asserties"

if [ "$mislukt" -ne 0 ]; then
  exit 1
fi

echo "Alle tests geslaagd."
