#!/usr/bin/env bash
# Fixture-tests voor de wijzigingsdetectie die de hele CI-keten aanstuurt. Een stille fout is hier
# duur in beide richtingen: te ruim kost per PR twee testruns, detekt, vier images en drie
# previews, te streng laat ongetoetste code door als 'skipped' (= succes voor branch protection).
#
# Geen netwerk: `gh` wordt in de main-tests door een shellfunctie geschaduwd, zodat ook het
# ophaal- en fail-safe-pad uitgeoefend wordt.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=wijzigingsfilter.sh
source "$HERE/wijzigingsfilter.sh"

fails=0
ok()   { echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

vergelijk() {
  local omschrijving=$1 gekregen=$2 verwachting=$3

  if [ "$gekregen" = "$verwachting" ]; then
    ok "$omschrijving"
  else
    fout "$omschrijving
  verwacht: $(tr '\n' ' ' <<<"$verwachting")
  gekregen: $(tr '\n' ' ' <<<"$gekregen")"
  fi
}

# Draait `classificeer` op een bestandenlijst en vergelijkt de vier uitkomsten met de verwachting.
# $1 = omschrijving, $2 = bestanden (nieuwe regels), $3 = verwachte uitkomsten (nieuwe regels),
# $4 = optioneel 'true' voor een bot-PR.
verwacht() {
  local omschrijving=$1 bestanden=$2 verwachting=$3 bot=${4:-false}

  vergelijk "$omschrijving" "$(classificeer "$bestanden" "$bot" 2>/dev/null)" "$verwachting"
}

# Draait `main` met een gestubde `gh`, zodat het ophaal-, fail-safe- en event-pad meetellen.
# $1 = omschrijving, $2 = definitie van de gh-stub, $3 = EVENT, $4 = PR_AUTHOR_TYPE, $5 = verwacht.
verwacht_main() {
  local omschrijving=$1 stub=$2 event=$3 auteur=$4 verwachting=$5

  local gekregen
  gekregen=$(eval "$stub"; EVENT="$event" PR_AUTHOR_TYPE="$auteur" REPO=o/r PR=1 main 2>/dev/null)
  vergelijk "$omschrijving" "$gekregen" "$verwachting"
}

ALLES_UIT='run=false
deploy=false
demo-only=true
fuzz=false'

ALLES_AAN='run=true
deploy=true
demo-only=false
fuzz=true'

TOETS_WORKFLOW='run=true
deploy=false
demo-only=false
fuzz=false'

# --- documentatie en repo-meta ------------------------------------------------------------------
# `demo-only=true` bij een docs-only PR is geen tegenstrijdigheid: de test-job is dan al via
# `run=false` uitgeschakeld, dus de scope-uitkomst wordt niet gebruikt.
verwacht "documentatie alleen" 'docs/ontwikkelen.md
README.md' "$ALLES_UIT"

verwacht "CLAUDE.md plus .gitignore — de combinatie die de hele keten aftrapte" 'CLAUDE.md
.gitignore' "$ALLES_UIT"

verwacht "losse repo-meta-bestanden" '.editorconfig
LICENSE
apis.json
.github/dependabot.yml
.github/ISSUE_TEMPLATE/bug-report.yml
.claude/settings.json' "$ALLES_UIT"

# Documentatie ín een module trok voorheen wél een fuzz-ronde: de allowlist keek niet naar de
# code-uitkomst. Vier uitkomsten die elkaar tegenspreken maken het filter onvoorspelbaar.
verwacht "documentatie binnen een module kost geen fuzz-ronde" 'services/berichtenmagazijn/README.md' "$ALLES_UIT"

# --- ankering en grenswaarden -------------------------------------------------------------------
# De meta-namen zijn op ^…$ verankerd, dus een gelijknamig bestand in een submap telt als code.
# Voor `\.md$` geldt dat bewust niet: documentatie mag overal staan.
verwacht "een bronbestand met een meta-naam in een submap" 'services/berichtenmagazijn/src/main/resources/LICENSE' "$ALLES_AAN"

verwacht "een docs-map bínnen een module is gewoon code" 'services/berichtenmagazijn/docs/hulp.sh' "$ALLES_AAN"

verwacht "prefix-buur van demo-console is een gewone module" 'services/demo-console-extra/Console.kt' "$ALLES_AAN"

verwacht "een workflow met .yaml-extensie valt buiten de toets-lijst" '.github/workflows/test.yaml' 'run=true
deploy=true
demo-only=false
fuzz=false'

# De uitsluitingen zijn op `^` verankerd. Zonder deze drie overleeft een ontankerde variant de
# suite, en dat is de dure kant: `demo/` ergens in een pad zou de tests wegscopen, `bruno/` de
# preview, en een bronbestand met een meta-naam de code-checks.
verwacht "een 'demo'-map bínnen een module scopet de tests niet weg" 'services/berichtenuitvraag/src/test/resources/demo/payload.json' "$ALLES_AAN"

verwacht "een 'bruno'-map bínnen een module blijft uitrol-relevant" 'services/berichtenmagazijn/src/test/bruno/Contract.kt' "$ALLES_AAN"

verwacht "een bronbestand met een meta-naam in de bestandsnaam is code" 'services/berichtenmagazijn/src/main/kotlin/GitignoreParser.kt' "$ALLES_AAN"

# Het enige niet-markdown-bestand onder docs/; zonder deze fixture overleeft het weghalen van
# `^docs/` de suite, en koopt een wijziging aan het C4-model de volledige keten.
verwacht "het C4-model onder docs/ is geen code" 'docs/architecture/workspace.dsl' "$ALLES_UIT"

# --- code -----------------------------------------------------------------------------------
verwacht "productiecode" 'services/berichtenmagazijn/src/main/kotlin/Bericht.kt' "$ALLES_AAN"

verwacht "documentatie naast code — code wint" 'README.md
libraries/fbs-common/src/main/kotlin/Problem.kt' "$ALLES_AAN"

verwacht "volgorde doet er niet toe — code eerst" 'libraries/fbs-common/src/main/kotlin/Problem.kt
README.md' "$ALLES_AAN"

verwacht "hetzelfde bestand dubbel" 'README.md
README.md' "$ALLES_UIT"

# --- wel toetsen, niet uitrollen ------------------------------------------------------------
verwacht "de Bruno-collectie" 'bruno/berichtenmagazijn/ophalen.bru' "$TOETS_WORKFLOW"

verwacht "de CI-scripts zelf" '.github/scripts/wijzigingsfilter.sh' "$TOETS_WORKFLOW"

verwacht "de fuzz-configuratie — wel toetsen en fuzzen, niet uitrollen" '.clusterfuzzlite/build.sh' 'run=true
deploy=false
demo-only=false
fuzz=true'

# Uit de lijst zelf gegenereerd: één handmatig gekozen voorbeeld liet vijf van de namen ongedekt,
# waardoor ze zonder rode test uit het patroon konden verdwijnen.
while IFS= read -r w; do
  verwacht "toets-workflow $w kost geen uitrol" ".github/workflows/$w.yml" "$TOETS_WORKFLOW"
done <<<"${TOETS_WORKFLOWS//|/$'\n'}"

# deploy.yml bepaalt de uitrol zelf: juist daar is een preview het bewijs dat de wijziging klopt.
verwacht "deploy.yml zelf" '.github/workflows/deploy.yml' 'run=true
deploy=true
demo-only=false
fuzz=false'

# --- test-scope -------------------------------------------------------------------------------
verwacht "uitsluitend demo-console" 'services/demo-console/src/main/kotlin/Console.kt' 'run=true
deploy=false
demo-only=true
fuzz=true'

verwacht "demo-console plus een andere module — volledige build" 'services/demo-console/src/main/kotlin/Console.kt
services/berichtenuitvraag/src/main/kotlin/Uitvraag.kt' "$ALLES_AAN"

verwacht "de demo-stack" 'demo/environment/federatie/federatie.sh' 'run=true
deploy=false
demo-only=true
fuzz=false'

# --- bot-PR ----------------------------------------------------------------------------------
verwacht "bot-PR met code — toetsen ja, uitrollen nee" 'pom.xml' 'run=true
deploy=false
demo-only=false
fuzz=true' true

verwacht "bot-PR met alleen documentatie" 'README.md' "$ALLES_UIT" true

# Zonder deze regel blijft de default van het tweede argument ongetest: elke andere aanroep geeft
# hem expliciet mee.
vergelijk "classificeer zonder bot-argument gedraagt zich als mens-PR" \
  "$(classificeer 'pom.xml' 2>/dev/null)" "$ALLES_AAN"

# --- fail-safe: lege lijst ---------------------------------------------------------------------
verwacht "lege bestandenlijst — niets vastgesteld, dus alles draait" '' "$ALLES_AAN"

verwacht "lege bestandenlijst op een bot-PR — wel toetsen, niet uitrollen" '' 'run=true
deploy=false
demo-only=false
fuzz=true' true

# --- fail-safe: grep-fout ----------------------------------------------------------------------
# Een ongeldige ERE dwingt grep naar exit 2. Dat is de enige reden dat grep_fail_safe bestaat, en
# de tak bleef ongetest. In een `if`-conditie, anders breekt de van het script geërfde `set -e`
# de test af in plaats van hem te laten falen.
if grep_fail_safe 'willekeurig' -E '['; then
  ok "een grep-fout (rc=2) valt terug op draaien"
else
  fout "een grep-fout (rc=2) leidt tot overslaan"
fi

# De waarschuwing hoort op stderr: stdout hangt rechtstreeks aan \$GITHUB_OUTPUT, en een
# ::warning::-regel is daar geen geldige sleutel=waarde.
if [ -z "$(grep_fail_safe 'willekeurig' -E '[' 2>/dev/null)" ]; then
  ok "de grep-waarschuwing lekt niet naar stdout"
else
  fout "de grep-waarschuwing komt op stdout terecht"
fi

# --- main: event, ophalen en fail-safe ---------------------------------------------------------
verwacht_main "push naar main — geen PR-lijst om tegen af te zetten" \
  'gh() { echo "nooit aanroepen" >&2; return 9; }' push '' "$ALLES_AAN"

verwacht_main "workflow_dispatch — geen PR-lijst" \
  'gh() { echo "nooit aanroepen" >&2; return 9; }' workflow_dispatch '' "$ALLES_AAN"

verwacht_main "ophalen faalt — alles draait" \
  'gh() { return 1; }' pull_request '' "$ALLES_AAN"

verwacht_main "gh ontbreekt (127) — alles draait" \
  'gh() { return 127; }' pull_request '' "$ALLES_AAN"

verwacht_main "ophalen faalt op een bot-PR — wel toetsen, niet uitrollen" \
  'gh() { return 1; }' pull_request Bot 'run=true
deploy=false
demo-only=false
fuzz=true'

verwacht_main "ophalen levert documentatie" \
  'gh() { printf "docs/a.md\nREADME.md\n"; }' pull_request '' "$ALLES_UIT"

verwacht_main "ophalen levert code op een bot-PR" \
  'gh() { echo services/berichtenmagazijn/A.kt; }' pull_request Bot 'run=true
deploy=false
demo-only=false
fuzz=true'

# `--paginate` ís het contract met de GitHub-API: zonder die vlag levert de aanroep alleen de
# eerste 30 bestanden. Een PR van 40 bestanden waarvan de eerste 30 documentatie zijn, zou dan
# `run=false` opleveren — alle checks 'skipped', wat als succes doortelt.
verwacht_main "de bestandenlijst wordt gepagineerd opgehaald" \
  'gh() { case " $* " in *" --paginate "*) echo services/a/A.kt ;; *) echo README.md ;; esac; }' \
  pull_request '' "$ALLES_AAN"

# --- entrypoint --------------------------------------------------------------------------------
# De workflows draaien `.github/scripts/wijzigingsfilter.sh` zonder `bash` ervoor, en tot nu toe
# riep geen enkele test het script als subproces aan: een verloren uitvoerbaar-bit of een kapotte
# `BASH_SOURCE`-guard was een stille degradatie naar "alles draait, elke PR de volle prijs".
if [ -x "$HERE/wijzigingsfilter.sh" ]; then
  ok "wijzigingsfilter.sh is uitvoerbaar"
else
  fout "wijzigingsfilter.sh is niet uitvoerbaar; de workflows roepen hem zonder 'bash' aan"
fi

vergelijk "directe uitvoering levert de vier uitkomsten" \
  "$(EVENT=push "$HERE/wijzigingsfilter.sh" 2>/dev/null)" "$ALLES_AAN"

# --- vorm van de uitkomsten --------------------------------------------------------------------
# De workflows lezen steps.filter.outputs.<sleutel>; een onbekende of ontbrekende sleutel leest
# als lege string, en juist bij `deploy` betekent leeg "niet uitrollen" — stil, en groen.
if diff <(alles_aan | cut -d= -f1 | sort) \
        <(classificeer 'services/a/A.kt' false 2>/dev/null | cut -d= -f1 | sort) >/dev/null; then
  ok "alles_aan levert dezelfde vier sleutels als classificeer"
else
  fout "alles_aan en classificeer leveren verschillende sleutels"
fi

for uitkomst in "$(alles_aan)" "$(classificeer 'services/a/A.kt' false 2>/dev/null)"; do
  aantal=$(grep -cE '^[a-z-]+=(true|false)$' <<<"$uitkomst" || true)

  if [ "$aantal" = 4 ]; then
    ok "vier geldige sleutel=waarde-regels"
  else
    fout "verwachtte vier geldige sleutel=waarde-regels, kreeg $aantal"
  fi
done

# --- kruiscontrole met de schijf ---------------------------------------------------------------
# NIET_DEPLOYBAAR draagt kennis over bestanden buiten dit script. Wie een workflow hernoemt of
# toevoegt, raakt .github/workflows/ en niet dit script — een fixture-test ziet dat nooit.
kruis_fouten=$fails

while IFS= read -r w; do
  [ -f "$REPO_ROOT/.github/workflows/$w.yml" ] \
    || fout "de toets-lijst noemt $w.yml, dat bestaat niet"
done <<<"${TOETS_WORKFLOWS//|/$'\n'}"

# Ook .yaml: GitHub honoreert beide extensies, terwijl NIET_DEPLOYBAAR alleen op `\.yml$` ankert.
for f in "$REPO_ROOT"/.github/workflows/*.yml "$REPO_ROOT"/.github/workflows/*.yaml; do
  [ -e "$f" ] || continue

  n=$(basename "$f" | sed 's/\.ya\?ml$//')

  case "|$TOETS_WORKFLOWS|$UITROL_RELEVANT|" in
    *"|$n|"*) ;;
    *) fout "workflow $n is nergens ingedeeld — kost nu stilzwijgend drie previews per PR" ;;
  esac
done

[ -d "$REPO_ROOT/services/demo-console" ] \
  || fout "services/demo-console/ bestaat niet meer; BUITEN_DEMO_CONSOLE is dode letter"

[ -f "$REPO_ROOT/docs/architecture/workspace.dsl" ] \
  || fout "docs/architecture/workspace.dsl bestaat niet meer; de ^docs/-fixture is dode letter"

# Voorwaardelijk: de melding stond eerder onvoorwaardelijk en printte OK ná een echte FAIL.
[ "$fails" -ne "$kruis_fouten" ] \
  || ok "kruiscontrole van de patronen met de bestanden op schijf"

if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
