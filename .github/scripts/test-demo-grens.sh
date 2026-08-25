#!/usr/bin/env bash
# Fixture-tests voor demo-grens.sh en demo-modules.sh. De controle is stil als hij niets vindt, dus
# de dure faalwijze is dat hij niets meer méét: een afwijkende pom-vorm, een lege wortel, een
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
  local wortel
  wortel=$(mktemp -d "$WERKMAP/repo-XXXXXX")

  mkdir -p "$wortel/demo" "$wortel/services" "$wortel/libraries"

  cat > "$wortel/pom.xml" <<'POM'
<project>
    <groupId>nl.rijksoverheid.moz</groupId>
    <artifactId>moza-poc-fbs-berichtenbox</artifactId>
    <modules>
    </modules>
    <dependencies>
    </dependencies>
</project>
POM

  echo "$wortel"
}

dependency_regels() {
  local a

  for a in "$@"; do
    printf '        <dependency><groupId>nl.rijksoverheid.moz</groupId><artifactId>%s</artifactId></dependency>\n' "$a"
  done
}

# $1 = wortel, $2 = modulepad (bv. demo/demo-console), $3 = pom-vorm, rest = dependencies.
# De drie vormen bestaan omdat ze alle drie in het wild voorkomen en de parser ze alle drie moet
# aankunnen: een geformatteerde pom, een compact parent-blok op één regel, en een module die
# bewust niet van de reactor-parent erft.
voeg_module() {
  local wortel=$1 pad=$2 vorm=$3
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

  mkdir -p "$wortel/$pad"

  # De vorm zonder eigen artifactId hoort hard te falen; hij mag hier dus niet stilzwijgend een
  # naam krijgen.
  local eigen="    <artifactId>$naam</artifactId>"

  if [ "$vorm" = "zonder-artifactid" ]; then
    eigen=""
  fi

  cat > "$wortel/$pad/pom.xml" <<POM
<project>
$parent
$eigen
    <dependencies>
$deps    </dependencies>
</project>
POM

  # Alleen demo-modules staan in de reactor-lijst die demo-modules.sh kruiscontroleert; de
  # stelsel-modules vindt demo-grens.sh via de mappen zelf.
  if [ "${pad%%/*}" = "demo" ]; then
    sed -i "s:    </modules>:        <module>$pad</module>\n    </modules>:" "$wortel/pom.xml"
  fi
}

root_dependency() {
  local wortel=$1
  shift

  local blok="$wortel/root-deps.xml"

  # Via een bestand en `sed r` in plaats van een `s`-substitutie: die zou `&` en `\` in een
  # artifactId als vervangingsopdracht lezen. Ankeren op de openingstag, want `r` voegt ná de
  # matchende regel in — op de sluittag belandt de dependency buiten het blok.
  dependency_regels "$@" > "$blok"
  sed -i -e "/^    <dependencies>/{r $blok" -e '}' "$wortel/pom.xml"
}

# $1 = omschrijving, $2 = wortel, $3 = verwachte exitcode, $4 = patroon dat in de uitvoer moet staan.
toets() {
  local omschrijving=$1 wortel=$2 verwachte_code=$3 patroon=$4
  local uitvoer code=0

  uitvoer=$(REPO_ROOT="$wortel" controleer 2>&1) || code=$?

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

# Een pom die geen enkele artifactId oplevert is onleesbaar of niet te parsen. Hem stil overslaan
# terwijl hij wél in de telling zit, is de "OK terwijl er niets gemeten is" die deze controle moet
# uitsluiten.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
: > "$w/services/berichtenuitvraag/pom.xml"
toets "een lege stelsel-pom valt niet stil weg" "$w" 1 "geen enkele artifactId gelezen"

# De wortel-guard moet dezelfde verzameling meten als de scan: telt hij pom's mee die de scan
# pruned (uit target/), dan is hij tevreden over een wortel waar niets gecontroleerd is.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
mkdir -p "$w/libraries/target"
printf '<project><artifactId>restant</artifactId></project>\n' > "$w/libraries/target/pom.xml"
toets "een pom in target/ telt niet als gecontroleerde wortel" "$w" 1 "geen enkele pom onder libraries/"

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
toets "een lege demo-wortel meldt dat er niets gemeten is" "$w" 1 \
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

# --- de meting zelf ---------------------------------------------------------------------------------
# Verdwijnt libraries/ of services/ (hernoemd, geherstructureerd, verkeerde REPO_ROOT), dan zou de
# lus nul keer draaien en de OK-regel alsnog verschijnen.
w=$(nieuw_repo)
voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
rmdir "$w/services" "$w/libraries"
toets "nul stelsel-modules meldt dat er niets gemeten is" "$w" 1 "geen enkele pom onder"

# Eén wortel is genoeg om een totaalteller boven zijn drempel te houden; die helft van het stelsel
# blijft dan ongemeten terwijl de melding groen is.
for weg in services libraries; do
  w=$(nieuw_repo)
  voeg_module "$w" demo/demo-console meerregelig quarkus-kotlin
  voeg_module "$w" services/berichtenuitvraag meerregelig quarkus-rest
  voeg_module "$w" libraries/fbs-common meerregelig quarkus-rest
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
  local omschrijving=$1 wortel=$2 verwachte_code=$3 patroon=$4
  local uitvoer code=0

  uitvoer=$(REPO_ROOT="$wortel" demo_modules 2>&1) || code=$?

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
lijst_toets "een reactor-verwijzing zonder module valt op" "$w" 1 "lopen uiteen"

# --- hygiëne ------------------------------------------------------------------------------------------
w=$(nieuw_repo)
rm -rf "${w:?}/demo"
lijst_toets "een ontbrekende demo-wortel meldt wat er mist" "$w" 1 "bestaat niet"

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
