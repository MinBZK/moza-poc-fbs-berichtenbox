#!/usr/bin/env bash
# Entrypoint van het contract-bootstrap-component op ZAD: draai één helft van de bootstrap, herhaald.
#
# ZAD kent alleen langlopende componenten — er is geen job-vorm en geen manier om args mee te geven.
# Vandaar een lus die zijn rol uit `FSC_ROL` leest en blijft draaien. Dat is hier geen omweg maar
# precies wat de gesplitste opzet nodig heeft: de twee helften coördineren niet met elkaar, ze
# convergeren. De consumer dient in en wacht; de provider tekent zodra het contract via de mesh bij
# hem binnenkomt. Wie van de twee als eerste start, doet er daardoor niet toe.
#
# Blijven draaien na succes is ook de herstelweg: wordt een contract ingetrokken of raakt een peer
# zijn state kwijt, dan zet de volgende ronde het opnieuw op. Daarom een lange interval na succes
# in plaats van slapend blijven hangen — één call per peer per interval is verwaarloosbaar, en de
# alternatieven (nooit meer kijken, of blijven pollen op het korte interval) zijn allebei slechter.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

ROL="${FSC_ROL:?zet FSC_ROL op 'consumer' of 'provider'}"

# Kort interval zolang er nog iets moet gebeuren, lang interval als alles staat.
WACHT="${FSC_LUS_WACHT:-15}"
HERHAAL="${FSC_LUS_HERHAAL:-300}"

case "$ROL" in
  consumer) HELFT="${HERE}/bootstrap-consumer.sh" ;;
  provider) HELFT="${HERE}/bootstrap-provider.sh" ;;
  *) echo "FAIL: FSC_ROL moet 'consumer' of 'provider' zijn, niet '${ROL}'." >&2; exit 2 ;;
esac

echo "zad-lus: rol=${ROL}, interval ${WACHT}s tot het staat, daarna ${HERHAAL}s."

# Stoppen moet meteen kunnen. Zonder dit blijft de container in een `sleep` hangen tot de
# stop-timeout van de orchestrator verloopt, en dat vertraagt elke herstart en elke rollout.
STOPPEN=0
trap 'STOPPEN=1' TERM INT

# pauzeer <seconden>: onderbreekbaar wachten. Een kale `sleep` vangt het signaal pas als hij klaar
# is; door hem naar de achtergrond te zetten en erop te wachten, breekt `wait` af op het signaal.
pauzeer() {
  sleep "$1" &
  wait $! 2>/dev/null || true
}

MISLUKT=0

while [ "$STOPPEN" -eq 0 ]; do
  rc=0
  "$HELFT" || rc=$?

  case "$rc" in
    0)
      MISLUKT=0
      pauzeer "$HERHAAL"
      ;;
    3)
      # Alleen de consumer-helft: ingediend, de provider moet nog tekenen. Geen fout.
      MISLUKT=0
      pauzeer "$WACHT"
      ;;
    2)
      # Configuratie deugt niet. Doorgaan heeft geen zin en stil blijven draaien zou het verbergen;
      # afbreken maakt er een zichtbare crashloop van.
      echo "FAIL: configuratiefout in de ${ROL}-helft; de lus stopt." >&2
      exit 2
      ;;
    *)
      MISLUKT=$((MISLUKT + 1))
      echo "WARN: de ${ROL}-helft faalde (exit ${rc}), poging ${MISLUKT}; opnieuw over ${WACHT}s." >&2
      pauzeer "$WACHT"
      ;;
  esac
done

echo "zad-lus: gestopt op verzoek."
