#!/usr/bin/env bash
# Stelt vast dat elk image dat een build-job hoort te publiceren, ook in het register staat, in een
# vorm die ook een arm64-node start.
#
# Een build die `BUILD SUCCESS` meldt, bewijst niet dat er iets gepubliceerd is: een push die stil
# niets doet, een pom zonder jib-extensie of een `quarkus.container-image.name` die afwijkt van wat de
# uitrol verwacht, eindigen allemaal groen. Daarom het register zelf vragen, op de naam en tag die de
# uitrol meegeeft.
#
# De vorm telt mee omdat de demo ook op een cluster op een Mac draait (k3d, arm64). Containerd daar
# weigert een index zonder linux/arm64 ("no match for platform"); een provenance-attestation naast
# amd64 maakt van een `docker build` al zo'n index. Een los manifest start daar wel, onder
# emulatie, zoals de jib-images.
#
# Elk ontbrekend of verkeerd gevormd image wordt gemeld, niet alleen het eerste: wie er één
# repareert, hoort niet pas bij de volgende run van de tweede te horen. Een lege lijst faalt ook —
# dan meet deze controle niets.
#
# Gebruik:
#   images-gepubliceerd.sh <registry>/<owner> <tag> <naam>...

set -euo pipefail

# Overschrijfbaar zodat de unittests het register kunnen onderscheppen zonder netwerk.
DOCKER=${IMAGES_DOCKER:-docker}
readonly POGINGEN=3
readonly WACHT=${IMAGES_WACHT:-5}

# Print het manifest van $1 zoals het register het teruggeeft. Een mislukte opvraging is niet per se
# een ontbrekend image: een 5xx of een netwerkhik krijgt nog twee kansen. Zit het image er wél, dan
# kost dit niets.
manifest() {
  local ref=$1 poging raw

  for poging in $(seq "$POGINGEN"); do
    if raw=$("$DOCKER" buildx imagetools inspect --raw "$ref"); then
      printf '%s\n' "$raw"
      return 0
    fi

    if [ "$poging" -lt "$POGINGEN" ]; then
      sleep "$WACHT"
    fi
  done

  return 1
}

# Slaagt voor een los image-manifest, of een index met linux/arm64 erin. Leest het manifest van stdin;
# wat geen van beide is, ook onleesbare JSON, faalt.
op_arm64_te_starten() {
  jq -e 'if has("manifests")
    then any(.manifests[]; .platform.os == "linux" and .platform.architecture == "arm64")
    else has("config") end' >/dev/null 2>&1
}

# Wat er wél stond, voor de melding: zonder zie je niet of de build-vlag zijn werk deed.
vorm() {
  jq -r '(.mediaType // "een manifest zonder mediaType")
    + (if has("manifests") then " met " + ([.manifests[].platform | "\(.os)/\(.architecture)"] | join(", ")) else "" end)' \
    2>/dev/null || echo "een onleesbaar manifest"
}

main() {
  if [ "$#" -lt 3 ]; then
    echo "::error title=Publicatiecontrole meet niets::Gebruik: $0 <registry>/<owner> <tag> <naam>... — zonder images valt er niets te controleren."

    exit 1
  fi

  # Zonder jq zou elk image als verkeerd gevormd lezen.
  if ! command -v jq >/dev/null; then
    echo "::error title=Publicatiecontrole zonder jq::jq ontbreekt; de vorm van de manifests is niet te controleren."

    exit 1
  fi

  local basis=$1 tag=$2
  shift 2

  local naam ref raw ontbrekend=() niet_op_arm64=()

  for naam in "$@"; do
    ref="$basis/$naam:$tag"

    if ! raw=$(manifest "$ref"); then
      echo "::error title=Image niet gepubliceerd::$naam staat niet in het register als $ref; de uitrol zou een image zetten dat niet bestaat."
      ontbrekend+=("$naam")
    elif ! op_arm64_te_starten <<<"$raw"; then
      echo "::error title=Image niet te starten op arm64::$naam staat als $ref in het register als $(vorm <<<"$raw"), niet als los manifest of als index met linux/arm64; containerd op een arm64-node, zoals k3d op een Mac, weigert het. Bouw het met docker build --provenance=false, of voor beide platforms."
      niet_op_arm64+=("$naam")
    else
      echo "Gepubliceerd: $ref"
    fi
  done

  if [ "${#ontbrekend[@]}" -gt 0 ]; then
    echo "Niet gepubliceerd: ${ontbrekend[*]}" >&2
  fi

  if [ "${#niet_op_arm64[@]}" -gt 0 ]; then
    echo "Niet te starten op arm64: ${niet_op_arm64[*]}" >&2
  fi

  if [ "${#ontbrekend[@]}" -gt 0 ] || [ "${#niet_op_arm64[@]}" -gt 0 ]; then
    exit 1
  fi
}

main "$@"
