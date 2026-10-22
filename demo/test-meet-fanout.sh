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
# van toetsen. Eén nep-server speelt uitvraag én paneel; de modus van het paneel staat in een
# bestand dat hij per aanroep leest, zodat niet elk geval een eigen server hoeft te starten.
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

modusbestand, logbestand, poortbestand, muurcookie = sys.argv[1:5]

# Over meerdere regels en met een afsluitende newline, zoals een echte inlogpagina: zo toetst elk
# geval ook dat het script de statusregel van de body scheidt op de láátste regel.
INLOGPAGINA = b"""<!DOCTYPE html>
<html>
<body>
  <h1>Inloggen</h1>
  <form>...</form>
</body>
</html>
"""

# Genoeg om de meting een complete ronde te laten zien; de tellingen zelf doen hier niet ter zake.
STREAM = "".join(
    f"data: {json.dumps(event)}\n\n"
    for event in (
        {"event": "magazijn-bevraging-gestart"},
        {"event": "magazijn-bevraging-voltooid", "status": "OK"},
        {"event": "ophalen-gereed", "geslaagd": 1, "mislukt": 0, "nietOpgehaald": 0},
    )
).encode()

# Relatief en met query: zo toetst dit geval ook dat curl er een absoluut adres van maakt en dat de
# melding de query weglaat.
REDIRECT = "/oauth2/start?rd=%2Fapi%2Fdemo%2Fsessie%2Fverlopen"

BEVESTIGD = (200, "application/json", b'{"gewisteKeys":2}')

PANEEL = {
    "bevestigd": BEVESTIGD,
    "nul": (200, "application/json;charset=UTF-8", b'{\n  "gewisteKeys" : 0\n}\n'),
    "302": (302, "text/html", b""),
    "303": (303, None, b""),
    "307": (307, None, b""),
    "302-zonder-adres": (302, None, b""),
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
}


class Nep(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def spoor(self, regel):
        with open(logbestand, "a", encoding="utf-8") as log:
            log.write(regel + "\n")

    def eerdere_legingen(self):
        with open(logbestand, encoding="utf-8") as log:
            return sum(1 for regel in log if regel.startswith("verlopen")) - 1

    def antwoord(self, status, content_type, body, redirect=False):
        self.send_response(status)

        if content_type:
            self.send_header("Content-Type", content_type)

        if redirect:
            self.send_header("Location", REDIRECT)

        if status != 204:
            self.send_header("Content-Length", str(len(body)))

        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/q/health/ready":
            self.antwoord(200, "application/json", b'{"status":"UP"}')
        elif self.path == "/api/v1/berichten/_ophalen":
            self.spoor("ophalen " + self.headers.get("X-Ontvanger", ""))
            self.antwoord(200, "text/event-stream", STREAM)
        else:
            self.antwoord(404, None, b"")

    def do_POST(self):
        if self.path != "/api/demo/sessie/verlopen":
            self.antwoord(404, None, b"")
            return

        cookie = self.headers.get("Cookie", "")
        self.spoor("verlopen " + cookie)

        with open(modusbestand, encoding="utf-8") as bestand:
            modus = bestand.read().strip()

        if modus == "muur":
            modus = "bevestigd" if cookie == muurcookie else "302"
        elif modus == "eerst-nul":
            # Zoals het echte paneel: de eerste keer is er nog niets te wissen, daarna wel.
            modus = "nul" if self.eerdere_legingen() == 0 else "bevestigd"
        elif modus == "altijd-nul":
            modus = "nul"
        elif modus == "verloopt-na-3":
            # Een cookie dat midden in de meting verloopt.
            modus = "bevestigd" if self.eerdere_legingen() < 3 else "302"

        status, content_type, body = PANEEL[modus]
        self.antwoord(status, content_type, body, redirect=300 <= status < 400 and modus != "302-zonder-adres")


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Nep)

with open(poortbestand, "w", encoding="utf-8") as bestand:
    bestand.write(str(server.server_address[1]))

server.serve_forever()
PY

MUURCOOKIE="_oauth2_proxy_0=abc; _oauth2_proxy_1=def"

python3 -I "$TMP/nep.py" "$TMP/modus" "$TMP/log" "$TMP/poort" "$MUURCOOKIE" &
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
# omgeving hoort niet tussen het script en de nep-server. DRAAI_PATH en DRAAI_CURL_HOME zetten per
# geval een kapotte python3 of een eigen .curlrc voorop.
mkdir -p "$TMP/curl-home"
: > "$TMP/curl-home/.curlrc"

# Draait het echte script met het paneel in de gegeven modus. Zet UIT (stdout+stderr), RC en LOG.
draai() {
    local modus="$1" console="${2:-$NEP}" cookie="${3:-}" rondes="${4:-1}"

    printf '%s' "$modus" > "$TMP/modus"
    : > "$TMP/log"
    rm -f "$TMP/uit.tsv"

    RC=0
    UIT="$(PATH="${DRAAI_PATH:-$PATH}" CURL_HOME="${DRAAI_CURL_HOME:-$TMP/curl-home}" \
        no_proxy=127.0.0.1 NO_PROXY=127.0.0.1 \
        UITVRAAG="$NEP" CONSOLE="$console" CONSOLE_COOKIE="$cookie" UITVOER="$TMP/uit.tsv" \
        TMPDIR="$TMP/streams" bash "$SCRIPT" "$rondes" 2>&1)" || RC=$?
    LOG="$(cat "$TMP/log")"
}

aantal() {
    grep -c "^$1" <<< "$LOG" || true
}

# Het aantal regels in het uitvoerbestand; 0 als het er niet is, zodat een vroeg gestopte meting
# een FAIL oplevert en de suite niet onder `set -e` afbreekt.
rijen() {
    if [ -f "$TMP/uit.tsv" ]; then
        wc -l < "$TMP/uit.tsv" | tr -d ' '
    else
        echo 0
    fi
}

# Toetst dat de meting stopte met de verwachte reden, en of de cookie-hint erbij staat (ja/nee).
gestopt() {
    local wat="$1" reden="$2" muur="$3" heeft_hint=nee

    [ "$RC" -ne 0 ] && ok "$wat: de meting stopt (exit $RC)" || fout "$wat: de meting ging door"

    if grep -qF -- "$reden" <<< "$UIT"; then
        ok "$wat: de melding zegt wat er misging"
    else
        fout "$wat: '$reden' ontbreekt in: $UIT"
    fi

    if grep -q "CONSOLE_COOKIE" <<< "$UIT"; then
        heeft_hint=ja
    fi

    [ "$heeft_hint" = "$muur" ] && ok "$wat: de cookie-hint staat er alleen bij een muur ($muur)" \
        || fout "$wat: cookie-hint is '$heeft_hint', verwacht '$muur'"
}

# --- geslaagd: de lokale demo-stack, zonder muur ---------------------------------------------------
# Vier ondernemers, twee rondes: elke ronde precies één keer legen, en wél vóór het ophalen.
# `eerst-nul` is het echte paneel: de eerste keer valt er nog niets te wissen.
verwacht="verlopen ophalen"

for _ in $(seq 2 8); do
    verwacht="$verwacht verlopen ophalen"
done

for modus in bevestigd eerst-nul; do
    draai "$modus" "$NEP" "" 2

    [ "$RC" -eq 0 ] && ok "$modus: de meting slaagt" || fout "$modus: de meting faalde (exit $RC): $UIT"

    volgorde="$(awk '{print $1}' <<< "$LOG" | paste -sd' ' -)"

    [ "$volgorde" = "$verwacht" ] && ok "$modus: elke ronde legt eerst de cache leeg en haalt dan op" \
        || fout "$modus: volgorde van aanroepen is '$volgorde', verwacht '$verwacht'"

    [ "$(rijen)" -eq 9 ] && ok "$modus: het uitvoerbestand heeft een kop en acht rondes" \
        || fout "$modus: het uitvoerbestand heeft $(rijen) regels, verwacht 9"

    if grep -q "FOUT" <<< "$UIT"; then
        fout "$modus: een geslaagde meting meldt een FOUT: $UIT"
    else
        ok "$modus: een geslaagde meting meldt geen FOUT"
    fi
done

# --- geslaagd achter een muur, met het juiste cookie -----------------------------------------------
draai muur "$NEP" "$MUURCOOKIE"

[ "$RC" -eq 0 ] && ok "muur met cookie: de meting slaagt" || fout "muur met cookie: de meting faalde (exit $RC): $UIT"

# Het cookie bevat een spatie na de puntkomma; een ongequote expansie zou het daar splitsen.
if grep -qxF "verlopen $MUURCOOKIE" <<< "$LOG"; then
    ok "muur met cookie: het cookie komt heel bij het paneel aan"
else
    fout "muur met cookie: het paneel kreeg een ander cookie: $LOG"
fi

# --- afgebroken: elke vorm waarin het eerste legen niet bevestigd is --------------------------------
# modus|reden in de melding|wijst de melding op de muur (CONSOLE_COOKIE)?
GEVALLEN=(
    "302|HTTP 302, doorverwezen naar $NEP/oauth2/start.|ja"
    "303|HTTP 303, doorverwezen naar $NEP/oauth2/start.|ja"
    "307|HTTP 307, doorverwezen naar $NEP/oauth2/start.|ja"
    "302-zonder-adres|niet geleegd via $ADRES: HTTP 302.|ja"
    "401|HTTP 401.|ja"
    "403|HTTP 403.|ja"
    "inlogpagina-200|HTTP 200 (text/html; charset=utf-8) zonder de bevestiging van het paneel.|ja"
    "204|HTTP 204 zonder de bevestiging van het paneel.|ja"
    "ander-json|HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "getal-als-tekst|HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "getal-als-boolean|HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "json-lijst|HTTP 200 (application/json) zonder de bevestiging van het paneel.|ja"
    "500|HTTP 500.|nee"
    "paneel-500|HTTP 500 van het paneel zelf: WRONGPASS invalid username-password pair.|nee"
    "paneel-403|HTTP 403 van het paneel zelf: Niet toegestaan.|nee"
)

for geval in "${GEVALLEN[@]}"; do
    IFS='|' read -r modus reden muur <<< "$geval"

    draai "$modus"
    gestopt "$modus" "$reden" "$muur"

    if grep -qF "FOUT: de sessiecache is niet geleegd via $ADRES" <<< "$UIT"; then
        ok "$modus: de melding noemt het adres"
    else
        fout "$modus: de melding noemt het adres niet: $UIT"
    fi

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
# Drie rondes lukken, het vierde legen loopt op de muur: elke ronde hoort gecontroleerd te worden,
# niet alleen de eerste.
draai verloopt-na-3
gestopt "verloopt-na-3" "HTTP 302, doorverwezen naar $NEP/oauth2/start." ja

[ "$(aantal verlopen)" -eq 4 ] && [ "$(aantal ophalen)" -eq 3 ] \
    && ok "verloopt-na-3: drie rondes gemeten, de vierde begint niet" \
    || fout "verloopt-na-3: $(aantal verlopen) keer legen en $(aantal ophalen) ophaalrondes, verwacht 4 en 3"

# --- afgebroken: het paneel kijkt naar een andere Redis dan de uitvraag ----------------------------
# Het paneel bevestigt wel, maar wist na een geslaagde ronde niets: dan raakt het legen de sessie
# van de gemeten uitvraag niet.
draai altijd-nul
gestopt "altijd-nul" "het paneel wiste 0 sessie-keys ná een geslaagde ronde, dus het kijkt naar een andere Redis dan de uitvraag op $NEP." nee

[ "$(aantal verlopen)" -eq 2 ] && [ "$(aantal ophalen)" -eq 1 ] \
    && ok "altijd-nul: na de eerste ronde begint er geen tweede" \
    || fout "altijd-nul: $(aantal verlopen) keer legen en $(aantal ophalen) ophaalrondes, verwacht 2 en 1"

# --- afgebroken: een muur met een verlopen of verkeerd cookie --------------------------------------
draai muur "$NEP" "_oauth2_proxy_0=verlopen"
gestopt "muur met verlopen cookie" "HTTP 302, doorverwezen naar $NEP/oauth2/start." ja

# Een echt cookie is een geldige sessie op een rijksaccount; ook een afgewezen cookie hoort niet in
# de uitvoer.
if grep -qF "_oauth2_proxy_0" <<< "$UIT"; then
    fout "muur met verlopen cookie: het cookie staat in de uitvoer: $UIT"
else
    ok "muur met verlopen cookie: het cookie staat niet in de uitvoer"
fi

[ "$(aantal ophalen)" -eq 0 ] && ok "muur met verlopen cookie: er wordt niets opgehaald" \
    || fout "muur met verlopen cookie: er werd toch opgehaald: $LOG"

# --- afgebroken: niets bereikbaar op het adres van het paneel --------------------------------------
draai bevestigd "$DICHT"
gestopt "dicht adres" "FOUT: de sessiecache is niet geleegd via $DICHT/api/demo/sessie/verlopen: geen antwoord (curl-exitcode 7)." nee

# Curl's eigen regel zegt wát er misging (DNS, certificaat, time-out); die hoort zichtbaar te blijven.
grep -q '^curl: (7)' <<< "$UIT" && ok "dicht adres: curl's eigen foutregel staat erboven" \
    || fout "dicht adres: curl's eigen foutregel ontbreekt: $UIT"

[ "$(aantal ophalen)" -eq 0 ] && ok "dicht adres: er wordt niets opgehaald" \
    || fout "dicht adres: er werd toch opgehaald: $LOG"

# --- afgebroken: python3 faalt bij het controleren van het antwoord --------------------------------
# Dan stopt de meting ook, maar de melding hoort niet naar de inlogmuur te wijzen: wie dat leest, gaat
# cookies zoeken terwijl python3 zelf faalt.
mkdir -p "$TMP/kapot"
printf '#!/bin/sh\nexit 1\n' > "$TMP/kapot/python3"
chmod +x "$TMP/kapot/python3"

DRAAI_PATH="$TMP/kapot:$PATH" draai bevestigd
gestopt "kapotte python3" "HTTP 200, maar python3 kon het antwoord niet controleren (exit 1)." nee

# --- een eigen ~/.curlrc beslist niet mee ------------------------------------------------------------
# Met `location` zou curl de redirect volgen en de status van de eindpagina melden, met `fail` zou
# curl zelf afbreken op 401 en 403, en met `include` staan de headers in de body en lijkt een
# geslaagd legen onbevestigd.
mkdir -p "$TMP/curlrc"
printf 'location\nfail\ninclude\n' > "$TMP/curlrc/.curlrc"

for geval in "302|HTTP 302, doorverwezen naar $NEP/oauth2/start.|ja" "403|HTTP 403.|ja"; do
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
