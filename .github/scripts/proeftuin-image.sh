#!/usr/bin/env bash
#
# Print de image-referentie van de berichtenbox (de proeftuin, MinBZK/moza-poc) uit compose.yaml.
#
# Eén vindplaats, drie aanroepers: de demo op de eigen machine draait wat compose.yaml zegt,
# deploy.yml zet dezelfde referentie op het ZAD-component, en proeftuin-component.sh gebruikt hem
# bij de eenmalige creatie. Compose is de bron omdat Dependabot alléén daar kijkt: hij bumpt de
# digest zodra hun `latest` verschuift, en dat werkt alleen op een letterlijke referentie in een
# bestand dat hij kent. Zou deploy.yml een eigen kopie dragen, dan zou elke Dependabot-PR met de
# hand nagelopen moeten worden.
set -euo pipefail

REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
COMPOSE=${COMPOSE:-$REPO_ROOT/compose.yaml}
IMAGE_PAD=${IMAGE_PAD:-ghcr.io/minbzk/moza-poc}

# Precies één treffer eisen: een tweede regel (een overlay-voorbeeld in commentaar, een tweede
# service) zou anders stilzwijgend de eerste winnen en een demo op een andere berichtenbox zetten
# dan de bedoeling was.
mapfile -t treffers < <(sed -nE "s|^[[:space:]]*image:[[:space:]]*(${IMAGE_PAD}[^[:space:]#]+).*$|\1|p" "$COMPOSE")

if [ "${#treffers[@]}" -ne 1 ]; then
  echo "verwachtte precies één image-regel met ${IMAGE_PAD} in ${COMPOSE}," >&2
  echo "  maar vond er ${#treffers[@]}" >&2
  exit 1
fi

image=${treffers[0]}

# De vorm toetsen vóór hij op een component belandt: een referentie zonder tag of digest trekt
# stilzwijgend `latest` en zet een demo op een andere versie dan het repo denkt.
case "$image" in
  *@sha256:*) ;;
  */*:*) ;;
  *)
    echo "'$image' ziet er niet uit als een image met een tag of digest" >&2
    exit 1
    ;;
esac

printf '%s\n' "$image"
