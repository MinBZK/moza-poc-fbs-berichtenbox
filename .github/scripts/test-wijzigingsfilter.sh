#!/usr/bin/env bash
# Fixture-tests voor de wijzigingsdetectie die de hele CI-keten aanstuurt. Een stille fout is hier
# duur in beide richtingen: te ruim kost per PR twee testruns, detekt, vier images en drie
# previews, te streng laat ongetoetste code door als 'skipped' (= succes voor branch protection).
#
# Geen netwerk en geen gh: de fetch zit in `main`, de beoordeling in `classificeer`, en alleen die
# laatste wordt hier uitgeoefend.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=wijzigingsfilter.sh
source "$HERE/wijzigingsfilter.sh"

fails=0
ok()   { echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

# Draait `classificeer` op een bestandenlijst en vergelijkt de vier uitkomsten met de verwachting.
# $1 = omschrijving, $2 = bestanden (nieuwe regels), $3 = verwachte uitkomsten (nieuwe regels),
# $4 = optioneel 'true' voor een bot-PR.
verwacht() {
  local omschrijving=$1 bestanden=$2 verwachting=$3 bot=${4:-false}

  local gekregen
  gekregen=$(classificeer "$bestanden" "$bot" 2>/dev/null)

  if [ "$gekregen" = "$verwachting" ]; then
    ok "$omschrijving"
  else
    fout "$omschrijving
  verwacht: $(tr '\n' ' ' <<<"$verwachting")
  gekregen: $(tr '\n' ' ' <<<"$gekregen")"
  fi
}

ALLES_UIT='run=false
deploy=false
demo-only=true
fuzz=false'

ALLES_AAN='run=true
deploy=true
demo-only=false
fuzz=true'

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

# Grenswaarde: het patroon is verankerd, dus een pad dat er alleen op eindigt telt als code.
verwacht "een bronbestand met een meta-naam in een submap" 'services/berichtenmagazijn/src/main/resources/LICENSE' "$ALLES_AAN"

# --- code -----------------------------------------------------------------------------------
verwacht "productiecode" 'services/berichtenmagazijn/src/main/kotlin/Bericht.kt' "$ALLES_AAN"

verwacht "documentatie naast code — code wint" 'README.md
libraries/fbs-common/src/main/kotlin/Problem.kt' "$ALLES_AAN"

# --- wel toetsen, niet uitrollen ------------------------------------------------------------
verwacht "de Bruno-collectie" 'bruno/berichtenmagazijn/ophalen.bru' 'run=true
deploy=false
demo-only=false
fuzz=false'

verwacht "een workflow die uitsluitend toetst" '.github/workflows/codeql.yml' 'run=true
deploy=false
demo-only=false
fuzz=false'

verwacht "de fuzz-configuratie — wel toetsen en fuzzen, niet uitrollen" '.clusterfuzzlite/build.sh' 'run=true
deploy=false
demo-only=false
fuzz=true'

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

# --- fail-safe --------------------------------------------------------------------------------
# Een lege lijst betekent "niets vastgesteld", niet "niets te doen": alles draait.
verwacht "lege bestandenlijst" '' 'run=true
deploy=true
demo-only=false
fuzz=false'

if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
