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
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
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
  local uitkomst

  uitkomst=$(classificeer "$bestanden" "$bot" 2>/dev/null)

  vergelijk "$omschrijving" "$uitkomst" "$verwachting"

  # Invariant over élke fixture, niet één losse test: `demo-only` scopet de tests naar de
  # demo-modules terwijl `deploy` previews van de twee services uitrolt. Samen betekent dat een
  # uitrol van code die in díe run niet getest is, en de uitrolpoort ziet alleen een geslaagde
  # test-check — dus niets wordt rood.
  if grep -q 'demo-only=true' <<<"$uitkomst" && grep -q 'deploy=true' <<<"$uitkomst"; then
    fout "$omschrijving levert demo-only=true én deploy=true — previews op een halve testrun"
  fi
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

GEEN_PREVIEW='run=true
deploy=false
demo-only=false
fuzz=false'

# Een Maven-module onder demo/ die buiten DEMO_BUITEN_UITROLPOORT valt: uitrol-relevant, want hij kan
# een eigen image voeden. `demo-only` valt daarmee terug op false — een preview rolt de services
# uit, en die horen in diezelfde run getest te zijn. Geen fuzz-ronde: de fuzz-doelen staan alleen
# in libraries/ en services/.
DEMO_MET_IMAGE='run=true
deploy=true
demo-only=false
fuzz=false'

# Een demo-onderdeel dat geen image voedt: geen uitrol, en de test-job scopet naar de demo-modules.
DEMO_STACK='run=true
deploy=false
demo-only=true
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

# `^demo/demo-console/` eindigt op een slash; zonder die anker-slash zou een gelijknamige
# prefix-buur ongemerkt uit de uitrol vallen.
verwacht "prefix-buur van demo-console valt niet in de uitrol-uitsluiting" 'demo/demo-console-extra/Console.kt' "$DEMO_MET_IMAGE"

verwacht "een workflow met .yaml-extensie valt buiten de toets-lijst" '.github/workflows/test.yaml' 'run=true
deploy=true
demo-only=false
fuzz=false'

# De uitsluitingen zijn op `^` verankerd. Zonder deze drie overleeft een ontankerde variant de
# suite, en dat is de dure kant: `demo/` ergens in een pad zou de tests wegscopen, `bruno/` de
# preview, en een bronbestand met een meta-naam de code-checks.
verwacht "een 'demo'-map bínnen een module scopet de tests niet weg" 'services/berichtenuitvraag/src/test/resources/demo/payload.json' "$ALLES_AAN"

# Isoleert het `^`-anker van BUITEN_DEMO: op een uitrol-relevant pad zou de invariant demo-only
# tóch op false zetten en zou de fixture ook zonder anker slagen.
verwacht "een 'demo'-map in een niet-uitrol-relevant pad scopet de tests niet weg" \
  'bruno/berichtenmagazijn/demo/ophalen.bru' 'run=true
deploy=false
demo-only=false
fuzz=false'

verwacht "een 'bruno'-map bínnen een module blijft uitrol-relevant" 'services/berichtenmagazijn/src/test/bruno/Contract.kt' "$ALLES_AAN"

verwacht "een bronbestand met een meta-naam in de bestandsnaam is code" 'services/berichtenmagazijn/src/main/kotlin/GitignoreParser.kt' "$ALLES_AAN"

# Het enige niet-markdown-bestand onder docs/; zonder deze fixture overleeft het weghalen van
# `^docs/` de suite, en koopt een wijziging aan het C4-model de volledige keten.
verwacht "het C4-model onder docs/ is geen code" 'docs/architecture/workspace.dsl' "$ALLES_UIT"

verwacht "een resource met een meta-naam binnen een module" 'services/berichtenmagazijn/src/main/resources/apis.json' "$ALLES_AAN"

verwacht "een fixture met .md middenin de naam is geen documentatie" 'services/berichtenuitvraag/src/test/resources/voorbeeld.md.json' "$ALLES_AAN"

verwacht "een .claude-map binnen een module" 'services/berichtenmagazijn/.claude/hook.kt' "$ALLES_AAN"

# --- code -----------------------------------------------------------------------------------
verwacht "productiecode" 'services/berichtenmagazijn/src/main/kotlin/Bericht.kt' "$ALLES_AAN"

verwacht "documentatie naast code — code wint" 'README.md
libraries/fbs-common/src/main/kotlin/Problem.kt' "$ALLES_AAN"

verwacht "volgorde doet er niet toe — code eerst" 'libraries/fbs-common/src/main/kotlin/Problem.kt
README.md' "$ALLES_AAN"

verwacht "hetzelfde bestand dubbel" 'README.md
README.md' "$ALLES_UIT"

# --- wel toetsen, niet uitrollen ------------------------------------------------------------
verwacht "de Bruno-collectie" 'bruno/berichtenmagazijn/ophalen.bru' "$GEEN_PREVIEW"

verwacht "de CI-scripts zelf" '.github/scripts/wijzigingsfilter.sh' "$GEEN_PREVIEW"

verwacht "de fuzz-configuratie — wel toetsen en fuzzen, niet uitrollen" '.clusterfuzzlite/build.sh' 'run=true
deploy=false
demo-only=false
fuzz=true'

# Uit de lijst zelf gegenereerd: één handmatig gekozen voorbeeld liet vijf van de namen ongedekt,
# waardoor ze zonder rode test uit het patroon konden verdwijnen.
while IFS= read -r w; do
  verwacht "workflow $w kost geen uitrol" ".github/workflows/$w.yml" "$GEEN_PREVIEW"
done <<<"${GEEN_PREVIEW_WORKFLOWS//|/$'\n'}"

# deploy.yml bepaalt de uitrol zelf: juist daar is een preview het bewijs dat de wijziging klopt.
verwacht "deploy.yml zelf" '.github/workflows/deploy.yml' 'run=true
deploy=true
demo-only=false
fuzz=false'

# --- test-scope -------------------------------------------------------------------------------
verwacht "uitsluitend demo-console" 'demo/demo-console/src/main/kotlin/Console.kt' 'run=true
deploy=false
demo-only=true
fuzz=false'

verwacht "demo-console plus een andere module — volledige build" 'demo/demo-console/src/main/kotlin/Console.kt
services/berichtenuitvraag/src/main/kotlin/Uitvraag.kt' "$ALLES_AAN"

# De kern van de padprecieze uitsluiting: een demo-module die een eigen image kan voeden moet zijn
# build en preview houden. Een kale `^demo/`-uitsluiting zou hem stil overslaan — een overgeslagen
# job telt als succes voor branch protection.
verwacht "demo-module met een eigen image kost wél een uitrol" \
  'demo/magazijn-simulator/src/main/kotlin/Simulator.kt' "$DEMO_MET_IMAGE"

verwacht "de demo-stack" 'demo/environment/federatie/federatie.sh' "$DEMO_STACK"

# Het derde alternatief van DEMO_BUITEN_UITROLPOORT (`^demo/[^/]*\.(sh|py)$`) dekt de scripts die de
# demo-stack aansturen. Zonder deze twee fixtures kon het compleet verdwijnen — of tot één
# extensie versmallen — zonder dat er iets rood werd.
verwacht "een shellscript direct onder demo/" 'demo/smoke.sh' "$DEMO_STACK"

verwacht "de magazijn-generator direct onder demo/" 'demo/genereer-magazijnen.py' "$DEMO_STACK"

# `[^/]*` steekt geen slash over en de extensielijst is kort: allebei bewust, zodat onbekende
# demo-paden aan de bouwende kant vallen in plaats van stil overgeslagen te worden.
verwacht "een script in een submap onder demo/ houdt zijn uitrol" 'demo/scripts/smoke.sh' "$DEMO_MET_IMAGE"

verwacht "een ander bestand direct onder demo/ houdt zijn uitrol" 'demo/compose.yaml' "$DEMO_MET_IMAGE"

# Het `$`-anker van de extensielijst: zonder dat anker zou een backup- of afgeleid bestand de
# uitsluiting binnenglippen en zijn build verliezen.
verwacht "een afgeleid bestand met .sh in de naam houdt zijn uitrol" 'demo/smoke.sh.bak' "$DEMO_MET_IMAGE"

# De prefix-buur aan de environment-kant; hetzelfde anker-argument als bij demo-console.
verwacht "prefix-buur van environment valt niet in de uitrol-uitsluiting" \
  'demo/environment-simulator/Stack.kt' "$DEMO_MET_IMAGE"

# De `^`-ankers: dezelfde mapnamen dieper in een pad zijn gewone code en horen de volle keten te
# kopen. Een fixture op een uitrol-relevant pad zou dit niet aantonen — die valt al door de
# invariant terug op demo-only=false.
verwacht "een 'demo/demo-console'-pad bínnen een module is gewone code" \
  'services/berichtenuitvraag/src/test/resources/demo/demo-console/fixture.json' "$ALLES_AAN"

# De pom van een demo-module: wél code en test-scope demo, maar geen fuzz-ronde — de fuzz-doelen
# staan alleen in libraries/ en services/. Zonder het `^`-anker op `pom\.xml` in FUZZ_RELEVANT
# koopt elke bump op een demo-pom een volledige ronde.
verwacht "de pom van een demo-module koopt geen fuzz-ronde" 'demo/demo-console/pom.xml' "$DEMO_STACK"

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
if grep_fail_safe 'willekeurig' -E '[' 2>/dev/null; then
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

# De hele aanroep is het contract met de GitHub-API, niet alleen de vlag: zonder `--paginate`
# levert hij de eerste 30 bestanden (een PR met documentatie vooraan geeft dan `run=false` — alle
# checks 'skipped', wat als succes doortelt), en een verkeerd endpoint of jq-filter classificeert
# op de verkeerde gegevens. Vandaar de volledige argumentenvector.
argv_bestand=$(mktemp)
trap 'rm -f "$argv_bestand"' EXIT
gh() { printf '%s' "$*" >"$argv_bestand"; echo services/a/A.kt; }
EVENT=pull_request PR_AUTHOR_TYPE='' REPO=o/r PR=42 main >/dev/null 2>&1
unset -f gh
vergelijk "de gh-aanroep haalt gepagineerd de bestandsnamen van déze PR op" \
  "$(cat "$argv_bestand")" "api --paginate repos/o/r/pulls/42/files --jq .[].filename"

# De EVENT-tak: met een bereikbare `gh` moet een push de PR-lijst helemaal niet raadplegen. De
# stub hieronder zou anders documentatie teruggeven en `run=false` opleveren.
verwacht_main "push raadpleegt de PR-lijst niet" \
  'gh() { echo README.md; }' push '' "$ALLES_AAN"

verwacht_main "workflow_dispatch raadpleegt de PR-lijst niet" \
  'gh() { echo README.md; }' workflow_dispatch '' "$ALLES_AAN"

# --- entrypoint --------------------------------------------------------------------------------
# De workflows draaien `.github/scripts/wijzigingsfilter.sh` zonder `bash` ervoor. Een verloren
# uitvoerbaar-bit of een kapotte `BASH_SOURCE`-guard degradeert stil naar "alles draait, elke PR
# de volle prijs" — vandaar een aanroep als subproces.
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
    || fout "de lijst noemt $w.yml, dat bestaat niet"
done <<<"${GEEN_PREVIEW_WORKFLOWS//|/$'\n'}"

# Ook .yaml: GitHub honoreert beide extensies, terwijl NIET_DEPLOYBAAR alleen op `\.yml$` ankert.
for f in "$REPO_ROOT"/.github/workflows/*.yml "$REPO_ROOT"/.github/workflows/*.yaml; do
  [ -e "$f" ] || continue

  n=$(basename "$f" | sed 's/\.ya\?ml$//')

  case "|$GEEN_PREVIEW_WORKFLOWS|$UITROL_RELEVANT|" in
    *"|$n|"*) ;;
    *) fout "workflow $n is nergens ingedeeld — kost nu stilzwijgend drie previews per PR" ;;
  esac
done

[ -d "$REPO_ROOT/demo/demo-console" ] \
  || fout "demo/demo-console/ bestaat niet meer; de uitsluiting in DEMO_BUITEN_UITROLPOORT is dode letter"

[ -d "$REPO_ROOT/demo/environment" ] \
  || fout "demo/environment/ bestaat niet meer; de uitsluiting in DEMO_BUITEN_UITROLPOORT is dode letter"

# De fuzz-allowlist in .clusterfuzzlite/build.sh is handwerk: een module met een Jazzer-doel die
# daar niet in staat, wordt niet gebouwd en niet gefuzzd terwijl de ronde groen rapporteert.
fuzz_modules=$(grep -rl 'fuzzerTestOneInput' --include='*.kt' --include='*.java' "$REPO_ROOT/libraries" "$REPO_ROOT/services" "$REPO_ROOT/demo" 2>/dev/null \
  | sed "s:^$REPO_ROOT/::; s:/src/.*::" | sort -u)

if [ -z "$fuzz_modules" ]; then
  fout "geen enkel Jazzer-doel gevonden; deze kruiscontrole meet niets"
else
  ontbrekend=""

  while IFS= read -r module; do
    grep -q "MODULES=(.*$module" "$REPO_ROOT/.clusterfuzzlite/build.sh" || ontbrekend="$ontbrekend $module"
  done <<<"$fuzz_modules"

  if [ -n "$ontbrekend" ]; then
    fout "module(s) met een Jazzer-doel ontbreken in de MODULES-lijst van .clusterfuzzlite/build.sh:$ontbrekend"
  else
    ok "elke module met een Jazzer-doel staat in de fuzz-allowlist"
  fi
fi

compgen -G "$REPO_ROOT/demo/*.sh" >/dev/null \
  || fout "geen *.sh direct onder demo/; het sh-alternatief in DEMO_BUITEN_UITROLPOORT is dode letter"

compgen -G "$REPO_ROOT/demo/*.py" >/dev/null \
  || fout "geen *.py direct onder demo/; het py-alternatief in DEMO_BUITEN_UITROLPOORT is dode letter"

# De uitsluiting legt vast dat deze paden geen image voeden dat aan de uitrolpoort hangt. Dat is
# handwerk, dus het kan verlopen: zodra een demo-module in de build-matrix van deploy.yml staat,
# zou een PR aan die module zijn eigen imagebuild overslaan — en overgeslagen telt als succes.
matrix=$(sed -n 's/.*service: \[\(.*\)\].*/\1/p' "$REPO_ROOT/.github/workflows/deploy.yml")

if [ -z "$matrix" ]; then
  fout "geen service-matrix gevonden in deploy.yml; deze kruiscontrole meet niets"
else
  while IFS= read -r uitgesloten; do
    case "$uitgesloten" in
      '^demo/'*'/') module=${uitgesloten#^demo/}; module=${module%/} ;;
      *) continue ;;
    esac

    grep -q "\b$module\b" <<<"$matrix" \
      && fout "$module staat in de build-matrix van deploy.yml én in DEMO_BUITEN_UITROLPOORT; zijn imagebuild wordt stil overgeslagen"
  done <<<"${DEMO_BUITEN_UITROLPOORT//|/$'\n'}"

  ok "geen enkel pad uit DEMO_BUITEN_UITROLPOORT staat in de build-matrix van deploy.yml"
fi

[ -f "$REPO_ROOT/docs/architecture/workspace.dsl" ] \
  || fout "docs/architecture/workspace.dsl bestaat niet meer; de ^docs/-fixture is dode letter"

# Alleen OK melden als de kruiscontrole zélf niets vond; anders volgt er een OK ná een FAIL, en
# telt die bovendien mee in de assertie-ondergrens van ci-scripts.yml.
[ "$fails" -ne "$kruis_fouten" ] \
  || ok "kruiscontrole van de patronen met de bestanden op schijf"

# --- de suite bewaakt zichzelf ------------------------------------------------------------------
# Zonder deze zelftest blijft een suite waaruit de vergelijking is weggevallen groen mét het volle
# aantal OK-regels: de teller in ci-scripts.yml telt dan geprinte regels, geen vergelijkingen.
if (fails=0; vergelijk zelftest a b >/dev/null 2>&1; [ "$fails" -eq 1 ]); then
  ok "vergelijk merkt een afwijking op"
else
  fout "vergelijk meldt geen afwijking meer; de suite meet niets"
fi

# --- het contract met de workflows --------------------------------------------------------------
# De hele wijziging bestaat om vier gekopieerde detecties te vervangen door één script. Wie er een
# terugdraait naar inline logica, maakt zonder deze controle geen enkele test rood.
for wf in deploy test detekt cflite_pr; do
  grep -q '\.github/scripts/wijzigingsfilter\.sh' "$REPO_ROOT/.github/workflows/$wf.yml" \
    && ok "$wf.yml roept het gedeelde filter aan" \
    || fout "$wf.yml roept het gedeelde filter niet meer aan — de detectie is teruggedreven"
done

# Het vangnet dat de uitkomst valideert staat noodgedwongen in de workflows zelf (het moet ook
# werken als het script ontbreekt) en is daarmee vatbaar voor exact de drift die dit script
# wegneemt. Vandaar een vingerafdruk over de vier blokken.
vingers=$(for wf in deploy test detekt cflite_pr; do
  sed -n '/uitkomsten=\$(\.github/,/^ *fi$/p' "$REPO_ROOT/.github/workflows/$wf.yml" \
    | sed 's/^ *//;/^#/d;/^$/d' | md5sum | cut -d' ' -f1
done)

if [ "$(sort -u <<<"$vingers" | grep -c .)" = 1 ]; then
  ok "de vier workflows valideren de uitkomst identiek"
else
  fout "de validatie van de uitkomst is tussen de workflows uiteengelopen"
fi

# De workflows lezen steps.filter.outputs.<sleutel>. Hernoem één kant en de andere leest leeg —
# bij `deploy` betekent leeg "niet uitrollen", stil en groen.
gelezen=$(grep -rho 'steps\.filter\.outputs\.[a-z-]*' "$REPO_ROOT"/.github/workflows/*.yml \
          | sed 's/.*outputs\.//' | sort -u)
geleverd=$(alles_aan | cut -d= -f1 | sort -u)

if [ "$gelezen" = "$geleverd" ]; then
  ok "de workflows lezen exact de sleutels die het script levert"
else
  fout "sleutelnamen in de workflows en het script lopen uiteen"
fi

# --- sourcen mag main niet uitvoeren ------------------------------------------------------------
if [ -z "$(bash -c "source '$HERE/wijzigingsfilter.sh'" 2>/dev/null)" ]; then
  ok "sourcen voert main niet uit"
else
  fout "sourcen voert main uit; de BASH_SOURCE-guard is weg"
fi

if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
echo "ASSERTIES=$geslaagd"
