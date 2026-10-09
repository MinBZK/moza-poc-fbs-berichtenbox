#!/usr/bin/env bash
# Unittests voor zad-taak-lib.sh: het wachten op een taak van Operations Manager.
#
# Wat hier bewaakt wordt, zijn twee kanten van dezelfde lus. Eén mislukte opvraging mag de stap niet
# afbreken — de deployment is dan al aangemaakt, en alleen de uitkomst raakt zoek. En een fout die
# blijft, mag niet de hele wachttijd uitzitten of als "nog niet klaar" eindigen.
#
# Geen netwerk en geen echte seconden: `$CURL` en `sleep` zijn functies die een geregisseerde reeks
# afspelen. Eén geval onderaan draait echte curl tegen een lokale server, omdat de stub de
# statuscode zelf achter de body zet en dus niet bewijst dat curl dat ook doet.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly LIB="$REPO_ROOT/.github/scripts/zad-taak-lib.sh"

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

faal() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    faal "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) faal "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) faal "$wat — '$naald' staat er toch in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

# shellcheck source=.github/scripts/zad-taak-lib.sh
source "$LIB"

werkmap=$(mktemp -d)
serverpid=""
trap '[ -z "$serverpid" ] || kill "$serverpid" 2>/dev/null || true; rm -rf "$werkmap"' EXIT

# Speelt per aanroep één stap van de reeks af; is de reeks op, dan blijft de laatste stap gelden.
# De teller staat in een bestand: de lib roept `$CURL` in een subshell aan.
#
#   curl:<n>      curl zelf faalt met exitcode n
#   http:<code>   een antwoord met die status en een body die geen taak is
#   <status>      HTTP 200 met een taak in die toestand
nepcurl() {
  local n stap

  printf '%s\n' "$*" >"$werkmap/argumenten"

  n=$(($(cat "$werkmap/teller") + 1))
  echo "$n" >"$werkmap/teller"

  stap=$(sed -n "${n}p" "$werkmap/reeks")
  [ -n "$stap" ] || stap=$(tail -1 "$werkmap/reeks")

  case "$stap" in
    curl:*) return "${stap#curl:}" ;;
    http:*) printf '<html>storing</html>\n%s' "${stap#http:}" ;;
    *) printf '{"task_id":"t-1","status":"%s"}\n200' "$stap" ;;
  esac
}

CURL=nepcurl

# Slapen kost de suite geen tijd en de lus standaard ook niet: zo telt elke test rondes, los van hoe
# snel de machine is. Met SLAAPSTAP zet een test de klok van de lus per ronde vooruit.
sleep() {
  SECONDS=$((SECONDS + ${SLAAPSTAP:-0}))
}

# Draait het wachten op de gegeven reeks, in een subshell omdat de `fout` van het aanroepende script
# de stap afbreekt. Zet $RC, $UITVOER, $OPVRAGINGEN en bij succes $STATUS.
wacht() {
  printf '%s\n' "$@" >"$werkmap/reeks"
  echo 0 >"$werkmap/teller"

  RC=0
  UITVOER=$(
    fout() {
      echo "::error::$*"

      exit 1
    }

    zad_wacht_op_taak http://api.test sleutel t-1 "uitkomst onbekend" "staat mogelijk niet" \
      completed failed cancelled 2>&1

    echo "STATUS=$zad_taak_status"
  ) || RC=$?

  OPVRAGINGEN=$(cat "$werkmap/teller")
  STATUS=$(sed -n 's/^STATUS=//p' <<<"$UITVOER")
}

herhaal() {
  local n=$1 stap=$2

  for _ in $(seq "$n"); do
    echo "$stap"
  done
}

# --- geen enkele mislukte opvraging ---------------------------------------------------------------
wacht completed
gelijk "een taak die meteen klaar is, is klaar" "0 completed 1" "$RC $STATUS $OPVRAGINGEN"

# De klok van de lus wordt alleen tússen twee opvragingen gelezen: zonder eigen grens loopt één
# opvraging die blijft hangen door tot de time-out van de job.
bevat "elke opvraging heeft een eigen tijdsgrens" '--max-time 10 ' "$(cat "$werkmap/argumenten")"
bevat "en gaat naar de taak, met de sleutel" 'X-API-Key: sleutel http://api.test/tasks/t-1' "$(cat "$werkmap/argumenten")"

wacht running running completed
gelijk "een taak die nog loopt wordt opnieuw opgevraagd" "0 completed 3" "$RC $STATUS $OPVRAGINGEN"

# Welke eindtoestand het is, beoordeelt de aanroeper; de lib meldt hem alleen.
wacht running failed
gelijk "een gefaalde taak komt als eindtoestand terug" "0 failed 2" "$RC $STATUS $OPVRAGINGEN"

# --- voorbijgaande fouten ---------------------------------------------------------------------------
# Het geval uit de praktijk: de deployment stond al, één GET mislukte, en de stap brak af.
wacht running http:503 completed
gelijk "één mislukte opvraging breekt het wachten niet af" "0 completed 3" "$RC $STATUS $OPVRAGINGEN"
bevat "en staat met zijn oorzaak in de log" 'HTTP 503' "$UITVOER"

wacht curl:7 completed
gelijk "een fout direct bij de eerste ronde ook niet" "0 completed 2" "$RC $STATUS $OPVRAGINGEN"

wacht running running curl:56 completed
gelijk "en een fout vlak voor de eindtoestand evenmin" "0 completed 4" "$RC $STATUS $OPVRAGINGEN"

# Elk soort voorbijgaande fout telt hetzelfde: een 5xx, een 429, een verbroken verbinding, een taak
# die er net nog niet is, en een antwoord zonder statuscode.
for soort in http:500 http:502 http:429 http:404 curl:28 curl:52; do
  wacht "$soort" completed
  gelijk "een voorbijgaande $soort wordt herhaald" "0 completed 2" "$RC $STATUS $OPVRAGINGEN"
done

# Niet aaneengesloten: elke geslaagde opvraging zet de telling terug. Samen zijn dit er meer dan de
# grens, maar nooit zoveel op rij.
readarray -t afwisselend < <(
  for _ in $(seq 3); do
    herhaal $((ZAD_TAAK_MAX_MISLUKT - 1)) http:503
    echo running
  done
  echo completed
)
wacht "${afwisselend[@]}"
gelijk "veel mislukkingen die niet aaneengesloten zijn breken het wachten niet af" \
  "0 completed ${#afwisselend[@]}" "$RC $STATUS $OPVRAGINGEN"

readarray -t net_onder < <(
  herhaal $((ZAD_TAAK_MAX_MISLUKT - 1)) curl:7
  echo completed
)
wacht "${net_onder[@]}"
gelijk "één mislukking minder dan de grens is nog geen fout" "0 completed $ZAD_TAAK_MAX_MISLUKT" "$RC $STATUS $OPVRAGINGEN"

# --- een fout die blijft ------------------------------------------------------------------------------
wacht running http:503
gelijk "een aanhoudende reeks mislukkingen stopt het wachten" 1 "$RC"
gelijk "precies bij de grens, niet pas na de hele wachttijd" $((ZAD_TAAK_MAX_MISLUKT + 1)) "$OPVRAGINGEN"
bevat "de melding zegt dat de uitkomst onbekend is" 'uitkomst onbekend' "$UITVOER"
bevat "en waarom het opvragen mislukte" 'laatste: HTTP 503' "$UITVOER"
bevat "als fout voor de job" '::error::' "$UITVOER"

wacht curl:6
gelijk "ook als het vanaf de eerste ronde misgaat" "1 $ZAD_TAAK_MAX_MISLUKT" "$RC $OPVRAGINGEN"
bevat "met de exitcode van curl als er geen HTTP-status was" 'laatste: curl-exitcode 6' "$UITVOER"

# De melding noemt de láátste oorzaak: begon het als 5xx en eindigde het als time-out, dan is die
# time-out wat er nu aan de hand is.
readarray -t wisselend < <(
  herhaal 5 http:503
  echo curl:28
)
wacht "${wisselend[@]}"
bevat "bij wisselende oorzaken noemt de melding de laatste" 'laatste: curl-exitcode 28' "$UITVOER"

# --- een fout waar herhalen niets aan verandert -----------------------------------------------------
for code in 401 403; do
  wacht running "http:$code" completed
  gelijk "een $code stopt het wachten meteen" "1 2" "$RC $OPVRAGINGEN"
  bevat "en noemt de status" "HTTP $code" "$UITVOER"
  bevat "en dat de uitkomst onbekend is" 'uitkomst onbekend' "$UITVOER"
done

# --- de wachttijd ------------------------------------------------------------------------------------
wacht running
gelijk "een taak die niet klaar komt stopt na de wachttijd" "1 $ZAD_TAAK_RONDES" "$RC $OPVRAGINGEN"
bevat "met de melding dat hij niet klaar was" 'nog niet klaar; staat mogelijk niet' "$UITVOER"
bevat_niet "en niet dat hij niet op te vragen was" 'niet op te vragen' "$UITVOER"

# Herhalen rekt de wachttijd niet op: de klok begrenst wat trage opvragingen bovenop de rondes
# leggen. Hier kost elke ronde dertig seconden, dus na vier is de tijd om.
SLAAPSTAP=30 wacht running
gelijk "trage rondes stoppen op de klok, niet op het aantal rondes" "1 4" "$RC $OPVRAGINGEN"

# Loopt de tijd af midden in een reeks mislukkingen, dan is "nog niet klaar" een uitspraak over de
# taak die niemand heeft kunnen doen.
SLAAPSTAP=30 wacht running running http:503
gelijk "de klok stopt ook een reeks mislukkingen" "1 4" "$RC $OPVRAGINGEN"
bevat "en zegt dan dat de taak niet op te vragen was" 'niet op te vragen binnen twee minuten (laatste 2 opvragingen mislukt: HTTP 503)' "$UITVOER"
bevat_niet "en niet dat hij nog niet klaar was" 'nog niet klaar' "$UITVOER"

# --- onbruikbare antwoorden ---------------------------------------------------------------------------
# HTTP 200 zonder leesbare taak is geen eindtoestand en geen mislukte opvraging: de lus vraagt door.
printf '%s\n' 'http:200' completed >"$werkmap/reeks"
echo 0 >"$werkmap/teller"
RC=0
UITVOER=$(
  fout() { exit 1; }

  zad_wacht_op_taak http://api.test sleutel t-1 a b completed 2>&1
  echo "STATUS=$zad_taak_status"
) || RC=$?
gelijk "een 200 zonder taak erin wordt opnieuw opgevraagd" "0 2" "$RC $(cat "$werkmap/teller")"
bevat_niet "en telt niet als mislukte opvraging" 'mislukt' "$UITVOER"

# --- echte curl ------------------------------------------------------------------------------------
# De stub hierboven zet de statuscode zelf achter de body. Dit geval bewijst dat curl dat met deze
# vlaggen ook doet: twee keer 503, daarna een taak die klaar is; en een 401 die meteen stopt.
cat >"$werkmap/server.py" <<'PY'
import http.server
import sys

reeks = [int(code) for code in sys.argv[2:]]


class Taak(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        code = reeks.pop(0) if len(reeks) > 1 else reeks[0]
        body = b'{"task_id":"t-1","status":"completed"}' if code == 200 else b"storing"
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Taak)

with open(sys.argv[1], "w", encoding="utf-8") as bestand:
    bestand.write(str(server.server_address[1]))

server.serve_forever()
PY

echte_curl() {
  local poort

  rm -f "$werkmap/poort"
  python3 "$werkmap/server.py" "$werkmap/poort" "$@" &
  serverpid=$!

  for _ in $(seq 50); do
    [ -s "$werkmap/poort" ] && break
    command sleep 0.1
  done

  poort=$(cat "$werkmap/poort" 2>/dev/null || true)

  RC=0
  UITVOER=$(
    fout() {
      echo "::error::$*"

      exit 1
    }

    CURL=curl
    # Een proxy uit de omgeving zou het verzoek van de lokale server weghalen.
    export no_proxy=127.0.0.1 NO_PROXY=127.0.0.1
    zad_wacht_op_taak "http://127.0.0.1:$poort" sleutel t-1 "uitkomst onbekend" "staat mogelijk niet" completed 2>&1
    echo "STATUS=$zad_taak_status"
  ) || RC=$?

  kill "$serverpid" 2>/dev/null || true
  wait "$serverpid" 2>/dev/null || true
  serverpid=""
}

echte_curl 503 503 200
gelijk "echte curl: twee 503's en dan klaar" 0 "$RC"
bevat "echte curl: de status van de taak komt uit de body" 'STATUS=completed' "$UITVOER"
gelijk "echte curl: beide 503's zijn als zodanig herkend" 2 "$(grep -c 'HTTP 503' <<<"$UITVOER")"

echte_curl 401
gelijk "echte curl: een 401 stopt meteen" 1 "$RC"
bevat "echte curl: en is als 401 herkend" 'HTTP 401' "$UITVOER"

echo
if [ "$mislukt" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "Er zijn tests mislukt."
fi

echo "ASSERTIES=$asserties"

exit "$mislukt"
