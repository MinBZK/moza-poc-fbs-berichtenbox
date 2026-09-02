#!/usr/bin/env bash
#
# Vergelijkt de digest waarop de berichtenbox in compose.yaml gepind staat met de laatste
# main-commit van MinBZK/moza-poc.
#
# Dit is een vangnet naast Dependabot, geen vervanger. Dependabot bumpt de digest zodra hun `latest`
# verschuift, en dat is het normale pad — mét PR, CI en review. Maar die bump hangt aan een keten
# die stil kan vallen (hun `latest` blijft staan, zijn docker-compose-parser struikelt over een
# volgende wijziging in dit bestand) en dan valt er niets op: geen PR is precies hoe "alles bij"
# eruitziet. Deze controle zegt actief wat de stand is.
#
# Blokkeert niets: een oudere berichtenbox is een oudere demo, geen kapotte build.
#
# Eindigt ALTIJD met 0 en schrijft `status` naar GITHUB_OUTPUT; de aanroeper beslist wat elke
# status betekent. Statussen:
#   ok               — de pin wijst naar de huidige main-commit van de proeftuin
#   verouderd        — er staat een image voor een nieuwere main-commit klaar
#   ontbreekt        — die main-commit heeft nog geen image (hun bouw loopt nog, of viel)
#   geen-pin         — geen bruikbare image-referentie in compose.yaml
#   oncontroleerbaar — GitHub of ghcr niet te bevragen; er is niets vastgesteld
set -euo pipefail

BRON_REPO=${BRON_REPO:-MinBZK/moza-poc}
IMAGE_PAD=${IMAGE_PAD:-minbzk/moza-poc}
REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}

meld() {
  local status=$1 tag=${2:-} digest=${3:-} huidig=${4:-}

  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      echo "status=$status"
      echo "verwachte_tag=$tag"
      echo "verwachte_digest=$digest"
      echo "huidige_digest=$huidig"
    } >> "$GITHUB_OUTPUT"
  fi

  printf 'status=%s verwachte_tag=%s verwachte_digest=%s huidige_digest=%s\n' \
    "$status" "$tag" "$digest" "$huidig"
  exit 0
}

# Publiek image: een anoniem pull-token volstaat. `jq -e` zodat een antwoord zonder token niet als
# de string "null" doorgaat en verderop een 401 oplevert die we voor "geen image" zouden aanzien.
ghcr_token() {
  curl -fsS "https://ghcr.io/token?scope=repository:${IMAGE_PAD}:pull&service=ghcr.io" | jq -er .token
}

# Print de digest van een tag, of niets als die tag er niet is. Alleen 404 betekent "bestaat niet";
# elke andere fout (401, 429, 5xx, DNS) zegt dat de controle zelf niet kon draaien en mag niet als
# "nog niet gebouwd" gelden — dan meldt de guard een oorzaak die hij nooit heeft vastgesteld.
digest_van() {
  local tag=$1 token=$2 headers http
  headers=$(mktemp)

  http=$(curl -sS -o /dev/null -D "$headers" -w '%{http_code}' -I \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json" \
    "https://ghcr.io/v2/${IMAGE_PAD}/manifests/${tag}" || echo "000")

  case "$http" in
    200) tr -d '\r' < "$headers" | awk '/[Dd]ocker-[Cc]ontent-[Dd]igest/ {print $2}' ;;
    404) return 0 ;;
    *) echo "ghcr antwoordde $http voor tag $tag" >&2; return 1 ;;
  esac
}

if ! image=$("$REPO_ROOT/.github/scripts/proeftuin-image.sh"); then
  meld geen-pin
fi

if ! token=$(ghcr_token); then
  meld oncontroleerbaar
fi

# De pin hoort een digest te dragen; staat er alleen een tag, dan is de vergelijking pas te maken
# na het opzoeken van diens digest. Zonder die tak zou een tijdelijke tag-pin (een overlay die
# iemand hierheen kopieerde) als "geen pin" langskomen terwijl er wel degelijk iets draait.
case "$image" in
  *@sha256:*) huidige_digest=${image##*@} ;;
  *)
    if ! huidige_digest=$(digest_van "${image##*:}" "$token"); then
      meld oncontroleerbaar
    fi
    ;;
esac

if [ -z "$huidige_digest" ]; then
  meld geen-pin
fi

# De token-header alleen zetten als er een token is: zonder valt de aanroep terug op de anonieme
# limiet van 60 per uur per IP, en die deelt een runner met elke andere runner op datzelfde IP.
gh_auth=()

if [ -n "${GH_TOKEN:-}" ]; then
  gh_auth=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

if ! standaardtak=$(curl -fsS "${gh_auth[@]}" "https://api.github.com/repos/${BRON_REPO}" | jq -er .default_branch); then
  meld oncontroleerbaar "" "" "$huidige_digest"
fi

if ! kop=$(curl -fsS "${gh_auth[@]}" \
  "https://api.github.com/repos/${BRON_REPO}/commits/${standaardtak}" | jq -er .sha); then
  meld oncontroleerbaar "" "" "$huidige_digest"
fi

# Hun bouw tagt op de korte sha van zeven tekens. Die tag is af te leiden uit de commit, waar de
# tag-lijst van het register ongesorteerd terugkomt en "de nieuwste" er dus niet uit te lezen valt.
# Via de commit weten we bovendien wélke wijzigingen een bump zou meenemen.
verwachte_tag="sha-${kop:0:7}"

if ! verwachte_digest=$(digest_van "$verwachte_tag" "$token"); then
  meld oncontroleerbaar "$verwachte_tag" "" "$huidige_digest"
fi

if [ -z "$verwachte_digest" ]; then
  meld ontbreekt "$verwachte_tag" "" "$huidige_digest"
fi

if [ "$huidige_digest" = "$verwachte_digest" ]; then
  meld ok "$verwachte_tag" "$verwachte_digest" "$huidige_digest"
fi

meld verouderd "$verwachte_tag" "$verwachte_digest" "$huidige_digest"
