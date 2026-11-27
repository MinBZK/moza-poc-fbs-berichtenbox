#!/usr/bin/env bash
# Fixture-tests voor portaal-image.sh. Dat script bepaalt welke berichtenbox een demo toont én
# welk image de deploy op het ZAD-component zet, en het draait op een tekstuele match in
# compose.yaml — een vorm die met elke opmaakwijziging kan omvallen.
#
# Elke assertie toetst naast de exitcode ook de uitvoer. Exitcode 1 betekent zowel "geen regel
# gevonden" als "de regel deugt niet"; zonder de melding zijn die twee niet te onderscheiden, en
# dan blijft deze suite groen terwijl de guard om de verkeerde reden faalt.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/portaal-image.sh"

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
#
# PORTAAL_IMAGE expliciet leeg: staat die variabele toevallig in de omgeving van wie de suite
# draait, dan zou elke assertie hieronder zijn waarde meten in plaats van de fixture.
draai() {
  STATUS=0
  UITVOER=$(COMPOSE="$1" PORTAAL_IMAGE="" "$GUARD" 2>&1) || STATUS=$?
}

draai_met_override() {
  STATUS=0
  UITVOER=$(COMPOSE="$1" PORTAAL_IMAGE="$2" "$GUARD" 2>&1) || STATUS=$?
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

verwacht_image "digest bij een tag (de vorm die portaal-pin-pr.sh zet)" \
  "$(compose_met "  portaal:" "    image: ghcr.io/minbzk/moza-portaal:main@$DIGEST")" \
  "ghcr.io/minbzk/moza-portaal:main@$DIGEST"

verwacht_image "een kale tag" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:main-6e0751e")" \
  "ghcr.io/minbzk/moza-portaal:main-6e0751e"

# Een preview van hun kant draagt een `pr-*`-tag in hetzelfde repository; wie tijdelijk hun nog
# niet gemergde werk beproeft, zet die referentie hier neer.
verwacht_image "een preview-referentie uit hun repository" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:pr-142-41c83e3")" \
  "ghcr.io/minbzk/moza-portaal:pr-142-41c83e3"

# Tags die met elke bouw verschuiven: op ZAD verandert het manifest dan niet en rolt er niets uit.
for tag in main latest ux-fork pr-303 edge Main pr- main-123456 main-1234567x pr-1-abc \
  xmain-1234567 main-ABCDEF0 main-123456g main-12345678 pr--1234567 pr-1-123456 pr-1-12345678 \
  pr-12-0123abc-r2 pr-12-0123abc-k7 v1.4.0 1.4.0 V1.4.0; do
  verwacht_fout "de tag :$tag zonder digest" \
    "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:$tag")" \
    "kan verschuiven"
done

# Een geldige digest redt geen kapotte referentie ervoor.
GOED="sha256:$(printf 'c%.0s' {1..64})"
for voor in "ghcr.io/minbzk/moza-portaal:" "ghcr.io/minbzk/moza-portaal:pr-302-5830f5e@$GOED"; do
  verwacht_fout "referentie '$voor' vóór een geldige digest" \
    "$(compose_met "    image: \"$voor@$GOED\"")" \
    "geen geldige"
done

# Een spatie kan alleen via de override binnenkomen: de compose-regel eindigt bij witruimte.
draai_met_override "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:main@$GOED")" "ghcr.io/minbzk/moza-portaal:ma in@$GOED"

if [ "$STATUS" -ne 0 ] && [[ "$UITVOER" == *"geen geldige image-referentie"* ]]; then
  ok "een spatie in de tag via PORTAAL_IMAGE wordt geweigerd"
else
  fout "een spatie in de tag via PORTAAL_IMAGE wordt geweigerd — exit $STATUS, uitvoer: $UITVOER"
fi

# Een digest moet er als digest uitzien: een afgekapte, getypte of anders gehashte waarde hangt het
# component in ImagePullBackOff.
for digest in "sha256:$(printf 'a%.0s' {1..63})" "sha256:$(printf 'A%.0s' {1..64})" "sha256:$(printf 'g%.0s' {1..64})" \
  "sha512:$(printf 'a%.0s' {1..64})" "sha256:" "sha256:$(printf 'a%.0s' {1..65})"; do
  verwacht_fout "digest '${digest:0:12}…' (${#digest} tekens)" \
    "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:main@$digest")" \
    "geen geldige digest"
done

verwacht_image "een meebewegende tag mét digest" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:main@sha256:$(printf 'b%.0s' {1..64})")" \
  "ghcr.io/minbzk/moza-portaal:main@sha256:$(printf 'b%.0s' {1..64})"

# Een commit-tag lijkt op een meebewegende, maar verschuift niet.
verwacht_image "een preview-commit-tag is geen meebewegende tag" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:pr-303-b08f20b")" \
  "ghcr.io/minbzk/moza-portaal:pr-303-b08f20b"

# YAML staat aanhalingstekens toe. Belanden die in de referentie, dan hangt het component op ZAD in
# ImagePullBackOff op een naam die in de logs correct oogt.
verwacht_image "dubbele aanhalingstekens worden gestript" \
  "$(compose_met "    image: \"ghcr.io/minbzk/moza-portaal:main@$DIGEST\"")" \
  "ghcr.io/minbzk/moza-portaal:main@$DIGEST"

verwacht_image "enkele aanhalingstekens worden gestript" \
  "$(compose_met "    image: 'ghcr.io/minbzk/moza-portaal:main-6e0751e'")" \
  "ghcr.io/minbzk/moza-portaal:main-6e0751e"

verwacht_image "commentaar achter de regel telt niet mee" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:main-6e0751e  # gepind, zie de guard")" \
  "ghcr.io/minbzk/moza-portaal:main-6e0751e"

# --- de cardinaliteiten: nul, één, meer dan één ---

verwacht_fout "geen enkele regel met dit image" \
  "$(compose_met "  portaal:" "    image: docker.io/library/nginx:1.31-alpine")" \
  "maar vond er 0"

verwacht_fout "twee regels met dit image" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal:main-6e0751e" \
                 "    image: ghcr.io/minbzk/moza-portaal:main-aaaaaaa")" \
  "maar vond er 2"

# Een uitgecommentarieerde regel is geen tweede vindplaats: het patroon eist `image:` aan het begin
# van de regel, en zonder deze assertie zou een strengere match dat stilzwijgend kunnen breken.
verwacht_image "een uitgecommentarieerd voorbeeld telt niet mee" \
  "$(compose_met "    # image: ghcr.io/minbzk/moza-portaal:oud" \
                 "    image: ghcr.io/minbzk/moza-portaal:main-6e0751e")" \
  "ghcr.io/minbzk/moza-portaal:main-6e0751e"

# --- referenties die niet deugen ---

verwacht_fout "zonder tag of digest" \
  "$(compose_met "    image: ghcr.io/minbzk/moza-portaal")" \
  "ziet er niet uit als een image met een tag of digest"

verwacht_fout "een lege tag" \
  "$(compose_met "    image: \"ghcr.io/minbzk/moza-portaal:\"")" \
  "ziet er niet uit als een image met een tag of digest"

verwacht_fout "een lege digest" \
  "$(compose_met "    image: \"ghcr.io/minbzk/moza-portaal@sha256:\"")" \
  "geen geldige digest"

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

if [ "$STATUS" -eq 0 ] && [[ "$UITVOER" == ghcr.io/minbzk/moza-portaal* ]]; then
  ok "de compose.yaml van deze repo levert een referentie op"
else
  fout "de compose.yaml van deze repo levert een referentie op — exit $STATUS, uitvoer: $UITVOER"
fi

# --- de override ---

geldig=$(compose_met "    image: \"ghcr.io/minbzk/moza-portaal:main@sha256:$(printf 'a%.0s' {1..64})\"")

draai_met_override "$geldig" "ghcr.io/minbzk/moza-portaal:pr-151-7bf2f4a"

if [ "$STATUS" -eq 0 ] && [[ "$UITVOER" == *"ghcr.io/minbzk/moza-portaal:pr-151-7bf2f4a"* ]]; then
  ok "PORTAAL_IMAGE wint van de pin in compose"
else
  fout "PORTAAL_IMAGE wint van de pin in compose — exit $STATUS, uitvoer: $UITVOER"
fi

# De melding hoort naar stderr te gaan en niet in de referentie te belanden: wat hier op stdout komt
# wordt ongewijzigd het image van een ZAD-component.
STATUS=0
ALLEEN_STDOUT=$(COMPOSE="$geldig" PORTAAL_IMAGE="ghcr.io/minbzk/moza-portaal:main-1234567" "$GUARD" 2>/dev/null) || STATUS=$?

if [ "$STATUS" -eq 0 ] && [ "$ALLEEN_STDOUT" = "ghcr.io/minbzk/moza-portaal:main-1234567" ]; then
  ok "de waarschuwing over de override gaat naar stderr, niet naar stdout"
else
  fout "de waarschuwing over de override gaat naar stderr — exit $STATUS, stdout: $ALLEEN_STDOUT"
fi

draai_met_override "$geldig" "kapot"

if [ "$STATUS" -ne 0 ] && [[ "$UITVOER" == *"ziet er niet uit als een image met een tag of digest"* ]]; then
  ok "een override zonder tag of digest wordt geweigerd"
else
  fout "een override zonder tag of digest wordt geweigerd — exit $STATUS, uitvoer: $UITVOER"
fi

# Een override is er juist om een demo te draaien zonder de repo aan te passen; dan mag een
# compose-bestand dat deze draai niet gebruikt wordt, hem niet blokkeren.
draai_met_override "$WERKMAP/bestaat-niet.yaml" "ghcr.io/minbzk/moza-portaal:main-1234567"

if [ "$STATUS" -eq 0 ] && [[ "$UITVOER" == *"ghcr.io/minbzk/moza-portaal:main-1234567"* ]]; then
  ok "een override werkt ook zonder leesbare compose.yaml"
else
  fout "een override werkt ook zonder leesbare compose.yaml — exit $STATUS, uitvoer: $UITVOER"
fi

# Leeg is "niet gezet": anders zou een workflow die de variabele altijd meegeeft, met een lege
# waarde de pin stilzwijgend uitschakelen en een component zonder image achterlaten.
draai_met_override "$geldig" ""

if [ "$STATUS" -eq 0 ] && [[ "$UITVOER" == ghcr.io/minbzk/moza-portaal:main@sha256:* ]]; then
  ok "een lege PORTAAL_IMAGE valt terug op de pin"
else
  fout "een lege PORTAAL_IMAGE valt terug op de pin — exit $STATUS, uitvoer: $UITVOER"
fi

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
