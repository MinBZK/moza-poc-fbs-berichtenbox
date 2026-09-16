#!/usr/bin/env bash
# Fixture-tests voor proeftuin-pin-pr.sh. Het script muteert gedeelde toestand — een branch, een PR
# en de pin in compose.yaml — en de dure faalwijze is niet "rood", maar "groen terwijl het de
# verkeerde kant op ging": een status die verkeerd valt, sluit de PR die de bump droeg, of zet een
# onbruikbare image-regel op main-koers.
#
# Daarom toetst elk geval naast de exitcode ook wélke gh- en git-aanroepen zijn gedaan, en waar het
# om de inhoud gaat de image-regel en de PR-body. Alleen de exitcode toetsen laat de statussen
# allemaal slagen zonder iets te onderscheiden.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/proeftuin-pin-pr.sh"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

IMAGE=ghcr.io/minbzk/moza-poc
OUD_DIGEST=sha256:bd4a32fe0a52616509a8dcee6f3cdcd198463f9cc4a2c0e0486a6d30a231f571
NIEUW_DIGEST=sha256:03b37173b801a479450006d32ae74509da67bdf98215a2622f66f549ccdc94e9
OUDE_REGEL="    image: $IMAGE:latest@$OUD_DIGEST"
NIEUWE_REGEL="    image: $IMAGE:latest@$NIEUW_DIGEST"

# De stubs leggen elke aanroep vast en bootsen alleen na wat het script echt uitleest: de PR-lijst
# (met de jq-filter die het script zelf meegeeft, zodat de fork-filter écht getoetst wordt), of
# compose.yaml gewijzigd is, en of de branch nog bestaat.
#
# Ze kunnen ook falen. Zonder die schakelaars overleeft elke `|| true` achter een gh-aanroep de
# suite, en juist die maakt een mislukte PR-actie stil.
mkdir -p "$WERKMAP/bin"

cat > "$WERKMAP/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf 'gh %s\n' "$*" >> "$AANROEPEN"

# De body gaat naar een eigen bestand: hij loopt over meerdere regels en zou het aanroepenlogboek
# onleesbaar maken, terwijl de inhoud wél getoetst moet worden.
vorige=""
for arg in "$@"; do
  [ "$vorige" = "--body" ] && printf '%s' "$arg" >> "$BODYS"
  vorige=$arg
done

if [ "${GH_FAALT:-}" = "${1:-} ${2:-}" ]; then
  echo "error connecting to api.github.com" >&2
  exit 1
fi

if [ "${1:-}" = "pr" ] && [ "${2:-}" = "list" ]; then
  filter=""
  vorige=""

  for arg in "$@"; do
    [ "$vorige" = "--jq" ] && filter=$arg
    vorige=$arg
  done

  printf '%s' "${PR_LIJST:-[]}" | jq -r "$filter"
fi

exit 0
STUB

cat > "$WERKMAP/bin/git" <<'STUB'
#!/usr/bin/env bash
printf 'git %s\n' "$*" >> "$AANROEPEN"

case "${1:-}" in
  # `git diff --quiet -- <bestand>`: 0 als er niets gewijzigd is. De momentopname is de staat van
  # vóór de aanroep, dus dit is een getrouwe simulatie en geen aanname.
  diff)      cmp -s "$MOMENTOPNAME" "$COMPOSE" ;;
  ls-remote) exit "${LS_REMOTE_CODE:-0}" ;;
  push)      [ "${GIT_PUSH_FAALT:-0}" = 0 ] ;;
  *)         true ;;
esac
STUB

chmod +x "$WERKMAP/bin/gh" "$WERKMAP/bin/git"
export PATH="$WERKMAP/bin:$PATH"

# $1 = de image-regel, rest = extra regels erboven (commentaar of een tweede image-regel).
schrijf_compose() {
  local image=$1
  shift

  {
    echo "services:"
    echo "  proeftuin:"
    [ "$#" -gt 0 ] && printf '%s\n' "$@"
    printf '%s\n' "$image"
    echo "    profiles: [demo]"
  } > "$COMPOSE"
}

# Zet een verse werkmap klaar voor één geval: eigen compose.yaml, momentopname, aanroepenlogboek en
# bodybestand. Het draaien zelf doet `uitvoeren`.
nieuw_geval() {
  local map="$WERKMAP/$1"

  mkdir -p "$map"
  export COMPOSE="$map/compose.yaml"
  export MOMENTOPNAME="$map/compose.yaml.voor"
  export AANROEPEN="$map/aanroepen"
  export BODYS="$map/bodys"
  : > "$AANROEPEN"
  : > "$BODYS"
}

vastleggen() { cp "$COMPOSE" "$MOMENTOPNAME"; }

# `set +e` omdat een deel van de gevallen juist een niet-nul exitcode verwacht en deze suite zelf
# onder `set -e` draait. BRANCH expliciet, zodat de aanroep-asserties niet meeschuiven als de default
# in het script wijzigt.
uitvoeren() {
  set +e
  UITVOER=$(BRANCH=chore/proeftuin-pin bash "$SCRIPT" 2>&1)
  CODE=$?
  set -e
}

bevat()      { grep -qF "$2" "$AANROEPEN" && ok "$1" || fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))"; }
bevat_niet() { grep -qF "$2" "$AANROEPEN" && fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))" || ok "$1"; }
body()       { grep -qF "$2" "$BODYS" && ok "$1" || fout "$1 (body: $(tr '\n' '|' < "$BODYS"))"; }
gelijk()     { [ "$2" = "$3" ] && ok "$1" || fout "$1 (verwacht '$3', kreeg '$2')"; }
niet_nul()   { [ "$2" -ne 0 ] && ok "$1" || fout "$1 (exitcode 0, uitvoer: $UITVOER)"; }
meldt()      { grep -qF "$2" <<<"$UITVOER" && ok "$1" || fout "$1 (uitvoer: $UITVOER)"; }
regel()      { grep -qxF "$2" "$COMPOSE" && ok "$1" || fout "$1 (bestand: $(tr '\n' '|' < "$COMPOSE"))"; }
regel_niet() { grep -qxF "$2" "$COMPOSE" && fout "$1 (bestand: $(tr '\n' '|' < "$COMPOSE"))" || ok "$1"; }

export GH_TOKEN=stub-token
export TAG=sha-7f1455f
export HUIDIG="$IMAGE:latest@$OUD_DIGEST"

EIGEN_PR='[{"number":42,"isCrossRepository":false}]'
FORK_PR='[{"number":99,"isCrossRepository":true}]'
FORK_EERST='[{"number":99,"isCrossRepository":true},{"number":42,"isCrossRepository":false}]'
EIGEN_EERST='[{"number":42,"isCrossRepository":false},{"number":99,"isCrossRepository":true}]'

# --- 1. Verouderd zonder open PR: nieuwe PR, branch gepusht ---
nieuw_geval zonder-pr
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "verouderde pin zonder open PR eindigt groen" "$CODE" 0
bevat "verouderde pin zonder open PR opent een PR" "gh pr create"
bevat "verouderde pin zonder open PR pusht de branch" "git push -f origin chore/proeftuin-pin"
body "de nieuwe PR noemt de tag waarnaar hij gaat" "$TAG"
body "de nieuwe PR noemt de referentie die er stond" "$HUIDIG"
regel "de image-regel draagt de nieuwe digest" "$NIEUWE_REGEL"
regel_niet "de oude image-regel is weg" "$OUDE_REGEL"

# --- 2. Verouderd met open PR: verversen, geen tweede PR ---
nieuw_geval met-pr
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST=$EIGEN_PR STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "verouderde pin met open PR eindigt groen" "$CODE" 0
bevat "verouderde pin met open PR werkt de body bij" "gh pr edit 42"
bevat_niet "verouderde pin met open PR opent geen tweede" "gh pr create"
bevat "verouderde pin met open PR pusht de branch" "git push -f origin chore/proeftuin-pin"
body "de bijgewerkte body noemt de tag van deze run" "$TAG"
regel "de bijgewerkte PR draagt de nieuwe digest" "$NIEUWE_REGEL"

# --- 3. Pin bij, open PR: opruimen ---
nieuw_geval bij-met-pr
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST=$EIGEN_PR STATUS=ok uitvoeren
gelijk "een bijgewerkte pin met open PR eindigt groen" "$CODE" 0
bevat "een bijgewerkte pin sluit de overbodige PR" "gh pr close 42"
bevat "een bijgewerkte pin ruimt de branch op" "git push origin --delete chore/proeftuin-pin"

# --- 4. Pin bij, geen open PR: niets doen ---
nieuw_geval bij-zonder-pr
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=ok uitvoeren
gelijk "een bijgewerkte pin zonder open PR eindigt groen" "$CODE" 0
bevat_niet "een bijgewerkte pin zonder open PR sluit niets" "gh pr close"
bevat_niet "een bijgewerkte pin zonder open PR opent niets" "gh pr create"
bevat_niet "een bijgewerkte pin zonder open PR pusht niets" "git push"

# --- 5. Branch al opgeruimd (ls-remote 2): sluiten blijft groen ---
nieuw_geval branch-weg
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST=$EIGEN_PR STATUS=ok LS_REMOTE_CODE=2 uitvoeren
gelijk "een al verwijderde branch maakt het opruimen niet rood" "$CODE" 0
bevat_niet "een al verwijderde branch wordt niet nog eens verwijderd" "git push origin --delete"
meldt "een al verwijderde branch wordt benoemd" "bestond al niet meer"

# --- 6. ls-remote faalt op iets anders dan 'niet gevonden' ---
nieuw_geval ls-remote-stuk
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST=$EIGEN_PR STATUS=ok LS_REMOTE_CODE=128 uitvoeren
niet_nul "een onbereikbare remote maakt het opruimen rood" "$CODE"
meldt "een onbereikbare remote noemt de oorzaak" "kon niet vaststellen"
bevat_niet "een onbereikbare remote verwijdert niets" "git push origin --delete"

# --- 7. Lege regel bij een verouderde pin ---
nieuw_geval lege-regel
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="" uitvoeren
niet_nul "een lege image-regel faalt hard" "$CODE"
meldt "een lege image-regel noemt de oorzaak" "geen bruikbare image-regel"
bevat_niet "een lege image-regel opent geen PR" "gh pr create"
regel "een lege image-regel laat compose.yaml ongemoeid" "$OUDE_REGEL"

# --- 8. Regel zonder digest ---
nieuw_geval regel-zonder-digest
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="    image: $IMAGE:sha-7f1455f" uitvoeren
niet_nul "een regel zonder digest faalt hard" "$CODE"
meldt "een regel zonder digest noemt de oorzaak" "geen bruikbare image-regel"
bevat_niet "een regel zonder digest opent geen PR" "gh pr create"
regel "een regel zonder digest laat compose.yaml ongemoeid" "$OUDE_REGEL"

# --- 9. Geen image-regel in compose.yaml ---
nieuw_geval geen-image-regel
schrijf_compose "    build: ."
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
niet_nul "een ontbrekende image-regel faalt hard" "$CODE"
meldt "een ontbrekende image-regel noemt het aantal" "heeft 0 image-regels"
bevat_niet "een ontbrekende image-regel opent geen PR" "gh pr create"

# --- 10. Twee image-regels op hetzelfde pad: niet half bijwerken ---
nieuw_geval twee-image-regels
schrijf_compose "$OUDE_REGEL" "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
niet_nul "twee image-regels falen hard" "$CODE"
meldt "twee image-regels noemen het aantal" "heeft 2 image-regels"
bevat_niet "twee image-regels leveren geen half bijgewerkte PR" "gh pr create"
regel "twee image-regels laten compose.yaml ongemoeid" "$OUDE_REGEL"

# --- 11. Een image-referentie in commentaar telt niet mee ---
nieuw_geval referentie-in-commentaar
schrijf_compose "$OUDE_REGEL" "#    image: $IMAGE:latest@$NIEUW_DIGEST"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "een referentie in commentaar laat de echte regel tellen" "$CODE" 0
bevat "een referentie in commentaar leidt tot een PR" "gh pr create"
regel "een referentie in commentaar laat het commentaar staan" "#    image: $IMAGE:latest@$NIEUW_DIGEST"
regel "een referentie in commentaar werkt de echte regel bij" "$NIEUWE_REGEL"

# --- 12. Statussen zonder oordeel: niets doen, openstaande PR laten staan ---
for status in preview ontbreekt oncontroleerbaar; do
  nieuw_geval "status-$status"
  schrijf_compose "$OUDE_REGEL"
  vastleggen
  PR_LIJST=$EIGEN_PR STATUS=$status uitvoeren
  gelijk "status $status eindigt groen" "$CODE" 0
  bevat_niet "status $status opent geen PR" "gh pr create"
  bevat_niet "status $status sluit de openstaande PR niet" "gh pr close"
  regel "status $status laat compose.yaml ongemoeid" "$OUDE_REGEL"
done

# --- 13. Statussen die op een kapotte inrichting wijzen: rood, niets aanraken ---
for status in pin-onvindbaar bron-weg geen-pin; do
  nieuw_geval "hard-$status"
  schrijf_compose "$OUDE_REGEL"
  vastleggen
  PR_LIJST=$EIGEN_PR STATUS=$status uitvoeren
  niet_nul "status $status maakt de run rood" "$CODE"
  bevat_niet "status $status opent geen PR" "gh pr create"
  bevat_niet "status $status sluit de openstaande PR niet" "gh pr close"
done

nieuw_geval lege-status
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS="" uitvoeren
niet_nul "een lege status maakt de run rood" "$CODE"
meldt "een lege status wordt als leeg benoemd" "status=<leeg>"

# --- 14. Fork-PR met dezelfde branchnaam telt niet als onze pin-PR ---
nieuw_geval fork-pr
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST=$FORK_PR STATUS=ok uitvoeren
gelijk "een fork-PR met dezelfde branchnaam eindigt groen" "$CODE" 0
bevat_niet "een fork-PR met dezelfde branchnaam wordt niet gesloten" "gh pr close"

# --- 15. Gemengde lijst: de eigen PR wordt gevonden, ongeacht de volgorde ---
nieuw_geval fork-eerst
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST=$FORK_EERST STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
bevat "met een fork-PR vooraan wordt de eigen PR bijgewerkt" "gh pr edit 42"
bevat_niet "met een fork-PR vooraan blijft die fork-PR ongemoeid" "gh pr edit 99"

nieuw_geval eigen-eerst
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST=$EIGEN_EERST STATUS=ok uitvoeren
bevat "met de eigen PR vooraan wordt die gesloten" "gh pr close 42"
bevat_niet "met de eigen PR vooraan blijft de fork-PR ongemoeid" "gh pr close 99"

# --- 16. Falende gh- en git-aanroepen planten zich voort ---
nieuw_geval pr-list-stuk
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" GH_FAALT="pr list" uitvoeren
niet_nul "een mislukte PR-lijst maakt de run rood" "$CODE"
bevat_niet "een mislukte PR-lijst opent geen PR" "gh pr create"

nieuw_geval pr-create-stuk
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" GH_FAALT="pr create" uitvoeren
niet_nul "een mislukte PR-aanmaak maakt de run rood" "$CODE"

nieuw_geval push-stuk
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" GIT_PUSH_FAALT=1 uitvoeren
niet_nul "een mislukte push maakt de run rood" "$CODE"
bevat_niet "een mislukte push opent geen PR" "gh pr create"

# --- 17. Ontbrekend token ---
nieuw_geval geen-token
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" GH_TOKEN="" uitvoeren
niet_nul "een ontbrekend token faalt hard" "$CODE"
meldt "een ontbrekend token noemt de secret" "PROEFTUIN_PIN_TOKEN ontbreekt"
bevat_niet "een ontbrekend token raakt de PR-lijst niet" "gh pr list"

# --- 18. Onleesbare compose.yaml: benoemde fout in plaats van een kale tool-melding ---
nieuw_geval geen-compose
COMPOSE="$WERKMAP/geen-compose/bestaat-niet" PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
niet_nul "een onleesbare compose.yaml faalt hard" "$CODE"
meldt "een onleesbare compose.yaml noemt de oorzaak" "niet te lezen"

# --- 19. Sourcen voert main niet uit ---
nieuw_geval sourcen
schrijf_compose "$OUDE_REGEL"
vastleggen
set +e
UITVOER=$(GH_TOKEN="" STATUS="" bash -c "source '$SCRIPT'; echo geladen" 2>&1)
CODE=$?
set -e
gelijk "sourcen eindigt groen" "$CODE" 0
meldt "sourcen laadt het script" "geladen"
bevat_niet "sourcen roept geen enkele gh-aanroep aan" "gh "

echo
if [ "$fails" -gt 0 ]; then
  echo "$fails test(s) gefaald." >&2
  echo "ASSERTIES=$geslaagd"
  exit 1
fi

echo "Alle tests geslaagd."
echo "ASSERTIES=$geslaagd"
