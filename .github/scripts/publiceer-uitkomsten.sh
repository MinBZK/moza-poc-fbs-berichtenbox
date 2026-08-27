#!/usr/bin/env bash
# Draait de wijzigingsdetectie, valideert de uitkomst en schrijft hem naar $GITHUB_OUTPUT.
#
# Dit blok stond eerder vier keer inline in de workflows. Dat was niet alleen duplicatie: het maakte
# de publicatie oncontroleerbaar. Elke bewaking erop kon alleen tekst vergelijken — staat het
# scriptpad er nog, is het blok byte-identiek — en tekst is te verslaan met een comment, een
# `if: false` op de stap, of één extra regel buiten het gemeten bereik. Zo'n regel volstaat om elke
# PR naar de demo-shard te scopen: de services worden dan niet getest en niets wordt rood.
#
# Als script is de publicatie gedrag geworden: de fixtures hieronder in test-wijzigingsfilter.sh
# draaien hem echt en toetsen wat eruit komt, ook op het fail-safe-pad.
#
# Fail-safe is de kern: elke twijfel over de uitkomst — het script viel om, er kwamen minder dan
# vier sleutels uit, een sleutel kwam dubbel — leidt tot alles draaien. Een overgeslagen job
# rapporteert 'skipped' en dat telt als succes voor branch protection, dus stilte is de dure kant.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Alles draaien; de vaste terugval bij elke twijfel over de uitkomst.
ALLES_AAN=$'run=true\ndeploy=true\ndemo-only=false\nfuzz=true'

publiceer() {
  local uitkomsten sleutels

  uitkomsten=$("$HERE/wijzigingsfilter.sh") || uitkomsten=""
  uitkomsten=$(grep -E '^(run|deploy|demo-only|fuzz)=(true|false)$' <<<"$uitkomsten" | sort -u) || true

  # Tellen op unieke sleutels, niet op regels: `run=true` én `run=false` zijn twee regels maar één
  # sleutel, en zouden een ontbrekende `deploy` anders maskeren.
  sleutels=$(cut -d= -f1 <<<"$uitkomsten" | sort -u | grep -c .) || true

  if [ "$(grep -c . <<<"$uitkomsten")" != 4 ] || [ "$sleutels" != 4 ]; then
    echo "::error::Wijzigingsdetectie leverde geen vier geldige uitkomsten — alles draait fail-safe." >&2
    uitkomsten=$ALLES_AAN
  fi

  printf '%s\n' "$uitkomsten"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  uitkomsten=$(publiceer)

  printf '%s\n' "$uitkomsten"

  # GITHUB_OUTPUT ontbreekt buiten Actions; dan is stdout de uitvoer en is er niets te schrijven.
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s\n' "$uitkomsten" >> "$GITHUB_OUTPUT"
  fi
fi
