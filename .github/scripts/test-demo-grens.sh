#!/usr/bin/env bash
# Fixture-tests voor demo-grens.sh. De controle zelf is stil als hij niets vindt, dus de dure
# faalwijze is dat hij niets meer méét: een gewijzigde pom-vorm, een lege demo-wortel of een
# stukgelopen sed levert dan een groene run zonder dat er iets gecontroleerd is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=demo-grens.sh
source "$HERE/demo-grens.sh"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Bouwt een repository-skelet met één demo-module en één module in het stelsel. $1 = de
# artifactId's die de stelsel-module als dependency declareert (spaties gescheiden, mag leeg).
bouw_repo() {
  local afhankelijkheden=${1:-}
  local wortel="$WERKMAP/repo-$RANDOM"
  local dep=""

  for a in $afhankelijkheden; do
    dep+="        <dependency><groupId>nl.rijksoverheid.moz</groupId><artifactId>$a</artifactId></dependency>"$'\n'
  done

  mkdir -p "$wortel/demo/demo-console" "$wortel/services/berichtenuitvraag" "$wortel/libraries/fbs-common"

  cat > "$wortel/demo/demo-console/pom.xml" <<'POM'
<project>
    <parent>
        <groupId>nl.rijksoverheid.moz</groupId>
        <artifactId>moza-poc-fbs-berichtenbox</artifactId>
    </parent>
    <artifactId>demo-console</artifactId>
    <dependencies>
        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-kotlin</artifactId></dependency>
    </dependencies>
</project>
POM

  cat > "$wortel/services/berichtenuitvraag/pom.xml" <<POM
<project>
    <parent>
        <groupId>nl.rijksoverheid.moz</groupId>
        <artifactId>moza-poc-fbs-berichtenbox</artifactId>
    </parent>
    <artifactId>berichtenuitvraag</artifactId>
    <dependencies>
$dep    </dependencies>
</project>
POM

  cp "$wortel/services/berichtenuitvraag/pom.xml" "$wortel/libraries/fbs-common/pom.xml"

  echo "$wortel"
}

# Draait de controle tegen een gebouwd skelet. $1 = omschrijving, $2 = dependencies van de
# stelsel-modules, $3 = verwachte exitcode.
verwacht() {
  local omschrijving=$1 afhankelijkheden=$2 verwachte_code=$3
  local wortel code=0

  wortel=$(bouw_repo "$afhankelijkheden")

  REPO_ROOT="$wortel" controleer >/dev/null 2>&1 || code=$?

  if [ "$code" -eq "$verwachte_code" ]; then
    ok "$omschrijving"
  else
    fout "$omschrijving — verwacht exitcode $verwachte_code, gekregen $code"
  fi
}

verwacht "een schone repository gaat door" '' 0
verwacht "alleen externe afhankelijkheden gaan door" 'quarkus-kotlin jackson-module-kotlin' 0
verwacht "een dependency op een demo-module valt op" 'demo-console' 1
verwacht "de demo-module valt op tussen andere dependencies" 'quarkus-kotlin demo-console fbs-common' 1

# De parent-artifactId staat in élke module-pom en is géén demo-module. Zonder de `</parent>`-
# afbakening zou hij als demo-artifactId meetellen en zou iedere module zichzelf rood maken.
verwacht "de parent-artifactId telt niet als demo-module" 'moza-poc-fbs-berichtenbox' 0

# Zonder demo-modules is er niets te meten; dat moet rood zijn, niet groen.
leeg=$(mktemp -d "$WERKMAP/leeg-XXXX")
mkdir -p "$leeg/demo" "$leeg/services" "$leeg/libraries"
code=0
REPO_ROOT="$leeg" controleer >/dev/null 2>&1 || code=$?
[ "$code" -eq 1 ] \
  && ok "een lege demo-wortel meldt dat er niets gemeten is" \
  || fout "een lege demo-wortel levert exitcode $code; de controle meet dan niets en meldt groen"

# --- de echte repository ------------------------------------------------------------------------
# De fixtures toetsen het gedrag; deze regel toetst de repository zoals hij nu is. Faalt hij, dan
# is de grens daadwerkelijk overschreden.
code=0
uitvoer=$(controleer 2>&1) || code=$?
[ "$code" -eq 0 ] \
  && ok "de repository zelf respecteert de grens" \
  || fout "de repository zelf overschrijdt de grens:
$uitvoer"

# --- de suite bewaakt zichzelf ------------------------------------------------------------------
# Zonder deze zelftest blijft een suite waaruit de vergelijking is weggevallen groen mét het volle
# aantal OK-regels: de teller in ci-scripts.yml telt geprinte regels, geen vergelijkingen.
if (fails=0; verwacht zelftest 'demo-console' 0 >/dev/null 2>&1; [ "$fails" -eq 1 ]); then
  ok "verwacht merkt een afwijking op"
else
  fout "verwacht meldt geen afwijking meer; de suite meet niets"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "$fails test(s) gefaald." >&2
fi

# Door ci-scripts.yml gelezen: een suite die stilletjes minder toetst, valt daar door de mand.
echo "ASSERTIES=$geslaagd"

exit $((fails > 0))
