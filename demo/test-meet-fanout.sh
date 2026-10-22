#!/usr/bin/env bash
# Fixture-tests voor demo/meet-fanout.sh: begint een ronde alleen met een aantoonbaar geleegde
# sessiecache, en stopt anders met een melding die zegt wat er misging en tegen welk adres.
#
# Het legen loopt via het bedieningspaneel, en dat staat op een gedeelde omgeving achter een
# inlogmuur. Zo'n muur kan een aanroep zonder geldige sessie weigeren, doorverwijzen of de
# inlogpagina meteen met 200 teruggeven. Mist de meting een van die vormen, dan begint een ronde
# zonder dat er geleegd is, en niets in de uitvoer laat dat zien. Een handmatige run raakt die
# vormen niet, want wie meet, heeft een geldig cookie.
#
# Er draait een echte HTTP-server en geen curl-stub: wat curl met een 3xx, een 401 of een eigen
# .curlrc doet, is precies wat hier getoetst wordt, en een stub zou dat gedrag aannemen in plaats
# van toetsen. Eén nep-server speelt uitvraag én paneel, met de sessies per ondernemer in het
# geheugen; de modus van het paneel staat in een bestand dat hij per aanroep leest, zodat niet elk
# geval een eigen server hoeft te starten.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/meet-fanout.sh"

fails=0

ok() {
    echo "OK: $1"
}

fout() {
    echo "FAIL: $1" >&2
    fails=$((fails + 1))
}

TMP="$(mktemp -d)"
NEP_PID=""

opruimen() {
    if [ -n "$NEP_PID" ]; then
        kill "$NEP_PID" 2>/dev/null || true
    fi

    rm -rf "$TMP"
}

trap opruimen EXIT

cat > "$TMP/nep.py" <<'PY'
import http.server
import json
import sys
import time

modusbestand, logbestand, poortbestand, cookiebestand = sys.argv[1:5]

with open(cookiebestand, encoding="utf-8") as bestand:
    muurcookie = bestand.read()

# Over meerdere regels en met een afsluitende newline, zoals een echte inlogpagina: zo toetst elk
# geval dat hem gebruikt ook dat het script de `-w`-regel op de láátste regel afsplitst.
INLOGPAGINA = b"""<!DOCTYPE html>
<html>
<body>
  <h1>Inloggen</h1>
  <form>...</form>
</body>
</html>
"""


def stream(status_magazijn, geslaagd, mislukt):
    return "".join(
        f"data: {json.dumps(event)}\n\n"
        for event in (
            {"event": "magazijn-bevraging-gestart"},
            {"event": "magazijn-bevraging-voltooid", "status": status_magazijn},
            {"event": "ophalen-gereed", "geslaagd": geslaagd, "mislukt": mislukt, "nietOpgehaald": 0},
        )
    ).encode()


# Genoeg om de meting een complete ronde te laten zien; de tellingen zelf doen hier niet ter zake.
STREAM_GESLAAGD = stream("OK", 1, 0)
# Een ronde waarin geen enkel magazijn slaagt: dan is "eerste bericht" leeg en "compleet" niet.
STREAM_GEEN_GESLAAGD = stream("FOUT", 0, 1)

# Relatief en met query: zo toetst dit geval ook dat curl er een absoluut adres van maakt en dat de
# melding de query weglaat.
REDIRECT = "/oauth2/start?rd=%2Fapi%2Fdemo%2Fsessie%2Fverlopen"



def probleem(status, code):
    body = {"type": f"urn:fbs:fout:{code}", "title": code, "status": status, "detail": "nep"}
    return (status, "application/problem+json", json.dumps(body).encode())


# De antwoorden van de uitvraag in de vorm van haar spec (`Problem`, `BerichtenLijst`).
NOG_NIET_OPGEHAALD = probleem(409, "nog-niet-opgehaald")
UITVRAAG_FOUTEN = {
    "uitvraag-bezig": probleem(409, "ophalen-bezig"),
    "uitvraag-mislukt": probleem(503, "ophalen-mislukt"),
    "uitvraag-geen-opslag": probleem(503, "tijdelijk-niet-beschikbaar"),
    # Een `type` met escape-codes en een regeleinde: een valse FOUT-regel en een gewiste terminal.
    "uitvraag-escape": (409, "application/problem+json", json.dumps({"type": "urn:x\u001b[2J\nFOUT: vals"}).encode()),
}
LIJST = (200, "application/json", b'{"berichten":[],"aantalNietGeleverd":0,"nietGeleverd":[],"afgekapt":false}')

BEVESTIGD = (200, "application/json", b'{"gewisteKeys":2}')

PANEEL = {
    "bevestigd": BEVESTIGD,
    "nul": (200, "application/json;charset=UTF-8", b'{\n  "gewisteKeys" : 0\n}\n'),
    "302": (302, "text/html", b""),
    "303": (303, None, b""),
    "307": (307, None, b""),
    "302-zonder-adres": (302, None, b""),
    "301-zelfde-pad": (301, None, b""),
    # Een absolute Location en een Content-Type met escape-codes: die codeert curl niet zelf.
    "302-escape": (302, None, b""),
    "inlogpagina-escape": (200, "text/html\u001b]0;TITEL\u0007", INLOGPAGINA),
    "401": (401, "text/html", INLOGPAGINA),
    "403": (403, "text/html; charset=utf-8", INLOGPAGINA),
    "inlogpagina-200": (200, "text/html; charset=utf-8", INLOGPAGINA),
    "204": (204, None, b""),
    "ander-json": (200, "application/json", b'{"status":"ok"}'),
    "getal-als-tekst": (200, "application/json", b'{"gewisteKeys":"2"}'),
    "getal-als-boolean": (200, "application/json", b'{"gewisteKeys":true}'),
    "json-lijst": (200, "application/json", b"[2]"),
    "500": (500, "application/problem+json", b'{"status":500}'),
    # De vorm van DemoFoutMapper: een fout van het paneel zelf, met uitleg.
    "paneel-500": (500, "application/json", b'{"fout":"WRONGPASS invalid username-password\\npair","soort":"RedisException"}'),
    "paneel-403": (403, "application/json", b'{"fout":"Niet toegestaan","soort":"ForbiddenException"}'),
    # Een uitleg die de terminal een titel geeft en het klembord vult (OSC 0 en OSC 52).
    "paneel-escape": (500, "application/json", json.dumps({"fout": "\u001b]0;TITEL\u0007\u001b]52;c;aGFjaw==\u0007kwaad"}).encode()),
    # Groter dan de grens van het script; een paneel dat blijft sturen, vult anders het geheugen.
    "groot": (200, "application/json", b'{"gewisteKeys":2,"opvulling":"' + b"x" * (2 * 1024 * 1024) + b'"}'),
}

# Ontvangers met een sessie in "de" Redis die de uitvraag leest.
sessies = set()


class Nep(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def spoor(self, regel):
        with open(logbestand, "a", encoding="utf-8") as log:
            log.write(regel + "\n")

    def modus(self):
        with open(modusbestand, encoding="utf-8") as bestand:
            return bestand.read().strip()

    def eerdere_legingen(self):
        with open(logbestand, encoding="utf-8") as log:
            return sum(1 for regel in log if regel.startswith("verlopen")) - 1

    def antwoord(self, status, content_type, body, location=None, lengte=None):
        self.send_response(status)

        if content_type:
            self.send_header("Content-Type", content_type)

        if location:
            self.send_header("Location", location)

        if status != 204:
            self.send_header("Content-Length", str(len(body) if lengte is None else lengte))

        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        ontvanger = self.headers.get("X-Ontvanger", "")
        pad, _, query = self.path.partition("?")
        modus = self.modus()

        if pad == "/q/health/ready":
            self.antwoord(200, "application/json", b'{"status":"UP"}')
        elif pad == "/api/v1/berichten/_ophalen":
            self.spoor("ophalen " + ontvanger)

            if modus == "ophalen-409":
                self.antwoord(*UITVRAAG_FOUTEN["uitvraag-bezig"])
                return

            sessies.add(ontvanger)
            self.antwoord(200, "text/event-stream", STREAM_GEEN_GESLAAGD if modus == "geen-geslaagd" else STREAM_GESLAAGD)
        elif pad == "/api/v1/berichten":
            self.spoor(f"berichten {ontvanger} {query} {self.headers.get('Accept', '')}")

            if modus in UITVRAAG_FOUTEN:
                self.antwoord(*UITVRAAG_FOUTEN[modus])
            elif ontvanger in sessies:
                self.antwoord(*LIJST)
            else:
                self.antwoord(*NOG_NIET_OPGEHAALD)
        else:
            self.antwoord(404, None, b"")

    def do_POST(self):
        if self.path == "/nep/reset":
            sessies.clear()
            self.antwoord(204, None, b"")
            return

        if self.path != "/api/demo/sessie/verlopen":
            self.antwoord(404, None, b"")
            return

        cookie = self.headers.get("Cookie", "")
        self.spoor("verlopen " + cookie)
        modus = self.modus()
        leegt = True

        if modus == "muur-traag" and self.eerdere_legingen() == 0:
            # Lang genoeg dat de suite de processen kan bekijken terwijl curl nog wacht.
            time.sleep(1.5)

        if modus in ("muur", "muur-traag"):
            modus = "bevestigd" if cookie == muurcookie else "302"
        elif modus == "eerst-nul":
            # Zoals het echte paneel op een verse stack: de eerste keer is er nog niets te wissen.
            modus = "nul" if self.eerdere_legingen() == 0 else "bevestigd"
        elif modus.startswith("verloopt-na-3:"):
            # Een cookie dat midden in de meting verloopt; daarna antwoordt de muur in de gegeven vorm.
            modus = "bevestigd" if self.eerdere_legingen() < 3 else modus.split(":", 1)[1]
        elif modus.startswith("andere-redis"):
            # Het paneel bevestigt, maar wist in een Redis waar de uitvraag niet uit leest.
            modus, leegt = "bevestigd", False
        elif modus in ("geen-geslaagd", "ophalen-409", "zonder-uitvoer") or modus in UITVRAAG_FOUTEN:
            modus = "bevestigd"
        elif modus == "afgekapt":
            # Het paneel wiste, maar het antwoord komt niet heel aan.
            sessies.clear()
            self.antwoord(*BEVESTIGD, lengte=len(BEVESTIGD[2]) + 10)
            return

        status, content_type, body = PANEEL[modus]

        if leegt and (status, content_type, body) == BEVESTIGD or modus == "nul":
            sessies.clear()

        if modus == "301-zelfde-pad":
            location = "https://elders.invalid/api/demo/sessie/verlopen"
        elif modus == "302-escape":
            location = "https://elders.invalid/\u001b]8;;https://kwaad.invalid\u0007inloggen"
        elif 300 <= status < 400 and modus != "302-zonder-adres":
            location = REDIRECT
        else:
            location = None

        self.antwoord(status, content_type, body, location=location)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Nep)

with open(poortbestand, "w", encoding="utf-8") as bestand:
    bestand.write(str(server.server_address[1]))

server.serve_forever()
PY

MUURCOOKIE="_oauth2_proxy_0=abc; _oauth2_proxy_1=def"

# Via een bestand en niet als argument: de toets op de argumentenlijst hieronder zou het cookie
# anders bij de nep-server zelf vinden.
printf '%s' "$MUURCOOKIE" > "$TMP/muurcookie"

python3 -I "$TMP/nep.py" "$TMP/modus" "$TMP/log" "$TMP/poort" "$TMP/muurcookie" &
NEP_PID=$!

for _ in $(seq 1 100); do
    [ -s "$TMP/poort" ] && break
    sleep 0.05
done

[ -s "$TMP/poort" ] || { echo "FAIL: de nep-server startte niet." >&2; exit 1; }

NEP="http://127.0.0.1:$(cat "$TMP/poort")"
ADRES="$NEP/api/demo/sessie/verlopen"

# Een poort waar niets luistert.
DICHT="http://127.0.0.1:$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"

mkdir -p "$TMP/streams"

# Een lege .curlrc in CURL_HOME: curl neemt het eerste config-bestand dat het vindt, en zoekt dan
# niet verder naar die van wie de suite draait. `no_proxy` om dezelfde reden: een proxy uit de
# omgeving hoort niet tussen het script en de nep-server. DRAAI_CURL_HOME zet per geval een eigen
# .curlrc voorop.
mkdir -p "$TMP/curl-home"
: > "$TMP/curl-home/.curlrc"

VORIGE_METING="uitvoer van een vorige meting"

# Draait het echte script met het paneel in de gegeven modus. Zet UIT (stdout+stderr), RC en LOG.
# UITVOER bevat vooraf de uitvoer van een "vorige meting": een meting die stopt, hoort die te laten
# staan.
draai() {
    local modus="$1" console="${2:-$NEP}" cookie="${3:-}" rondes="${4:-1}"

    printf '%s' "$modus" > "$TMP/modus"
    curl -q -sf -X POST "$NEP/nep/reset"
    : > "$TMP/log"
    printf '%s\n' "$VORIGE_METING" > "$TMP/uit.tsv"

    RC=0
    UIT="$(CURL_HOME="${DRAAI_CURL_HOME:-$TMP/curl-home}" no_proxy=127.0.0.1 NO_PROXY=127.0.0.1 \
        UITVRAAG="$NEP" CONSOLE="$console" CONSOLE_COOKIE="$cookie" UITVOER="$TMP/uit.tsv" \
        TMPDIR="$TMP/streams" bash "$SCRIPT" "$rondes" 2>&1)" || RC=$?
    LOG="$(cat "$TMP/log")"
}

aantal() {
    grep -c "^$1" <<< "$LOG" || true
}

rijen() {
    wc -l < "$TMP/uit.tsv" | tr -d ' '
}

# Toetst dat de meting stopte met de verwachte reden, of de cookie-hint erbij staat (ja/nee), en dat
# de uitvoer van de vorige meting bleef staan.
gestopt() {
    local wat="$1" reden="$2" muur="$3" heeft_hint=nee

    [ "$RC" -ne 0 ] && ok "$wat: de meting stopt (exit $RC)" || fout "$wat: de meting ging door"

    if grep -qF -- "$reden" <<< "$UIT"; then
        ok "$wat: de melding zegt wat er misging"
    else
        fout "$wat: '$reden' ontbreekt in: $UIT"
    fi

    if grep -qF "Staat het bedieningspaneel achter een inlogmuur" <<< "$UIT"; then
        heeft_hint=ja
    fi

    [ "$heeft_hint" = "$muur" ] && ok "$wat: de cookie-hint staat er alleen bij een muur ($muur)" \
        || fout "$wat: cookie-hint is '$heeft_hint', verwacht '$muur'"

    [ "$(cat "$TMP/uit.tsv")" = "$VORIGE_METING" ] && ok "$wat: de uitvoer van de vorige meting blijft staan" \
        || fout "$wat: de uitvoer van de vorige meting is overschreven: $(cat "$TMP/uit.tsv")"
}

# --- geslaagd: de lokale demo-stack, zonder muur ---------------------------------------------------
# Vier ondernemers, twee rondes: elke ronde precies één keer legen vóór het ophalen, en de toets bij
# de uitvraag één keer, vóór de tweede ronde — elke toets is een regel in het logboek van de uitvraag.
verwacht="verlopen ophalen verlopen berichten ophalen"

for _ in $(seq 3 8); do
    verwacht="$verwacht verlopen ophalen"
done

for modus in bevestigd eerst-nul; do
    draai "$modus" "$NEP" "" 2

    [ "$RC" -eq 0 ] && ok "$modus: de meting slaagt" || fout "$modus: de meting faalde (exit $RC): $UIT"

    volgorde="$(awk '{print $1}' <<< "$LOG" | paste -sd' ' -)"

    [ "$volgorde" = "$verwacht" ] && ok "$modus: elke ronde legt eerst de cache leeg, toetst en haalt dan op" \
        || fout "$modus: volgorde van aanroepen is '$volgorde', verwacht '$verwacht'"

    [ "$(rijen)" -eq 9 ] && ok "$modus: het uitvoerbestand heeft een kop en acht rondes" \
        || fout "$modus: het uitvoerbestand heeft $(rijen) regels, verwacht 9"

    if grep -q "FOUT" <<< "$UIT"; then
        fout "$modus: een geslaagde meting meldt een FOUT: $UIT"
    else
        ok "$modus: een geslaagde meting meldt geen FOUT"
    fi

    # Op de ondernemer van de eerste ronde, met zo min mogelijk gelezen als de toets misgaat, en met
    # het type dat de uitvraag bij een fout teruggeeft.
    grep -qxF "berichten BSN:999993653 paginaGrootte=1 application/json, application/problem+json" <<< "$LOG" \
        && ok "$modus: de toets vraagt één bericht van de vorige ondernemer, en accepteert problem+json" \
        || fout "$modus: de toets klopt niet: $(grep '^berichten' <<< "$LOG")"

    grep -qF "verwijder die map zodra de uitvoer als bewijsstuk is vastgelegd" <<< "$UIT" \
        && ok "$modus: de meting meldt dat de ruwe stromen blijven staan" \
        || fout "$modus: geen melding over de ruwe stromen: $UIT"
done

# --- geslaagd: een ronde waarin geen enkel magazijn slaagt -----------------------------------------
# "Eerste bericht" is dan leeg en "compleet" niet; die twee horen niet van kolom te wisselen.
draai geen-geslaagd

eerste_rij="$(sed -n 2p "$TMP/uit.tsv")"

if [ "$RC" -eq 0 ] && [ "$(cut -f8 <<< "$eerste_rij")" = "" ] && [[ "$(cut -f9 <<< "$eerste_rij")" =~ ^[0-9]+$ ]]; then
    ok "geen-geslaagd: eerste_ms blijft leeg en compleet_ms staat in zijn eigen kolom"
else
    fout "geen-geslaagd: verwacht een lege eerste_ms en een getal in compleet_ms (exit $RC): '$eerste_rij'"
fi

# --- geslaagd achter een muur, met het juiste cookie -----------------------------------------------
draai muur "$NEP" "$MUURCOOKIE"

[ "$RC" -eq 0 ] && ok "muur met cookie: de meting slaagt" || fout "muur met cookie: de meting faalde (exit $RC): $UIT"

# Het cookie bevat een spatie na de puntkomma; een ongequote expansie zou het daar splitsen.
if grep -qxF "verlopen $MUURCOOKIE" <<< "$LOG"; then
    ok "muur met cookie: het cookie komt heel bij het paneel aan"
else
    fout "muur met cookie: het paneel kreeg een ander cookie: $LOG"
fi

# --- afgebroken: elke vorm waarin het paneel het eerste legen niet bevestigt ------------------------
# modus|reden in de melding|wijst de melding op de muur (CONSOLE_COOKIE)?
GEVALLEN=(
    "302|$ADRES gaf HTTP 302, doorverwezen naar $NEP/oauth2/start.|ja"
    "303|$ADRES gaf HTTP 303, doorverwezen naar $NEP/oauth2/start.|ja"
    "307|$ADRES gaf HTTP 307, doorverwezen naar $NEP/oauth2/start.|ja"
    "302-zonder-adres|$ADRES gaf HTTP 302.|ja"
    "301-zelfde-pad|$ADRES gaf HTTP 301, doorverwezen naar https://elders.invalid/api/demo/sessie/verlopen: hetzelfde pad op een ander adres, dus CONSOLE klopt niet.|nee"
    "401|$ADRES gaf HTTP 401.|ja"
    "403|$ADRES gaf HTTP 403.|ja"
    "inlogpagina-200|$ADRES gaf HTTP 200 (text/html; charset=utf-8) zonder de bevestiging van het paneel.|ja"
    "204|$ADRES gaf HTTP 204 zonder de bevestiging van het paneel.|ja"
    "ander-json|$ADRES gaf HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "getal-als-tekst|$ADRES gaf HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "getal-als-boolean|$ADRES gaf HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "json-lijst|$ADRES gaf HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "500|$ADRES gaf HTTP 500.|nee"
    "paneel-500|$ADRES gaf HTTP 500 met de uitleg van het paneel: WRONGPASS invalid username-password pair.|nee"
    "paneel-403|$ADRES gaf HTTP 403 met de uitleg van het paneel: Niet toegestaan.|nee"
    "afgekapt|$ADRES gaf geen (volledig) antwoord (curl-exitcode 18).|nee"
    "groot|$ADRES gaf geen (volledig) antwoord (curl-exitcode 63).|nee"
    "paneel-escape|$ADRES gaf HTTP 500 met de uitleg van het paneel: ?]0;TITEL??]52;c;aGFjaw==?kwaad.|nee"
)

for geval in "${GEVALLEN[@]}"; do
    IFS='|' read -r modus reden muur <<< "$geval"

    draai "$modus"
    gestopt "$modus" "FOUT: het legen van de sessiecache is niet bevestigd: $reden" "$muur"

    [ "$(aantal ophalen)" -eq 0 ] && ok "$modus: er wordt niets opgehaald" \
        || fout "$modus: er werd toch opgehaald: $LOG"

    [ "$(aantal verlopen)" -eq 1 ] && ok "$modus: één poging tot legen" \
        || fout "$modus: $(aantal verlopen) pogingen tot legen, verwacht 1"
done

# Het querydeel van de redirect hoort niet in de melding.
draai 302

if grep -qF "rd=" <<< "$UIT"; then
    fout "302: de melding bevat de query van de redirect: $UIT"
else
    ok "302: de melding laat de query van de redirect weg"
fi

# --- afgebroken: een cookie dat midden in de meting verloopt ---------------------------------------
# Drie rondes lukken, het vierde legen loopt op de muur, in elk van zijn vormen: elke ronde hoort
# gecontroleerd te worden, niet alleen de eerste.
for geval in \
    "302|$ADRES gaf HTTP 302, doorverwezen naar $NEP/oauth2/start." \
    "403|$ADRES gaf HTTP 403." \
    "inlogpagina-200|$ADRES gaf HTTP 200 (text/html; charset=utf-8) zonder de bevestiging van het paneel."; do
    IFS='|' read -r vorm reden <<< "$geval"

    draai "verloopt-na-3:$vorm"
    gestopt "verloopt-na-3 ($vorm)" "$reden" ja

    [ "$(aantal verlopen)" -eq 4 ] && [ "$(aantal ophalen)" -eq 3 ] \
        && ok "verloopt-na-3 ($vorm): drie rondes gemeten, de vierde begint niet" \
        || fout "verloopt-na-3 ($vorm): $(aantal verlopen) keer legen en $(aantal ophalen) ophaalrondes, verwacht 4 en 3"
done

# --- afgebroken: het paneel bevestigt, maar de uitvraag ziet nog een sessie ------------------------
# Dan leegde het paneel een andere Redis dan waar de gemeten uitvraag uit leest. De toets valt na de
# eerste ronde, op de ondernemer van die ronde: daar stond zeker een sessie. Elke soort antwoord van de
# uitvraag krijgt zijn eigen uitleg.
GEVALLEN_UITVRAAG=(
    "andere-redis|ziet na het legen nog een sessie voor kleine-eenmanszaak, dus het paneel leegde een andere Redis."
    "uitvraag-mislukt|kent na het legen nog een mislukte ronde voor kleine-eenmanszaak, dus het paneel leegde een andere Redis."
    "uitvraag-bezig|haalt na het legen op voor kleine-eenmanszaak: het paneel leegde een andere Redis, of er haalt tegelijk iemand anders op voor deze persona."
    "uitvraag-geen-opslag|bereikt haar tijdelijke berichtenopslag niet."
)

for geval in "${GEVALLEN_UITVRAAG[@]}"; do
    IFS='|' read -r modus reden <<< "$geval"

    draai "$modus"
    gestopt "$modus" "FOUT: het legen van de sessiecache is niet bevestigd: de uitvraag op $NEP $reden" nee

    [ "$(aantal ophalen)" -eq 1 ] && [ "$(aantal berichten)" -eq 1 ] \
        && ok "$modus: één ronde gemeten, één toets, de tweede ronde begint niet" \
        || fout "$modus: $(aantal ophalen) ophaalrondes en $(aantal berichten) toetsen, verwacht 1 en 1"

    # De ondernemer heet bij naam; het identificatienummer (hier een BSN) hoort niet in de melding.
    if grep -qF "999993653" <<< "$UIT"; then
        fout "$modus: de melding bevat het identificatienummer: $UIT"
    else
        ok "$modus: de melding noemt de ondernemer zonder identificatienummer"
    fi
done

# --- tekst van een server komt zonder escape-codes op de terminal ---------------------------------
for modus in paneel-escape uitvraag-escape 302-escape inlogpagina-escape; do
    draai "$modus"

    if grep -q $'[\x1b\x07]' <<< "$UIT"; then
        fout "$modus: de uitvoer bevat escape-codes van de server: $(cat -v <<< "$UIT")"
    else
        ok "$modus: de uitvoer bevat geen escape-codes van de server"
    fi

    # Een regeleinde in het `type` mag geen eigen, valse FOUT-regel opleveren.
    if grep -q '^FOUT: vals' <<< "$UIT"; then
        fout "$modus: de server kon een eigen FOUT-regel tonen: $UIT"
    else
        ok "$modus: de server kan geen eigen FOUT-regel tonen"
    fi
done

# --- afgebroken: de ophaalronde zelf wordt geweigerd ------------------------------------------------
# De melding draagt het `type` van de uitvraag, en de naam en niet het nummer van de ondernemer.
draai ophalen-409
gestopt "ophalen-409" "FOUT: de ophaalronde voor kleine-eenmanszaak gaf HTTP 409 (urn:fbs:fout:ophalen-bezig)" nee

if grep -qF "999993653" <<< "$UIT"; then
    fout "ophalen-409: de melding bevat het identificatienummer: $UIT"
else
    ok "ophalen-409: de melding noemt de ondernemer zonder identificatienummer"
fi

# --- afgebroken: een muur met een verlopen of verkeerd cookie --------------------------------------
draai muur "$NEP" "_oauth2_proxy_0=verlopen"
gestopt "muur met verlopen cookie" "$ADRES gaf HTTP 302, doorverwezen naar $NEP/oauth2/start." ja

# Een echt cookie is een geldige sessie op een rijksaccount; ook een afgewezen cookie hoort niet in
# de uitvoer.
if grep -qF "_oauth2_proxy_0" <<< "$UIT"; then
    fout "muur met verlopen cookie: het cookie staat in de uitvoer: $UIT"
else
    ok "muur met verlopen cookie: het cookie staat niet in de uitvoer"
fi

[ "$(aantal ophalen)" -eq 0 ] && ok "muur met verlopen cookie: er wordt niets opgehaald" \
    || fout "muur met verlopen cookie: er werd toch opgehaald: $LOG"

# --- het cookie gaat niet onversleuteld over de lijn ------------------------------------------------
# Een http-adres buiten deze machine weigert het script zodra er een cookie is, nog vóór er een
# aanroep de deur uit gaat.
draai muur "http://paneel.invalid" "$MUURCOOKIE"
gestopt "cookie over http" "FOUT: CONSOLE_COOKIE gaat alleen over https mee, en CONSOLE is http://paneel.invalid." nee

[ "$(aantal verlopen)" -eq 0 ] && ok "cookie over http: er gaat niets naar het paneel" \
    || fout "cookie over http: het paneel kreeg toch een aanroep: $LOG"

# Een adres dat met localhost begint maar via inloggegevens naar een andere host gaat, en een host
# die alleen met localhost begint.
for adres in "http://localhost:@paneel.invalid/" "http://localhost.paneel.invalid" "https://x@paneel.invalid"; do
    draai muur "$adres" "$MUURCOOKIE"

    if [ "$RC" -ne 0 ] && grep -q "^FOUT: CONSOLE" <<< "$UIT" && [ "$(aantal verlopen)" -eq 0 ]; then
        ok "cookie naar $adres: geweigerd vóór de eerste aanroep"
    else
        fout "cookie naar $adres: niet geweigerd (exit $RC): $UIT"
    fi
done

# Een regeleinde in het cookie zou curl als extra header versturen.
draai muur "$NEP" $'abc\r\nX-Ingevoegd: ja'
gestopt "cookie met regeleinde" "FOUT: CONSOLE_COOKIE bevat een regeleinde; kopieer alleen de waarde van de Cookie-kopregel." nee

[ "$(aantal verlopen)" -eq 0 ] && ok "cookie met regeleinde: er gaat niets naar het paneel" \
    || fout "cookie met regeleinde: het paneel kreeg toch een aanroep: $LOG"

# --- zonder UITVOER blijft de tabel in de map van de run -------------------------------------------
# Een vast pad in /tmp kan een andere gebruiker vooraf als symlink of eigen bestand neerzetten.
printf '%s' zonder-uitvoer > "$TMP/modus"
curl -q -sf -X POST "$NEP/nep/reset"
UIT="$(CURL_HOME="$TMP/curl-home" no_proxy=127.0.0.1 NO_PROXY=127.0.0.1 UITVRAAG="$NEP" CONSOLE="$NEP" \
    TMPDIR="$TMP/streams" bash "$SCRIPT" 1 2>&1)" || true
tabel="$(sed -n 's/^Meten over 1 ronde(s); uitvoer in \(.*\), ruwe stromen in .*$/\1/p' <<< "$UIT")"

case "$tabel" in
    "$TMP/streams/fanout-"*/meting.tsv) in_run=ja ;;
    *) in_run=nee ;;
esac

if [ "$in_run" = ja ] && [ "$(wc -l < "$tabel" | tr -d ' ')" -eq 5 ]; then
    ok "zonder UITVOER: de tabel staat in de map van de run, met een kop en vier rondes"
else
    fout "zonder UITVOER: geen tabel in de map van de run ('$tabel'): $UIT"
fi

# --- het cookie staat in geen enkele argumentenlijst -----------------------------------------------
# Een argument van een proces is voor iedere gebruiker op de machine te lezen. Het paneel antwoordt
# het eerste legen traag, zodat curl lang genoeg loopt om in de proceslijst te staan. Het patroon via
# een bestand: als argument van grep zou het zichzelf vinden.
printf '%s' "${MUURCOOKIE#*; }" > "$TMP/cookie-patroon"
printf '%s' muur-traag > "$TMP/modus"
curl -q -sf -X POST "$NEP/nep/reset"
: > "$TMP/log"

CURL_HOME="$TMP/curl-home" no_proxy=127.0.0.1 NO_PROXY=127.0.0.1 UITVRAAG="$NEP" CONSOLE="$NEP" \
    CONSOLE_COOKIE="$MUURCOOKIE" UITVOER="$TMP/uit.tsv" TMPDIR="$TMP/streams" \
    bash "$SCRIPT" 1 > /dev/null 2>&1 &
meting=$!
cookie_gezien=0
curl_gezien=0

while kill -0 "$meting" 2>/dev/null; do
    ps -eo args > "$TMP/ps.txt" 2>/dev/null || true
    cookie_gezien=$((cookie_gezien + $(grep -cFf "$TMP/cookie-patroon" "$TMP/ps.txt" || true)))
    curl_gezien=$((curl_gezien + $(grep -cF "api/demo/sessie/verlopen" "$TMP/ps.txt" || true)))
    sleep 0.1
done

wait "$meting" || true

# Zonder curl in de proceslijst meet deze toets niets.
[ "$curl_gezien" -gt 0 ] && ok "argumentenlijst: de aanroep van het paneel stond in de proceslijst" \
    || fout "argumentenlijst: de aanroep van het paneel is nooit gezien; deze toets meet niets"

[ "$cookie_gezien" -eq 0 ] && ok "argumentenlijst: het cookie stond in geen enkele" \
    || fout "argumentenlijst: het cookie stond $cookie_gezien keer in de proceslijst"

# --- afgebroken: niets bereikbaar op het adres van het paneel --------------------------------------
draai bevestigd "$DICHT"
gestopt "dicht adres" "$DICHT/api/demo/sessie/verlopen gaf geen (volledig) antwoord (curl-exitcode 7)." nee

# Curl's eigen regel zegt wát er misging (DNS, certificaat, time-out); die hoort zichtbaar te blijven.
grep -q '^curl: (7)' <<< "$UIT" && ok "dicht adres: curl's eigen foutregel staat erboven" \
    || fout "dicht adres: curl's eigen foutregel ontbreekt: $UIT"

[ "$(aantal ophalen)" -eq 0 ] && ok "dicht adres: er wordt niets opgehaald" \
    || fout "dicht adres: er werd toch opgehaald: $LOG"

# --- afgebroken: python3 faalt bij het controleren van het antwoord --------------------------------
# Dan stopt de meting ook, maar de melding hoort niet naar de inlogmuur te wijzen: wie dat leest, gaat
# cookies zoeken terwijl python3 zelf faalt. Een geëxporteerde functie en geen stub op PATH: die
# vraagt geen uitvoerrecht in TMPDIR, dat op een geharde machine kan ontbreken.
python3() {
    return 1
}

export -f python3
draai bevestigd
unset -f python3

gestopt "kapotte python3" "$ADRES gaf HTTP 200, maar python3 kon het antwoord niet controleren (exit 1)." nee

# --- een eigen ~/.curlrc beslist niet mee ------------------------------------------------------------
# Met `location` zou curl de redirect volgen en de status van de eindpagina melden, met `fail` zou
# curl zelf afbreken op 401 en 403, en met `include` staan de headers in de body en lijkt een
# geslaagd legen onbevestigd.
mkdir -p "$TMP/curlrc"
printf 'location\nfail\ninclude\n' > "$TMP/curlrc/.curlrc"

for geval in "302|$ADRES gaf HTTP 302, doorverwezen naar $NEP/oauth2/start.|ja" "403|$ADRES gaf HTTP 403.|ja"; do
    IFS='|' read -r modus reden muur <<< "$geval"

    DRAAI_CURL_HOME="$TMP/curlrc" draai "$modus"
    gestopt "$modus met een eigen .curlrc" "$reden" "$muur"
done

DRAAI_CURL_HOME="$TMP/curlrc" draai bevestigd

[ "$RC" -eq 0 ] && ok "bevestigd met een eigen .curlrc: de meting slaagt" \
    || fout "bevestigd met een eigen .curlrc: de meting faalde (exit $RC): $UIT"

echo
if [ "$fails" -ne 0 ]; then
    echo "ROOD: ${fails} test(s) gefaald." >&2
    exit 1
fi

echo "GROEN: alle meet-fanout-tests geslaagd."
