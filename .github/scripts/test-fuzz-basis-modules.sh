#!/usr/bin/env bash
# Fixture-tests voor fuzz-basis-modules.sh. De controle is stil als hij niets vindt, dus de dure
# faalwijze is dat hij niets meer méét: een verschoven werkmap, een Dockerfile-vorm die de
# awk-regel niet herkent of een lege modulelijst levert dan een groene run zonder dat er iets
# gecontroleerd is.
#
# Daarom toetst elke assertie naast de exitcode ook de melding. Exitcode 1 betekent zowel "de
# COPY-lijst loopt achter" als "niets gemeten"; zonder de melding zijn die twee niet uit elkaar te
# houden en blijft een suite groen terwijl de controle om de verkeerde reden rood is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fuzz-basis-modules.sh
source "$HERE/fuzz-basis-modules.sh"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# $1 = root, rest = modulepaden. Elke module krijgt een pom op schijf, want de reactor-uitlezing
# loopt de boom af en een ontbrekende module-pom is daar terecht een harde fout.
schrijf_pom() {
  local pom=$1
  shift

  mkdir -p "$(dirname "$pom")"

  {
    echo '<project>'
    echo '    <groupId>nl.rijksoverheid.moz</groupId>'
    echo "    <artifactId>$(basename "$(dirname "$pom")")</artifactId>"

    if [ "$#" -gt 0 ]; then
      echo '    <modules>'
      printf '        <module>%s</module>\n' "$@"
      echo '    </modules>'
    fi

    echo '</project>'
  } > "$pom"
}

# $1 = root, rest = modulepaden voor de root-pom. De modules zelf krijgen een lege pom.
nieuw_repo() {
  local root
  root=$(mktemp -d "$WERKMAP/repo-XXXXXX")

  schrijf_pom "$root/pom.xml" "$@"

  local module
  for module in "$@"; do
    schrijf_pom "$root/$module/pom.xml"
  done

  echo "$root"
}

# $1 = root, rest = COPY-bestemmingen (modulepaden). De vaste regels eromheen staan er altijd bij:
# die moeten door de filtering vallen, en een fixture zonder die regels zou dat nooit aantonen.
schrijf_dockerfile() {
  local root=$1
  shift

  {
    echo 'FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1'
    echo 'WORKDIR /warmup'
    echo 'COPY .mvn .mvn'
    echo 'COPY mvnw mvnw'
    echo 'COPY pom.xml pom.xml'

    local module
    for module in "$@"; do
      printf 'COPY %s/pom.xml %s/pom.xml\n' "$module" "$module"
    done

    echo 'RUN chmod +x mvnw'
  } > "$root/Dockerfile"
}

# $1 = naam, $2 = root, $3 = verwachte exitcode, $4 = tekst die in de uitvoer moet staan.
toets() {
  local naam=$1 root=$2 verwachte_code=$3 verwachte_tekst=$4
  local uitvoer code=0

  uitvoer=$(REPO_ROOT="$root" FUZZ_BASIS_DOCKERFILE="$root/Dockerfile" bash -c '
    set -euo pipefail
    source "$1/fuzz-basis-modules.sh"
    controleer
  ' _ "$HERE" 2>&1) || code=$?

  if [ "$code" -ne "$verwachte_code" ]; then
    fout "$naam: exitcode $code, verwacht $verwachte_code"
    # Ingesprongen, want een meegeprinte 'OK:'-regel uit het script zelf zou meetellen in de
    # asserties-telling die ci-scripts.yml op deze uitvoer doet.
    sed 's/^/    | /' <<<"$uitvoer" >&2

    return
  fi

  if ! grep -qF "$verwachte_tekst" <<<"$uitvoer"; then
    fout "$naam: melding bevat niet '$verwachte_tekst'"
    sed 's/^/    | /' <<<"$uitvoer" >&2

    return
  fi

  ok "$naam"
}

# --- alles gedekt -------------------------------------------------------------------------------

w=$(nieuw_repo libraries/fbs-common services/berichtenuitvraag demo/demo-console)
schrijf_dockerfile "$w" libraries/fbs-common services/berichtenuitvraag demo/demo-console
# De fixture draagt ook `COPY .mvn .mvn`, `COPY mvnw mvnw` en `COPY pom.xml pom.xml`. Zouden die
# als module tellen, dan leverde deze fixture drie verweesde bevindingen in plaats van groen.
toets "volledige COPY-lijst is groen" "$w" 0 "OK: alle 3 reactor-module(s)"

# Bij meerdere bronnen is het laatste veld een map, niet de bestemming van één bestand. Zonder die
# vorm in de fixture zou de awk-regel er ongemerkt op stuk kunnen gaan.
printf 'COPY services/a/pom.xml services/b/pom.xml /warmup/hulp/\n' >> "$w/Dockerfile"
toets "COPY met meerdere bronnen naar een map telt niet als module" "$w" 0 "alle 3"

# --- één module mist ----------------------------------------------------------------------------

w=$(nieuw_repo libraries/fbs-common services/berichtenuitvraag demo/demo-console)
schrijf_dockerfile "$w" libraries/fbs-common services/berichtenuitvraag
toets "één ontbrekende module wordt gemeld" "$w" 1 "module demo/demo-console staat niet in de COPY-lijst"
toets "de melding noemt de regel die eraan moet" "$w" 1 "COPY demo/demo-console/pom.xml demo/demo-console/pom.xml"

# --- meerdere modules missen --------------------------------------------------------------------

# De kern van deze controle: het Dockerfile stopt bij de eerste ontbrekende module, waardoor twee
# nieuwe modules twee rondes kosten. Hier moeten ze allebei in één uitvoer staan.
w=$(nieuw_repo libraries/fbs-common demo/demo-personas demo/magazijn-simulator)
schrijf_dockerfile "$w" libraries/fbs-common
toets "twee ontbrekende modules: de eerste staat erin" "$w" 1 "module demo/demo-personas staat niet"
toets "twee ontbrekende modules: de tweede ook" "$w" 1 "module demo/magazijn-simulator staat niet"

# --- COPY zonder module -------------------------------------------------------------------------

w=$(nieuw_repo libraries/fbs-common)
schrijf_dockerfile "$w" libraries/fbs-common services/verdwenen
toets "verweesde COPY-regel wordt gemeld" "$w" 1 "services/verdwenen is geen module van de reactor"

# --- beide richtingen tegelijk ------------------------------------------------------------------

w=$(nieuw_repo libraries/fbs-common demo/demo-personas)
schrijf_dockerfile "$w" libraries/fbs-common services/verdwenen
toets "ontbrekend én verweesd: de ontbrekende module" "$w" 1 "module demo/demo-personas staat niet"
toets "ontbrekend én verweesd: de verweesde regel" "$w" 1 "services/verdwenen is geen module"

# --- transitieve module -------------------------------------------------------------------------

# Een module mag zélf modules declareren; die staan niet in de root-pom maar Maven bouwt ze wel.
w=$(nieuw_repo services/berichtenuitvraag)
schrijf_pom "$w/services/berichtenuitvraag/pom.xml" ../../libraries/fbs-common
schrijf_pom "$w/libraries/fbs-common/pom.xml"
schrijf_dockerfile "$w" services/berichtenuitvraag
toets "geneste module telt mee" "$w" 1 "module libraries/fbs-common staat niet in de COPY-lijst"

# --- niets gemeten ------------------------------------------------------------------------------

w=$(nieuw_repo)
schrijf_dockerfile "$w" libraries/fbs-common
toets "root-pom zonder modules faalt in plaats van groen te melden" "$w" 1 "deze controle meet niets"

w=$(nieuw_repo libraries/fbs-common)
schrijf_dockerfile "$w"
toets "COPY-lijst zonder module-pom faalt in plaats van groen te melden" "$w" 1 "deze controle meet niets"

w=$(nieuw_repo libraries/fbs-common)
toets "ontbrekend Dockerfile faalt" "$w" 1 "bestaat niet"

w=$(nieuw_repo libraries/fbs-common)
schrijf_dockerfile "$w" libraries/fbs-common
printf 'COPY services/a/pom.xml \\\n    services/a/pom.xml\n' >> "$w/Dockerfile"
toets "voortgezette COPY-regel faalt in plaats van half gelezen te worden" "$w" 1 "loopt door op de volgende regel"

# --- de toets zelf ------------------------------------------------------------------------------

w=$(nieuw_repo libraries/fbs-common)
schrijf_dockerfile "$w" libraries/fbs-common

if (fails=0; toets zelftest "$w" 1 "OK:" >/dev/null 2>&1; [ "$fails" -eq 1 ]); then
  ok "toets merkt een afwijkende exitcode op"
else
  fout "toets meldt geen afwijking meer; de suite meet niets"
fi

if (fails=0; toets zelftest "$w" 0 "een melding die er niet staat" >/dev/null 2>&1; [ "$fails" -eq 1 ]); then
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
