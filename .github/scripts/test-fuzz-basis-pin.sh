#!/usr/bin/env bash
# Fixture-tests voor fuzz-basis-pin.sh. Het script muteert gedeelde toestand — een branch, een PR en
# de pin in het Dockerfile — en de dure faalwijze is niet "rood", maar "groen terwijl het de
# verkeerde kant op ging": een vergelijking die per ongeluk matcht, sluit de PR die de fix droeg.
#
# Daarom toetst elk geval naast de exitcode ook wélke gh- en git-aanroepen zijn gedaan, en waar het
# om de inhoud gaat ook de FROM-regel en de PR-body. Alleen de exitcode toetsen laat de vier
# PR-toestanden allemaal slagen zonder iets te onderscheiden.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/fuzz-basis-pin.sh"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

IMAGE=ghcr.io/minbzk/fbs-fuzz-base
OUD=$IMAGE@sha256:283ebfd78ce10ac2d9e023d37f6f9eb60fbe7a72a23018d844a4ddbb9530ac95
NIEUW=$IMAGE@sha256:a34281d2286452925dff21dc375122b503c0813c6979f7168d5f84928cf556bb

# De stubs leggen elke aanroep vast en bootsen alleen na wat het script echt uitleest: de PR-lijst
# (met de jq-filter die het script zelf meegeeft, zodat de fork-filter écht getoetst wordt), of het
# Dockerfile is gewijzigd, en of de branch nog bestaat.
#
# Ze kunnen ook falen. Zonder die schakelaars overleeft elke `|| true` achter een gh-aanroep de
# suite, en juist die maakt een mislukte PR-actie stil.
mkdir -p "$WERKMAP/bin"

cat > "$WERKMAP/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf 'gh %s\n' "$*" >> "$AANROEPEN"

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
  diff)      cmp -s "$MOMENTOPNAME" "$DOCKERFILE" ;;
  ls-remote) exit "${LS_REMOTE_CODE:-0}" ;;
  push)      [ "${GIT_PUSH_FAALT:-0}" = 0 ] ;;
  *)         true ;;
esac
STUB

chmod +x "$WERKMAP/bin/gh" "$WERKMAP/bin/git"
export PATH="$WERKMAP/bin:$PATH"

# $1 = FROM-regel, rest = extra regels erboven (commentaar of een tweede FROM).
schrijf_dockerfile() {
  local from=$1
  shift

  : > "$DOCKERFILE"
  [ "$#" -gt 0 ] && printf '%s\n' "$@" >> "$DOCKERFILE"
  printf 'FROM %s\n' "$from" >> "$DOCKERFILE"
  printf 'COPY . /src\n' >> "$DOCKERFILE"
}

# Zet een verse werkmap klaar voor één geval: eigen Dockerfile, momentopname en aanroepenlogboek.
# Het draaien zelf doet `uitvoeren`.
nieuw_geval() {
  local map="$WERKMAP/$1"

  mkdir -p "$map"
  export DOCKERFILE="$map/Dockerfile"
  export MOMENTOPNAME="$map/Dockerfile.voor"
  export AANROEPEN="$map/aanroepen"
  : > "$AANROEPEN"
}

vastleggen() { cp "$DOCKERFILE" "$MOMENTOPNAME"; }

# `set +e` omdat een deel van de gevallen juist een niet-nul exitcode verwacht en deze suite zelf
# onder `set -e` draait. BRANCH expliciet, zodat de aanroep-asserties niet meeschuiven als de default
# in het script wijzigt.
uitvoeren() {
  set +e
  UITVOER=$(BRANCH=chore/fuzz-basis-pin bash "$SCRIPT" 2>&1)
  CODE=$?
  set -e
}

bevat()      { grep -qF "$2" "$AANROEPEN" && ok "$1" || fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))"; }
bevat_niet() { grep -qF "$2" "$AANROEPEN" && fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))" || ok "$1"; }
gelijk()     { [ "$2" = "$3" ] && ok "$1" || fout "$1 (verwacht '$3', kreeg '$2')"; }
niet_nul()   { [ "$2" -ne 0 ] && ok "$1" || fout "$1 (exitcode 0, uitvoer: $UITVOER)"; }
meldt()      { grep -qF "$2" <<<"$UITVOER" && ok "$1" || fout "$1 (uitvoer: $UITVOER)"; }
regel()      { grep -qxF "$2" "$DOCKERFILE" && ok "$1" || fout "$1 (bestand: $(tr '\n' '|' < "$DOCKERFILE"))"; }

export GH_TOKEN=stub-token
export POMS=784a07194fe785b210158bc95025a0b483eef565edb44623b62af64b553a6db2

EIGEN_PR='[{"number":42,"isCrossRepository":false}]'
FORK_PR='[{"number":99,"isCrossRepository":true}]'
FORK_EERST='[{"number":99,"isCrossRepository":true},{"number":42,"isCrossRepository":false}]'
EIGEN_EERST='[{"number":42,"isCrossRepository":false},{"number":99,"isCrossRepository":true}]'

# --- 1. Pin verouderd, geen open PR: nieuwe PR, branch gepusht ---
nieuw_geval zonder-pr
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "verouderde pin zonder open PR eindigt groen" "$CODE" 0
bevat "verouderde pin zonder open PR opent een PR" "gh pr create"
bevat "verouderde pin zonder open PR pusht de branch" "git push -f origin chore/fuzz-basis-pin"
bevat "de nieuwe PR draagt de pom-hash van deze bouw" "$POMS"
regel "de FROM-regel draagt de nieuwe digest" "FROM $NIEUW"

# --- 2. Pin verouderd, open PR: verversen, geen tweede PR ---
nieuw_geval met-pr
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW uitvoeren
gelijk "verouderde pin met open PR eindigt groen" "$CODE" 0
bevat "verouderde pin met open PR werkt de body bij" "gh pr edit 42"
bevat "de bijgewerkte body draagt de pom-hash van deze bouw" "$POMS"
bevat "verouderde pin met open PR pusht de branch" "git push -f origin chore/fuzz-basis-pin"
bevat_niet "verouderde pin met open PR opent geen tweede" "gh pr create"
regel "de bijgewerkte PR draagt de nieuwe digest" "FROM $NIEUW"

# --- 3. Pin al goed, open PR: opruimen ---
nieuw_geval al-goed-met-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW uitvoeren
gelijk "actuele pin met open PR eindigt groen" "$CODE" 0
bevat "actuele pin sluit de overbodige PR" "gh pr close 42"
bevat "actuele pin ruimt de branch op" "git push origin --delete chore/fuzz-basis-pin"

# --- 4. Pin al goed, geen open PR: niets doen ---
nieuw_geval al-goed-zonder-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "actuele pin zonder open PR eindigt groen" "$CODE" 0
bevat_niet "actuele pin zonder open PR sluit niets" "gh pr close"
bevat_niet "actuele pin zonder open PR opent niets" "gh pr create"
bevat_niet "actuele pin zonder open PR pusht niets" "git push"

# --- 5. Branch al opgeruimd (ls-remote 2): sluiten blijft groen ---
nieuw_geval branch-weg
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW LS_REMOTE_CODE=2 uitvoeren
gelijk "een al verwijderde branch maakt het opruimen niet rood" "$CODE" 0
bevat_niet "een al verwijderde branch wordt niet nog eens verwijderd" "git push origin --delete"
meldt "een al verwijderde branch wordt benoemd" "bestond al niet meer"

# --- 6. ls-remote faalt op iets anders dan 'niet gevonden': niet stil overslaan ---
nieuw_geval ls-remote-stuk
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW LS_REMOTE_CODE=128 uitvoeren
niet_nul "een onbereikbare remote maakt het opruimen rood" "$CODE"
meldt "een onbereikbare remote noemt de oorzaak" "kon niet vaststellen"
bevat_niet "een onbereikbare remote verwijdert niets" "git push origin --delete"

# --- 7. Lege digest ---
nieuw_geval lege-digest
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST="" uitvoeren
niet_nul "een lege digest faalt hard" "$CODE"
meldt "een lege digest noemt de oorzaak" "geen bruikbare digest"
bevat_niet "een lege digest sluit de openstaande PR niet" "gh pr close"

# --- 8. Afgekapte digest (image-pad zonder hash) ---
nieuw_geval halve-digest
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST="$IMAGE@" uitvoeren
niet_nul "een afgekapte digest faalt hard" "$CODE"
meldt "een afgekapte digest noemt de oorzaak" "geen bruikbare digest"
bevat_niet "een afgekapte digest sluit de openstaande PR niet" "gh pr close"
regel "een afgekapte digest laat het Dockerfile ongemoeid" "FROM $OUD"

# --- 9. Digest staat alleen in een commentaarregel: de echte FROM-regel telt ---
nieuw_geval digest-in-commentaar
schrijf_dockerfile "$OUD" "# Voorbeeld: FROM $NIEUW"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "een digest in commentaar laat de pin als verouderd gelden" "$CODE" 0
bevat "een digest in commentaar leidt tot een PR" "gh pr create"
regel "een digest in commentaar laat de FROM-regel bijwerken" "FROM $NIEUW"

# --- 10. FROM-regel van vorm veranderd: harde fout, geen lege PR ---
nieuw_geval vorm-gewijzigd
schrijf_dockerfile "$IMAGE:poms-abc"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
niet_nul "een FROM-regel zonder digest faalt hard" "$CODE"
meldt "een FROM-regel zonder digest noemt het aantal" "heeft 0 FROM-regels"
bevat_niet "een FROM-regel zonder digest opent geen PR" "gh pr create"

# --- 11. Twee FROM-regels op hetzelfde image: niet half bijwerken ---
nieuw_geval multi-stage
schrijf_dockerfile "$OUD" "FROM $OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
niet_nul "twee pinnende FROM-regels falen hard" "$CODE"
meldt "twee pinnende FROM-regels noemen het aantal" "heeft 2 FROM-regels"
bevat_niet "twee pinnende FROM-regels leveren geen half bijgewerkte PR" "gh pr create"

# --- 12. Fork-PR met dezelfde branchnaam telt niet als onze pin-PR ---
nieuw_geval fork-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$FORK_PR DIGEST=$NIEUW uitvoeren
gelijk "een fork-PR met dezelfde branchnaam eindigt groen" "$CODE" 0
bevat_niet "een fork-PR met dezelfde branchnaam wordt niet gesloten" "gh pr close"

# --- 13. Gemengde lijst: de eigen PR wordt gevonden, ongeacht de volgorde ---
nieuw_geval fork-eerst
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$FORK_EERST DIGEST=$NIEUW uitvoeren
bevat "met een fork-PR vooraan wordt de eigen PR gesloten" "gh pr close 42"
bevat_niet "met een fork-PR vooraan blijft die fork-PR ongemoeid" "gh pr close 99"

nieuw_geval eigen-eerst
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_EERST DIGEST=$NIEUW uitvoeren
bevat "met de eigen PR vooraan wordt die gesloten" "gh pr close 42"
bevat_niet "met de eigen PR vooraan blijft de fork-PR ongemoeid" "gh pr close 99"

# --- 14. Falende gh- en git-aanroepen planten zich voort ---
nieuw_geval pr-list-stuk
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW GH_FAALT="pr list" uitvoeren
niet_nul "een mislukte PR-lijst maakt de run rood" "$CODE"
bevat_niet "een mislukte PR-lijst opent geen PR" "gh pr create"

nieuw_geval pr-create-stuk
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW GH_FAALT="pr create" uitvoeren
niet_nul "een mislukte PR-aanmaak maakt de run rood" "$CODE"

nieuw_geval push-stuk
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW GIT_PUSH_FAALT=1 uitvoeren
niet_nul "een mislukte push maakt de run rood" "$CODE"
bevat_niet "een mislukte push opent geen PR" "gh pr create"

# --- 15. Onleesbaar Dockerfile: benoemde fout in plaats van een kale tool-melding ---
nieuw_geval geen-dockerfile
DOCKERFILE="$WERKMAP/geen-dockerfile/bestaat-niet" PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
niet_nul "een onleesbaar Dockerfile faalt hard" "$CODE"
meldt "een onleesbaar Dockerfile noemt de oorzaak" "niet te lezen"

# --- 16. Ontbrekend token ---
nieuw_geval geen-token
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW GH_TOKEN="" uitvoeren
niet_nul "een ontbrekend token faalt hard" "$CODE"
meldt "een ontbrekend token noemt de secret" "FUZZ_PIN_TOKEN ontbreekt"
bevat_niet "een ontbrekend token raakt de PR-lijst niet" "gh pr list"

# --- 17. Sourcen voert main niet uit ---
nieuw_geval sourcen
schrijf_dockerfile "$OUD"
vastleggen
set +e
UITVOER=$(GH_TOKEN="" DIGEST="" bash -c "source '$SCRIPT'; echo geladen" 2>&1)
CODE=$?
set -e
gelijk "sourcen eindigt groen" "$CODE" 0
meldt "sourcen laadt het script" "geladen"
bevat_niet "sourcen roept geen enkele gh-aanroep aan" "gh "

echo
# Door ci-scripts.yml gelezen: een suite die stilletjes minder toetst, valt daar door de mand.
echo "ASSERTIES=$geslaagd"

if [ "$fails" -gt 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
