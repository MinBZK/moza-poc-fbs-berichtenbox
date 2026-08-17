#!/usr/bin/env bash
# Zet idempotent een geldig, wederzijds ondertekend ServiceConnectionGrant-contract op tussen een
# consumer-peer en een provider-peer, door de twee helften achter elkaar te draaien.
#
# Generiek: alle peers, adressen en certificaten komen uit env. `fbs-contracten.sh` ernaast vult die
# in voor de FBS-peers en loopt over de magazijnen.
#
# WAAROM TWEE HELFTEN. Op ZAD isoleert de tenant-baseline-NetworkPolicy per deployment en heeft de
# interne manager-API geen route: één proces dat beide managers aanspreekt bestaat daar niet. De
# helften praten daarom elk met precies één manager en het contract kruist via de FSC-mesh. Dit
# script is de lokale aanroeper van diezelfde twee helften — niet een aparte, eenvoudigere variant.
# Wat hier lokaal getoetst wordt, is dus de code die op ZAD draait.
#
# De scheiding wordt hier afgedwongen en niet alleen afgesproken: elke helft start met `env -u` op
# de adres- en certificaat-variabelen van de overkant. Zou er ooit een call naar de andere manager
# in sluipen, dan faalt die hier meteen in plaats van pas op ZAD.
#
# Stroom:
#   1. consumer-helft: contract indienen bij de eigen manager (of vaststellen dat het er al is);
#   2. provider-helft: het binnengekomen contract tekenen, na de autorisatietoets;
#   3. consumer-helft opnieuw: vaststellen dat het contract nu geldig is.
#
# Bestaat het contract al, dan eindigt stap 1 meteen op 0 en zijn de andere twee niet nodig. Bij een
# verse bootstrap eindigt hij op 3 ("ingediend, nog niet getekend") — de normale tussenstand, geen
# fout. Zie de moduledocs van de twee helften voor de details per kant.
#
# Uitgangen: 0 = contract staat, 2 = configuratie deugt niet (doorgegeven uit de helft die het
# vaststelde), 1 = al het overige.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

HERE_LIB="${FSC_LIBDIR:-$(cd "${HERE}/../../lib" && pwd)}"
# shellcheck source=../../lib/fsc-harness.sh
. "${HERE_LIB}/fsc-harness.sh"
# shellcheck source=../../lib/fsc-contract.sh
. "${HERE_LIB}/fsc-contract.sh"

CONSUMER_OIN="$(fsc_env_vereist FSC_CONSUMER_OIN 'de OIN van de afnemende peer')"
SERVICE_NAME="$(fsc_env_vereist FSC_SERVICE_NAME 'de dienst die afgenomen wordt')"

# De variabelen die de ene helft nooit mag zien. Alleen adressen en sleutelmateriaal: de OIN's zijn
# publieke organisatienummers en beide helften hebben ze nodig om te weten waar het contract over
# gaat.
PROVIDER_GEHEIM=(FSC_PROVIDER_MANAGER FSC_PROVIDER_CERT FSC_PROVIDER_KEY FSC_PROVIDER_CA FSC_PROVIDER_ADRES)
# Het outway-cert hoort óók bij de consumer-kant: het is het group-cert van díé peer, en de
# provider-helft heeft er niets mee te maken.
CONSUMER_GEHEIM=(FSC_CONSUMER_MANAGER FSC_CONSUMER_CERT FSC_CONSUMER_KEY FSC_CONSUMER_CA FSC_CONSUMER_ADRES
                 FSC_OUTWAY_CERT FSC_OUTWAY_THUMBPRINT)

# zonder <var...> -- <commando...>: draai het commando zonder die variabelen in zijn omgeving.
zonder() {
  local args=()
  while [ "$1" != -- ]; do args+=(-u "$1"); shift; done
  shift

  env "${args[@]}" "$@"
}

consumer_helft() {
  zonder "${PROVIDER_GEHEIM[@]}" -- "${HERE}/bootstrap-consumer.sh"
}

provider_helft() {
  # De allowlist van de provider is hier precies één dienst en één consumer: dit script zet één
  # contract op en heeft geen reden een bredere toestemming te openen. Op ZAD staat de lijst in de
  # component-env en kan hij meer dan één regel dragen.
  zonder "${CONSUMER_GEHEIM[@]}" -- \
    FSC_DIENSTEN="$SERVICE_NAME" FSC_CONSUMERS="$CONSUMER_OIN" \
    "${HERE}/bootstrap-provider.sh"
}

# --- 1. Consumer dient in -------------------------------------------------------------------------
rc=0
consumer_helft || rc=$?

if [ "$rc" -eq 0 ]; then
  echo "BOOTSTRAP OK (bestaand contract)."
  exit 0
fi

[ "$rc" -eq 3 ] || {
  echo "FAIL: de consumer-helft brak af (exit ${rc})." >&2
  exit "$rc"
}

# --- 2. Provider tekent ---------------------------------------------------------------------------
echo
rc=0
provider_helft || rc=$?

# 4 = er lagen contracten die de autorisatietoets niet haalden. Die melding staat al in de log van
# de helft zelf; hier telt alleen dat er niet getekend is, en stap 3 stelt dat vervolgens vast.
if [ "$rc" -ne 0 ] && [ "$rc" -ne 4 ]; then
  echo "FAIL: de provider-helft brak af (exit ${rc})." >&2
  exit "$rc"
fi

# --- 3. Consumer stelt vast dat het contract geldig is --------------------------------------------
# Het wachten staat hier en niet in de consumer-helft. Die weet niet of de provider al getekend
# heeft, dus daar zou elke aanroep blind pollen — ook de allereerste, waar de provider gegarandeerd
# nog niet langs is geweest. Deze aanroeper weet het wél: stap 2 is net klaar. Op ZAD vervult de
# lus om de helften heen dezelfde rol.
wacht_tot_geldig() {
  local timeout="${FSC_SYNC_TIMEOUT:-20}" interval="${FSC_SYNC_INTERVAL:-2}" elapsed=0 uitkomst

  # Interval 0 zou `elapsed` nooit laten groeien en er een tight loop van maken.
  [ "$interval" -gt 0 ] || interval=1

  while :; do
    uitkomst=0
    consumer_helft || uitkomst=$?

    # Alleen 3 ("ingediend, nog niet getekend") is het wachten waard; 0 is klaar en de rest is fout.
    [ "$uitkomst" -eq 3 ] || return "$uitkomst"
    [ "$elapsed" -lt "$timeout" ] || return 3

    sleep "$interval"; elapsed=$((elapsed + interval))
    echo "  ...contract nog niet geldig bij de consumer (${elapsed}s)" >&2
  done
}

echo
rc=0
wacht_tot_geldig || rc=$?

if [ "$rc" -eq 3 ]; then
  # De provider heeft getekend, dus als de consumer het contract nog niet geldig ziet is de
  # accept-handtekening onderweg blijven steken. Dat is precies het geval waar de provider-helft
  # zijn her-distributie voor heeft; die is al één keer gestuurd, dus hier nog eens forceren.
  echo
  echo "bootstrap: contract nog niet geldig bij de consumer; her-distributie forceren..." >&2
  rc=0
  FSC_FORCEER_DISTRIBUTIE=1 provider_helft || rc=$?

  if [ "$rc" -ne 0 ] && [ "$rc" -ne 4 ]; then
    echo "FAIL: her-distributie via de provider-helft brak af (exit ${rc})." >&2
    exit "$rc"
  fi

  echo
  rc=0
  wacht_tot_geldig || rc=$?
fi

[ "$rc" -eq 0 ] || {
  echo "FAIL: het contract werd niet geldig bij de consumer (exit ${rc})." >&2
  exit "$rc"
}

echo "BOOTSTRAP OK."
