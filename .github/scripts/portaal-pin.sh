#!/usr/bin/env bash
#
# Vergelijkt de image-referentie van de Berichtenbox in compose.yaml met de laatste main-commit van
# MinBZK/moza-portaal.
#
# Stelt alleen vast wat de stand is; de aanroeper beslist wat hij ermee doet. pin-consistency.yml
# blokkeert een PR bij een pin die niet te trekken is, en portaal-pin.yml biedt een achterlopende
# pin als PR aan. Dependabot doet dit image niet meer — de reden staat bij de `ignore`-regel in
# .github/dependabot.yml — dus dit is geen vangnet naast dat pad, maar het pad zelf.
#
# Eindigt ALTIJD met 0 en schrijft `status` naar GITHUB_OUTPUT; de aanroeper beslist wat elke
# status betekent. Statussen:
#   ok               — de pin wijst naar de huidige main-commit van het portaal
#   verouderd        — er staat een image voor een nieuwere main-commit klaar
#   ontbreekt        — geen van de laatste main-commits heeft een image. Hun main-images worden
#                      met de hand gebouwd (`workflow_dispatch` op hun deploy.yml); vergeleken
#                      wordt met de nieuwste commit die er wél een heeft
#   preview          — de pin staat op nog niet gemergd werk van hun kant; die tag verdwijnt zodra
#                      hun PR sluit, dus hier valt niets te vergelijken met main
#   pin-onvindbaar   — de gepinde tag bestaat niet (meer) in het register
#   geen-pin         — geen leesbare image-referentie in compose.yaml
#   bron-weg         — het bron-repository bestaat niet meer onder deze naam
#   oncontroleerbaar — GitHub of ghcr niet te bevragen; er is niets vastgesteld
set -euo pipefail

BRON_REPO=${BRON_REPO:-MinBZK/moza-portaal}
# Het register-pad van hun main-image. Niet te verwarren met COMPOSE_PAD in portaal-image.sh: dat
# is de prefix waarop dáár in compose.yaml gematcht wordt. Waar de pin werkelijk staat, leidt dit
# script hieronder uit de referentie zelf af.
MAIN_PAD=${MAIN_PAD:-minbzk/moza-portaal}
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

if ! image=$("$REPO_ROOT/.github/scripts/portaal-image.sh"); then
  meld geen-pin
fi

# Het register-pad uit de referentie zelf: `ghcr.io/` eraf, en alles vanaf de tag of digest ook.
# Hardcoden zou een pin op een ander repository (een fork, een verhuisd image) onder het main-pad
# opzoeken, daar een 404 krijgen en die als "geen pin" rapporteren in plaats van als preview.
zonder_registry=${image#ghcr.io/}
pad=${zonder_registry%%@*}
tag=""

if [[ $pad == *:* ]]; then
  tag=${pad##*:}
  pad=${pad%:*}
fi

if [ "$zonder_registry" = "$image" ]; then
  echo "referentie '$image' staat niet op ghcr.io; deze controle kent alleen dat register" >&2
  meld oncontroleerbaar "" "" "$image"
fi

if ! token=$(ghcr_token "$pad"); then
  meld oncontroleerbaar "" "" "$image"
fi

# Een tag-pin heeft zijn digest nog niet bij zich; die eerst opzoeken, anders vergelijkt de controle
# straks een tag met een digest en meldt hij eeuwig "verouderd". Een digest-pin zoeken we óók op: een
# preview-image verdwijnt zodra hun PR sluit, en zonder deze vraag bleef zo'n pin groen tot de
# eerstvolgende herstart van het component hem niet meer kan trekken.
case "$image" in
  *@sha256:*)
    huidige_digest=${image##*@}
    digest_van "$pad" "$huidige_digest" "$token" >/dev/null || case $? in
      2) meld pin-onvindbaar "" "" "$image" ;;
      *) meld oncontroleerbaar "" "" "$image" ;;
    esac
    ;;
  *)
    huidige_digest=$(digest_van "$pad" "${image##*:}" "$token") || case $? in
      2) meld pin-onvindbaar "" "" "$image" ;;
      *) meld oncontroleerbaar "" "" "$image" ;;
    esac
    ;;
esac

# Een pin op een `pr-*`-tag, of buiten het main-repository, is nog niet gemergd werk van hun kant.
# Vergelijken met hun main heeft dan geen betekenis: die tag hóórt af te wijken. Hun previews staan
# in hetzelfde repository als main, dus alleen de tag onderscheidt ze.
if [ "$pad" != "$MAIN_PAD" ] || [[ $tag == pr-* ]]; then
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

# De laatste commits van hun main, nieuwste eerst. Hun main-images worden met de hand gebouwd, dus
# de nieuwste commit heeft vaak nog geen image; dan telt de nieuwste die er wél een heeft. Alleen
# naar de kop kijken zou de bump laten wachten op een dag waarop hun bouw toevallig precies de kop
# raakt, en elke merge zonder bouw zet hem weer terug.
if ! commits=$(curl -fsS "${gh_auth[@]}" \
  "https://api.github.com/repos/${BRON_REPO}/commits?sha=${standaardtak}&per_page=${COMMITS_TERUG:-30}" |
  jq -er '[.[].sha] | select(length > 0) | .[]'); then
  meld oncontroleerbaar "" "" "$image"
fi

mapfile -t commitlijst <<<"$commits"
kop=${commitlijst[0]}

# `jq -er` grijpt alleen op `null` en `false`, dus een antwoord met een lege of afgekapte `.sha`
# passeert. Dat zou hieronder een tag als `main-` opleveren, die in het register een 404 geeft en
# daarmee als `ontbreekt` langskomt — een status die "hun bouw loopt nog" betekent en de aanroeper
# rustig laat wachten op iets wat nooit komt.
if ! [[ $kop =~ ^[0-9a-f]{40}$ ]]; then
  echo "GitHub gaf geen bruikbare commit-sha ('$kop') voor ${BRON_REPO}" >&2
  meld oncontroleerbaar "" "" "$image"
fi

# Hun bouw tagt een main-image als `main-<korte sha van zeven tekens>`. Die tag is af te leiden uit de commit, waar de
# tag-lijst van het register ongesorteerd terugkomt en "de nieuwste" er dus niet uit te lezen valt.
# Via de commit weten we bovendien wélke wijzigingen een bump zou meenemen.
verwachte_tag="main-${kop:0:7}"
verwachte_digest=""

for commit in "${commitlijst[@]}"; do
  if ! [[ $commit =~ ^[0-9a-f]{40}$ ]]; then
    echo "GitHub gaf geen bruikbare commit-sha ('$commit') voor ${BRON_REPO}" >&2
    meld oncontroleerbaar "$verwachte_tag" "" "$image"
  fi

  rc=0
  digest=$(digest_van "$MAIN_PAD" "main-${commit:0:7}" "$token") || rc=$?

  case $rc in
    0) verwachte_tag="main-${commit:0:7}"; verwachte_digest=$digest; break ;;
    2) ;;
    *) meld oncontroleerbaar "main-${commit:0:7}" "" "$image" ;;
  esac
done

if [ -z "$verwachte_digest" ]; then
  meld ontbreekt "$verwachte_tag" "" "$image"
fi

if [ "$huidige_digest" = "$verwachte_digest" ]; then
  meld ok "$verwachte_tag" "" "$image"
fi

# De hele regel meegeven en niet alleen de digest: het image-pad staat dan op één plek in dit repo
# in plaats van ook nog eens in de tekst van de aangeboden regel, die bij een verhuizing stil
# achterblijft. `main` als leesbare aanduiding hoort bij de digest die dáár nu op staat, dus die
# zoeken we op — de digest van de commit-tag hoeft die van `main` niet te zijn, en juist in het geval
# waarvoor deze regel wordt aangeboden lopen ze uiteen.
main_digest=$(digest_van "$MAIN_PAD" main "$token" || true)

if [ "$main_digest" = "$verwachte_digest" ]; then
  meld verouderd "$verwachte_tag" "    image: ghcr.io/${MAIN_PAD}:main@${verwachte_digest}" "$image"
fi

meld verouderd "$verwachte_tag" "    image: ghcr.io/${MAIN_PAD}:${verwachte_tag}@${verwachte_digest}" "$image"
