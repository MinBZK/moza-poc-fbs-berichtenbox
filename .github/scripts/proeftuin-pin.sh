#!/usr/bin/env bash
#
# Vergelijkt de proeftuin-pin in compose.yaml met de laatste main-commit van MinBZK/moza-poc.
#
# Waarom een eigen controle: Dependabot ziet deze pin niet. Zijn docker-compose-parser resolvet de
# `${VAR:-<tag>}`-vorm niet, en een `sha-<commit>`-tag heeft geen opvolgerelatie die hij kan
# ordenen — dus er komt nooit een bump-PR voor de berichtenbox, terwijl dat voor de andere images
# in compose.yaml wél gebeurt. Zonder deze job is er geen enkel signaal dat de demo een verouderde
# berichtenbox toont.
#
# Blokkeert niets: een oudere proeftuin is een oudere demo, geen kapotte build. De aanroeper
# waarschuwt.
#
# Eindigt ALTIJD met 0 en schrijft `status` naar GITHUB_OUTPUT; de aanroeper beslist wat elke
# status betekent. Statussen:
#   ok               — de pin wijst naar de huidige main-commit van de proeftuin
#   verouderd        — er is een image voor een nieuwere main-commit
#   ontbreekt        — die main-commit heeft nog geen image (hun bouw loopt nog, of viel)
#   geen-pin         — geen proeftuin-image met tag in compose.yaml
#   oncontroleerbaar — GitHub of ghcr niet te bevragen; er is niets vastgesteld
set -euo pipefail

COMPOSE=${COMPOSE:-compose.yaml}
BRON_REPO=${BRON_REPO:-MinBZK/moza-poc}
IMAGE_PAD=${IMAGE_PAD:-minbzk/moza-poc}

meld() {
  local status=$1 verwacht=${2:-} huidig=${3:-}

  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      echo "status=$status"
      echo "verwachte_tag=$verwacht"
      echo "huidige_tag=$huidig"
    } >> "$GITHUB_OUTPUT"
  fi

  printf 'status=%s verwachte_tag=%s huidige_tag=%s\n' "$status" "$verwacht" "$huidig"
  exit 0
}

# Compose is de bron: infra-image-pins bewaakt al dat deploy.yml dezelfde waarde draagt, dus twee
# vindplaatsen aflezen zou hier alleen dezelfde controle overdoen. `|| true` omdat sed zonder
# match niets print en de melding hieronder dat afhandelt.
huidige_tag=$(sed -nE \
  "s|^[[:space:]]*image:[[:space:]]*ghcr\.io/${IMAGE_PAD}:(\\\$\{[A-Za-z_][A-Za-z0-9_]*:-)?([A-Za-z0-9._-]+)\}?[[:space:]]*$|\2|p" \
  "$COMPOSE" | head -1 || true)

if [ -z "$huidige_tag" ]; then
  meld geen-pin
fi

# De token-header alleen zetten als er een token is: zonder valt de aanroep terug op de anonieme
# limiet van 60 per uur per IP, en die deelt een runner met elke andere runner op datzelfde IP.
gh_auth=()

if [ -n "${GH_TOKEN:-}" ]; then
  gh_auth=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

if ! standaardtak=$(curl -fsS "${gh_auth[@]}" "https://api.github.com/repos/${BRON_REPO}" | jq -er .default_branch); then
  meld oncontroleerbaar "" "$huidige_tag"
fi

if ! kop=$(curl -fsS "${gh_auth[@]}" \
  "https://api.github.com/repos/${BRON_REPO}/commits/${standaardtak}" | jq -er .sha); then
  meld oncontroleerbaar "" "$huidige_tag"
fi

# Hun bouw tagt op de korte sha van zeven tekens; de pin die daaruit volgt is dus af te leiden
# zonder de tag-lijst van het register af te struinen (die komt ongesorteerd terug, dus "de
# nieuwste" valt er niet uit te lezen).
verwachte_tag="sha-${kop:0:7}"

if [ "$huidige_tag" = "$verwachte_tag" ]; then
  meld ok "$verwachte_tag" "$huidige_tag"
fi

# Publiek image: een anoniem pull-token volstaat. `jq -e` zodat een antwoord zonder token niet als
# de string "null" doorgaat en verderop een 401 oplevert die we voor "geen image" zouden aanzien.
if ! token=$(curl -fsS "https://ghcr.io/token?scope=repository:${IMAGE_PAD}:pull&service=ghcr.io" | jq -er .token); then
  meld oncontroleerbaar "$verwachte_tag" "$huidige_tag"
fi

# Alleen 404 betekent "voor deze commit is nog geen image gebouwd". Elke andere fout (401, 429,
# 5xx, DNS) zegt dat de controle zelf niet kon draaien, en die mag niet als "nog niet gebouwd"
# gelden — dan meldt de guard een oorzaak die hij nooit heeft vastgesteld.
http=$(curl -sS -o /dev/null -w '%{http_code}' -I \
  -H "Authorization: Bearer $token" \
  -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json" \
  "https://ghcr.io/v2/${IMAGE_PAD}/manifests/${verwachte_tag}" || echo "000")

case "$http" in
  200) meld verouderd "$verwachte_tag" "$huidige_tag" ;;
  404) meld ontbreekt "$verwachte_tag" "$huidige_tag" ;;
  *)
    echo "ghcr antwoordde $http"
    meld oncontroleerbaar "$verwachte_tag" "$huidige_tag"
    ;;
esac
