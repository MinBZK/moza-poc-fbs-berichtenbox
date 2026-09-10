#!/usr/bin/env bash
#
# Print de image-referentie van de berichtenbox (de proeftuin, MinBZK/moza-poc) uit compose.yaml.
#
# Compose is de bron omdat Dependabot alléén daar kijkt: hij bumpt de digest zodra hun `latest`
# verschuift, en dat werkt alleen op een letterlijke referentie in een bestand dat hij kent. Alles
# wat wil weten welke berichtenbox er draait, leest die regel via dit script — anders zou elke
# Dependabot-PR met de hand nagelopen moeten worden.
#
# `PROEFTUIN_IMAGE` overschrijft die regel voor één draai. Bedoeld om nog niet gemergd werk van hun
# kant te beproeven, of een demo op een bevroren release-tag te zetten. Bewust een omgevingsvariabele
# en geen tweede regel in compose.yaml: zo blijft er één gepinde waarde die Dependabot bijhoudt, en
# is de afwijking zichtbaar op de plek waar hij gezet wordt in plaats van in de repo te blijven staan.
set -euo pipefail

REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
COMPOSE=${COMPOSE:-$REPO_ROOT/compose.yaml}
COMPOSE_PAD=${COMPOSE_PAD:-ghcr.io/minbzk/moza-poc}

# De vorm toetsen vóór hij op een component belandt. Een referentie zonder tag of digest trekt
# stilzwijgend `latest` en zet een demo op een andere versie dan het repo denkt; een lege tag
# (`...moza-poc:`) is bovendien geen geldige referentie, maar passeert wél `docker compose config`
# zodra de waarde geciteerd is. Het `?` in de patronen eist dus minstens één teken ná de scheiding.
toets() {
  case "$1" in
    *@sha256:?*) ;;
    */*:?*) ;;
    *)
      echo "'$1' ziet er niet uit als een image met een tag of digest" >&2
      exit 1
      ;;
  esac
}

# Apart van de treffer-telling hieronder: een onleesbaar of ontbrekend bestand levert nul regels op,
# en dan zou de melding "de regel staat er niet" beweren terwijl niemand ooit gekeken heeft.
# De override eerst, en dan pas compose lezen: staat de variabele, dan doet de gepinde regel er niet
# toe en hoeft een ontbrekende compose.yaml deze draai niet te blokkeren. De vormcontrole onderaan
# geldt voor beide bronnen — een typfout in een handmatig gezette waarde hangt een component net zo
# hard in ImagePullBackOff als een typfout in de pin.
if [ -n "${PROEFTUIN_IMAGE:-}" ]; then
  echo "let op: PROEFTUIN_IMAGE overschrijft de pin in ${COMPOSE}" >&2

  toets "$PROEFTUIN_IMAGE"
  printf '%s\n' "$PROEFTUIN_IMAGE"

  exit 0
fi

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

toets "$image"

printf '%s\n' "$image"
