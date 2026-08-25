#!/usr/bin/env bash
# Bewaakt de grens tussen het stelsel (libraries/, services/) en de demonstratiecode (demo/): geen
# enkele pom van het stelsel mag de naam van een demo-module noemen — niet als dependency, niet als
# parent, niet als plugin.
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

# Élke artifactId in een pom, ongeacht het omliggende element. Bewust niet afgebakend tot
# <dependencies>: ook via <dependencyManagement>, een <profile> of een plugin-dependency komt
# demo-code de build binnen, en de naam van een demo-module hoort sowieso nergens in een pom van
# het stelsel te staan.
#
# XML parsen en niet matchen met een regex. Maven sluit op XML-vorm, een regex op tekstvorm, en dat
# verschil is een bypass-generator: een gespreid element, witruimte in de tag, een attribuut, een
# CDATA-sectie of een entity levert een dependency op die Maven gewoon resolvet en die een regex
# niet ziet. Elke ronde zou een nieuwe deelverzameling dichten; de parser sluit ze in één keer.
artifact_ids() {
  python3 "$HERE/pom-artifactids.py" --alle "$1"
}

# De artifactId van de module zelf: het directe kind van <project>. Het <parent>-blok valt daar
# vanzelf buiten — zonder dat onderscheid zou de artifactId van de parent als die van de module
# gelden, en dat is stil, want er komt gewoon een naam uit.
artifact_id() {
  python3 "$HERE/pom-artifactids.py" --eigen "$1"
}

# De artifactId's van alle demo-modules. Faalt zodra één module er geen oplevert: een pom-vorm die
# niet geparsed wordt zou anders geruisloos uit de controle vallen terwijl de rest groen meldt.
demo_artifacts() {
  local module id modules

  # Eerst de lijst ophalen en de status vasthouden: `done < <(demo_modules)` zou een mislukking
  # daar geruisloos veranderen in een lege lus, en dan levert deze functie nul namen met exitcode 0.
  modules=$(demo_modules) || return 1

  while IFS= read -r module; do
    # Status apart van de leegte-controle: een ontbrekende interpreter of een onleesbare pom is
    # iets anders dan een pom zonder artifactId, en de melding hoort dat verschil te tonen.
    id=$(artifact_id "$REPO_ROOT/$module/pom.xml") || return 1

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
  local wortel gevonden

  printf '%s\n' "$REPO_ROOT/pom.xml"

  for wortel in "${STELSEL_WORTELS[@]}"; do
    # Een wortel die helemaal weg is, is de per-wortel-guard van `controleer` — daar staat de
    # bruikbare melding. Hier alleen doorlopen zodat die guard aan bod komt.
    [ -d "$REPO_ROOT/$wortel" ] || continue

    # Status vasthouden en stderr laten staan: `find` levert bij een onleesbare submap gedeeltelijke
    # uitvoer én een foutstatus. Onderdrukt en genegeerd zou dat een halve boom opleveren die als
    # volledige meting doorgaat — met een overtreding die niemand ziet.
    if ! gevonden=$(find "$REPO_ROOT/$wortel" -name target -prune -o -name pom.xml -print); then
      echo "FOUT: $wortel/ is niet volledig te doorzoeken — de meting is afgebroken." >&2

      return 1
    fi

    printf '%s\n' "$gevonden"
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

  # Een reactor-module buiten de bekende wortels valt buiten élke lijst in deze keten — de scan
  # hieronder, de CodeQL-lussen, de jacoco-globs en de fuzz-allowlist. Dan is de grens niet meer
  # bewaakt zonder dat er iets roods verschijnt, dus is een nieuwe wortel een expliciete keuze.
  local onbekend
  onbekend=$(reactor_modules \
    | awk -v wortels="${STELSEL_WORTELS[*]} demo" '
      BEGIN { aantal = split(wortels, bekend, " ") }
      {
        for (i = 1; i <= aantal; i++) {
          if (index($0, bekend[i] "/") == 1) next
        }

        print
      }')

  if [ -n "$onbekend" ]; then
    echo "FOUT: reactor-module(s) buiten de bekende wortels: $(tr '\n' ' ' <<<"$onbekend")"
    echo "      Voeg de wortel toe aan STELSEL_WORTELS en aan de module-lussen van codeql.yml en test.yml."

    return 1
  fi

  local pomlijst
  pomlijst=$(stelsel_poms) || return 1

  # Per wortel tellen uit diezelfde lijst, niet uit een tweede `find`: een guard die anders meet dan
  # de scan is geen guard. Verdwijnt er één wortel (hernoemd, geherstructureerd, verkeerde
  # REPO_ROOT), dan blijft een totaalteller ruim boven nul terwijl die helft ongemeten is.
  for wortel in "${STELSEL_WORTELS[@]}"; do
    # Op prefix vergelijken en niet met grep: REPO_ROOT gaat daar ongeëscaped een reguliere
    # expressie in, en een metateken in het pad maakt de telling stil onbruikbaar.
    aantal=$(awk -v prefix="$REPO_ROOT/$wortel/" 'index($0, prefix) == 1' <<<"$pomlijst" | grep -c . || true)

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
