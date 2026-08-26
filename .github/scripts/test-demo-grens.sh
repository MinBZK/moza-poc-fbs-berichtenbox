#!/usr/bin/env bash
# Fixture-tests voor demo-grens.sh en demo-modules.sh. De controle is stil als hij niets vindt, dus
# de dure faalwijze is dat hij niets meer méét: een afwijkende pom-vorm, een lege root, een
# verschoven glob of een stukgelopen parser levert dan een groene run zonder dat er iets
# gecontroleerd is.
#
# Daarom toetst elke assertie naast de exitcode ook de melding. Exitcode 1 betekent zowel "grens
# overschreden" als "niets gemeten"; zonder de melding zijn die twee niet uit elkaar te houden en
# blijft een suite groen terwijl de controle om de verkeerde reden rood is.
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

# Een repository-skelet met alleen een root-pom. Modules worden er los in gezet, zodat elke test
# zijn eigen samenstelling kiest.
#
# `mktemp -d` en geen oplopende teller: deze functie draait in een command-substitutie, en een
# teller die dáár ophoogt is na afloop weer weg — alle fixtures zouden dezelfde map delen en
# elkaars modules zien.
nieuw_repo() {
  local root
  root=$(mktemp -d "$WERKMAP/repo-XXXXXX")

  mkdir -p "$root/demo" "$root/services" "$root/libraries"

  cat > "$root/pom.xml" <<'POM'
<project>
    <groupId>nl.rijksoverheid.moz</groupId>
    <artifactId>moza-poc-fbs-berichtenbox</artifactId>
    <packaging>pom</packaging>
    <modules>
    </modules>
    <dependencies>
    </dependencies>
</project>
POM

  echo "$root"
}

dependency_regels() {
  local a

  for a in "$@"; do
    printf '        <dependency><groupId>nl.rijksoverheid.moz</groupId><artifactId>%s</artifactId></dependency>\n' "$a"
  done
}

# $1 = root, $2 = modulepad (bv. demo/demo-console), $3 = pom-vorm, rest = dependencies.
# De drie vormen bestaan omdat ze alle drie in het wild voorkomen en de parser ze alle drie moet
# aankunnen: een geformatteerde pom, een compact parent-blok op één regel, en een module die
# bewust niet van de reactor-parent erft.
voeg_module() {
  local root=$1 pad=$2 vorm=$3
  shift 3

  local naam=${pad##*/}
  local parent deps

  case "$vorm" in
    meerregelig)
      parent=$'    <parent>\n        <groupId>nl.rijksoverheid.moz</groupId>\n        <artifactId>moza-poc-fbs-berichtenbox</artifactId>\n        <relativePath>../../pom.xml</relativePath>\n    </parent>'
      ;;
    eenregelig)
      parent='    <parent><groupId>nl.rijksoverheid.moz</groupId><artifactId>moza-poc-fbs-berichtenbox</artifactId></parent>'
      ;;
    parent-met-witruimte)
      parent='    <parent ><groupId>nl.rijksoverheid.moz</groupId><artifactId>moza-poc-fbs-berichtenbox</artifactId></parent >'
      ;;
    zonder-artifactid)
      parent='    <parent><artifactId>moza-poc-fbs-berichtenbox</artifactId></parent>'
      ;;
    zonder-parent)
      parent=''
      ;;
    *)
      echo "onbekende pom-vorm: $vorm" >&2

      return 1
      ;;
  esac

  deps=$(dependency_regels "$@")

  mkdir -p "$root/$pad"

  # De vorm zonder eigen artifactId hoort hard te falen; hij mag hier dus niet stilzwijgend een
  # naam krijgen.
  local eigen="    <artifactId>$naam</artifactId>"

  if [ "$vorm" = "zonder-artifactid" ]; then
    eigen=""
  fi

  cat > "$root/$pad/pom.xml" <<POM
<project>
$parent
$eigen
    <dependencies>
$deps    </dependencies>
</project>
POM

  # Élke module in de reactor registreren, zoals in de echte repository: de controles leiden hun
  # modulelijst daaruit af, dus een fixture die alleen de demo-kant registreert zou een gat
  # verbergen in plaats van blootleggen.
  sed -i "s:    </modules>:        <module>$pad</module>\n    </modules>:" "$root/pom.xml"
}

# Haalt een module weer uit de reactor. Nodig zodra een fixture de map verwijdert: een reactor die
# naar een verdwenen module wijst, is een eigen fout en die zou de fixture eerder laten falen dan
# het gedrag dat hij wil toetsen.
verwijder_module() {
  local root=$1 pad=$2

  sed -i "\|<module>$pad</module>|d" "$root/pom.xml"
}

root_dependency() {
  local root=$1
  shift

  local blok="$root/root-deps.xml"

  # Via een bestand en `sed r` in plaats van een `s`-substitutie: die zou `&` en `\` in een
  # artifactId als vervangingsopdracht lezen. Ankeren op de openingstag, want `r` voegt ná de
  # matchende regel in — op de sluittag belandt de dependency buiten het blok.
  dependency_regels "$@" > "$blok"
  sed -i -e "/^    <dependencies>/{r $blok" -e '}' "$root/pom.xml"
}

# $1 = omschrijving, $2 = root, $3 = verwachte exitcode, $4 = patroon dat in de uitvoer moet staan.
toets() {
  local omschrijving=$1 root=$2 verwachte_code=$3 patroon=$4
  local uitvoer code=0

  uitvoer=$(REPO_ROOT="$root" controleer 2>&1) || code=$?

  if [ "$code" -ne "$verwachte_code" ]; then
    fout "$omschrijving — verwacht exitcode $verwachte_code, gekregen $code
  uitvoer: $uitvoer"

    return
  fi

  if ! grep -qF "$patroon" <<<"$uitvoer"; then
    fout "$omschrijving — melding mist '$patroon'
  uitvoer: $uitvoer"

    return
  fi

  ok "$omschrijving"
}

# --- de toegestane richting en het schone geval ---------------------------------------------------
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig fbs-common
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een schone repository gaat door" "$w" 0 "OK: 3 pom('s) van het stelsel"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig fbs-common quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een demo-module mág van het stelsel afhangen" "$w" 0 "OK:"

# --- de verboden richting, per boom ---------------------------------------------------------------
# Los per boom, want met identieke pom's in beide bomen levert elke overtreding twee bevindingen en
# blijft het weghalen van één van de twee globs onzichtbaar.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een service die van een demo-module afhangt" "$w" 1 \
  "services/berichtenuitvraag/pom.xml noemt demo-module 'demo-console'"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig demo-console
toets "een library die van een demo-module afhangt" "$w" 1 \
  "libraries/fbs-common/pom.xml noemt demo-module 'demo-console'"

# De root-pom is de goedkoopste manier om de grens te slechten: zijn <dependencies>-blok wordt door
# élke module geërfd, dus één regel daar zet demo-code op het classpath van het hele stelsel.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
root_dependency "$w" demo-console
toets "de root-pom die van een demo-module afhangt" "$w" 1 \
  "pom.xml noemt demo-module 'demo-console'"

# Een gelijkenis is geen treffer: `demo-console-mock` is een andere module dan `demo-console`, en
# een substring-vergelijking zou hem als overtreding melden zonder dat iemand kan verklaren waarom.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console-mock
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een artifactId dat een demo-naam bevat is geen overtreding" "$w" 0 "OK:"

# Alle overtredingen in één run, niet alleen de eerste: wie er één fixt en opnieuw draait, hoort
# niet verrast te worden door de volgende.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig demo-console
uitvoer=$(REPO_ROOT="$w" controleer 2>&1) || true
[ "$(grep -c 'noemt demo-module' <<<"$uitvoer")" -eq 2 ] \
  && ok "beide overtredingen worden in één run gemeld" \
  || fout "niet alle overtredingen gemeld:
$uitvoer"

# Overerven is de tweede route om de grens heen: een stelsel-module die een demo-module als parent
# neemt, trekt diens hele dependency- en pluginconfiguratie mee. Daarom kijkt de controle naar élke
# artifactId in de pom en niet alleen naar het dependencies-blok.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<artifactId>moza-poc-fbs-berichtenbox</artifactId>:<artifactId>demo-console</artifactId>:' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "een stelsel-module die van een demo-module erft" "$w" 1 "noemt demo-module 'demo-console'"

# XML mag een element over meerdere regels spreiden en witruimte binnen een tag is betekenisloos —
# Maven resolvet zo'n dependency gewoon. Een regel-gebaseerde regex laat precies die vorm passeren,
# en dat is een bypass die elke formatter vanzelf produceert.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
python3 - "$w/services/berichtenuitvraag/pom.xml" <<'PYEOF'
import sys
pad = sys.argv[1]
inhoud = open(pad).read().replace(
    "<artifactId>quarkus-rest</artifactId>",
    "<artifactId>\n            demo-console\n        </artifactId>")
open(pad, "w").write(inhoud)
PYEOF
toets "een artifactId over meerdere regels" "$w" 1 "noemt demo-module 'demo-console'"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<artifactId>quarkus-rest</artifactId>:<artifactId> demo-console </artifactId>:' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "een artifactId met witruimte eromheen" "$w" 1 "noemt demo-module 'demo-console'"

# Windows-regeleinden gecombineerd met een gespreid element: zonder normalisatie blijft de CR aan
# de naam plakken en matcht de vergelijking nooit — stil, want er komt gewoon een naam uit.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
python3 - "$w/services/berichtenuitvraag/pom.xml" <<'PYEOF'
import sys
pad = sys.argv[1]
inhoud = open(pad).read().replace(
    "<artifactId>quarkus-rest</artifactId>",
    "<artifactId>\n            demo-console\n        </artifactId>")
open(pad, "w", newline="\r\n").write(inhoud)
PYEOF
toets "een gespreid element in een pom met CRLF-regeleinden" "$w" 1 "noemt demo-module 'demo-console'"

# Dezelfde vorm aan de demo-kant: leest de parser de modulenaam met een CR eraan vast, dan matcht
# geen enkele dependency er ooit mee en meldt de controle groen over een module die feitelijk
# buiten de bewaking valt.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
python3 - "$w/demo/demo-console/pom.xml" <<'PYEOF'
import sys
pad = sys.argv[1]
inhoud = open(pad).read().replace(
    "<artifactId>demo-console</artifactId>",
    "<artifactId>\n        demo-console\n    </artifactId>")
open(pad, "w", newline="\r\n").write(inhoud)
PYEOF
toets "de modulenaam zelf gespreid en met CRLF" "$w" 1 "noemt demo-module 'demo-console'"

# Witruimte in de tag zelf (`<artifactId >`) is geldige XML en Maven resolvet het gewoon. Het
# parent-blok vangt die vorm al af; de tag die de poort bewaakt hoort dat net zo goed te doen.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<artifactId>quarkus-rest</artifactId>:<artifactId >demo-console</artifactId >:' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "witruimte in de artifactId-tag" "$w" 1 "noemt demo-module 'demo-console'"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<artifactId>demo-console</artifactId>:<artifactId >demo-console</artifactId >:' \
  "$w/demo/demo-console/pom.xml"
toets "witruimte in de artifactId-tag van de modulenaam" "$w" 1 "noemt demo-module 'demo-console'"

# Een pom die geen enkele artifactId oplevert is onleesbaar of niet te parsen. Hem stil overslaan
# terwijl hij wél in de telling zit, is de "OK terwijl er niets gemeten is" die deze controle moet
# uitsluiten.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
: > "$w/services/berichtenuitvraag/pom.xml"
toets "een onleesbare stelsel-pom valt niet stil weg" "$w" 1 "niet als XML te lezen"

# Geldige XML zonder één artifactId is óók "niets gemeten": zo'n pom stil overslaan terwijl hij wel
# in de telling zit, is precies de OK-zonder-meting die deze controle moet uitsluiten.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
printf '<project></project>\n' > "$w/services/berichtenuitvraag/pom.xml"
toets "een stelsel-pom zonder artifactId valt niet stil weg" "$w" 1 "geen enkele artifactId gelezen"

# De root-guard moet dezelfde verzameling meten als de scan: telt hij pom's mee die de scan
# pruned (uit target/), dan is hij tevreden over een root waar niets gecontroleerd is.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
mkdir -p "$w/libraries/target"
printf '<project><artifactId>restant</artifactId></project>\n' > "$w/libraries/target/pom.xml"
toets "een pom in target/ telt niet als gecontroleerde root" "$w" 1 "geen enkele pom onder libraries/"

# --- cardinaliteit: nul, één, meerdere -----------------------------------------------------------
# Met één demo-module verbergt de suite of de controle "de eerste/enige" pakt of écht per module
# discrimineert; de overtreding wijst hier daarom naar de tweede.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" demo/magazijn-simulator meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig magazijn-simulator
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "de tweede van twee demo-modules wordt óók bewaakt" "$w" 1 \
  "noemt demo-module 'magazijn-simulator'"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" demo/magazijn-simulator meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "twee schone demo-modules tellen allebei mee" "$w" 0 "geen van de 2 demo-module(s)"

w=$(nieuw_repo)
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een lege demo-root meldt dat er niets gemeten is" "$w" 1 \
  "geen enkele <module>demo/"

# --- pom-vormen ------------------------------------------------------------------------------------
# Een compact parent-blok op één regel liet een regel-gebaseerde parser de párent-artifactId pakken;
# de echte module viel dan stil buiten de controle terwijl de melding groen bleef.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console eenregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een demo-pom met een eenregelig parent-blok" "$w" 1 \
  "noemt demo-module 'demo-console'"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console zonder-parent quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een demo-pom zonder parent-blok" "$w" 1 \
  "noemt demo-module 'demo-console'"

# De parent-artifactId staat in élke module-pom en is géén demo-module. Zonder de parent-afbakening
# zou hij als demo-artifactId meetellen en zou iedere module zichzelf rood maken.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig moza-poc-fbs-berichtenbox
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "de parent-artifactId telt niet als demo-module" "$w" 0 "OK:"

# Een parent-tag mag witruimte of een attribuut dragen. Matcht de parser alleen de letterlijke
# `<parent>`, dan geldt de parent-artifactId als die van de module en passeert een dependency op de
# échte module ongezien.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console parent-met-witruimte quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "een parent-tag met witruimte" "$w" 1 "noemt demo-module 'demo-console'"

# Twee dependencies op één regel: een regel-gebaseerde extractie levert er hoogstens één op, en dan
# verdwijnt juist de eerste.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<dependency><groupId>nl.rijksoverheid.moz</groupId><artifactId>quarkus-rest</artifactId></dependency>:<dependency><artifactId>demo-console</artifactId></dependency><dependency><artifactId>quarkus-rest</artifactId></dependency>:' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "twee dependencies op één regel" "$w" 1 "noemt demo-module 'demo-console'"

# Een reactor-module buiten de bekende roots valt buiten élke lijst in de keten — de scan, de
# CodeQL-lussen, de jacoco-globs en de fuzz-allowlist — zonder dat er iets roods verschijnt.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
mkdir -p "$w/platform/kern"
printf '<project><artifactId>kern</artifactId></project>\n' > "$w/platform/kern/pom.xml"
sed -i 's:    </modules>:        <module>platform/kern</module>\n    </modules>:' "$w/pom.xml"
toets "een reactor-module buiten de bekende roots" "$w" 1 "buiten de bekende roots"

# `find` levert bij een onleesbare submap gedeeltelijke uitvoer én een foutstatus. Genegeerd zou dat
# een halve boom opleveren die als volledige meting doorgaat — met een overtreding die niemand ziet.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
voeg_module "$w" libraries/geheim meerregelig demo-console
# De reactor mag er niet meer naar wijzen: het lezen van díe pom faalt dan eerder dan het
# doorzoeken van de root, en dan toetst de fixture een andere guard dan bedoeld.
verwijder_module "$w" libraries/geheim
chmod 000 "$w/libraries/geheim"

if [ "$(id -u)" -eq 0 ]; then
  # root leest door een 000-map heen, dus deze fixture kan het gedrag daar niet uitlokken.
  chmod 755 "$w/libraries/geheim"
  ok "onleesbare submap (niet uit te lokken als root)"
else
  toets "een onleesbare submap onder een root" "$w" 1 "Permission denied"
  chmod 755 "$w/libraries/geheim"
fi

# Maven sluit op de effective POM: `${demo.art}` wordt daar een gewone naam. De ruwe XML laat dan
# niets zien, dus zo'n artifactId is niet statisch te controleren en doorlaten zou stil zijn.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<artifactId>quarkus-rest</artifactId>:<artifactId>${demo.art}</artifactId>:' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "property-interpolatie in een artifactId van het stelsel" "$w" 1 "property-interpolatie"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's:<artifactId>demo-console</artifactId>:<artifactId>demo-${suffix}</artifactId>:' \
  "$w/demo/demo-console/pom.xml"
toets "property-interpolatie in de naam van een demo-module" "$w" 1 "property-interpolatie"

# Een gespreide <module>-regel is geldige XML die Maven gewoon bouwt. Met een regex-lezing valt zo'n
# module buiten élke controle in de keten; met een uitgecommentarieerde zou hij juist meetellen en
# een geldige repository rood maken.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
mkdir -p "$w/nieuweroot/foo"
printf '<project><artifactId>foo</artifactId></project>\n' > "$w/nieuweroot/foo/pom.xml"
sed -i 's:    </modules>:        <module>\n            nieuweroot/foo\n        </module>\n    </modules>:' "$w/pom.xml"
toets "een gespreide module-registratie buiten de bekende roots" "$w" 1 "buiten de bekende roots"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's|    </modules>|        <!-- tijdelijk eruit: <module>tooling/generator</module> -->\n    </modules>|' "$w/pom.xml"
toets "een uitgecommentarieerde module telt niet mee" "$w" 0 "OK:"

# Een module mag zélf modules declareren, en die staan niet in de root-pom. Wie alleen daar kijkt,
# mist precies de module die niemand mist — en die valt dan buiten élke controle in de keten.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
mkdir -p "$w/tooling/generator"
printf '<project><artifactId>generator</artifactId><dependencies><dependency><artifactId>demo-console</artifactId></dependency></dependencies></project>\n' \
  > "$w/tooling/generator/pom.xml"
sed -i 's|    <dependencies>|    <modules><module>../../tooling/generator</module></modules>\n    <dependencies>|' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "een geneste module buiten de bekende roots" "$w" 1 "buiten de bekende roots"

# Maven lost `${…}` in een modulepad op tegen de properties; de ruwe XML laat dan niet zien welke
# module er gebouwd wordt. Doorlaten zou die module buiten élke controle in de keten houden — en
# in een profiel viel dat sinds kort stil weg in plaats van hard.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's|</project>|    <properties><t>tooling/verstopt</t></properties>\n    <profiles><profile><activation><activeByDefault>true</activeByDefault></activation><modules><module>${t}</module></modules></profile></profiles>\n</project>|' \
  "$w/pom.xml"
toets "property-interpolatie in een modulepad" "$w" 1 "property-interpolatie in een modulepad"

# Staat dezelfde module in het gewone blok én in een profiel, dan telt de strengste eis. Anders
# bepaalt de volgorde in het XML-bestand of een ontbrekende module fataal is of wordt overgeslagen.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's|</project>|    <profiles><profile><modules><module>services/spook</module></modules></profile></profiles>\n</project>|' "$w/pom.xml"
sed -i 's|    </modules>|        <module>services/spook</module>\n    </modules>|' "$w/pom.xml"
toets "een module in beide blokken volgt de strengste eis" "$w" 1 "bestaat niet"

# Een module die in de ene pom optioneel is en in de andere verplicht, moet de verplichte
# declaratie nog steeds laten falen. Anders bepaalt de volgorde van de wandeling of een echte
# reactor-fout gemeld wordt of stil verdwijnt.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's|</project>|    <profiles><profile><modules><module>services/spook</module></modules></profile></profiles>\n</project>|' "$w/pom.xml"
sed -i 's|    <dependencies>|    <modules><module>../spook</module></modules>\n    <dependencies>|' \
  "$w/services/berichtenuitvraag/pom.xml"
toets "optioneel in de ene pom, verplicht in de andere" "$w" 1 "bestaat niet"

# --- de meting zelf ---------------------------------------------------------------------------------
# Verdwijnt libraries/ of services/ (hernoemd, geherstructureerd, verkeerde REPO_ROOT), dan zou de
# lus nul keer draaien en de OK-regel alsnog verschijnen.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
rmdir "$w/services" "$w/libraries"
toets "nul stelsel-modules meldt dat er niets gemeten is" "$w" 1 "geen enkele pom onder"

# Eén root is genoeg om een totaalteller boven zijn drempel te houden; die helft van het stelsel
# blijft dan ongemeten terwijl de melding groen is.
for weg in services libraries; do
  w=$(nieuw_repo)
  voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
  voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
  voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest

  case "$weg" in
    services) verwijder_module "$w" services/berichtenuitvraag ;;
    libraries) verwijder_module "$w" libraries/fbs-common ;;
  esac

  rm -rf "${w:?}/$weg"
  toets "alleen $weg/ weg meldt dat die helft niet gemeten is" "$w" 1 "geen enkele pom onder $weg/"
done

# Een demo-pom waar geen artifactId uit komt mag niet geruisloos uit de lijst vallen.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
printf '<project><parent><artifactId>p</artifactId></parent></project>\n' > "$w/demo/demo-console/pom.xml"
toets "een demo-pom zonder artifactId valt niet stil weg" "$w" 1 "geen artifactId gevonden"

# Met twéé demo-modules blijft de lijst gevuld als de tweede niet parseert; alleen een expliciet
# doorgegeven exitcode maakt dat nog rood.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" demo/magazijn-simulator zonder-artifactid
voeg_module "$w" services/berichtenuitvraag meerregelig magazijn-simulator
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
toets "de tweede demo-pom zonder artifactId valt niet stil weg" "$w" 1 "geen artifactId gevonden"

# --- demo-modules.sh: reactor tegenover schijf ------------------------------------------------------
lijst_toets() {
  local omschrijving=$1 root=$2 verwachte_code=$3 patroon=$4
  local uitvoer code=0

  uitvoer=$(REPO_ROOT="$root" demo_modules 2>&1) || code=$?

  if [ "$code" -eq "$verwachte_code" ] && grep -qF "$patroon" <<<"$uitvoer"; then
    ok "$omschrijving"
  else
    fout "$omschrijving — exitcode $code (verwacht $verwachte_code)
  uitvoer: $uitvoer"
  fi
}

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" demo/magazijn-simulator meerregelig quarkus-kotlin
lijst_toets "de lijst is gesorteerd en compleet" "$w" 0 "demo/demo-console
demo/magazijn-simulator"

# Registratievolgorde is geen sorteervolgorde: zonder de sort aan beide kanten lopen reactor en
# schijf uiteen zodra iemand een module niet achteraan het alfabet toevoegt, en dan valt de hele
# grensbewaking én de demo-shard om op een verschil dat er niet is.
w=$(nieuw_repo)
voeg_module "$w" demo/zeta meerregelig quarkus-kotlin
voeg_module "$w" demo/alfa meerregelig quarkus-kotlin
lijst_toets "een niet-alfabetische registratievolgorde levert dezelfde lijst" "$w" 0 "demo/alfa
demo/zeta"

# De demo-shard leidt zijn modulelijst uit de reactor af. Een module die alleen op schijf staat,
# wordt daardoor niet gebouwd en niet getest, terwijl een PR die hem raakt wél naar `demo-only`
# scopet — de run meldt dan groen over code die niemand heeft aangeraakt. Andersom is het een
# reactor-verwijzing zonder module, en dan faalt Maven zelf.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
mkdir -p "$w/demo/vergeten-module"
printf '<project><artifactId>vergeten</artifactId></project>\n' > "$w/demo/vergeten-module/pom.xml"
lijst_toets "een demo-module buiten de reactor valt op" "$w" 1 "lopen uiteen"

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
rm -rf "$w/demo/demo-console"
lijst_toets "een reactor-verwijzing zonder module valt op" "$w" 1 "bestaat niet"

# --- de parser rechtstreeks ---------------------------------------------------------------------------
# `--packaging` en de volgorde van `--reactor` hebben geen eigen pad door `controleer`, terwijl de
# workflows er wél op sluiten: detekt.yml en codeql.yml slaan een aggregator over op wat hier
# uitkomt, en een niet-deterministische volgorde laat de reactor/schijf-vergelijking spoken zien.
parser_toets() {
  local omschrijving=$1 verwacht=$2
  shift 2

  local gekregen
  gekregen=$(python3 "$HERE/pom-artifactids.py" "$@" 2>&1) || true

  if [ "$gekregen" = "$verwacht" ]; then
    ok "$omschrijving"
  else
    fout "$omschrijving
  verwacht: $verwacht
  gekregen: $gekregen"
  fi
}

w=$(nieuw_repo)
parser_toets "packaging van een aggregator" "pom" --packaging "$w/pom.xml"

voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
parser_toets "packaging zonder element is jar" "jar" --packaging "$w/demo/demo-console/pom.xml"

printf '<project><packaging>\n    pom\n</packaging></project>\n' > "$w/demo/demo-console/pom.xml"
parser_toets "packaging over meerdere regels" "pom" --packaging "$w/demo/demo-console/pom.xml"

# Alleen het directe kind van <project> telt: een <packaging> in een profiel of in een
# plugin-configuratie gaat over iets anders, en die voor de module aanzien zou een gewone module
# als aggregator laten wegvallen uit de volledigheidscontroles.
printf '<project><profiles><profile><build><plugins><plugin><configuration><packaging>pom</packaging></configuration></plugin></plugins></build></profile></profiles></project>\n' \
  > "$w/demo/demo-console/pom.xml"
parser_toets "packaging in een profiel telt niet" "jar" --packaging "$w/demo/demo-console/pom.xml"

# Registratievolgorde is geen uitvoervolgorde: zonder sortering ziet de vergelijking van reactor en
# schijf een verschil dat er niet is.
w=$(nieuw_repo)
voeg_module "$w" services/zeta meerregelig quarkus-rest
voeg_module "$w" demo/alfa meerregelig quarkus-kotlin
parser_toets "de reactorlijst is gesorteerd" "demo/alfa
services/zeta" --reactor "$w/pom.xml"

# Een profiel met activeByDefault of een file-activation draait zonder `-P`, dus Maven bouwt zo'n
# module gewoon — en dan hoort hij ook onder de controles te vallen.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
mkdir -p "$w/tooling/generator"
printf '<project><artifactId>generator</artifactId><dependencies><dependency><artifactId>demo-console</artifactId></dependency></dependencies></project>\n' \
  > "$w/tooling/generator/pom.xml"
sed -i 's|</project>|    <profiles><profile><activation><activeByDefault>true</activeByDefault></activation><modules><module>tooling/generator</module></modules></profile></profiles>\n</project>|' \
  "$w/pom.xml"
toets "een module in een actief profiel telt mee in de reactor" "$w" 1 "buiten de bekende roots"

# Maar het bestaan is niet af te dwingen: een release-only profiel mag naar een map wijzen die er
# in een gewone checkout niet is. Dat hard laten falen zou een groene build rood maken.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's|</project>|    <profiles><profile><modules><module>tooling/alleen-bij-release</module></modules></profile></profiles>\n</project>|' \
  "$w/pom.xml"
toets "een profielmodule die niet bestaat wordt overgeslagen" "$w" 0 "OK:"

# Uit het gewone <modules>-blok is het wél een echte reactor-fout; Maven meldt die zelf ook.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
sed -i 's|    </modules>|        <module>services/spook</module>\n    </modules>|' "$w/pom.xml"
toets "een ontbrekende module uit het gewone blok faalt hard" "$w" 1 "bestaat niet"

# Diezelfde profielmodule blijft wél gedekt zodra hij op schijf staat: de grensbewaking scant de
# roots van schijf, niet alleen de reactor.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
mkdir -p "$w/services/alleen-bij-release"
printf '<project><artifactId>release-hulp</artifactId><dependencies><dependency><artifactId>demo-console</artifactId></dependency></dependencies></project>\n' \
  > "$w/services/alleen-bij-release/pom.xml"
sed -i 's|</project>|    <profiles><profile><modules><module>services/alleen-bij-release</module></modules></profile></profiles>\n</project>|' \
  "$w/pom.xml"
toets "een profielmodule op schijf blijft onder de grensbewaking vallen" "$w" 1 \
  "services/alleen-bij-release/pom.xml noemt demo-module 'demo-console'"

# --- hygiëne ------------------------------------------------------------------------------------------
w=$(nieuw_repo)
rm -rf "${w:?}/demo"
lijst_toets "een ontbrekende demo-root meldt wat er mist" "$w" 1 "bestaat niet"

# De roots staan op twee plekken ingetypt: hier en in de module-lussen van codeql.yml. Lopen ze
# uiteen, dan dekt de ene guard een root die de andere overslaat — en dat is stil, want beide
# blijven groen over wat ze wél zien. Béíde lussen toetsen: codeql.yml controleert de classes én de
# geëxtraheerde bronbestanden, en met alleen de unieke waarden zou één gemuteerde lus wegvallen
# tegen de andere.
codeql_lussen=$( { grep -oE 'for module in [a-z*/ ]+; do' "$HERE/../workflows/codeql.yml" || true; } \
  | sed 's/for module in //; s/; do//; s:/\*::g')
eigen_roots="${STELSEL_ROOTS[*]} demo"
codeql_aantal=$(grep -c . <<<"$codeql_lussen" || true)
codeql_afwijkend=$(grep -cvxF "$eigen_roots" <<<"$codeql_lussen" || true)

if [ "$codeql_aantal" -ne 2 ]; then
  fout "codeql.yml heeft $codeql_aantal modulelussen in plaats van 2; deze kruiscontrole meet niet wat hij hoort te meten"
elif [ "$codeql_afwijkend" -ne 0 ]; then
  fout "een modulelus in codeql.yml wijkt af van de grensbewaking ($eigen_roots): $(tr '\n' ' ' <<<"$codeql_lussen")"
else
  ok "beide modulelussen in codeql.yml hanteren dezelfde roots als de grensbewaking"
fi

w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
mkdir -p "$w/demo/demo-console/diep"
chmod 000 "$w/demo/demo-console/diep"

if [ "$(id -u)" -eq 0 ]; then
  chmod 755 "$w/demo/demo-console/diep"
  ok "onleesbare submap onder demo/ (niet uit te lokken als root)"
else
  lijst_toets "een onleesbare submap onder demo/" "$w" 1 "niet volledig te doorzoeken"
  chmod 755 "$w/demo/demo-console/diep"
fi

for script in demo-grens.sh demo-modules.sh; do
  [ -x "$HERE/$script" ] \
    && ok "$script is uitvoerbaar" \
    || fout "$script is niet uitvoerbaar; de directe aanroep in CI faalt"
done

# De BASH_SOURCE-guard bestaat voor deze suite: breekt hij, dan draait de controle tegen de echte
# repository zodra een test het script sourcet, en vervuilt dat de uitvoer van elke fixture.
uitvoer=$(source "$HERE/demo-grens.sh" 2>&1)
[ -z "$uitvoer" ] \
  && ok "sourcen voert de controle niet uit" \
  || fout "sourcen van demo-grens.sh voert de controle uit: $uitvoer"

# --- de echte repository ---------------------------------------------------------------------------
# De fixtures toetsen het gedrag; deze regel toetst de repository zoals hij nu is. Faalt hij, dan is
# de grens daadwerkelijk overschreden.
code=0
uitvoer=$(controleer 2>&1) || code=$?
[ "$code" -eq 0 ] \
  && ok "de repository zelf respecteert de grens" \
  || fout "de repository zelf overschrijdt de grens:
$uitvoer"

# --- de suite bewaakt zichzelf ------------------------------------------------------------------
# Zonder deze zelftest blijft een suite waaruit de vergelijking is weggevallen groen mét het volle
# aantal OK-regels: ci-scripts.yml leest de ASSERTIES-regel die deze suite zelf rapporteert, en die
# telt geslaagde asserties — niet of er nog iets vergeleken wordt.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig demo-console
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest

if (fails=0; toets zelftest "$w" 0 "OK:" >/dev/null 2>&1; [ "$fails" -eq 1 ]); then
  ok "toets merkt een afwijkende exitcode op"
else
  fout "toets meldt geen afwijking meer; de suite meet niets"
fi

if (fails=0; toets zelftest "$w" 1 "een melding die er niet staat" >/dev/null 2>&1; [ "$fails" -eq 1 ]); then
  ok "toets merkt een afwijkende melding op"
else
  fout "toets vergelijkt de melding niet meer; exitcode 1 blijft dan dubbelzinnig"
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
