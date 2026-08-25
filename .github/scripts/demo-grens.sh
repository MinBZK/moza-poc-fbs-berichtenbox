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
    function strip_parent(s,   uit) {
      uit = ""

      while (length(s) > 0) {
        if (in_parent) {
          if (!match(s, /<\/parent[ \t\r]*>/)) return uit
          s = substr(s, RSTART + RLENGTH)
          in_parent = 0
        } else {
          # Op de tag-vorm matchen en niet op de letterlijke string: <parent > en <parent
          # xmlns="…"> zijn allebei geldig, en een gemiste opening laat de parent-artifactId
          # als die van de module gelden — stil, want er komt gewoon een naam uit.
          if (!match(s, /<parent[ \t\r>]/)) return uit s
          uit = uit substr(s, 1, RSTART - 1)
          s = substr(s, RSTART + RLENGTH - 1)
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

# Élke <artifactId> in de pom, ongeacht het omliggende element. Bewust niet afgebakend tot
# <dependencies>: een regel-gebaseerde afbakening levert hoogstens één treffer per regel op, dus
# twee dependencies op één regel verbergen de tweede. En de naam van een demo-module hoort sowieso
# nergens in een pom van het stelsel te staan — ook niet in <dependencyManagement> of bij een
# plugin, want ook langs die weg komt demo-code de build binnen.
alle_artifacts() {
  awk '
    {
      s = $0

      while (match(s, /<artifactId>[^<]*<\/artifactId>/)) {
        print substr(s, RSTART + 12, RLENGTH - 25)
        s = substr(s, RSTART + RLENGTH)
      }
    }
  ' "$1"
}

# De artifactId's van alle demo-modules. Faalt zodra één module er geen oplevert: een pom-vorm die
# niet geparsed wordt zou anders geruisloos uit de controle vallen terwijl de rest groen meldt.
demo_artifacts() {
  local module id modules

  # Eerst de lijst ophalen en de status vasthouden: `done < <(demo_modules)` zou een mislukking
  # daar geruisloos veranderen in een lege lus, en dan levert deze functie nul namen met exitcode 0.
  modules=$(demo_modules) || return 1

  while IFS= read -r module; do
    id=$(artifact_id "$REPO_ROOT/$module/pom.xml")

    if [ -z "$id" ]; then
      echo "FOUT: geen artifactId gevonden in $module/pom.xml — die module valt buiten de grensbewaking." >&2

      return 1
    fi

    printf '%s\n' "$id"
  done <<<"$modules"
}

# De pom's die de grens moeten respecteren. De root-pom hoort erbij: zijn <dependencies>-blok
# wordt door élke module geërfd, dus één regel daar koppelt het hele stelsel aan demo-code.
STELSEL_WORTELS=(libraries services)

stelsel_poms() {
  local wortel

  printf '%s\n' "$REPO_ROOT/pom.xml"

  for wortel in "${STELSEL_WORTELS[@]}"; do
    find "$REPO_ROOT/$wortel" -name target -prune -o -name pom.xml -print 2>/dev/null
  done | sort
}

controleer() {
  local bevindingen=0 gescand=0 demo pom afhankelijkheid wortel

  # Niet `mapfile < <(demo_artifacts)`: die vorm gooit de exitcode van de procesvervanging weg, en
  # dan blijft een module die niet parseert stil buiten de lijst zolang er één andere wél parseert.
  # Een lege lijst kan hier niet aankomen: demo_modules faalt daar zelf op, en dat is de enige
  # plek waar die garantie hoort te staan.
  local demolijst
  demolijst=$(demo_artifacts) || return 1

  local -a demos
  mapfile -t demos <<<"$demolijst"

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
    done < <(alle_artifacts "$pom")
  done < <(stelsel_poms)

  # Dezelfde guard aan de andere kant, en per wortel: valt er één weg (hernoemd, geherstructureerd,
  # of een verkeerde REPO_ROOT), dan blijft een totaalteller ruim boven nul terwijl die helft van
  # het stelsel ongemeten is.
  for wortel in "${STELSEL_WORTELS[@]}"; do
    if [ -z "$(find "$REPO_ROOT/$wortel" -name pom.xml -print -quit 2>/dev/null)" ]; then
      echo "FOUT: geen enkele pom onder $wortel/ — die helft van het stelsel is niet gecontroleerd."

      return 1
    fi
  done

  if [ "$bevindingen" -ne 0 ]; then
    return 1
  fi

  echo "OK: $gescand pom('s) van het stelsel (root + ${STELSEL_WORTELS[*]}) noemen geen van de ${#demos[@]} demo-module(s)."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  controleer
fi
