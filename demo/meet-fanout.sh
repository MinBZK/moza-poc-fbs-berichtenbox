#!/usr/bin/env bash
# Meet hoe lang een ondernemer op zijn berichten wacht, per aantal aangesloten organisaties.
#
# Twee getallen per ondernemer:
#
#   * tijd tot het eerste bericht — vanaf het versturen van de aanvraag tot het eerste magazijn dat
#     een geslaagd antwoord geeft. Dit is wat iemand ervaart als "de lijst begint te vullen".
#   * tijd tot compleet           — vanaf diezelfde aanvraag tot het slotevent van de ronde. Dit is
#     wanneer hij er zeker van kan zijn dat hij alles heeft gezien.
#
# Beide komen uit de SSE-stroom van de uitvraag zelf. Een stopwatch meet de beleving van degene die
# klikt en is niet te herhalen; deze meting wel, en dat is het punt — een volgende ronde moet met
# deze te vergelijken zijn.
#
# Het meten gebeurt daarom binnen één proces: de aanvraag, de klok en het tijdstempelen per regel
# zitten in hetzelfde Python-programma. Een leesloop in de shell die per SSE-regel `date` aanroept
# lijkt hetzelfde te doen, maar kost bijna een milliseconde per regel — bij honderd organisaties
# arriveren er honderd 'gestart'-regels vóór het eerste antwoord, en dan meet je vooral je eigen
# meetlat.
#
# Vereist een draaiende demo-stack:  docker compose --profile demo up -d
# Gebruik:  demo/meet-fanout.sh [aantal-rondes]
#
# Tegen een omgeving waar het bedieningspaneel achter een authorization-wall staat (ZAD) komt een
# aanroep zonder geldige sessie niet bij het paneel aan. Geef dan het sessiecookie van een ingelogde
# browser mee in CONSOLE_COOKIE; zonder (geldig) cookie stopt de meting vóór de eerste ronde, of vóór
# de eerstvolgende als het cookie onderweg verloopt.
#
# curl leest hier nergens ~/.curlrc (`-q`); een proxy of CA-bundel hoort via de omgeving
# (`https_proxy`, `CURL_CA_BUNDLE`).
set -euo pipefail

UITVRAAG="${UITVRAAG:-http://localhost:8086}"
CONSOLE="${CONSOLE:-http://localhost:8095}"
CONSOLE_COOKIE="${CONSOLE_COOKIE:-}"
RONDES="${1:-3}"
UITVOER="${UITVOER:-/tmp/fanout-meting.tsv}"

# Eigen map per run. Zonder dat overschrijft een tweede meting de stromen van de eerste, en juist
# die ruwe stromen zijn nodig om een conclusie later nog na te kunnen rekenen.
STROMEN="$(mktemp -d -t fanout-XXXXXX)"

# De ondernemers uit demo/genereer-magazijnen.py, met hun verwachte aantal organisaties. De mappen-demo
# ontbreekt bewust: die heeft dezelfde fan-out als landelijk concern en zou de meting alleen verdubbelen.
ONDERNEMERS=(
    "kleine-eenmanszaak|BSN:999993653|3"
    "klein-bedrijf|KVK:90000014|15"
    "grootbedrijf|KVK:90000001|45"
    "landelijk-concern|KVK:90000003|100"
)

curl -q -sf --max-time 30 "$UITVRAAG/q/health/ready" > /dev/null \
    || { echo "FOUT: de uitvraag op $UITVRAAG is niet gezond; draait de stack?" >&2; exit 1; }

# Het cookie als array en niet als losse string: `-H "Cookie: $x"` zonder quotes zou op de spatie
# splitsen en curl een half argument geven. De `[@]+`-vorm houdt het geheel ook onder `set -u` goed
# wanneer er geen cookie is.
COOKIE_ARG=()

if [ -n "$CONSOLE_COOKIE" ]; then
    COOKIE_ARG=(-H "Cookie: $CONSOLE_COOKIE")
fi

VERVAL_PAD="/api/demo/sessie/verlopen"
VERVAL_ADRES="$CONSOLE$VERVAL_PAD"

# Elke ronde begint met een lege sessiecache: `_ophalen` leest die cache niet, maar zo begint elke
# ronde vanuit dezelfde toestand, zonder `:lock` van een eerdere, afgebroken aanvraag voor dezelfde
# ondernemer. Deze stap mag dus niet stil mislukken, en twee dingen moeten kloppen:
#   - het paneel bevestigt het legen zelf (`gewisteKeys`). De statuscode alleen bewijst niets: een
#     inlogmuur kan doorverwijzen, wat `curl -f` doorlaat, of de inlogpagina met 200 teruggeven;
#   - de gemeten uitvraag ziet daarna geen sessie meer. Pas dat bewijst dat het paneel dezelfde
#     Redis leegde als waar de uitvraag uit leest.
vervalcache() {
    leeg_via_paneel
    toets_bij_uitvraag "$1" "$2"
}

leeg_via_paneel() {
    local antwoord body status content_type doel uitleg rc=0 oordeel=0

    # `-q` als eerste argument: anders leest curl de eigen ~/.curlrc, en een `location`, `fail` of
    # `include` daarin verandert wat hier binnenkomt. `--max-time`: een paneel dat niet antwoordt,
    # zou de meting anders zonder melding laten hangen.
    antwoord="$(curl -q -sS --max-time 60 -X POST "$VERVAL_ADRES" ${COOKIE_ARG[@]+"${COOKIE_ARG[@]}"} \
        -w '\n%{http_code}|%{content_type}|%{redirect_url}')" || rc=$?

    if [ "$rc" -ne 0 ]; then
        stop_meting "$VERVAL_ADRES gaf geen (volledig) antwoord (curl-exitcode $rc)"
    fi

    # `|` als scheiding en geen tab: opeenvolgende tabs smelten in `read` samen, en dan schuift een
    # leeg Content-Type de redirect een veld op.
    IFS='|' read -r status content_type doel <<< "${antwoord##*$'\n'}"
    body="${antwoord%$'\n'*}"

    case "$status" in
        2??)
            lees_antwoord <<< "$body" > /dev/null || oordeel=$?

            if [ "$oordeel" -eq "$NIET_BEVESTIGD" ]; then
                stop_meting "$VERVAL_ADRES gaf HTTP $status${content_type:+ ($content_type)} zonder de bevestiging van het paneel" muur
            elif [ "$oordeel" -ne 0 ]; then
                stop_meting "$VERVAL_ADRES gaf HTTP $status, maar python3 kon het antwoord niet controleren (exit $oordeel)"
            fi
            ;;
        3??)
            # Zonder query: die van een inlogpagina is lang en zegt de lezer niets, het pad wel.
            doel="${doel%%\?*}"

            # Naar hetzelfde pad op een ander adres (http → https, een andere host): dan klopt
            # CONSOLE niet, en een inlogmuur stuurt nooit daarheen.
            case "$doel" in
                *"$VERVAL_PAD") stop_meting "$VERVAL_ADRES gaf HTTP $status, doorverwezen naar $doel: hetzelfde pad op een ander adres, dus CONSOLE klopt niet" ;;
            esac

            stop_meting "$VERVAL_ADRES gaf HTTP $status${doel:+, doorverwezen naar $doel}" muur
            ;;
        *)
            # Geeft het paneel zelf de fout, dan staat er een uitleg in en is het geen muur.
            uitleg="$(lees_antwoord <<< "$body")" || true

            if [ -n "$uitleg" ]; then
                stop_meting "$VERVAL_ADRES gaf HTTP $status met de uitleg van het paneel: $uitleg"
            fi

            case "$status" in
                401 | 403) stop_meting "$VERVAL_ADRES gaf HTTP $status" muur ;;
                *) stop_meting "$VERVAL_ADRES gaf HTTP $status" ;;
            esac
            ;;
    esac
}

# Zonder sessie geeft de uitvraag `409` met `type` `nog-niet-opgehaald`, net als vóór een eerste
# ophaalronde. Op `type` en niet op `title` of `detail`: alleen de codes liggen in de API vast.
toets_bij_uitvraag() {
    local naam="$1" ontvanger="$2" antwoord status type rc=0

    antwoord="$(curl -q -sS --max-time 30 -H "X-Ontvanger: $ontvanger" -H "Accept: application/json" \
        "$UITVRAAG/api/v1/berichten" -w '\n%{http_code}')" || rc=$?

    if [ "$rc" -ne 0 ]; then
        stop_meting "de uitvraag op $UITVRAAG gaf na het legen geen (volledig) antwoord (curl-exitcode $rc)"
    fi

    status="${antwoord##*$'\n'}"
    type="$(probleemtype <<< "${antwoord%$'\n'*}")" || true

    if [ "$status" = 409 ] && [ "$type" = "urn:fbs:fout:nog-niet-opgehaald" ]; then
        return 0
    fi

    if [ "$status" = 200 ]; then
        stop_meting "de uitvraag op $UITVRAAG ziet na het legen nog een sessie voor $naam, dus het paneel leegde een andere Redis"
    fi

    stop_meting "de uitvraag op $UITVRAAG gaf na het legen HTTP $status${type:+ ($type)} voor $naam, en niet nog-niet-opgehaald"
}

# Een eigen exitcode en niet 1: die geeft Python ook bij een traceback, en een ontbrekende python3
# geeft 127. Zonder onderscheid wijst de melding dan naar de inlogmuur.
NIET_BEVESTIGD=3

# Bevestigd: print het aantal gewiste keys. Niet bevestigd: exit NIET_BEVESTIGD, en print de uitleg
# die het paneel bij een eigen fout meegeeft (`{"fout": ...}`), op één regel en ingekort.
lees_antwoord() {
    python3 -c '
import json
import sys

niet_bevestigd = int(sys.argv[1])

try:
    antwoord = json.load(sys.stdin)
except ValueError:
    sys.exit(niet_bevestigd)

if not isinstance(antwoord, dict):
    sys.exit(niet_bevestigd)

gewist = antwoord.get("gewisteKeys")

# `type(...) is int` en niet isinstance: in Python is True ook een int.
if type(gewist) is int:
    print(gewist)
    sys.exit(0)

if isinstance(antwoord.get("fout"), str):
    print(" ".join(antwoord["fout"].split())[:200])

sys.exit(niet_bevestigd)
' "$NIET_BEVESTIGD"
}

# Het `type` van een problem+json-antwoord, of niets.
probleemtype() {
    python3 -c '
import json
import sys

try:
    antwoord = json.load(sys.stdin)
except ValueError:
    sys.exit(0)

if isinstance(antwoord, dict) and isinstance(antwoord.get("type"), str):
    print(antwoord["type"])
'
}

stop_meting() {
    echo "FOUT: het legen van de sessiecache is niet bevestigd: $1." >&2
    echo "      Een ronde hoort met een lege sessiecache te beginnen; de meting stopt." >&2
    echo "      De rondes tot nu toe staan in $TABEL; $UITVOER is niet bijgewerkt." >&2

    if [ "${2:-}" = muur ]; then
        echo "      Staat het bedieningspaneel achter een inlogmuur, geef dan het sessiecookie van een" >&2
        echo "      ingelogde browser mee in CONSOLE_COOKIE — of een verse, als het verlopen is." >&2
    fi

    exit 1
}

# Eén ophaalronde: aanvraag, klok en ontleding in hetzelfde proces. Schrijft de ruwe stroom met een
# tijdstempel in milliseconden sinds het versturen van de aanvraag, en print de zes getallen.
meet() {
    python3 - "$UITVRAAG" "$1" "$2" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.request

uitvraag, ontvanger, bestand = sys.argv[1:4]

verzoek = urllib.request.Request(
    f"{uitvraag}/api/v1/berichten/_ophalen",
    headers={"X-Ontvanger": ontvanger, "Accept": "text/event-stream"},
    method="GET",
)

regels = []
begin = time.monotonic()

try:
    with urllib.request.urlopen(verzoek, timeout=180) as stroom, open(bestand, "w", encoding="utf-8") as ruw:
        for regel in stroom:
            # Tijdstempel zodra de regel binnen is, vóór het decoderen en wegschrijven.
            verstreken = int((time.monotonic() - begin) * 1000)
            tekst = regel.decode("utf-8", "replace").rstrip("\n")

            ruw.write(f"{verstreken}\t{tekst}\n")

            if tekst.startswith("data:"):
                try:
                    regels.append((verstreken, json.loads(tekst[len("data:"):].strip())))
                except json.JSONDecodeError:
                    print(f"WAARSCHUWING: onleesbare SSE-regel na {verstreken} ms", file=sys.stderr)

except (urllib.error.URLError, TimeoutError) as fout:
    print(f"FOUT: de ophaalronde voor {ontvanger} mislukte: {fout}", file=sys.stderr)
    sys.exit(1)

if not regels:
    print(f"FOUT: geen enkel event ontvangen voor {ontvanger}; de stroom staat in {bestand}", file=sys.stderr)
    sys.exit(1)

# Het slotevent draagt de tellingen van de uitvraag zelf. Ontbreekt het, dan is de stroom vroegtijdig
# afgebroken en is 'tijd tot compleet' een willekeurig tussenmoment — een te laag getal dat er goed
# uitziet. Zo'n ronde telt niet mee.
laatste = regels[-1][1]
compleet_gemeten = laatste.get("event") == "ophalen-gereed"

gestart = sum(1 for _, d in regels if d.get("event") == "magazijn-bevraging-gestart")
voltooid = [(t, d) for t, d in regels if d.get("event") == "magazijn-bevraging-voltooid"]
geslaagd = [t for t, d in voltooid if d.get("status") == "OK"]
# Niet-opgehaald apart van mislukt: die organisaties zijn niet bevraagd omdat de uitvraag te veel
# werk tegelijk had. Ze samenvoegen laat precies zien wat de meting moet aantonen — of de
# gelijktijdigheidsgrens organisaties buiten beeld houdt — in een storingscijfer verdwijnen.
niet_opgehaald = sum(1 for _, d in voltooid if d.get("status") == "NIET_OPGEHAALD")
mislukt = len(voltooid) - len(geslaagd) - niet_opgehaald

if compleet_gemeten:
    # Kruiscontrole tegen de eigen telling; loopt dat uiteen, dan mist de stroom events.
    geteld = (len(geslaagd), mislukt, niet_opgehaald)
    gemeld = (laatste.get("geslaagd"), laatste.get("mislukt"), laatste.get("nietOpgehaald"))

    if gemeld != geteld:
        print(
            f"WAARSCHUWING: het slotevent meldt {gemeld} (geslaagd, mislukt, nietOpgehaald), "
            f"geteld zijn er {geteld}",
            file=sys.stderr,
        )
else:
    print(f"WAARSCHUWING: de stroom eindigde niet met 'ophalen-gereed' maar met '{laatste.get('event')}'", file=sys.stderr)

# Een leeg veld en geen nul: nul is de béste denkbare uitkomst en zou als zodanig in de mediaan
# meetellen, terwijl 'geen enkel bericht' juist de slechtste is.
eerste = min(geslaagd) if geslaagd else ""
compleet = regels[-1][0] if compleet_gemeten else ""

# `|` en geen tab, om dezelfde reden als bij het legen: zonder geslaagd magazijn is `eerste` leeg, en
# met tabs schoof `compleet` dan in de kolom van het eerste bericht.
print(f"{gestart}|{len(geslaagd)}|{mislukt}|{niet_opgehaald}|{eerste}|{compleet}")
PY
}

# Eerst in de map van deze run, en pas na de laatste ronde naar UITVOER: een meting die stopt, laat
# de uitvoer van de vorige dan staan, en een halve tabel is niet voor een hele aan te zien.
TABEL="$STROMEN/meting.tsv"

printf 'ondernemer\tronde\torganisaties\tbevraagd\tgeslaagd\tmislukt\tniet_opgehaald\teerste_ms\tcompleet_ms\n' > "$TABEL"

echo "Meten over $RONDES ronde(s); uitvoer in $UITVOER, ruwe stromen in $STROMEN"

for regel in "${ONDERNEMERS[@]}"; do
    IFS='|' read -r naam ontvanger verwacht <<< "$regel"

    echo "  $naam ($verwacht organisaties)"

    for ronde in $(seq 1 "$RONDES"); do
        vervalcache "$naam" "$ontvanger"

        # Eerst in een variabele: bij een `read <<<` uit een commando-substitutie blijft een
        # mislukte ronde onopgemerkt, want dan telt alleen de exitcode van `read`.
        uitkomst="$(meet "$ontvanger" "$STROMEN/$naam-$ronde.tsv")"

        IFS='|' read -r bevraagd geslaagd mislukt niet_opgehaald eerste compleet <<< "$uitkomst"

        if [ "$bevraagd" -ne "$verwacht" ]; then
            echo "    WAARSCHUWING: $bevraagd organisaties bevraagd, verwacht $verwacht" >&2
        fi

        # `organisaties` is het verwachte aantal en `bevraagd` wat er werkelijk langskwam. Ze apart
        # houden: een gedegradeerde ronde zou anders het aantal van de hele ondernemer verzetten.
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$naam" "$ronde" "$verwacht" "$bevraagd" "$geslaagd" "$mislukt" "$niet_opgehaald" \
            "$eerste" "$compleet" >> "$TABEL"

        echo "    ronde $ronde: eerste bericht na ${eerste:-—}ms, compleet na ${compleet:-—}ms ($geslaagd ok, $mislukt mislukt, $niet_opgehaald niet opgehaald)"
    done
done

cp "$TABEL" "$UITVOER"

echo
echo "Samenvatting (mediaan over $RONDES ronde(s)):"

python3 - "$UITVOER" <<'PY'
import statistics
import sys
from collections import defaultdict

per_ondernemer = defaultdict(list)

with open(sys.argv[1], encoding="utf-8") as bestand:
    next(bestand)

    for regel in bestand:
        naam, _, organisaties, _, geslaagd, mislukt, niet_opgehaald, eerste, compleet = regel.rstrip("\n").split("\t")
        per_ondernemer[naam].append(
            (int(organisaties), int(geslaagd), int(mislukt), int(niet_opgehaald), eerste, compleet)
        )


def mediaan(waardes):
    """Mediaan over de rondes die een getal opleverden; '—' als er geen enkele was."""
    getallen = [int(w) for w in waardes if w != ""]

    return f"{round(statistics.median(getallen))}" if getallen else "—"


print(
    f"{'ondernemer':22s} {'orgs':>5s} {'ok':>4s} {'mislukt':>8s} {'niet_opg':>9s} "
    f"{'eerste':>9s} {'compleet':>10s}"
)

for naam, metingen in per_ondernemer.items():
    organisaties = metingen[0][0]
    print(
        f"{naam:22s} {organisaties:5d} "
        f"{round(statistics.median(m[1] for m in metingen)):4d} "
        f"{round(statistics.median(m[2] for m in metingen)):8d} "
        f"{round(statistics.median(m[3] for m in metingen)):9d} "
        f"{mediaan([m[4] for m in metingen]):>7s}ms "
        f"{mediaan([m[5] for m in metingen]):>8s}ms"
    )
PY
