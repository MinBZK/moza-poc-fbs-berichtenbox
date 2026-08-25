#!/usr/bin/env bash
# Bewaakt de grens tussen het stelsel (libraries/, services/) en de demonstratiecode (demo/):
# een module uit het stelsel mag niet afhangen van een demo-module.
#
# Zonder deze controle is de scheiding een afspraak die alleen in review houdt, en dat is precies
# de kant waar hij faalt: één `<dependency>` erbij is in een grote diff een detail, terwijl het
# gevolg is dat demo-code meegaat naar productie — en dat een demo-eis het stelsel gaat sturen.
# Andersom mag wél: de demo-console en de simulator mogen van het stelsel afhangen.
#
# Contract: bevindingen op stdout, exitcode 1 zodra er één is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "$HERE/../.." && pwd)}"

# De artifactId van elke Maven-module onder demo/. Uit de pom's gelezen en niet ingetypt: een
# nieuwe demo-module hoort vanzelf onder de controle te vallen, zonder dat iemand deze lijst
# bijwerkt.
demo_artifacts() {
  local pom

  for pom in "$REPO_ROOT"/demo/*/pom.xml; do
    [ -f "$pom" ] || continue

    # De eerste <artifactId> ná </parent> is die van de module zelf; de parent-blok-artifactId
    # staat erboven en zou anders als demo-module tellen.
    sed -n '/<\/parent>/,$p' "$pom" \
      | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p' \
      | head -1
  done
}

# Elke <artifactId> binnen het <dependencies>-blok van een pom. Genoeg voor deze controle: de
# demo-artifactIds zijn uniek in de reactor, dus een treffer is een echte afhankelijkheid.
dependency_artifacts() {
  sed -n '/<dependencies>/,/<\/dependencies>/p' "$1" \
    | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p'
}

controleer() {
  local bevindingen=0 demo pom afhankelijkheid

  local -a demos
  mapfile -t demos < <(demo_artifacts)

  # Een lege lijst betekent "niets gemeten", niet "niets gevonden": zonder deze guard rapporteert
  # de controle groen zodra demo/ leeg is of de pom-vorm verandert.
  if [ ${#demos[@]} -eq 0 ]; then
    echo "FOUT: geen enkele Maven-module onder demo/ gevonden — deze controle meet niets."

    return 1
  fi

  for pom in "$REPO_ROOT"/libraries/*/pom.xml "$REPO_ROOT"/services/*/pom.xml; do
    [ -f "$pom" ] || continue

    while IFS= read -r afhankelijkheid; do
      for demo in "${demos[@]}"; do
        if [ "$afhankelijkheid" = "$demo" ]; then
          echo "FOUT: ${pom#"$REPO_ROOT"/} hangt af van demo-module '$demo' — demonstratiecode hoort niet in het stelsel."
          bevindingen=$((bevindingen + 1))
        fi
      done
    done < <(dependency_artifacts "$pom")
  done

  if [ "$bevindingen" -ne 0 ]; then
    return 1
  fi

  echo "OK: geen module onder libraries/ of services/ hangt af van een demo-module (${#demos[@]} gecontroleerd)."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  controleer "$@"
fi
