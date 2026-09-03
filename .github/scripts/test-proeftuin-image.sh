#!/usr/bin/env bash
# Fixture-tests voor proeftuin-image.sh. Dat script bepaalt welke berichtenbox een demo toont én
# welk image de deploy op het ZAD-component zet, en het draait op een tekstuele match in
# compose.yaml — een vorm die met elke opmaakwijziging kan omvallen.
#
# Elke assertie toetst naast de exitcode ook de uitvoer. Exitcode 1 betekent zowel "geen regel
# gevonden" als "de regel deugt niet"; zonder de melding zijn die twee niet te onderscheiden, en
# dan blijft deze suite groen terwijl de guard om de verkeerde reden faalt.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/proeftuin-image.sh"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Elke fixture zijn eigen bestand, zodat een test niet op de resten van een vorige leunt.
compose_met() {
  local bestand
  bestand=$(mktemp "$WERKMAP/compose.XXXXXX")

  printf '%s\n' "$@" > "$bestand"
  printf '%s' "$bestand"
}

# `|| STATUS=$?` en niet `set +e`: een onverwachte fout in de rest van de suite moet nog steeds
# afbreken.
draai() {
  STATUS=0
  UITVOER=$(COMPOSE="$1" "$GUARD" 2>&1) || STATUS=$?
}

verwacht_image() {
  local wat=$1 bestand=$2 verwacht=$3

  draai "$bestand"

  if [ "$STATUS" -eq 0 ] && [ "$UITVOER" = "$verwacht" ]; then
    ok "$wat"
  else
    fout "$wat — exit $STATUS, uitvoer: $UITVOER"
  fi
}

verwacht_fout() {
  local wat=$1 bestand=$2 fragment=$3

  draai "$bestand"

  if [ "$STATUS" -ne 0 ] && [[ "$UITVOER" == *"$fragment"* ]]; then
    ok "$wat"
  else
    fout "$wat — exit $STATUS, uitvoer: $UITVOER"
  fi
}

DIGEST="sha256:3e2974ac9e9692b509dee4f5b9c5a63fccbf94f413c65b03916115ee8f850875"

# --- de vormen die in compose.yaml kunnen staan ---

verwacht_image "digest bij een tag (de vorm die Dependabot bijhoudt)" \
  "$(compose_met "  proeftuin:" "    image: ghcr.io/minbzk/moza-poc:latest@$DIGEST")" \
  "ghcr.io/minbzk/moza-poc:latest@$DIGEST"

verwacht_image "een kale tag" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-poc:sha-6e0751e")" \
  "ghcr.io/minbzk/moza-poc:sha-6e0751e"

# Het preview-repository van hun kant is een ánder pad onder dezelfde prefix; wie tijdelijk hun nog
# niet gemergde werk beproeft, zet die referentie hier neer.
verwacht_image "een preview-referentie uit hun eigen repository" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-poc/preview:pr-142-41c83e3")" \
  "ghcr.io/minbzk/moza-poc/preview:pr-142-41c83e3"

# YAML staat aanhalingstekens toe. Belanden die in de referentie, dan hangt het component op ZAD in
# ImagePullBackOff op een naam die in de logs correct oogt.
verwacht_image "dubbele aanhalingstekens worden gestript" \
  "$(compose_met "    image: \"ghcr.io/minbzk/moza-poc:latest@$DIGEST\"")" \
  "ghcr.io/minbzk/moza-poc:latest@$DIGEST"

verwacht_image "enkele aanhalingstekens worden gestript" \
  "$(compose_met "    image: 'ghcr.io/minbzk/moza-poc:sha-6e0751e'")" \
  "ghcr.io/minbzk/moza-poc:sha-6e0751e"

verwacht_image "commentaar achter de regel telt niet mee" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-poc:sha-6e0751e  # gepind, zie de guard")" \
  "ghcr.io/minbzk/moza-poc:sha-6e0751e"

# --- de cardinaliteiten: nul, één, meer dan één ---

verwacht_fout "geen enkele regel met dit image" \
  "$(compose_met "  proeftuin:" "    image: docker.io/library/nginx:1.31-alpine")" \
  "maar vond er 0"

verwacht_fout "twee regels met dit image" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-poc:sha-6e0751e" \
                 "    image: ghcr.io/minbzk/moza-poc:sha-aaaaaaa")" \
  "maar vond er 2"

# Een uitgecommentarieerde regel is geen tweede vindplaats: het patroon eist `image:` aan het begin
# van de regel, en zonder deze assertie zou een strengere match dat stilzwijgend kunnen breken.
verwacht_image "een uitgecommentarieerd voorbeeld telt niet mee" \
  "$(compose_met "    # image: ghcr.io/minbzk/moza-poc:oud" \
                 "    image: ghcr.io/minbzk/moza-poc:sha-6e0751e")" \
  "ghcr.io/minbzk/moza-poc:sha-6e0751e"

# --- referenties die niet deugen ---

verwacht_fout "zonder tag of digest" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-poc")" \
  "ziet er niet uit als een image met een tag of digest"

verwacht_fout "een lege tag" \
  "$(compose_met "    image: \"ghcr.io/minbzk/moza-poc:\"")" \
  "ziet er niet uit als een image met een tag of digest"

verwacht_fout "een lege digest" \
  "$(compose_met "    image: \"ghcr.io/minbzk/moza-poc@sha256:\"")" \
  "ziet er niet uit als een image met een tag of digest"

# --- het bestand zelf ---

verwacht_fout "een compose-bestand dat er niet is" \
  "$WERKMAP/bestaat-niet.yaml" \
  "is niet te lezen"

verwacht_fout "een map in plaats van een bestand" \
  "$WERKMAP" \
  "is niet te lezen"

# Deze assertie is de enige die de guard tegen de echte compose.yaml houdt. Zonder haar blijft de
# suite groen terwijl de repo zelf een regel draagt die het script niet meer leest.
draai "$HERE/../../compose.yaml"

if [ "$STATUS" -eq 0 ] && [[ "$UITVOER" == ghcr.io/minbzk/moza-poc* ]]; then
  ok "de compose.yaml van deze repo levert een referentie op"
else
  fout "de compose.yaml van deze repo levert een referentie op — exit $STATUS, uitvoer: $UITVOER"
fi

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
