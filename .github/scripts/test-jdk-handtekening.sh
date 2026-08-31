#!/usr/bin/env bash
# Fixture-tests voor jdk-handtekening.py. De controle is stil als hij niets vindt, dus de dure
# faalwijze is dat hij niets meer méét: een verschoven map, een onleesbare workflow of een
# stapvorm die de parser niet herkent levert dan een groene run zonder dat er iets gecontroleerd is.
#
# Daarom toetst elke assertie naast de exitcode ook de melding. Exitcode 1 betekent zowel "stap
# zonder vlag" als "niets gemeten"; zonder de melding zijn die twee niet uit elkaar te houden en
# blijft deze suite groen terwijl de guard om de verkeerde reden rood is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/jdk-handtekening.py"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Elke fixture zijn eigen map: `mktemp -d` en geen oplopende teller, want deze functie draait in
# een command-substitutie en een teller die daar ophoogt is na afloop weer weg — alle fixtures
# zouden dan dezelfde map delen en elkaars workflows zien.
nieuwe_map() {
  mktemp -d "$WERKMAP/wf.XXXXXX"
}

# Draait de guard en legt uitvoer én exitcode vast. `|| STATUS=$?` en niet `set +e`: een
# onverwachte fout in de rest van de suite moet nog steeds afbreken.
draai() {
  UITVOER=$(python3 "$GUARD" "$1" 2>&1) && STATUS=0 || STATUS=$?
}

# Eén setup-java-stap, met het meegegeven with-blok eronder.
stap() {
  printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc123\n        with:\n%s\n' "$1"
}

verwacht_ok() {
  local naam=$1 map=$2 fragment=$3

  draai "$map"

  if [ "$STATUS" -ne 0 ]; then
    fout "$naam: verwachtte exitcode 0, kreeg $STATUS — $UITVOER"
  elif ! grep -qF "$fragment" <<<"$UITVOER"; then
    fout "$naam: melding bevat '$fragment' niet — $UITVOER"
  else
    ok "$naam"
  fi
}

verwacht_fout() {
  local naam=$1 map=$2 fragment=$3

  draai "$map"

  if [ "$STATUS" -eq 0 ]; then
    fout "$naam: verwachtte een fout, kreeg exitcode 0 — $UITVOER"
  elif ! grep -qF "$fragment" <<<"$UITVOER"; then
    fout "$naam: melding bevat '$fragment' niet — $UITVOER"
  else
    ok "$naam"
  fi
}

# --- de vlag zelf ------------------------------------------------------------------------------

map=$(nieuwe_map)
stap '          distribution: temurin
          verify-signature: true' > "$map/goed.yml"
verwacht_ok "vlag aan wordt geaccepteerd" "$map" "alle 1 actions/setup-java-stap(pen)"

map=$(nieuwe_map)
stap '          verify-signature: "true"' > "$map/gequoteerd.yml"
verwacht_ok "gequoteerde 'true' telt als aan" "$map" "OK:"

map=$(nieuwe_map)
stap '          verify-signature: TRUE' > "$map/hoofdletters.yml"
verwacht_ok "TRUE telt als aan" "$map" "OK:"

map=$(nieuwe_map)
stap '          distribution: temurin' > "$map/zonder.yml"
verwacht_fout "ontbrekende vlag is een bevinding" "$map" 'zonder `verify-signature: true`'

map=$(nieuwe_map)
stap '          verify-signature: false' > "$map/uit.yml"
verwacht_fout "vlag op false is een bevinding" "$map" 'zonder `verify-signature: true`'

# Een expressie kan naar false evalueren; wat eruit komt is hier niet te zien. Zou die als "aan"
# tellen, dan is de guard met één `${{ }}` te omzeilen zonder dat er iets roods verschijnt.
map=$(nieuwe_map)
stap '          verify-signature: ${{ inputs.verifieer }}' > "$map/expressie.yml"
verwacht_fout "een expressie telt niet als aan" "$map" 'zonder `verify-signature: true`'

map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc123\n' > "$map/geen-with.yml"
verwacht_fout "stap zonder with-blok is een bevinding" "$map" 'zonder `verify-signature: true`'

# --- de melding wijst de stap aan --------------------------------------------------------------

map=$(nieuwe_map)
printf 'jobs:\n  publiceer:\n    steps:\n      - uses: actions/checkout@abc\n      - uses: actions/setup-java@abc123\n        with:\n          verify-signature: true\n      - uses: actions/setup-java@abc123\n' > "$map/tweede.yml"
draai "$map"

if [ "$STATUS" -eq 0 ]; then
  fout "de melding wijst de stap aan: verwachtte een fout"
elif grep -qF "job 'publiceer' stap 3" <<<"$UITVOER" && grep -qF "tweede.yml" <<<"$UITVOER"; then
  ok "de melding noemt bestand, job en stapnummer"
else
  fout "de melding wijst de stap aan: '$UITVOER' noemt niet bestand + job + stapnummer"
fi

if grep -qF "stap 2" <<<"$UITVOER"; then
  fout "alleen de stap zonder vlag wordt gemeld: stap 2 staat er ook in — $UITVOER"
else
  ok "de stap mét vlag wordt niet gemeld"
fi

# --- cardinaliteit: nul, één, meerdere ---------------------------------------------------------

map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/checkout@abc\n' > "$map/geen-java.yml"
verwacht_fout "nul setup-java-stappen faalt in plaats van stil groen" "$map" "bewaakt niets meer"

map=$(nieuwe_map)
stap '          verify-signature: true' > "$map/een.yml"
verwacht_ok "één stap wordt geteld" "$map" "alle 1 "

# Meerdere stappen, verdeeld over meerdere jobs én meerdere bestanden: een guard die alleen de
# eerste job of het eerste bestand leest, telt hier te laag en valt door de mand.
map=$(nieuwe_map)
printf 'jobs:\n  a:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n  b:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n' > "$map/veel.yml"
stap '          verify-signature: true' > "$map/nog-een.yaml"
verwacht_ok "meerdere stappen over meerdere jobs en bestanden worden alle geteld" "$map" "alle 4 "

# --- de guard meet wel degelijk ----------------------------------------------------------------

map=$(nieuwe_map)
verwacht_fout "een lege map faalt in plaats van stil groen" "$map" "meet niets"

verwacht_fout "een niet-bestaande map faalt" "$WERKMAP/bestaat-niet" "is geen map"

map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n   steps:\n  - onmogelijk\n     inspringen\n' > "$map/kapot.yml"
verwacht_fout "onleesbare YAML faalt in plaats van te worden overgeslagen" "$map" "niet gecontroleerd"

map=$(nieuwe_map)
printf 'gewoon een tekstregel\n' > "$map/geen-mapping.yml"
verwacht_fout "een workflow die geen mapping is, faalt" "$map" "geen YAML-mapping"

# --- afbakening: wat de guard wel en niet moet zien --------------------------------------------

# Een ongepinde `uses` zonder `@` is de slechtste variant; die overslaan zou de guard laten
# zwijgen over precies de stap die de meeste aandacht verdient.
map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java\n' > "$map/ongepind.yml"
verwacht_fout "een ongepinde setup-java telt mee" "$map" 'zonder `verify-signature: true`'

# Een andere action met setup-java als naamprefix mag de telling niet vullen: dan zou de
# "nul stappen"-guard te laat aanslaan.
map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java-toolkit@abc\n' > "$map/lijkt-erop.yml"
verwacht_fout "een action met setup-java als naamprefix telt niet mee" "$map" "bewaakt niets meer"

map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-node@abc\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n' > "$map/andere-action.yml"
verwacht_ok "andere actions worden genegeerd" "$map" "alle 1 "

# Een job die een reusable workflow aanroept heeft geen `steps`; de parser mag daar niet op vallen.
map=$(nieuwe_map)
printf 'jobs:\n  hergebruik:\n    uses: ./.github/workflows/iets.yml\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n' > "$map/reusable.yml"
verwacht_ok "een job zonder steps laat de guard niet vallen" "$map" "alle 1 "

# Alleen .yml/.yaml: een notitiebestand naast de workflows mag de meting niet beïnvloeden — niet
# als extra stap, en niet als parse-fout.
map=$(nieuwe_map)
stap '          verify-signature: true' > "$map/echt.yml"
printf 'dit is geen workflow: [\n' > "$map/notities.txt"
verwacht_ok "niet-YAML-bestanden worden overgeslagen" "$map" "alle 1 "

# --- de echte workflows van deze repo ----------------------------------------------------------

# Alles hierboven draait op fixtures; deze assertie is de enige die de guard tegen de werkelijke
# workflows houdt. Zonder haar blijft de suite groen terwijl de repo zelf een stap zonder vlag heeft.
verwacht_ok "de workflows van deze repo dragen de vlag allemaal" "$HERE/../workflows" "OK:"

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
