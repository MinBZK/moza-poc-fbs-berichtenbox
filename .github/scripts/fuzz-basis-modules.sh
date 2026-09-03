#!/usr/bin/env bash
# Bewaakt dat de handgeschreven COPY-lijst in .clusterfuzzlite/base/Dockerfile de reactor volgt:
# elke Maven-module krijgt zijn pom mee in de bouwcontext van het fuzz-basis-image, en elke
# gekopieerde pom hoort bij een module die nog bestaat.
#
# Het Dockerfile draagt deze controle zelf ook, maar die kan pas afgaan tijdens de bouw — en die
# bouw draait alleen op een push naar main, want ze pusht een image. Een nieuwe module komt
# daardoor binnen via een PR die de vraag nooit stelt, en de eerste run die hem wél stelt is
# meteen een rode main. Dit is dezelfde vraag, gesteld op het moment dat het antwoord nog gratis
# te repareren is; de controle in het Dockerfile blijft staan als laatste verdediging van de bouw.
#
# Twee verschillen met die controle, allebei opzettelijk. Ze noemt élke ontbrekende module in
# plaats van te stoppen bij de eerste — twee modules tegelijk erbij is precies hoe dit gat ontstond
# en dan is één ronde per module een ronde te veel. En ze kijkt ook de andere kant op: een
# COPY-regel voor een verdwenen module laat de bouw stuklopen op een pad dat niet bestaat.
#
# De dure faalwijze is niet "rood terwijl het goed is" maar "groen terwijl er niets gemeten is".
# Vandaar dat elke telling die nul of onverwacht is hier hard faalt in plaats van door te gaan.
#
# Contract: bevindingen op stdout, diagnostiek op stderr, exitcode 1 zodra er één bevinding is of
# zodra de meting onbetrouwbaar is.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${REPO_ROOT:="$(cd "$HERE/../.." && pwd)"}"
: "${FUZZ_BASIS_DOCKERFILE:="$REPO_ROOT/.clusterfuzzlite/base/Dockerfile"}"

# reactor_modules komt hiervandaan: één bron voor "wat zit er in de reactor". Een tweede,
# eigen uitlezing zou van deze afdrijven, en dan bewaakt deze controle een andere modulelijst dan
# de rest van de keten. De lijst is transitief en XML-geparsed, dus strenger dan de sed in het
# Dockerfile — terecht, want Maven leest ook die geneste en gespreide vormen.
# shellcheck source=demo-modules.sh
source "$HERE/demo-modules.sh"

# De bestemmingen van alle COPY-regels: het laatste veld. Een COPY met meerdere bronnen heeft een
# map als bestemming en valt vanzelf af — die eindigt niet op /pom.xml.
copy_bestemmingen() {
  if [ ! -f "$FUZZ_BASIS_DOCKERFILE" ]; then
    echo "FOUT: $FUZZ_BASIS_DOCKERFILE bestaat niet — er valt geen COPY-lijst te controleren." >&2

    return 1
  fi

  # Een voortgezette regel (afsluitende backslash) breekt de veldtelling: het laatste veld is dan
  # het vervolgteken en niet de bestemming. Liever hard stoppen dan een lijst opleveren die er
  # compleet uitziet terwijl er een pad in ontbreekt.
  if grep -qE '^[[:space:]]*COPY[[:space:]].*\\[[:space:]]*$' "$FUZZ_BASIS_DOCKERFILE"; then
    echo "FOUT: een COPY-regel in $FUZZ_BASIS_DOCKERFILE loopt door op de volgende regel; deze controle leest hem verkeerd." >&2

    return 1
  fi

  awk '$1 == "COPY" { print $NF }' "$FUZZ_BASIS_DOCKERFILE"
}

# De modules waarvan een pom meegaat. De root-pom valt af: die hoort bij geen module, en `.mvn`
# en `mvnw` eindigen niet op /pom.xml.
gekopieerde_modules() {
  local bestemmingen

  # Status vasthouden in plaats van doorpijpen: een mislukte uitlezing wordt in een pipeline een
  # lege lijst, en die is niet te onderscheiden van "geen enkele module gekopieerd".
  bestemmingen=$(copy_bestemmingen) || return 1

  printf '%s\n' "$bestemmingen" \
    | { grep -E '^.+/pom\.xml$' || true; } \
    | sed 's:/pom\.xml$::' \
    | sort
}

controleer() {
  local modules gekopieerd ontbreekt verweesd bevindingen=0 pad
  local dockerfile=${FUZZ_BASIS_DOCKERFILE#"$REPO_ROOT"/}

  modules=$(reactor_modules) || return 1
  modules=$(sort <<<"$modules")

  # Leeg betekent "niets vastgesteld", niet "er zijn er geen": een verschoven werkmap of een
  # root-pom zonder <modules> geeft dezelfde lege lijst als een correcte meting.
  if [ -z "$modules" ]; then
    echo "FOUT: geen enkele module in $REPO_ROOT/pom.xml — deze controle meet niets."

    return 1
  fi

  gekopieerd=$(gekopieerde_modules) || return 1

  if [ -z "$gekopieerd" ]; then
    echo "FOUT: geen enkele module-pom in de COPY-lijst van $dockerfile — deze controle meet niets."

    return 1
  fi

  # comm en geen geneste lus: die vergelijkt beide richtingen in één keer, en het resultaat is per
  # richting een lijst in plaats van de eerste treffer.
  ontbreekt=$(comm -23 <(printf '%s\n' "$modules") <(printf '%s\n' "$gekopieerd"))
  verweesd=$(comm -13 <(printf '%s\n' "$modules") <(printf '%s\n' "$gekopieerd"))

  while IFS= read -r pad; do
    [ -n "$pad" ] || continue

    echo "FOUT: module $pad staat niet in de COPY-lijst van $dockerfile."
    echo "      Zonder zijn pom kan Maven de reactor niet lezen en levert de bouw een leeg image."
    echo "      Voeg toe: COPY $pad/pom.xml $pad/pom.xml"
    bevindingen=$((bevindingen + 1))
  done <<<"$ontbreekt"

  while IFS= read -r pad; do
    [ -n "$pad" ] || continue

    echo "FOUT: $dockerfile kopieert $pad/pom.xml, maar $pad is geen module van de reactor."
    echo "      De bouw struikelt op een pad dat niet bestaat; haal de COPY-regel weg."
    bevindingen=$((bevindingen + 1))
  done <<<"$verweesd"

  if [ "$bevindingen" -ne 0 ]; then
    return 1
  fi

  echo "OK: alle $(grep -c . <<<"$modules") reactor-module(s) staan in de COPY-lijst van $dockerfile."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  controleer
fi
