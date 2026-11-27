#!/usr/bin/env bash
#
# Print de image-referentie van de Berichtenbox (het MOZa-portaal, MinBZK/moza-portaal) uit compose.yaml.
#
# Compose is de bron omdat de pin daar staat: één letterlijke referentie, die portaal-pin-pr.sh
# leest en vervangt wanneer hij een bump aanbiedt. Alles wat wil weten welke berichtenbox er draait,
# leest die regel via dit script — anders zou elke bump op meerdere plekken met de hand nagelopen
# moeten worden.
#
# `PORTAAL_IMAGE` overschrijft die regel voor één draai. Bedoeld om nog niet gemergd werk van hun
# kant te beproeven, of een demo op een vaste eerdere versie te zetten. Bewust een omgevingsvariabele
# en geen tweede regel in compose.yaml: zo blijft er één gepinde waarde die bijgehouden wordt, en is
# de afwijking zichtbaar op de plek waar hij gezet wordt in plaats van in de repo te blijven staan.
set -euo pipefail

REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
COMPOSE=${COMPOSE:-$REPO_ROOT/compose.yaml}
COMPOSE_PAD=${COMPOSE_PAD:-ghcr.io/minbzk/moza-portaal}

# De vorm toetsen vóór hij op een component belandt. Een referentie zonder tag of digest trekt
# stilzwijgend `latest` en zet een demo op een andere versie dan het repo denkt; een lege tag
# (`...moza-portaal:`) is bovendien geen geldige referentie, maar passeert wél `docker compose config`
# zodra de waarde geciteerd is. Het `?` in de patronen eist dus minstens één teken ná de scheiding.
# Zonder digest alleen een commit-tag: een tag als `main` of `pr-303` wijst na elke bouw naar iets
# anders, en ook een releasetag kan opnieuw gepusht worden. Op ZAD verandert het gerenderde manifest
# dan niet, dus rolt er niets uit terwijl de deploy groen is. Een digest moet er als digest uitzien:
# een afgekapte of getypte waarde hangt het component in ImagePullBackOff.
toets() {
  local tag

  case "$1" in
    */*@*)
      if ! [[ ${1##*@} =~ ^sha256:[0-9a-f]{64}$ ]]; then
        echo "'$1' heeft geen geldige digest (sha256: plus 64 hex-tekens)" >&2
        exit 1
      fi

      # Ook wat ervoor staat: een lege tag, een tweede digest of een spatie haalt de vorm van de
      # digest niet onderuit, maar wel de pull.
      if ! [[ ${1%@*} =~ ^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+(:[A-Za-z0-9_][A-Za-z0-9._-]{0,127})?$ ]]; then
        echo "'$1' is geen geldige image-referentie" >&2
        exit 1
      fi

      return 0
      ;;
    */*:?*) ;;
    *)
      echo "'$1' ziet er niet uit als een image met een tag of digest" >&2
      exit 1
      ;;
  esac

  tag=${1##*:}

  if ! [[ $tag =~ ^(main-[0-9a-f]{7}|pr-[0-9]+-[0-9a-f]{7})$ ]]; then
    echo "'$1' heeft geen digest en een tag die kan verschuiven; neem een commit-tag (main-<sha7>, pr-<n>-<sha7>) of een digest" >&2
    exit 1
  fi
}

# Apart van de treffer-telling hieronder: een onleesbaar of ontbrekend bestand levert nul regels op,
# en dan zou de melding "de regel staat er niet" beweren terwijl niemand ooit gekeken heeft.
# De override eerst, en dan pas compose lezen: staat de variabele, dan doet de gepinde regel er niet
# toe en hoeft een ontbrekende compose.yaml deze draai niet te blokkeren. De vormcontrole onderaan
# geldt voor beide bronnen — een typfout in een handmatig gezette waarde hangt een component net zo
# hard in ImagePullBackOff als een typfout in de pin.
if [ -n "${PORTAAL_IMAGE:-}" ]; then
  echo "let op: PORTAAL_IMAGE overschrijft de pin in ${COMPOSE}" >&2

  toets "$PORTAAL_IMAGE"
  printf '%s\n' "$PORTAAL_IMAGE"

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
