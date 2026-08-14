#!/usr/bin/env bash
#
# Vergelijkt de digest-pin in .clusterfuzzlite/Dockerfile met het basis-image dat bij de huidige
# dependency-declaraties hoort.
#
# Twee aanroepers met verschillende consequenties, één bron van waarheid: pin-consistency.yml
# waarschuwt op de PR (blokkeren zou een dependency-bump laten vastlopen, want het nieuwe image
# ontstaat pas bij de push die een falende check tegenhoudt), en cflite_cron.yml faalt — die
# wekelijkse ronde kost geen wachttijd, en juist daar doet een trage voorbereiding de hele week
# pijn. Zonder dit script zouden de twee stilzwijgend uiteen gaan lopen.
#
# Verwacht VERWACHT = de pom-hash. Die komt uit `hashFiles(...)`, wat alleen in een
# workflow-expressie te berekenen is, dus geeft de aanroeper hem door.
#
# Eindigt ALTIJD met 0 en schrijft `status` naar GITHUB_OUTPUT; de aanroeper beslist wat elke
# status betekent. Statussen:
#   ok               — de pin hoort bij deze pom's
#   verouderd        — er is een passend image, maar de pin wijst naar een ander
#   ontbreekt        — er is nog geen image voor deze pom's (normaal na een dependency-wijziging)
#   geen-pin         — geen FROM-regel met digest in de Dockerfile
#   geen-pom-hash    — de aanroeper gaf geen pom-hash mee
#   oncontroleerbaar — registry niet te bevragen; er is niets vastgesteld
set -euo pipefail

DOCKERFILE=${DOCKERFILE:-.clusterfuzzlite/Dockerfile}

meld() {
  local status=$1 digest=${2:-} pad=${3:-}

  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      echo "status=$status"
      echo "verwachte_digest=$digest"
      echo "image_pad=$pad"
    } >> "$GITHUB_OUTPUT"
  fi

  printf 'status=%s verwachte_digest=%s image_pad=%s\n' "$status" "$digest" "$pad"
  exit 0
}

# Anker op de FROM-regel: een losse sha256-grep pakt ook een voorbeeld-digest uit het
# commentaarblok erboven en vergelijkt dan stilzwijgend de verkeerde. `|| true` omdat grep zonder
# match exit 1 geeft, wat met `pipefail` de melding hieronder zou overslaan.
from_regel=$(grep -oE '^FROM +[^ ]+@sha256:[a-f0-9]{64}' "$DOCKERFILE" || true)
gepind=${from_regel##*@}
image_pad=$(sed -E 's|^FROM +ghcr\.io/||; s|@sha256:.*||' <<<"$from_regel")

if [ -z "$from_regel" ] || [ -z "$image_pad" ]; then
  meld geen-pin
fi

if [ -z "${VERWACHT:-}" ]; then
  meld geen-pom-hash
fi

# Publiek image: een anoniem pull-token volstaat. `jq -e` zodat een antwoord zonder token niet als
# de string "null" doorgaat en verderop een 401 oplevert die we voor "geen image" zouden aanzien.
if ! token=$(curl -fsS "https://ghcr.io/token?scope=repository:${image_pad}:pull&service=ghcr.io" | jq -er .token); then
  meld oncontroleerbaar "" "$image_pad"
fi

# Alleen 404 betekent "dit image bestaat nog niet". Elke andere fout (401, 429, 5xx, DNS) zegt dat
# de controle zelf niet kon draaien; die mag niet als "nog niet gebouwd" gelden, want dan meldt de
# guard een oorzaak die hij nooit heeft vastgesteld — en blijft dat eeuwig doen.
headers=$(mktemp)
http=$(curl -sS -o /dev/null -D "$headers" -w '%{http_code}' -I \
  -H "Authorization: Bearer $token" \
  -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json" \
  "https://ghcr.io/v2/${image_pad}/manifests/poms-${VERWACHT}" || echo "000")

case "$http" in
    200)
        verwachte_digest=$(tr -d '\r' < "$headers" | awk '/[Dd]ocker-[Cc]ontent-[Dd]igest/ {print $2}')

        if [ -z "$verwachte_digest" ]; then
            meld oncontroleerbaar "" "$image_pad"
        fi
        ;;
    404)
        meld ontbreekt "" "$image_pad"
        ;;
    *)
        echo "ghcr antwoordde $http"
        meld oncontroleerbaar "" "$image_pad"
        ;;
esac

if [ "$gepind" != "$verwachte_digest" ]; then
    meld verouderd "$verwachte_digest" "$image_pad"
fi

meld ok "$verwachte_digest" "$image_pad"
