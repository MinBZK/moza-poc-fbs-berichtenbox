#!/usr/bin/env bash
# Bewaakt de grens tussen het stelsel (libraries/, services/) en de demonstratiecode (demo/):
# een module uit het stelsel mag niet afhangen van een demo-module.
#
# Zonder deze controle is de scheiding een afspraak die alleen in review houdt, en dat is precies
# de kant waar hij faalt: één `<dependency>` erbij is in een grote diff een detail, terwijl het
# gevolg is dat demo-code meegaat naar productie — en dat een demo-eis het stelsel gaat sturen.
# Andersom mag wél: een demo-module mag van het stelsel afhangen.
#
# De dure faalwijze is niet "rood terwijl het goed is" maar "groen terwijl er niets gemeten is".
# Vandaar dat elke telling die nul of onverwacht is hier hard faalt in plaats van door te gaan.
#
# Contract: bevindingen op stdout, exitcode 1 zodra er één is of zodra de meting onbetrouwbaar is.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${REPO_ROOT:="$(cd "$HERE/../.." && pwd)"}"

# shellcheck source=demo-modules.sh
source "$HERE/demo-modules.sh"

# De artifactId van een module: de eerste <artifactId> buiten het <parent>-blok. Awk in plaats van
# sed omdat het parent-blok ook op één regel mag staan — dan begint een regel-gebaseerde range er
# middenin en levert de greedy substitutie de parent-artifactId op, waarna de échte module stil
# buiten de controle valt.
artifact_id() {
  awk '
    function strip_parent(s,   uit, p, q) {
      uit = ""

      while (length(s) > 0) {
        if (in_parent) {
          q = index(s, "</parent>")
          if (q == 0) return uit
          s = substr(s, q + 9)
          in_parent = 0
        } else {
          p = index(s, "<parent>")
          if (p == 0) return uit s
          uit = uit substr(s, 1, p - 1)
          s = substr(s, p + 8)
          in_parent = 1
        }
      }

      return uit
    }
    {
      rest = strip_parent($0)

      if (match(rest, /<artifactId>[^<]*<\/artifactId>/)) {
        print substr(rest, RSTART + 12, RLENGTH - 25)
        exit
      }
    }
  ' "$1"
}

# Elke <artifactId> binnen een <dependencies>-blok. Dat is ruimer dan alleen de directe
# afhankelijkheden — <dependencyManagement> en plugin-dependencies vallen er ook onder — en dat is
# hier gewenst: ook langs die weg belandt demo-code op het classpath van het stelsel.
dependency_artifacts() {
  sed -n '/<dependencies>/,/<\/dependencies>/p' "$1" \
    | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p'
}

# De artifactId's van alle demo-modules. Faalt zodra één module er geen oplevert: een pom-vorm die
# niet geparsed wordt zou anders geruisloos uit de controle vallen terwijl de rest groen meldt.
demo_artifacts() {
  local module id

  while IFS= read -r module; do
    id=$(artifact_id "$REPO_ROOT/$module/pom.xml")

    if [ -z "$id" ]; then
      echo "FOUT: geen artifactId gevonden in $module/pom.xml — die module valt buiten de grensbewaking." >&2

      return 1
    fi

    printf '%s\n' "$id"
  done < <(demo_modules)
}

# De pom's die de grens moeten respecteren. De root-pom hoort erbij: zijn <dependencies>-blok
# wordt door élke module geërfd, dus één regel daar koppelt het hele stelsel aan demo-code.
stelsel_poms() {
  local pom

  printf '%s\n' "$REPO_ROOT/pom.xml"

  while IFS= read -r pom; do
    printf '%s\n' "$pom"
  done < <(find "$REPO_ROOT/libraries" "$REPO_ROOT/services" -name target -prune -o -name pom.xml -print 2>/dev/null | sort)
}

controleer() {
  local bevindingen=0 gescand=0 demo pom afhankelijkheid

  local -a demos
  mapfile -t demos < <(demo_artifacts)

  # Een lege lijst betekent "niets gemeten", niet "niets gevonden".
  if [ ${#demos[@]} -eq 0 ]; then
    echo "FOUT: geen enkele demo-module gevonden — deze controle meet niets."

    return 1
  fi

  while IFS= read -r pom; do
    [ -f "$pom" ] || continue
    gescand=$((gescand + 1))

    while IFS= read -r afhankelijkheid; do
      for demo in "${demos[@]}"; do
        if [ "$afhankelijkheid" = "$demo" ]; then
          echo "FOUT: ${pom#"$REPO_ROOT"/} hangt af van demo-module '$demo' — demonstratiecode hoort niet in het stelsel."
          bevindingen=$((bevindingen + 1))
        fi
      done
    done < <(dependency_artifacts "$pom")
  done < <(stelsel_poms)

  # Dezelfde guard aan de andere kant: verdwijnt libraries/ of services/ (hernoemd, geherstructureerd,
  # of een verkeerde REPO_ROOT), dan zou de lus nul keer draaien en de OK-regel alsnog verschijnen.
  # Eén pom is de root-pom zelf, dus daar telt pas vanaf twee.
  if [ "$gescand" -lt 2 ]; then
    echo "FOUT: $gescand pom('s) onder libraries/ en services/ gescand — deze controle meet niets."

    return 1
  fi

  if [ "$bevindingen" -ne 0 ]; then
    return 1
  fi

  echo "OK: $gescand pom('s) van het stelsel bevatten geen van de ${#demos[@]} demo-module(s) als dependency."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  controleer
fi
