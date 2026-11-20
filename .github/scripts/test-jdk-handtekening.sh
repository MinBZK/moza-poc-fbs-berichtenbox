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

# De stap die na elke setup-java hoort; als printf-fragment, voor de fixtures die hem nodig hebben.
CACHE='      - uses: ./.github/actions/maven-cache\n        with:\n          scope: bouw\n'

# Eén setup-java-stap, met het meegegeven with-blok eronder, gevolgd door de maven-cache-action.
stap() {
  printf "jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc123\n        with:\n%s\n$CACHE" "$1"
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

# --- de Maven-cache van setup-java blijft uit --------------------------------------------------

map=$(nieuwe_map)
stap '          verify-signature: true
          cache: maven' > "$map/cache.yml"
verwacht_fout "cache: maven is een bevinding" "$map" 'zet `cache:` aan'

# Een expressie kan naar "maven" evalueren; wat eruit komt is hier niet te zien.
map=$(nieuwe_map)
stap '          verify-signature: true
          cache: ${{ inputs.cache }}' > "$map/cache-expressie.yml"
verwacht_fout "een cache-expressie is een bevinding" "$map" 'zet `cache:` aan'

map=$(nieuwe_map)
stap "          verify-signature: true
          cache: ''" > "$map/cache-leeg.yml"
verwacht_ok "een lege cache-waarde zet de cache uit en is in orde" "$map" "alle 1 "

# De runner leest inputs zonder onderscheid in hoofdletters (`INPUT_CACHE`).
map=$(nieuwe_map)
stap '          verify-signature: true
          Cache: maven' > "$map/cache-hoofdletter.yml"
verwacht_fout "Cache: met hoofdletter is een bevinding" "$map" 'zet `cache:` aan'

# setup-java faalt zelf al op `false`; als bevinding vastgelegd zodat een guard die alleen op
# waarheidswaarde toetst (`if met.get("cache")`) niet ongemerkt binnensluipt.
map=$(nieuwe_map)
stap '          verify-signature: true
          cache: false' > "$map/cache-false.yml"
verwacht_fout "cache: false is een bevinding" "$map" 'zet `cache:` aan'

map=$(nieuwe_map)
stap '          verify-signature: true
          cache:' > "$map/cache-null.yml"
verwacht_ok "cache zonder waarde is in orde" "$map" "alle 1 "

# --- de maven-cache-action volgt op setup-java -------------------------------------------------

map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n' > "$map/zonder-action.yml"
verwacht_fout "setup-java zonder maven-cache-action is een bevinding" "$map" "volgt geen"

# Vóór setup-java overschrijft setup-java de settings.xml met de mirror.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$CACHE      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n" > "$map/action-ervoor.yml"
verwacht_fout "maven-cache-action vóór setup-java is een bevinding" "$map" "volgt geen"

# De action in een andere job telt niet: elke job draait op een eigen runner. In job a staat hij
# op een hoger stapnummer dan setup-java in job b, zodat een guard die over jobs heen kijkt hem
# als "later" zou meetellen.
map=$(nieuwe_map)
printf "jobs:\n  a:\n    steps:\n      - uses: actions/checkout@abc\n$CACHE  b:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n" > "$map/andere-job.yml"
verwacht_fout "maven-cache-action in een andere job telt niet" "$map" "job 'b' stap 1: na actions/setup-java volgt geen"

# Een setup-java-stap met handtekening, als printf-fragment.
SJ='      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n'

# Een tweede setup-java na de action overschrijft de settings opnieuw.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ$CACHE$SJ" > "$map/tweede-java-na-cache.yml"
verwacht_fout "een tweede setup-java na de action is een bevinding" "$map" "job 'bouw' stap 3: na actions/setup-java volgt geen"

if grep -qF "stap 1" <<<"$UITVOER"; then
  fout "alleen de tweede setup-java wordt gemeld: stap 1 staat er ook in — $UITVOER"
else
  ok "de setup-java vóór de action wordt niet gemeld"
fi

# Twee JDK's na elkaar en dan de action: die schrijft de settings na allebei.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ$SJ$CACHE" > "$map/twee-java-dan-cache.yml"
verwacht_ok "twee setup-java's vóór de action zijn in orde" "$map" "alle 2 "

map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - uses: ./.github/actions/maven-cache-oud\n" > "$map/lijkt-op-cache.yml"
verwacht_fout "een action met maven-cache als naamprefix telt niet" "$map" "volgt geen"

# Pas na Maven komt de mirror te laat.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - run: ./mvnw -B verify\n$CACHE" > "$map/cache-na-mvnw.yml"
verwacht_fout "de action na een Maven-aanroep is een bevinding" "$map" "volgt geen"

map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - run: |\n          echo voorbereiden\n          mvn -q package\n$CACHE" > "$map/cache-na-mvn.yml"
verwacht_fout "een kale mvn in een meerregelige run telt als Maven-aanroep" "$map" "volgt geen"

# Een pad of woord met mvnw erin is geen aanroep.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - run: cat .mvn/wrapper/maven-wrapper.properties mvnw.log\n$CACHE" > "$map/geen-aanroep.yml"
verwacht_ok "een run zonder Maven-aanroep vóór de action is in orde" "$map" "alle 1 "

# Een action die overgeslagen kan worden terwijl setup-java (en dus Maven) draait.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - uses: ./.github/actions/maven-cache\n        if: false\n        with:\n          scope: bouw\n" > "$map/cache-met-if.yml"
verwacht_fout "een action met een eigen if is een bevinding" "$map" "volgt geen"

map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc\n        if: inputs.java\n        with:\n          verify-signature: true\n      - uses: ./.github/actions/maven-cache\n        if: inputs.java\n        with:\n          scope: bouw\n" > "$map/zelfde-if.yml"
verwacht_ok "action en setup-java onder dezelfde if zijn in orde" "$map" "alle 1 "

# Andersom ook niet: of `inputs.java` altijd waar is, ziet de guard niet, dus hij eist gelijkheid.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc\n        if: inputs.java\n        with:\n          verify-signature: true\n$CACHE" > "$map/alleen-java-if.yml"
verwacht_fout "setup-java met if en de action zonder is een bevinding" "$map" "volgt geen"

# --- de action krijgt een scope ----------------------------------------------------------------

# De runner dwingt `required: true` van een composite action niet af.
map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - uses: ./.github/actions/maven-cache\n" > "$map/zonder-scope.yml"
verwacht_fout "de action zonder scope is een bevinding" "$map" "volgt geen"

map=$(nieuwe_map)
printf "jobs:\n  bouw:\n    steps:\n$SJ      - uses: ./.github/actions/maven-cache\n        with:\n          scope: ' '\n" > "$map/lege-scope.yml"
verwacht_fout "de action met een lege scope is een bevinding" "$map" "volgt geen"

# --- vormen van een Maven-aanroep vóór de action -----------------------------------------------

# Elke vorm een eigen fixture: een regex die één scheidingsteken mist, valt dan op die ene.
maven_vorm() {
  local naam=$1 run=$2 verwachting=$3 map

  map=$(nieuwe_map)
  printf "jobs:\n  bouw:\n    steps:\n$SJ      - run: %s\n$CACHE" "$run" > "$map/vorm.yml"

  if [ "$verwachting" = aanroep ]; then
    verwacht_fout "Maven-aanroep vóór de action: $naam" "$map" "volgt geen"
  else
    verwacht_ok "geen Maven-aanroep: $naam" "$map" "alle 1 "
  fi
}

maven_vorm 'command substitution' "'v=\$(./mvnw -q help:evaluate)'" aanroep
maven_vorm 'backticks' "'v=\`./mvnw -q help:evaluate\`'" aanroep
maven_vorm 'vanuit een submap' "'cd x && ../mvnw -B'" aanroep
maven_vorm 'binnen sh -c' "'sh -c \"mvn -B package\"'" aanroep
maven_vorm 'na &&' "'echo x && mvn'" aanroep
maven_vorm 'direct gevolgd door ;' "'./mvnw;echo klaar'" aanroep
maven_vorm 'gequote commando' "'\"./mvnw\" -B verify'" aanroep
maven_vorm 'mvnd is een andere tool' "'mvnd -B verify'" geen
maven_vorm 'mvnw.cmd is Windows' "'type mvnw.cmd'" geen

# Alle drie de eisen tegelijk geschonden: elk levert een eigen regel, zodat de fix van de ene de
# andere niet pas in de volgende run aan het licht brengt.
map=$(nieuwe_map)
printf 'jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          cache: maven\n' > "$map/alle-drie.yml"
draai "$map"

if [ "$STATUS" -ne 0 ] \
  && grep -qF 'zonder `verify-signature: true`' <<<"$UITVOER" \
  && grep -qF 'zet `cache:` aan' <<<"$UITVOER" \
  && grep -qF 'volgt geen' <<<"$UITVOER"; then
  ok "een stap die alle drie de eisen schendt levert drie bevindingen"
else
  fout "een stap die alle drie de eisen schendt levert drie bevindingen: $STATUS — $UITVOER"
fi

# --- de melding wijst de stap aan --------------------------------------------------------------

map=$(nieuwe_map)
printf "jobs:\n  publiceer:\n    steps:\n      - uses: actions/checkout@abc\n      - uses: actions/setup-java@abc123\n        with:\n          verify-signature: true\n      - uses: actions/setup-java@abc123\n$CACHE" > "$map/tweede.yml"
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
printf "jobs:\n  a:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n$CACHE  b:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n$CACHE" > "$map/veel.yml"
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
printf "jobs:\n  bouw:\n    steps:\n      - uses: actions/setup-node@abc\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n$CACHE" > "$map/andere-action.yml"
verwacht_ok "andere actions worden genegeerd" "$map" "alle 1 "

# Een job die een reusable workflow aanroept heeft geen `steps`; de parser mag daar niet op vallen.
map=$(nieuwe_map)
printf "jobs:\n  hergebruik:\n    uses: ./.github/workflows/iets.yml\n  bouw:\n    steps:\n      - uses: actions/setup-java@abc\n        with:\n          verify-signature: true\n$CACHE" > "$map/reusable.yml"
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
verwacht_ok "de workflows van deze repo dragen de vlag, geen setup-java-cache en de maven-cache-action" "$HERE/../workflows" "OK:"

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
