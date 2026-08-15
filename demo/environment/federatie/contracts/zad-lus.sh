#!/usr/bin/env bash
# Entrypoint van het contract-bootstrap-component op ZAD: draai één helft van de bootstrap, herhaald.
#
# ZAD kent alleen langlopende componenten — er is geen job-vorm en geen manier om args mee te geven.
# Vandaar een lus die zijn rol uit `FSC_ROL` leest en blijft draaien. Dat is hier geen omweg maar
# precies wat de gesplitste opzet nodig heeft: de twee helften coördineren niet met elkaar, ze
# convergeren. Wie van de twee als eerste start, doet er daardoor niet toe.
#
# Blijven draaien na succes is ook de herstelweg: wordt een contract ingetrokken of raakt een peer
# zijn state kwijt, dan zet de volgende ronde het opnieuw op. Daarom een lang interval na succes in
# plaats van slapend blijven hangen.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBDIR="${FSC_LIBDIR:-$(cd "${HERE}/../../lib" && pwd)}"

# shellcheck source=../../lib/fsc-harness.sh
. "${LIBDIR}/fsc-harness.sh"
# shellcheck source=../../lib/fsc-contract.sh
. "${LIBDIR}/fsc-contract.sh"

# Via de helper en niet via `${FSC_ROL:?}`: die eindigt op 1, en 1 is hier "probeer opnieuw".
ROL="$(fsc_env_vereist FSC_ROL "'consumer' of 'provider'")"

# Kort interval zolang er nog iets moet gebeuren, lang interval als alles staat.
#
# Het lange interval bewaakt een gebeurtenis die zelden voorkomt — een ingetrokken contract of een
# peer die zijn state kwijt is — terwijl het contract zelf tien jaar geldig is. Een uur is daarvoor
# ruim genoeg; korter pollen kost per peer honderdduizenden calls per jaar om iets te vinden dat er
# een paar keer is.
WACHT="$(fsc_getal_vereist FSC_LUS_WACHT "${FSC_LUS_WACHT:-15}")"
HERHAAL="$(fsc_getal_vereist FSC_LUS_HERHAAL "${FSC_LUS_HERHAAL:-3600}")"

# Na dit aantal opeenvolgende mislukkingen stopt de lus. Een blijvende fout — verlopen cert, manager
# die de client weigert — wordt door opnieuw proberen nooit beter, en een component dat eeuwig
# doordraait ziet er op het platform gezond uit. Afbreken maakt er een zichtbare crashloop van.
#
# Acht en niet twintig: met de ladder hieronder is acht pogingen ongeveer 32 minuten, en twintig
# zou ruim twaalf uur zijn. Een container die eens per twaalf uur afsluit, is voor het platform geen
# crashloop maar een herstart — precies het signaal dat deze grens moet opleveren.
MAX_MISLUKT="$(fsc_getal_vereist FSC_LUS_MAX_MISLUKT "${FSC_LUS_MAX_MISLUKT:-8}")"

# Idem voor "ik wacht al heel lang op de overkant": geen fout, maar wel iets om te melden in plaats
# van eindeloos dezelfde regel te herhalen.
MELD_WACHT_NA="$(fsc_getal_vereist FSC_LUS_MELD_WACHT_NA "${FSC_LUS_MELD_WACHT_NA:-20}")"

# En een bovengrens op het wachten zelf. Een consumer die eeuwig op de provider wacht, is de enige
# permanente blokkade die geen crashloop oplevert — terwijl de oorzaak (onze OIN staat niet in de
# allowlist van de overkant) juist een van de waarschijnlijkste is. Zonder grens blijft het component
# "Running" en ziet het platform niets.
MAX_WACHT="$(fsc_getal_vereist FSC_LUS_MAX_WACHT "${FSC_LUS_MAX_WACHT:-200}")"

case "$ROL" in
  consumer) HELFT="${FSC_HELFT_CONSUMER:-${HERE}/bootstrap-consumer.sh}" ;;
  provider) HELFT="${FSC_HELFT_PROVIDER:-${HERE}/bootstrap-provider.sh}" ;;
  *) echo "FAIL: FSC_ROL moet 'consumer' of 'provider' zijn, niet '${ROL}'." >&2; exit 2 ;;
esac

echo "zad-lus: rol=${ROL}, interval ${WACHT}s tot het staat, daarna ${HERHAAL}s."

# Stoppen moet zonder de stop-timeout uit te zitten. De trap zet alleen een vlag: bash voert een
# handler pas uit als het lopende voorgrondcommando klaar is, dus komt het signaal binnen tijdens de
# helft, dan gebeurt er hier niets tot die terug is. `pauzeer` toetst de vlag daarom zelf ook — zonder
# dat zou de lus na een TERM alsnog het volle interval afwachten voordat de conditie weer aan bod komt.
STOPPEN=0
trap 'STOPPEN=1' TERM INT

pauzeer() {
  [ "$STOPPEN" -eq 0 ] || return 0

  # Een kale `sleep` vangt het signaal pas als hij klaar is; naar de achtergrond en `wait` erop
  # breekt wél af op het signaal.
  sleep "$1" &
  wait $! 2>/dev/null || true
}

MISLUKT=0
GEWACHT=0

while [ "$STOPPEN" -eq 0 ]; do
  rc=0
  "$HELFT" || rc=$?

  case "$rc" in
    0)
      MISLUKT=0
      GEWACHT=0
      pauzeer "$HERHAAL"
      ;;
    3)
      # Wachten op de overkant: de consumer heeft ingediend en de provider moet nog tekenen, of de
      # provider heeft nog niets binnen. Geen fout — maar wel iets dat blijvend kan zijn, bijvoorbeeld
      # als de OIN niet in de allowlist van de overkant staat. Dan hoort het te gaan opvallen in
      # plaats van elke ronde dezelfde regel te herhalen.
      MISLUKT=0
      GEWACHT=$((GEWACHT + 1))

      if [ "$GEWACHT" -ge "$MAX_WACHT" ]; then
        echo "FAIL: ${GEWACHT} rondes gewacht zonder dat het contract geldig werd; de lus stopt." >&2
        echo "  Kijk aan providerkant naar de WEIGER-regels en naar FSC_DIENSTEN/FSC_CONSUMERS." >&2
        exit 1
      fi

      if [ "$GEWACHT" -eq "$MELD_WACHT_NA" ]; then
        echo "WARN: al ${GEWACHT} rondes (~$((GEWACHT * WACHT))s) wachten op de andere helft." >&2
        echo "  Controleer daar FSC_DIENSTEN/FSC_CONSUMERS en de WEIGER-regels in de log." >&2
      fi

      # Na de melding op het lange interval verder: blijven pollen verandert er niets aan.
      [ "$GEWACHT" -lt "$MELD_WACHT_NA" ] && pauzeer "$WACHT" || pauzeer "$HERHAAL"
      ;;
    2)
      echo "FAIL: configuratiefout in de ${ROL}-helft; de lus stopt." >&2
      exit 2
      ;;
    4)
      # Provider-helft: er lagen contracten die de toets niet haalden en niets mocht getekend worden.
      # Geen storing, dus niet opnieuw proberen op het korte interval — de allowlist verandert alleen
      # doordat iemand hem aanpast. Maar het telt wél mee als wachten: anders is dit de enige
      # permanente blokkade waarop de lus nooit escaleert, en blijft het component uren gezond ogen
      # terwijl er niets gebeurt.
      MISLUKT=0
      GEWACHT=$((GEWACHT + 1))

      if [ "$GEWACHT" -ge "$MAX_WACHT" ]; then
        echo "FAIL: ${GEWACHT} rondes zonder dat er iets getekend kon worden; de lus stopt." >&2
        echo "  Kijk naar de WEIGER-regels hierboven en naar FSC_DIENSTEN/FSC_CONSUMERS." >&2
        exit 1
      fi

      pauzeer "$HERHAAL"
      ;;
    *)
      MISLUKT=$((MISLUKT + 1))

      if [ "$MISLUKT" -ge "$MAX_MISLUKT" ]; then
        echo "FAIL: de ${ROL}-helft faalde ${MISLUKT} keer op rij; de lus stopt." >&2
        exit 1
      fi

      # Exponentieel terug, afgetopt op het lange interval: een manager die weg is, wordt niet
      # sneller bereikbaar door hem elke 15 seconden opnieuw te bevragen.
      pauze="$WACHT"
      for _ in $(seq 2 "$MISLUKT"); do
        pauze=$((pauze * 2))
        [ "$pauze" -lt "$HERHAAL" ] || { pauze="$HERHAAL"; break; }
      done

      echo "WARN: de ${ROL}-helft faalde (exit ${rc}), poging ${MISLUKT}; opnieuw over ${pauze}s." >&2
      pauzeer "$pauze"
      ;;
  esac
done

# De lus verlaat zijn conditie alleen als STOPPEN gezet is; valt hij er om een andere reden uit, dan
# doet `set -e` dat met een non-zero status. Een nulafsluiting hier betekent dus altijd een
# stopverzoek.
echo "zad-lus: gestopt op verzoek."
