#!/usr/bin/env bash
# Stelt vast dat elk image dat een build-job hoort te publiceren, ook in het register staat.
#
# Een build die `BUILD SUCCESS` meldt, bewijst niet dat er iets gepubliceerd is: een push die stil
# niets doet, een pom zonder jib-extensie of een `quarkus.container-image.name` die afwijkt van wat de
# uitrol verwacht, eindigen allemaal groen. Daarom het register zelf vragen, op de naam en tag die de
# uitrol meegeeft.
#
# Elk ontbrekend image wordt gemeld, niet alleen het eerste: wie er één repareert, hoort niet pas bij
# de volgende run van de tweede te horen. Een lege lijst faalt ook — dan meet deze controle niets.
#
# Gebruik:
#   images-gepubliceerd.sh <registry>/<owner> <tag> <naam>...

set -euo pipefail

# Overschrijfbaar zodat de unittests het register kunnen onderscheppen zonder netwerk.
DOCKER=${IMAGES_DOCKER:-docker}
readonly POGINGEN=3
readonly WACHT=${IMAGES_WACHT:-5}

# Een mislukte opvraging is niet per se een ontbrekend image: een 5xx of een netwerkhik krijgt nog
# twee kansen. Zit het image er wél, dan kost dit niets.
gepubliceerd() {
  local ref=$1 poging

  for poging in $(seq "$POGINGEN"); do
    "$DOCKER" buildx imagetools inspect --raw "$ref" >/dev/null && return 0

    if [ "$poging" -lt "$POGINGEN" ]; then
      sleep "$WACHT"
    fi
  done

  return 1
}

main() {
  if [ "$#" -lt 3 ]; then
    echo "::error title=Publicatiecontrole meet niets::Gebruik: $0 <registry>/<owner> <tag> <naam>... — zonder images valt er niets te controleren."

    exit 1
  fi

  local basis=$1 tag=$2
  shift 2

  local naam ref ontbrekend=()

  for naam in "$@"; do
    ref="$basis/$naam:$tag"

    if gepubliceerd "$ref"; then
      echo "Gepubliceerd: $ref"
    else
      echo "::error title=Image niet gepubliceerd::$naam staat niet in het register als $ref; de uitrol zou een image zetten dat niet bestaat."
      ontbrekend+=("$naam")
    fi
  done

  if [ "${#ontbrekend[@]}" -gt 0 ]; then
    echo "Niet gepubliceerd: ${ontbrekend[*]}" >&2

    exit 1
  fi
}

main "$@"
