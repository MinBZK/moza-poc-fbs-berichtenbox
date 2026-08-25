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

# De artifactId's uit een pom, in volgorde van voorkomen. De hele pom wordt eerst tot één regel
# genormaliseerd: XML mag een tag over meerdere regels spreiden en witruimte binnen een element is
# betekenisloos (`<artifactId>\n  demo-console\n</artifactId>` resolvet Maven gewoon), dus een
# regel-gebaseerde regex laat precies die vorm ongezien passeren — een bypass die elke formatter
# vanzelf produceert.
#
# Geen XML-parser: `xmllint` staat niet op elke runner en deze controle mag niet afhangen van een
# pakket dat er toevallig is. De normalisatie haalt het verschil weg dat er wél toe doet.
artifact_ids() {
    awk '
    { doc = doc " " $0 }

    END {
      gsub(/[ \t\r\n]+/, " ", doc)

      while (match(doc, /<artifactId>[^<]*<\/artifactId>/)) {
        waarde = substr(doc, RSTART + 12, RLENGTH - 25)
        gsub(/^ +| +$/, "", waarde)

        if (waarde != "") print waarde

        doc = substr(doc, RSTART + RLENGTH)
      }
    }
  ' "$1"
}

# De artifactId van de module zelf: de eerste buiten het <parent>-blok. Het parent-blok gaat er
# eerst uit, want anders geldt de artifactId van de parent als die van de module — stil, want er
# komt gewoon een naam uit. Op de tag-vorm matchen en niet op de letterlijke string: `<parent >` en
# `<parent xmlns="…">` zijn allebei geldig.
artifact_id() {
    awk '
    { doc = doc " " $0 }

    END {
      gsub(/[ \t\r\n]+/, " ", doc)

      if (match(doc, /<parent[ >]/)) {
        kop = substr(doc, 1, RSTART - 1)
        rest = substr(doc, RSTART)

        if (match(rest, /<\/parent *>/)) {
          doc = kop substr(rest, RSTART + RLENGTH)
        } else {
          doc = kop
        }
      }

      if (match(doc, /<artifactId>[^<]*<\/artifactId>/)) {
        waarde = substr(doc, RSTART + 12, RLENGTH - 25)
        gsub(/^ +| +$/, "", waarde)
        print waarde
      }
    }
  ' "$1"
}

# De pom's die de grens moeten respecteren. De root-pom hoort erbij: zijn <dependencies>-blok
# wordt door élke module geërfd, dus één regel daar koppelt het hele stelsel aan demo-code.
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

STELSEL_WORTELS=(libraries services)

# Alle pom's die de grens moeten respecteren: de root-pom (waar élke module van erft, dus één regel
# daar koppelt het hele stelsel aan demo-code) plus alles onder de stelsel-wortels. `target/` eruit:
# een build kan daar pom-kopieën achterlaten, en die tellen niet mee als module.
stelsel_poms() {
  local wortel

  printf '%s\n' "$REPO_ROOT/pom.xml"

  for wortel in "${STELSEL_WORTELS[@]}"; do
    find "$REPO_ROOT/$wortel" -name target -prune -o -name pom.xml -print 2>/dev/null
  done | sort
}

controleer() {
  local bevindingen=0 demo pom afhankelijkheid wortel aantal ids

  # Niet `mapfile < <(demo_artifacts)`: die vorm gooit de exitcode van de procesvervanging weg, en
  # dan blijft een module die niet parseert stil buiten de lijst zolang er één andere wél parseert.
  # Een lege lijst kan hier niet aankomen: demo_modules faalt daar zelf op, en dat is de enige
  # plek waar die garantie hoort te staan.
  local demolijst
  demolijst=$(demo_artifacts) || return 1

  local -a demos
  mapfile -t demos <<<"$demolijst"

  local pomlijst
  pomlijst=$(stelsel_poms)

  # Per wortel tellen uit diezelfde lijst, niet uit een tweede `find`: een guard die anders meet dan
  # de scan is geen guard. Verdwijnt er één wortel (hernoemd, geherstructureerd, verkeerde
  # REPO_ROOT), dan blijft een totaalteller ruim boven nul terwijl die helft ongemeten is.
  for wortel in "${STELSEL_WORTELS[@]}"; do
    aantal=$(grep -c "^$REPO_ROOT/$wortel/" <<<"$pomlijst" || true)

    if [ "$aantal" -eq 0 ]; then
      echo "FOUT: geen enkele pom onder $wortel/ — die helft van het stelsel is niet gecontroleerd."

      return 1
    fi
  done

  while IFS= read -r pom; do
    # Elke pom moet minstens zijn eigen artifactId opleveren. Nul betekent onleesbaar of niet te
    # parsen — en die pom stil overslaan terwijl hij wél in de telling zit, is precies de "OK
    # terwijl er niets gemeten is" die deze controle moet uitsluiten.
    ids=$(artifact_ids "$pom") || return 1

    if [ -z "$ids" ]; then
      echo "FOUT: geen enkele artifactId gelezen uit ${pom#"$REPO_ROOT"/} — die pom is niet gecontroleerd."

      return 1
    fi

    while IFS= read -r afhankelijkheid; do
      for demo in "${demos[@]}"; do
        if [ "$afhankelijkheid" = "$demo" ]; then
          echo "FOUT: ${pom#"$REPO_ROOT"/} noemt demo-module '$demo' — demonstratiecode hoort niet in het stelsel."
          bevindingen=$((bevindingen + 1))
        fi
      done
    done <<<"$ids"
  done <<<"$pomlijst"

  if [ "$bevindingen" -ne 0 ]; then
    return 1
  fi

  echo "OK: $(grep -c . <<<"$pomlijst") pom('s) van het stelsel (root + ${STELSEL_WORTELS[*]}) noemen geen van de ${#demos[@]} demo-module(s)."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  controleer
fi
