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
TESTBRANCH=chore/fuzz-basis-pin
# Het gemeten script leest het te wijzigen bestand als DOCKERFILE; het harnas zet die naam per geval.
DOELVAR=DOCKERFILE

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# De stubs, de asserties en de opzet per geval zijn gedeeld met test-proeftuin-pin-pr.sh: beide
# suites meten een script dat een branch, een PR en één regel in één bestand muteert.
# shellcheck source=.github/scripts/pin-pr-teststubs.sh
source "$HERE/pin-pr-teststubs.sh"
pin_pr_stubs_opzetten

IMAGE=ghcr.io/minbzk/fbs-fuzz-base
OUD=$IMAGE@sha256:283ebfd78ce10ac2d9e023d37f6f9eb60fbe7a72a23018d844a4ddbb9530ac95
NIEUW=$IMAGE@sha256:a34281d2286452925dff21dc375122b503c0813c6979f7168d5f84928cf556bb

# $1 = FROM-regel, rest = extra regels erboven (commentaar of een tweede FROM).
schrijf_dockerfile() {
  local from=$1
  shift

  : > "$DOCKERFILE"
  [ "$#" -gt 0 ] && printf '%s\n' "$@" >> "$DOCKERFILE"
  printf 'FROM %s\n' "$from" >> "$DOCKERFILE"
  printf 'COPY . /src\n' >> "$DOCKERFILE"
}

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
# De volledige aanroepen, niet alleen het commando: een `commit -a` of een weggevallen pathspec zou
# stilzwijgend andere wijzigingen uit de checkout meedragen in een PR die één regel belooft, en een
# verdwenen identiteit laat de commit op de runner-default staan.
bevat "de commit is tot het Dockerfile begrensd" "git commit -m chore(ci): pin het fuzz-basis-image op de huidige pom-set -- $DOCKERFILE"
bevat "de commit draagt de bot-identiteit" "git config user.email 41898282+github-actions[bot]@users.noreply.github.com"
bevat "de PR gaat naar main met de eigen branch als head" "gh pr create --base main --head chore/fuzz-basis-pin"

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

# --- 4. Pin al goed, geen open PR en geen branch: niets doen ---
nieuw_geval al-goed-zonder-pr
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW LS_REMOTE_CODE=2 uitvoeren
gelijk "actuele pin zonder open PR eindigt groen" "$CODE" 0
bevat_niet "actuele pin zonder open PR sluit niets" "gh pr close"
bevat_niet "actuele pin zonder open PR opent niets" "gh pr create"
bevat_niet "actuele pin zonder open PR pusht niets" "git push"

# --- 4b. Pin al goed, geen open PR, branch nog aanwezig ---
# Zo ziet de remote eruit na een run die tussen het sluiten van de PR en het verwijderen van de
# branch afbrak. Hing het opruimen aan een ópen PR, dan bleef die branch voorgoed staan en droeg de
# eerstvolgende bump de historie van een vorige cyclus.
nieuw_geval al-goed-verweesde-branch
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW uitvoeren
gelijk "een verweesde pin-branch zonder PR eindigt groen" "$CODE" 0
bevat "een verweesde pin-branch wordt alsnog verwijderd" "git push origin --delete chore/fuzz-basis-pin"
bevat_niet "een verweesde pin-branch levert geen PR-sluiting op" "gh pr close"

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

# --- 8b. Meerregelige digest: de vormcontrole mag niet op de tweede regel slagen ---
# Precies waarvoor `digest_is_welgevormd` `[[ =~ ]]` gebruikt in plaats van een per-regel ankerende
# grep. Een waarde uit GITHUB_OUTPUT kan meerregelig zijn.
nieuw_geval digest-met-newline
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST="$(printf 'rommel\n%s' "$NIEUW")" uitvoeren
niet_nul "een meerregelige digest faalt hard" "$CODE"
bevat_niet "een meerregelige digest opent geen PR" "gh pr create"
regel "een meerregelige digest laat het Dockerfile ongemoeid" "FROM $OUD"

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

# De twee muterende aanroepen die een halve toestand achterlaten: een PR die niet sluit terwijl de
# branch wél verdwijnt, en een body die niet meeschuift met de digest die zojuist gepusht is.
nieuw_geval pr-close-stuk
schrijf_dockerfile "$NIEUW"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW GH_FAALT="pr close" uitvoeren
niet_nul "een mislukte PR-sluiting maakt de run rood" "$CODE"
bevat_niet "een mislukte PR-sluiting verwijdert de branch niet" "git push origin --delete"

nieuw_geval pr-edit-stuk
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST=$EIGEN_PR DIGEST=$NIEUW GH_FAALT="pr edit" uitvoeren
niet_nul "een mislukte body-verversing maakt de run rood" "$CODE"

# `git commit` eindigt niet-nul als er niets te committen valt. Zonder deze dekking zou een script
# dat die uitkomst slikt een branch pushen zonder de wijziging erop.
nieuw_geval commit-stuk
schrijf_dockerfile "$OUD"
vastleggen
PR_LIJST='[]' DIGEST=$NIEUW GIT_COMMIT_FAALT=1 uitvoeren
niet_nul "een mislukte commit maakt de run rood" "$CODE"
bevat_niet "een mislukte commit pusht niets" "git push -f"
bevat_niet "een mislukte commit opent geen PR" "gh pr create"

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

# Print de uitkomst plus de ASSERTIES-regel die ci-scripts.yml leest: een suite die stilletjes
# minder toetst, valt daar door de mand.
pin_pr_uitkomst
