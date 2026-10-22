#!/usr/bin/env bash
# Beoordeelt na afloop van een preview-opruiming wat er gebeurd is: gelukt, niets te doen, of
# mislukt. Precies één van de drie, als regel in de job-samenvatting én als annotatie.
#
# Bestaat omdat de opruimjobs zichzelf legitiem overslaan zodra `doel` het PR-nummer afwijst of
# omvalt: dan staan ze allemaal op 'skipped', en dat is in de Actions-tab niet te onderscheiden van
# "er viel niets op te ruimen". Op een gesloten PR kijkt daar niemand meer naar. Elke onbepaalde
# uitkomst is hier daarom een fout, geen stilte.
#
# "Niets te doen" en "gelukt" onderscheiden we aan wat de opruimjobs zelf aantroffen. Een preview
# die nooit is aangemaakt (een docs-PR, een fork-PR, een gestapelde PR) is een geldige toestand en
# levert dus een groene uitkomst op, geen alarm.
#
# Contract: alle invoer via de omgeving, diagnostiek op stdout, de exitcode is het oordeel.
#   CANCELLED github.cancelled() — of de RUN is afgebroken
#   NEEDS     toJSON(needs), met per job zijn `result` en `outputs`:
#     doel                    outputs.pr
#     cleanup-preview-zad     outputs.bestond-<project>: true | false | onbekend, per matrix-leg
#     cleanup-preview-github  outputs.comments, outputs.deployments (aantallen verwijderd) en
#                             outputs.omgeving (HTTP-status van de DELETE)
#     cleanup-preview-images  outputs.versies (aantal verwijderd)
set -Eeuo pipefail

# Vaste lijst zodat een verdwenen job een fout oplevert in plaats van een kortere lus die groen
# meldt over minder werk dan er is; test-opruim-oordeel.sh kruist hem met cleanup-preview.yml.
OPRUIM_JOBS='cleanup-preview-zad cleanup-preview-github cleanup-preview-images'

mislukt() {
  echo "::error title=Opruiming mislukt::$1"
  samenvatting "mislukt" "$1"

  exit 1
}

# Vangnet voor alles wat niet via `mislukt` loopt: een ontbrekende variabele, een omgevallen jq.
# Zonder dit eindigt zo'n afbreking wel non-zero maar zonder annotatie.
onverwacht() {
  echo "::error title=Opruiming onbepaald::opruim-oordeel.sh brak af op regel $2 met exitcode $1 — de uitkomst is onbepaald."

  exit "$1"
}

samenvatting() {
  # Buiten Actions (de unittests) is er geen samenvatting om naar te schrijven.
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0

  echo "**Opruiming ${PR_LABEL:-van de preview}: $1** — $2" >>"$GITHUB_STEP_SUMMARY"
}

# Eén veld uit NEEDS; een ontbrekende job of output wordt leeg. De status teruggeven en hier géén
# `mislukt` aanroepen: deze functie draait in een command-substitutie, dus een `exit` zou alleen de
# subshell doden.
veld() {
  jq -r --arg j "$1" --arg v "$2" '.[$j] | getpath($v | split(".")) // "" | tostring' <<<"$NEEDS"
}

# Een aantal dat een opruimjob rapporteerde, in de variabele met dezelfde naam als de output. Leeg
# of geen getal betekent dat de job zijn deel van het contract niet nakwam; dat mag niet als "nul
# verwijderd" doorgaan, want dan leest een opruiming die iets wegnam als "niets te doen".
#
# Geen command-substitutie: `mislukt` moet de hele beoordeling beëindigen, niet alleen een subshell.
lees_aantal() {
  local job=$1 output=$2 waarde

  waarde=$(veld "$job" "outputs.$output") \
    || mislukt "NEEDS is geen bruikbare toJSON(needs)-uitvoer — de uitkomst is onbepaald."

  case "$waarde" in
    '' | *[!0-9]*)
      mislukt "$job meldde '$waarde' als $output in plaats van een aantal — het contract met cleanup-preview.yml is verbroken."
      ;;
  esac

  printf -v "$output" '%s' "$waarde"
}

beoordeel() {
  # Een afgebroken run bewijst niets, en is aan de job-resultaten niet te herkennen: de opruimjobs
  # die nog niet begonnen waren, staan dan op 'skipped' of 'cancelled'.
  if [ "$CANCELLED" != false ]; then
    mislukt "De run is afgebroken; wat er is blijven staan, is niet vastgesteld."
  fi

  jq -e 'type == "object"' <<<"$NEEDS" >/dev/null 2>&1 \
    || mislukt "NEEDS is geen bruikbare toJSON(needs)-uitvoer — de uitkomst is onbepaald."

  local doel pr
  doel=$(veld doel result) && pr=$(veld doel outputs.pr) \
    || mislukt "NEEDS is geen bruikbare toJSON(needs)-uitvoer — de uitkomst is onbepaald."
  PR_LABEL="van pr-${pr:-?}"

  # `doel` faalt op een ongeldig of open PR-nummer; dan is er bewust niets opgeruimd, maar de
  # vraag waarvoor de run gestart werd, is ook niet beantwoord.
  if [ "$doel" != success ] || [ -z "$pr" ]; then
    mislukt "Het doel eindigde als '${doel:-ontbreekt}' (PR '${pr}') — er is niets opgeruimd. Zie de job 'doel' voor de reden."
  fi

  local job resultaat niet_gelukt=""

  for job in $OPRUIM_JOBS; do
    resultaat=$(veld "$job" result)

    [ "$resultaat" = success ] || niet_gelukt="$niet_gelukt $job=${resultaat:-ontbreekt}"
  done

  if [ -n "$niet_gelukt" ]; then
    mislukt "Niet elke opruimjob slaagde:${niet_gelukt}. Herdraai met: gh workflow run cleanup-preview.yml -f pr=$pr"
  fi

  # Wat er aangetroffen en verwijderd is. Een ZAD-leg met 'onbekend' heeft de delete gedraaid en de
  # afwezigheid daarna bewezen; die telt dus als werk.
  local zad comments deployments omgeving versies werk=()

  zad=$(jq -r '.["cleanup-preview-zad"].outputs // {} | to_entries[]
    | select(.key | startswith("bestond-")) | "\(.key | ltrimstr("bestond-"))=\(.value)"' <<<"$NEEDS") \
    || mislukt "NEEDS is geen bruikbare toJSON(needs)-uitvoer — de uitkomst is onbepaald."

  [ -n "$zad" ] \
    || mislukt "cleanup-preview-zad meldde voor geen enkel project of er een preview stond — het contract met cleanup-preview.yml is verbroken."

  local naam waarde

  while IFS='=' read -r naam waarde; do
    case "$waarde" in
      true | onbekend) werk+=("ZAD-deployment in $naam") ;;
      false) ;;
      *) mislukt "cleanup-preview-zad meldde '$waarde' voor $naam in plaats van true/false/onbekend." ;;
    esac
  done <<<"$zad"

  lees_aantal cleanup-preview-github comments
  lees_aantal cleanup-preview-github deployments
  lees_aantal cleanup-preview-github omgeving
  lees_aantal cleanup-preview-images versies

  [ "$comments" -eq 0 ] || werk+=("$comments deploy-comment(s)")
  [ "$deployments" -eq 0 ] || werk+=("$deployments GitHub-deployment(s)")
  # 204 is de enige status waarmee GitHub een bestaande omgeving als verwijderd bevestigt; een 404
  # is hier "bestond niet", want de nameting in de job heeft de afwezigheid al bewezen.
  [ "$omgeving" != 204 ] || werk+=("GitHub-omgeving")
  [ "$versies" -eq 0 ] || werk+=("$versies ghcr-versie(s)")

  if [ "${#werk[@]}" -eq 0 ]; then
    echo "::notice title=Opruiming: niets te doen::pr-$pr had geen preview-resten; er is niets verwijderd."
    samenvatting "niets te doen" "er stond niets van deze PR."

    return 0
  fi

  local lijst
  lijst=$(printf '%s, ' "${werk[@]}")
  lijst=${lijst%, }

  echo "::notice title=Opruiming gelukt::pr-$pr opgeruimd en nagemeten: $lijst."
  samenvatting "gelukt" "verwijderd en nagemeten: $lijst."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen. De trap
# hangt aan die tak, zodat sourcen de aanroeper niet omver trekt.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  trap 'onverwacht "$?" "$LINENO"' ERR

  # `set -u` beëindigt de shell op een ontbrekende variabele zónder de ERR-trap te draaien, dus
  # zonder annotatie. Het contract met de workflow wordt daarom expliciet gecontroleerd.
  for naam in CANCELLED NEEDS; do
    [ -n "${!naam+gezet}" ] \
      || mislukt "Omgevingsvariabele $naam ontbreekt — het contract met cleanup-preview.yml is verbroken."
  done

  beoordeel
fi
