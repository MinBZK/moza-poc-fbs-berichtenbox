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
TESTBRANCH=chore/proeftuin-pin
# Het gemeten script leest het te wijzigen bestand als COMPOSE; het harnas zet die naam per geval.
DOELVAR=COMPOSE

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# De stubs, de asserties en de opzet per geval zijn gedeeld met test-fuzz-basis-pin.sh: beide suites
# meten een script dat een branch, een PR en één regel in één bestand muteert.
# shellcheck source=.github/scripts/pin-pr-teststubs.sh
source "$HERE/pin-pr-teststubs.sh"
pin_pr_stubs_opzetten

IMAGE=ghcr.io/minbzk/moza-poc
OUD_DIGEST=sha256:bd4a32fe0a52616509a8dcee6f3cdcd198463f9cc4a2c0e0486a6d30a231f571
NIEUW_DIGEST=sha256:03b37173b801a479450006d32ae74509da67bdf98215a2622f66f549ccdc94e9
OUDE_REGEL="    image: $IMAGE:latest@$OUD_DIGEST"
NIEUWE_REGEL="    image: $IMAGE:latest@$NIEUW_DIGEST"

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
# De volledige aanroepen, niet alleen het commando: een `commit -a` of een weggevallen pathspec zou
# stilzwijgend andere wijzigingen uit de checkout meedragen in een PR die één regel belooft, en een
# PR op een andere base zou nergens opvallen.
bevat "de commit is tot compose.yaml begrensd" "git commit -m chore(demo): zet de berichtenbox op de huidige main van de proeftuin -- $COMPOSE"
bevat "de commit draagt de bot-identiteit" "git config user.email 41898282+github-actions[bot]@users.noreply.github.com"
bevat "de PR gaat naar main met de eigen branch als head" "gh pr create --base main --head chore/proeftuin-pin"

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
# De sluitreden is de enige uitleg die een reviewer op zijn verdwenen PR ziet.
bevat "de sluitreden zegt waarom de PR dichtging" "hoort inmiddels bij de huidige main van de proeftuin"

# --- 4. Pin bij, geen open PR en geen branch: niets doen ---
nieuw_geval bij-zonder-pr
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=ok LS_REMOTE_CODE=2 uitvoeren
gelijk "een bijgewerkte pin zonder open PR eindigt groen" "$CODE" 0
bevat_niet "een bijgewerkte pin zonder open PR sluit niets" "gh pr close"
bevat_niet "een bijgewerkte pin zonder open PR opent niets" "gh pr create"
bevat_niet "een bijgewerkte pin zonder open PR pusht niets" "git push"

# --- 4b. Pin bij, geen open PR, branch nog aanwezig ---
# Zo ziet de remote eruit na een run die tussen het sluiten van de PR en het verwijderen van de
# branch afbrak. Hing het opruimen aan een ópen PR, dan bleef die branch voorgoed staan en droeg de
# eerstvolgende bump de historie van een vorige cyclus.
nieuw_geval bij-verweesde-branch
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=ok uitvoeren
gelijk "een verweesde pin-branch zonder PR eindigt groen" "$CODE" 0
bevat "een verweesde pin-branch wordt alsnog verwijderd" "git push origin --delete chore/proeftuin-pin"
bevat_niet "een verweesde pin-branch levert geen PR-sluiting op" "gh pr close"

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

# --- 11b. Een toelichting áchter de image-regel blijft staan ---
# Die vorm komt in dit repo voor — test-proeftuin-image.sh dekt hem expliciet — en wie hem schrijft,
# zet er een reden bij. Een vervanging die de hele regel platslaat, gooit die reden elke run opnieuw
# weg zonder dat de PR er anders uitziet.
nieuw_geval toelichting-achter-de-regel
schrijf_compose "$OUDE_REGEL # gepind, zie pin-consistency.yml"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "een regel met een toelichting erachter eindigt groen" "$CODE" 0
regel "de toelichting achter de regel blijft staan" "$NIEUWE_REGEL # gepind, zie pin-consistency.yml"

# --- 11c. Een geciteerde image-regel ---
# proeftuin-image.sh leest deze vorm expliciet, dus de pin kan zo in compose.yaml staan. Struikelde
# dit script daarover, dan brak één nette quote de hele bump — met een melding die "0 image-regels"
# beweert terwijl er wel degelijk één staat.
nieuw_geval geciteerde-image-regel
schrijf_compose "    image: \"$IMAGE:latest@$OUD_DIGEST\""
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "een geciteerde image-regel eindigt groen" "$CODE" 0
regel "een geciteerde image-regel houdt zijn aanhalingstekens" "    image: \"$IMAGE:latest@$NIEUW_DIGEST\""

# --- 11d. Een afwijkende inspringing blijft afwijkend ---
# De aangeboden regel draagt altijd vier spaties. Werd de hele regel vervangen, dan zou een pin die
# dieper staat stil verspringen — en dat verandert de YAML-structuur zonder dat iemand het vroeg.
nieuw_geval afwijkende-inspringing
schrijf_compose "      image: $IMAGE:latest@$OUD_DIGEST"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "een dieper ingesprongen image-regel eindigt groen" "$CODE" 0
regel "de inspringing van de bestaande regel blijft staan" "      image: $IMAGE:latest@$NIEUW_DIGEST"

# --- 11e. De aangeboden regel staat er al ---
# Zo ziet een bron eruit die `ok` en `verouderd` verwisselt, of een race waarin de pin tussen de
# meting en deze run al is bijgewerkt. Zonder het vangnet zou er een PR volgen met een lege diff.
nieuw_geval regel-al-gezet
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
niet_nul "een regel die er al staat levert geen lege PR" "$CODE"
meldt "een regel die er al staat noemt de oorzaak" "niet gewijzigd terwijl dat wel had gemoeten"
bevat_niet "een regel die er al staat opent geen PR" "gh pr create"

# --- 11f. Een meerregelige waarde ---
# GITHUB_OUTPUT kan meerregelige waarden dragen; de vormcontrole mag niet op de tweede regel slagen.
nieuw_geval regel-met-newline
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$(printf 'rommel\n%s' "$NIEUWE_REGEL")" uitvoeren
niet_nul "een meerregelige regel faalt hard" "$CODE"
meldt "een meerregelige regel noemt de oorzaak" "geen bruikbare image-regel"
regel "een meerregelige regel laat compose.yaml ongemoeid" "$OUDE_REGEL"

# --- 11g. De aangeboden regel wijst naar een ander image dan de pin ---
# Dit script kent de berichtenbox nergens bij naam: wélke regel geraakt wordt, komt uit de aangeboden
# regel. Wijst die ergens anders heen — een gewijzigd uitvoerformaat aan de bronkant, een
# overschreven MAIN_PAD — dan zou het een vreemde image-regel herschrijven in een PR die er normaal
# uitziet.
nieuw_geval vreemd-pad
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="    image: ghcr.io/minbzk/een-ander-image:latest@$NIEUW_DIGEST" uitvoeren
niet_nul "een regel voor een ander image faalt hard" "$CODE"
meldt "een regel voor een ander image noemt beide paden" "terwijl de pin op $IMAGE staat"
bevat_niet "een regel voor een ander image opent geen PR" "gh pr create"
regel "een regel voor een ander image laat compose.yaml ongemoeid" "$OUDE_REGEL"

# --- 11h. Een verhuisd image-pad ---
# De tegenhanger van het vorige geval: klopt de pin mét de aangeboden regel, dan hoort een ander pad
# gewoon te werken. Zonder dit geval zou een hardgecodeerd pad in dit script niemand opvallen.
nieuw_geval verhuisd-pad
schrijf_compose "    image: ghcr.io/minbzk/moza-poc-berichtenbox:latest@$OUD_DIGEST"
vastleggen
PR_LIJST='[]' STATUS=verouderd \
  HUIDIG="ghcr.io/minbzk/moza-poc-berichtenbox:latest@$OUD_DIGEST" \
  REGEL="    image: ghcr.io/minbzk/moza-poc-berichtenbox:latest@$NIEUW_DIGEST" uitvoeren
gelijk "een verhuisd image-pad eindigt groen" "$CODE" 0
bevat "een verhuisd image-pad levert een PR op" "gh pr create"
regel "een verhuisd image-pad wordt bijgewerkt" "    image: ghcr.io/minbzk/moza-poc-berichtenbox:latest@$NIEUW_DIGEST"

# --- 12. Statussen zonder oordeel: niets doen, openstaande PR laten staan ---
for status in preview ontbreekt; do
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
# `oncontroleerbaar` hoort hier en niet bij de groep hierboven: op een PR is "niets vastgesteld" een
# tijdelijke hik, maar in de geplande run is dit de enige plek waar hij zichtbaar wordt. Groen
# blijven zou die run weken laten zwijgen terwijl de bump uitblijft.
for status in pin-onvindbaar bron-weg geen-pin oncontroleerbaar; do
  nieuw_geval "hard-$status"
  schrijf_compose "$OUDE_REGEL"
  vastleggen
  PR_LIJST=$EIGEN_PR STATUS=$status uitvoeren
  niet_nul "status $status maakt de run rood" "$CODE"
  bevat_niet "status $status opent geen PR" "gh pr create"
  bevat_niet "status $status sluit de openstaande PR niet" "gh pr close"
  regel "status $status laat compose.yaml ongemoeid" "$OUDE_REGEL"
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

# --- 15b. Twee eigen PR's op dezelfde branch ---
# Een lijst van één verbergt "geeft de eerste terug" achter "kiest de juiste". GitHub houdt dit
# normaal tegen, maar het gedrag hoort vast te liggen: precies één PR wordt bijgewerkt.
TWEE_EIGEN='[{"number":7,"isCrossRepository":false},{"number":42,"isCrossRepository":false}]'
nieuw_geval twee-eigen-prs
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST=$TWEE_EIGEN STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
bevat "met twee eigen PR's wordt er één bijgewerkt" "gh pr edit 7"
bevat_niet "met twee eigen PR's blijft de tweede ongemoeid" "gh pr edit 42"
bevat_niet "met twee eigen PR's komt er geen derde bij" "gh pr create"

# --- 15c. Fork-PR met dezelfde branchnaam bij een verouderde pin ---
# De kant waar de force-push zit: hier zou andermans werk geraakt kunnen worden.
nieuw_geval fork-pr-verouderd
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST=$FORK_PR STATUS=verouderd REGEL="$NIEUWE_REGEL" uitvoeren
gelijk "een fork-PR bij een verouderde pin eindigt groen" "$CODE" 0
bevat "een fork-PR bij een verouderde pin levert een eigen PR op" "gh pr create"
bevat_niet "een fork-PR bij een verouderde pin wordt niet bijgewerkt" "gh pr edit 99"

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

# De twee muterende aanroepen die een halve toestand achterlaten: een PR die niet sluit terwijl de
# branch wél verdwijnt, en een body die niet meeschuift met wat er zojuist gepusht is.
nieuw_geval pr-close-stuk
schrijf_compose "$NIEUWE_REGEL"
vastleggen
PR_LIJST=$EIGEN_PR STATUS=ok GH_FAALT="pr close" uitvoeren
niet_nul "een mislukte PR-sluiting maakt de run rood" "$CODE"
bevat_niet "een mislukte PR-sluiting verwijdert de branch niet" "git push origin --delete"

nieuw_geval pr-edit-stuk
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST=$EIGEN_PR STATUS=verouderd REGEL="$NIEUWE_REGEL" GH_FAALT="pr edit" uitvoeren
niet_nul "een mislukte body-verversing maakt de run rood" "$CODE"

nieuw_geval commit-stuk
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" GIT_COMMIT_FAALT=1 uitvoeren
niet_nul "een mislukte commit maakt de run rood" "$CODE"
bevat_niet "een mislukte commit pusht niets" "git push -f"
bevat_niet "een mislukte commit opent geen PR" "gh pr create"

# --- 17. Ontbrekend token ---
nieuw_geval geen-token
schrijf_compose "$OUDE_REGEL"
vastleggen
PR_LIJST='[]' STATUS=verouderd REGEL="$NIEUWE_REGEL" GH_TOKEN="" uitvoeren
niet_nul "een ontbrekend token faalt hard" "$CODE"
meldt "een ontbrekend token noemt de secret" "FUZZ_PIN_TOKEN ontbreekt"
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

# Print de uitkomst plus de ASSERTIES-regel die ci-scripts.yml leest: een suite die stilletjes
# minder toetst, valt daar door de mand.
pin_pr_uitkomst
