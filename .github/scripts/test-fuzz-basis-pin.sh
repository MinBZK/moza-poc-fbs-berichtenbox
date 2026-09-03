#!/usr/bin/env bash
# Fixture-tests voor fuzz-basis-pin.sh. Het script muteert gedeelde toestand — een branch, een PR en de
# pin in het Dockerfile — en de dure faalwijze is niet "rood", maar "groen terwijl het de verkeerde
# kant op ging": een vergelijking die per ongeluk matcht, sluit de PR die de fix droeg.
#
# Daarom toetst elke assertie naast de exitcode ook wélke gh-aanroepen zijn gedaan. Alleen de
# exitcode toetsen laat de vier PR-gevallen allemaal slagen zonder iets te onderscheiden.
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

# De stubs leggen elke aanroep vast en bootsen alleen na wat het script echt uitleest: de
# PR-lijst (met de jq-filter die het script zelf meegeeft, zodat de fork-filter écht getoetst
# wordt), of het Dockerfile is gewijzigd, en of de branch nog bestaat.
mkdir -p "$WERKMAP/bin"

cat > "$WERKMAP/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf 'gh %s\n' "$*" >> "$AANROEPEN"

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
  diff)     cmp -s "$MOMENTOPNAME" "$DOCKERFILE" ;;
  ls-remote) [ "${TAK_BESTAAT:-1}" = 1 ] ;;
  *)        true ;;
esac
STUB

chmod +x "$WERKMAP/bin/gh" "$WERKMAP/bin/git"
export PATH="$WERKMAP/bin:$PATH"

# $1 = FROM-regel, rest = extra regels erboven (commentaar).
schrijf_dockerfile() {
  local from=$1
  shift

  : > "$DOCKERFILE"
  [ "$#" -gt 0 ] && printf '%s\n' "$@" >> "$DOCKERFILE"
  printf 'FROM %s\n' "$from" >> "$DOCKERFILE"
  printf 'COPY . /src\n' >> "$DOCKERFILE"
}

# Draait het script in een verse omgeving. Zet UITVOER, CODE en AANROEPEN voor de asserties.
draai() {
  local geval=$1
  local map="$WERKMAP/$geval"

  mkdir -p "$map"
  export DOCKERFILE="$map/Dockerfile"
  export MOMENTOPNAME="$map/Dockerfile.voor"
  export AANROEPEN="$map/aanroepen"
  : > "$AANROEPEN"
}

vastleggen() { cp "$DOCKERFILE" "$MOMENTOPNAME"; }

uitvoeren() {
  set +e
  UITVOER=$(BRANCH=chore/fuzz-basis-pin bash "$SCRIPT" 2>&1)
  CODE=$?
  set -e
}

bevat()      { grep -qF "$2" "$AANROEPEN" && ok "$1" || fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))"; }
bevat_niet() { grep -qF "$2" "$AANROEPEN" && fout "$1 (aanroepen: $(tr '\n' '|' < "$AANROEPEN"))" || ok "$1"; }
gelijk()     { [ "$2" = "$3" ] && ok "$1" || fout "$1 (verwacht '$3', kreeg '$2')"; }
meldt()      { grep -qF "$2" <<<"$UITVOER" && ok "$1" || fout "$1 (uitvoer: $UITVOER)"; }

export GH_TOKEN=stub-token
export POMS=784a07194fe785b210158bc95025a0b483eef565edb44623b62af64b553a6db2

EIGEN_PR='[{"number":42,"isCrossRepository":false}]'
FORK_PR='[{"number":99,"isCrossRepository":true}]'

# --- 1. Pin verouderd, geen open PR: nieuwe PR, branch gepusht ---
draai zonder-pr
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "verouderde pin zonder open PR eindigt groen" "$CODE" 0
bevat "verouderde pin zonder open PR opent een PR" "gh pr create"
bevat "verouderde pin zonder open PR pusht de branch" "git push -f origin chore/fuzz-basis-pin"
grep -qxF "FROM $NIEUW" "$DOCKERFILE" && ok "de FROM-regel draagt de nieuwe digest" || fout "de FROM-regel draagt de nieuwe digest"

# --- 2. Pin verouderd, open PR: verversen, geen tweede PR ---
draai met-pr
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW uitvoeren
gelijk "verouderde pin met open PR eindigt groen" "$CODE" 0
bevat "verouderde pin met open PR werkt de body bij" "gh pr edit 42"
bevat_niet "verouderde pin met open PR opent geen tweede" "gh pr create"

# --- 3. Pin al goed, open PR: opruimen ---
draai al-goed-met-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW uitvoeren
gelijk "actuele pin met open PR eindigt groen" "$CODE" 0
bevat "actuele pin sluit de overbodige PR" "gh pr close 42"
bevat "actuele pin ruimt de branch op" "git push origin --delete chore/fuzz-basis-pin"

# --- 4. Pin al goed, geen open PR: niets doen ---
draai al-goed-zonder-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "actuele pin zonder open PR eindigt groen" "$CODE" 0
bevat_niet "actuele pin zonder open PR sluit niets" "gh pr close"
bevat_niet "actuele pin zonder open PR opent niets" "gh pr create"
bevat_niet "actuele pin zonder open PR pusht niets" "git push"

# --- 5. Branch al opgeruimd: sluiten blijft groen ---
draai branch-weg
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW TAK_BESTAAT=0 uitvoeren
gelijk "een al verwijderde branch maakt het opruimen niet rood" "$CODE" 0
bevat_niet "een al verwijderde branch wordt niet nog eens verwijderd" "git push origin --delete"

# --- 6. Lege digest: geen enkele match, harde fout ---
draai lege-digest
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST="" uitvoeren
gelijk "een lege digest faalt hard" "$CODE" 1
meldt "een lege digest noemt de oorzaak" "geen bruikbare digest"
bevat_niet "een lege digest sluit de openstaande PR niet" "gh pr close"

# --- 7. Afgekapte digest (image-pad zonder hash): idem ---
draai halve-digest
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST="$IMAGE@" uitvoeren
gelijk "een afgekapte digest faalt hard" "$CODE" 1
bevat_niet "een afgekapte digest sluit de openstaande PR niet" "gh pr close"

# --- 8. Digest staat alleen in een commentaarregel: de echte FROM-regel telt ---
draai digest-in-commentaar
schrijf_dockerfile "$OUD" "# Voorbeeld: FROM $NIEUW"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "een digest in commentaar laat de pin als verouderd gelden" "$CODE" 0
bevat "een digest in commentaar leidt tot een PR" "gh pr create"
grep -qxF "FROM $NIEUW" "$DOCKERFILE" && ok "een digest in commentaar laat de FROM-regel bijwerken" || fout "een digest in commentaar laat de FROM-regel bijwerken"

# --- 9. FROM-regel van vorm veranderd: harde fout, geen lege PR ---
draai vorm-gewijzigd
schrijf_dockerfile "$IMAGE:poms-abc"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "een FROM-regel zonder digest faalt hard" "$CODE" 1
meldt "een FROM-regel zonder digest noemt de oorzaak" "niet herkend"
bevat_niet "een FROM-regel zonder digest opent geen PR" "gh pr create"

# --- 10. Fork-PR met dezelfde branchnaam telt niet als onze pin-PR ---
draai fork-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$FORK_PR DIGEST=$NIEUW uitvoeren
gelijk "een fork-PR met dezelfde branchnaam eindigt groen" "$CODE" 0
bevat_niet "een fork-PR met dezelfde branchnaam wordt niet gesloten" "gh pr close"

# --- 11. Ontbrekend token ---
draai geen-token
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW GH_TOKEN="" uitvoeren
gelijk "een ontbrekend token faalt hard" "$CODE" 1
meldt "een ontbrekend token noemt de secret" "FUZZ_PIN_TOKEN ontbreekt"
bevat_niet "een ontbrekend token raakt de PR-lijst niet" "gh pr list"

echo
echo "ASSERTIES=$geslaagd"

if [ "$fails" -gt 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
