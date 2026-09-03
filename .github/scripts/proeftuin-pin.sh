#!/usr/bin/env bash
#
# Vergelijkt de image-referentie van de berichtenbox in compose.yaml met de laatste main-commit van
# MinBZK/moza-poc.
#
# Dit is een vangnet naast Dependabot, geen vervanger. Dependabot bumpt de digest zodra hun `latest`
# verschuift, en dat is het normale pad — mét PR, CI en review. Maar die bump hangt aan een keten
# die stil kan vallen (hun `latest` blijft staan, zijn docker-compose-parser struikelt over een
# volgende wijziging in dat bestand) en dan valt er niets op: geen PR is precies hoe "alles bij"
# eruitziet. Deze controle zegt actief wat de stand is.
#
# Eindigt ALTIJD met 0 en schrijft `status` naar GITHUB_OUTPUT; de aanroeper beslist wat elke
# status betekent. Statussen:
#   ok               — de pin wijst naar de huidige main-commit van de proeftuin
#   verouderd        — er staat een image voor een nieuwere main-commit klaar
#   ontbreekt        — die main-commit heeft nog geen image (hun bouw loopt nog, of viel)
#   preview          — de pin staat op nog niet gemergd werk van hun kant; die tag verdwijnt zodra
#                      hun PR sluit, dus hier valt niets te vergelijken met main
#   pin-onvindbaar   — de gepinde tag bestaat niet (meer) in het register
#   geen-pin         — geen leesbare image-referentie in compose.yaml
#   bron-weg         — het bron-repository bestaat niet meer onder deze naam
#   oncontroleerbaar — GitHub of ghcr niet te bevragen; er is niets vastgesteld
set -euo pipefail

BRON_REPO=${BRON_REPO:-MinBZK/moza-poc}
# Het register-pad van hun main-image. Niet te verwarren met COMPOSE_PAD in proeftuin-image.sh: dat
# is de prefix waarop dáár in compose.yaml gematcht wordt. Waar de pin werkelijk staat, leidt dit
# script hieronder uit de referentie zelf af — hun preview-images liggen in een ánder repository.
MAIN_PAD=${MAIN_PAD:-minbzk/moza-poc}
REPO_ROOT=${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}

meld() {
  local status=$1 tag=${2:-} regel=${3:-} huidig=${4:-}

  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      echo "status=$status"
      echo "verwachte_tag=$tag"
      echo "verwachte_regel=$regel"
      echo "huidige_referentie=$huidig"
    } >> "$GITHUB_OUTPUT"
  fi

  printf 'status=%s verwachte_tag=%s verwachte_regel=%s huidige_referentie=%s\n' \
    "$status" "$tag" "$regel" "$huidig"
  exit 0
}

# Publiek image: een anoniem pull-token volstaat. `jq -e` zodat een antwoord zonder token als
# "oncontroleerbaar" langskomt in plaats van als de string "null", die verderop elke lookup zou
# laten stranden op een fout die met de tag niets te maken heeft.
ghcr_token() {
  curl -fsS "https://ghcr.io/token?scope=repository:${1}:pull&service=ghcr.io" | jq -er .token
}

# Print de digest van een tag. Exitcodes: 0 = gevonden, 2 = bestaat niet, 1 = niet vastgesteld.
#
# Alleen 404 betekent "bestaat niet". Elke andere fout (401, 429, 5xx, DNS) zegt dat de controle
# zelf niet kon draaien, en die mag niet als "nog niet gebouwd" gelden — dan meldt de guard een
# oorzaak die hij nooit heeft vastgesteld. Een 200 zonder digest-header valt om dezelfde reden
# onder "niet vastgesteld": de tag bestaat, maar we weten niet waarnaar hij wijst.
digest_van() {
  local pad=$1 tag=$2 token=$3 headers http digest
  headers=$(mktemp)
  trap 'rm -f "$headers"' RETURN

  http=$(curl -sS -o /dev/null -D "$headers" -w '%{http_code}' -I \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json" \
    "https://ghcr.io/v2/${pad}/manifests/${tag}" || true)

  case "$http" in
    200)
      # `head -1`: een tweede matchende headerregel zou een meerregelige waarde opleveren, en die
      # schrijft in GITHUB_OUTPUT een sleutelloze regel weg die Actions negeert.
      digest=$(tr -d '\r' < "$headers" | awk '/[Dd]ocker-[Cc]ontent-[Dd]igest/ {print $2}' | head -1)

      [ -n "$digest" ] || return 1

      printf '%s' "$digest"
      ;;
    404) return 2 ;;
    *) echo "ghcr antwoordde '$http' voor ${pad}:${tag}" >&2; return 1 ;;
  esac
}

if ! image=$("$REPO_ROOT/.github/scripts/proeftuin-image.sh"); then
  meld geen-pin
fi

# Het register-pad uit de referentie zelf: `ghcr.io/` eraf, en alles vanaf de tag of digest ook.
# Hardcoden zou een pin op hun preview-repository (`minbzk/moza-poc/preview`) onder het main-pad
# opzoeken, daar een 404 krijgen en die als "geen pin" rapporteren — een harde fout op een route
# die het runbook juist als toegestaan beschrijft.
zonder_registry=${image#ghcr.io/}
pad=${zonder_registry%%@*}
pad=${pad%:*}

if [ "$zonder_registry" = "$image" ]; then
  echo "referentie '$image' staat niet op ghcr.io; deze controle kent alleen dat register" >&2
  meld oncontroleerbaar "" "" "$image"
fi

if ! token=$(ghcr_token "$pad"); then
  meld oncontroleerbaar "" "" "$image"
fi

# Een tag-pin heeft zijn digest nog niet bij zich; die eerst opzoeken, anders vergelijkt de controle
# straks een tag met een digest en meldt hij eeuwig "verouderd".
case "$image" in
  *@sha256:*) huidige_digest=${image##*@} ;;
  *)
    huidige_digest=$(digest_van "$pad" "${image##*:}" "$token") || case $? in
      2) meld pin-onvindbaar "" "" "$image" ;;
      *) meld oncontroleerbaar "" "" "$image" ;;
    esac
    ;;
esac

# Een pin buiten het main-repository is nog niet gemergd werk van hun kant. Vergelijken met hun main
# heeft dan geen betekenis: die tag hóórt af te wijken, en hij verdwijnt zodra hun PR sluit.
if [ "$pad" != "$MAIN_PAD" ]; then
  meld preview "" "" "$image"
fi

# De token-header alleen zetten als er een token is: zonder valt de aanroep terug op de anonieme
# limiet van 60 per uur per IP, en die deelt een runner met elke andere runner op datzelfde IP.
gh_auth=()

if [ -n "${GH_TOKEN:-}" ]; then
  gh_auth=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

# Geen `-L`: een hernoemd of verwijderd repository geeft 301 of 404, en dat zijn vastgestelde
# feiten over de inrichting van deze guard — geen tijdelijke onbereikbaarheid. Stil doorlopen zou
# hem voorgoed op "oncontroleerbaar" laten staan, groen en onopgemerkt.
repo_http=$(curl -sS -o /dev/null -w '%{http_code}' "${gh_auth[@]}" \
  "https://api.github.com/repos/${BRON_REPO}" || true)

case "$repo_http" in
  200) ;;
  301|404) meld bron-weg "" "" "$image" ;;
  *) echo "GitHub antwoordde '$repo_http' voor ${BRON_REPO}" >&2; meld oncontroleerbaar "" "" "$image" ;;
esac

if ! standaardtak=$(curl -fsS "${gh_auth[@]}" "https://api.github.com/repos/${BRON_REPO}" | jq -er .default_branch); then
  meld oncontroleerbaar "" "" "$image"
fi

if ! kop=$(curl -fsS "${gh_auth[@]}" \
  "https://api.github.com/repos/${BRON_REPO}/commits/${standaardtak}" | jq -er .sha); then
  meld oncontroleerbaar "" "" "$image"
fi

# Hun bouw tagt op de korte sha van zeven tekens. Die tag is af te leiden uit de commit, waar de
# tag-lijst van het register ongesorteerd terugkomt en "de nieuwste" er dus niet uit te lezen valt.
# Via de commit weten we bovendien wélke wijzigingen een bump zou meenemen.
verwachte_tag="sha-${kop:0:7}"

verwachte_digest=$(digest_van "$MAIN_PAD" "$verwachte_tag" "$token") || case $? in
  2) meld ontbreekt "$verwachte_tag" "" "$image" ;;
  *) meld oncontroleerbaar "$verwachte_tag" "" "$image" ;;
esac

if [ "$huidige_digest" = "$verwachte_digest" ]; then
  meld ok "$verwachte_tag" "" "$image"
fi

# De hele regel meegeven en niet alleen de digest: het image-pad staat dan op één plek in dit repo
# in plaats van ook nog eens in de tekst van een melding, die bij een verhuizing stil achterblijft.
# `latest` als leesbare aanduiding hoort bij de digest die dáár nu op staat, dus die zoeken we op —
# de digest van de sha-tag hoeft die van `latest` niet te zijn, en juist in het geval waarvoor deze
# melding bestaat lopen ze uiteen.
latest_digest=$(digest_van "$MAIN_PAD" latest "$token" || true)

if [ "$latest_digest" = "$verwachte_digest" ]; then
  meld verouderd "$verwachte_tag" "    image: ghcr.io/${MAIN_PAD}:latest@${verwachte_digest}" "$image"
fi

meld verouderd "$verwachte_tag" "    image: ghcr.io/${MAIN_PAD}:${verwachte_tag}@${verwachte_digest}" "$image"
