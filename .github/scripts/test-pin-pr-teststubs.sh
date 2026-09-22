#!/usr/bin/env bash
# Zelftest van pin-pr-teststubs.sh: de harness die test-fuzz-basis-pin.sh en test-proeftuin-pin-pr.sh
# delen.
#
# Zonder deze suite draagt één ongetoetst bestand de onderscheidende kracht van beide pin-suites. Eén
# regel volstaat om ze allebei betekenisloos te maken — `bevat() { ok "$1"; }` laat elke assertie
# slagen, terwijl de tellingen exact op hun ondergrens blijven en elke guard in ci-scripts.yml
# tevreden is. De assertie-functies moeten dus zélf in beide richtingen vastliggen: slagen waar het
# hoort, én falen waar het hoort.
#
# De te toetsen functie draait steeds in een subshell, zodat zijn tellers de telling van deze suite
# niet vervuilen; wat eruit komt is de `fails`-teller zoals die functie hem achterliet.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Het harnas eist deze drie vóór het eerste geval. SCRIPT wijst naar een fixture die hieronder de
# doorgegeven branch en exitcode zichtbaar maakt.
DOELVAR=DOELTEST
TESTBRANCH=chore/zelftest
SCRIPT="$WERKMAP/gemeten-script.sh"

cat > "$SCRIPT" <<'FIXTURE'
#!/usr/bin/env bash
echo "branch=$BRANCH"
exit "${FIXTURE_CODE:-0}"
FIXTURE
chmod +x "$SCRIPT"

# shellcheck source=.github/scripts/pin-pr-teststubs.sh
source "$HERE/pin-pr-teststubs.sh"
pin_pr_stubs_opzetten

nieuw_geval zelftest
printf 'gh pr create --base main --head chore/zelftest\ngit push -f origin chore/zelftest\n' > "$AANROEPEN"
printf 'eerste regel\ntweede regel\n' > "$DOELBESTAND"
printf 'de body van de PR' > "$BODYS"
UITVOER='::error::de melding uit het gemeten script'

# Draait één assertie-functie afgezonderd en meldt of hij een fout registreerde. De `fails=0` staat
# binnen de subshell: zo begint elke meting schoon, ongeacht wat deze suite zelf al geteld heeft.
faalde() {
  local resultaat
  resultaat=$(
    fails=0
    "$@" >/dev/null 2>&1
    printf '%s' "$fails"
  )

  [ "${resultaat:-0}" -gt 0 ]
}

moet_slagen() {
  local naam=$1
  shift

  if faalde "$@"; then fout "$naam"; else ok "$naam"; fi
}

moet_falen() {
  local naam=$1
  shift

  if faalde "$@"; then ok "$naam"; else fout "$naam"; fi
}

# --- de assertie-functies, elk in beide richtingen ---

moet_slagen "bevat slaagt op een gedane aanroep" bevat x "gh pr create"
moet_falen  "bevat faalt op een aanroep die niet gedaan is" bevat x "gh pr close"

moet_slagen "bevat_niet slaagt op een aanroep die niet gedaan is" bevat_niet x "gh pr close"
moet_falen  "bevat_niet faalt op een gedane aanroep" bevat_niet x "gh pr create"

moet_slagen "regel slaagt op een hele regel" regel x "eerste regel"
# `regel` gebruikt `grep -qxF`: een deelregel mag niet tellen, anders zou een half vervangen
# image-regel als "de nieuwe regel staat er" doorgaan.
moet_falen  "regel faalt op een deelregel" regel x "eerste"
moet_falen  "regel faalt op een afwezige regel" regel x "derde regel"

moet_slagen "regel_niet slaagt op een afwezige regel" regel_niet x "derde regel"
moet_falen  "regel_niet faalt op een aanwezige regel" regel_niet x "tweede regel"

moet_slagen "body slaagt op de doorgegeven body" body x "de body van de PR"
moet_falen  "body faalt op tekst die niet in de body staat" body x "een andere body"

moet_slagen "meldt slaagt op tekst uit de uitvoer" meldt x "de melding uit het gemeten script"
moet_falen  "meldt faalt op tekst die niet in de uitvoer staat" meldt x "een melding die er niet was"

moet_slagen "gelijk slaagt op twee gelijke waarden" gelijk x 3 3
moet_falen  "gelijk faalt op twee verschillende waarden" gelijk x 3 4

moet_slagen "niet_nul slaagt op een niet-nul exitcode" niet_nul x 3
moet_falen  "niet_nul faalt op exitcode 0" niet_nul x 0

# --- de opzet per geval ---

# Het gemeten script leest het doelbestand onder zijn eigen naam; schuift die niet mee met elk nieuw
# geval, dan meet de volgende assertie het bestand van het vorige.
[ "${DOELTEST:-}" = "$DOELBESTAND" ] \
  && ok "nieuw_geval exporteert het doelbestand onder de naam uit DOELVAR" \
  || fout "nieuw_geval exporteert DOELVAR niet (DOELTEST='${DOELTEST:-}')"

vorig_doel=$DOELBESTAND
nieuw_geval tweede-geval
[ "$DOELBESTAND" != "$vorig_doel" ] \
  && ok "een tweede geval krijgt een eigen doelbestand" \
  || fout "een tweede geval hergebruikt het doelbestand van het vorige"
[ "${DOELTEST:-}" = "$DOELBESTAND" ] \
  && ok "een tweede geval schuift ook de naam uit DOELVAR mee" \
  || fout "DOELTEST wijst na een tweede geval nog naar het vorige bestand"
[ -s "$AANROEPEN" ] \
  && fout "het aanroepenlogboek van een nieuw geval is niet leeg" \
  || ok "een nieuw geval begint met een leeg aanroepenlogboek"

# --- uitvoeren: branch doorgeven en exitcode vangen ---

uitvoeren
gelijk "uitvoeren vangt exitcode 0" "$CODE" 0
meldt "uitvoeren geeft de branch uit TESTBRANCH door" "branch=chore/zelftest"

FIXTURE_CODE=3 uitvoeren
gelijk "uitvoeren vangt een niet-nul exitcode in plaats van af te breken" "$CODE" 3

# --- de stubs ---

nieuw_geval stubs

# De echte `gh pr list` levert zonder deze vlaggen een andere verzameling: elke open PR in het repo,
# en zonder `--state open` ook gesloten PR's. Een stub die de vlaggen negeert laat dat stil slagen.
PR_LIJST='[{"number":42,"isCrossRepository":false}]' \
  gh pr list --head chore/zelftest --state open --json number,isCrossRepository \
  --jq '[.[] | select(.isCrossRepository | not)] | .[0].number // empty' >"$WERKMAP/pr-lijst" 2>&1 \
  && ok "de gh-stub beantwoordt een volledige pr list" \
  || fout "de gh-stub weigert een volledige pr list"
[ "$(cat "$WERKMAP/pr-lijst")" = 42 ] \
  && ok "de gh-stub past de meegegeven jq-filter toe" \
  || fout "de gh-stub geeft de PR-lijst niet door de filter (kreeg '$(cat "$WERKMAP/pr-lijst")')"

gh pr list --state open --json number --jq '.' >/dev/null 2>&1 \
  && fout "de gh-stub accepteert een pr list zonder --head" \
  || ok "de gh-stub weigert een pr list zonder --head"

gh pr list --head chore/zelftest --json number --jq '.' >/dev/null 2>&1 \
  && fout "de gh-stub accepteert een pr list zonder --state open" \
  || ok "de gh-stub weigert een pr list zonder --state open"

GH_FAALT="pr close" gh pr close 42 --comment x >/dev/null 2>&1 \
  && fout "GH_FAALT laat de genoemde aanroep slagen" \
  || ok "GH_FAALT laat de genoemde aanroep falen"

GH_FAALT="pr close" gh pr create --base main >/dev/null 2>&1 \
  && ok "GH_FAALT raakt alleen de genoemde aanroep" \
  || fout "GH_FAALT laat ook andere aanroepen falen"

GIT_PUSH_FAALT=1 git push -f origin chore/zelftest >/dev/null 2>&1 \
  && fout "GIT_PUSH_FAALT laat de push slagen" \
  || ok "GIT_PUSH_FAALT laat de push falen"

GIT_COMMIT_FAALT=1 git commit -m x -- "$DOELBESTAND" >/dev/null 2>&1 \
  && fout "GIT_COMMIT_FAALT laat de commit slagen" \
  || ok "GIT_COMMIT_FAALT laat de commit falen"

git ls-remote --exit-code --heads origin chore/zelftest >/dev/null 2>&1
gelijk "ls-remote geeft standaard 0: de branch bestaat" "$?" 0

set +e
LS_REMOTE_CODE=2 git ls-remote --exit-code --heads origin chore/zelftest >/dev/null 2>&1
ls_code=$?
set -e
gelijk "LS_REMOTE_CODE dicteert de uitkomst van ls-remote" "$ls_code" 2

# De git-stub legt élke aanroep vast, ook de niet-gestubde: anders zou een assertie op `git config`
# of `git switch` niets kunnen vinden terwijl de aanroep wél gedaan is.
: > "$AANROEPEN"
git config user.name "github-actions[bot]" >/dev/null 2>&1
bevat "de git-stub legt ook een niet-gestubde aanroep vast" "git config user.name github-actions[bot]"

# --- de afsluiting ---

# `pin_pr_uitkomst` draagt de ASSERTIES-regel die ci-scripts.yml leest. Bij een gefaalde suite moet
# die regel er óók staan: zonder telling zou een rode suite niet te onderscheiden zijn van een suite
# die niets rapporteert, en dat verschil bewaakt die workflow juist.
# `set +e` eromheen: de functie eindigt bij een gefaalde suite met `exit 1`, en dat is precies wat
# hier getoetst wordt — zonder deze schakelaar zou de subshell déze suite meeslepen.
set +e
uitkomst_rood=$(
  fails=1
  geslaagd=7
  pin_pr_uitkomst 2>&1
)
rood_code=$?
set -e

grep -q 'ASSERTIES=7' <<<"$uitkomst_rood" \
  && ok "een gefaalde suite rapporteert nog steeds zijn telling" \
  || fout "een gefaalde suite rapporteert geen ASSERTIES-regel ($uitkomst_rood)"
niet_nul "pin_pr_uitkomst breekt een gefaalde suite af" "$rood_code"

uitkomst_groen=$(
  fails=0
  geslaagd=9
  pin_pr_uitkomst 2>&1
)
gelijk "pin_pr_uitkomst laat een geslaagde suite doorlopen" "$?" 0
grep -q 'Alle tests geslaagd.' <<<"$uitkomst_groen" \
  && ok "een geslaagde suite meldt dat ook" \
  || fout "een geslaagde suite meldt zijn uitkomst niet"
grep -q 'ASSERTIES=9' <<<"$uitkomst_groen" \
  && ok "een geslaagde suite rapporteert zijn telling" \
  || fout "een geslaagde suite rapporteert geen ASSERTIES-regel"

pin_pr_uitkomst
