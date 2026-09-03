#!/usr/bin/env bash
#
# Print de image-referentie van de berichtenbox (de proeftuin, MinBZK/moza-poc) uit compose.yaml.
#
# Compose is de bron omdat Dependabot alléén daar kijkt: hij bumpt de digest zodra hun `latest`
# verschuift, en dat werkt alleen op een letterlijke referentie in een bestand dat hij kent. Alles
# wat wil weten welke berichtenbox er draait, leest die regel via dit script — anders zou elke
# Dependabot-PR met de hand nagelopen moeten worden.
set -euo pipefail

REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
COMPOSE=${COMPOSE:-$REPO_ROOT/compose.yaml}
COMPOSE_PAD=${COMPOSE_PAD:-ghcr.io/minbzk/moza-poc}

# Apart van de treffer-telling hieronder: een onleesbaar of ontbrekend bestand levert nul regels op,
# en dan zou de melding "de regel staat er niet" beweren terwijl niemand ooit gekeken heeft.
if [ ! -f "$COMPOSE" ] || [ ! -r "$COMPOSE" ]; then
  echo "$COMPOSE is niet te lezen" >&2
  exit 1
fi

# Precies één treffer eisen: een tweede service met hetzelfde image zou anders stilzwijgend de
# eerste laten winnen en een demo op een andere berichtenbox zetten dan de bedoeling was.
#
# De aanhalingstekens zijn optioneel én worden gestript: `image: "ghcr.io/..."` is geldige YAML, en
# zonder deze afhandeling belanden die tekens in de image-naam waarmee het component op ZAD in
# ImagePullBackOff hangt.
mapfile -t treffers < <(
  sed -nE "s|^[[:space:]]*image:[[:space:]]*[\"']?(${COMPOSE_PAD}[^[:space:]\"'#]*)[\"']?.*$|\1|p" "$COMPOSE"
)

if [ "${#treffers[@]}" -ne 1 ]; then
  echo "verwachtte precies één image-regel met ${COMPOSE_PAD} in ${COMPOSE}," >&2
  echo "  maar vond er ${#treffers[@]}" >&2
  exit 1
fi

image=${treffers[0]}

# De vorm toetsen vóór hij op een component belandt. Een referentie zonder tag of digest trekt
# stilzwijgend `latest` en zet een demo op een andere versie dan het repo denkt; een lege tag
# (`...moza-poc:`) is bovendien geen geldige referentie, maar passeert wél `docker compose config`
# zodra de waarde geciteerd is. Het `?` in de patronen eist dus minstens één teken ná de scheiding.
case "$image" in
  *@sha256:?*) ;;
  */*:?*) ;;
  *)
    echo "'$image' ziet er niet uit als een image met een tag of digest" >&2
    exit 1
    ;;
esac

printf '%s\n' "$image"
