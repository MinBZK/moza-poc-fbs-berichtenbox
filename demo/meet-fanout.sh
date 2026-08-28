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
set -euo pipefail

UITVRAAG="${UITVRAAG:-http://localhost:8086}"
CONSOLE="${CONSOLE:-http://localhost:8095}"
RONDES="${1:-3}"
UITVOER="${UITVOER:-/tmp/fanout-meting.tsv}"

# Eigen map per run. Zonder dat overschrijft een tweede meting de stromen van de eerste, en juist
# die ruwe stromen zijn nodig om een conclusie later nog na te kunnen rekenen.
STROMEN="$(mktemp -d -t fanout-XXXXXX)"

# De vier ondernemers uit demo/genereer-magazijnen.py, met hun verwachte aantal organisaties.
ONDERNEMERS=(
    "kleine-eenmanszaak|BSN:999993653|3"
    "klein-bedrijf|KVK:12345678|15"
    "grootbedrijf|KVK:90000001|45"
    "landelijk-concern|KVK:90000003|100"
)

curl -sf "$UITVRAAG/q/health/ready" > /dev/null \
    || { echo "FOUT: de uitvraag op $UITVRAAG is niet gezond; draait de stack?" >&2; exit 1; }

# Elke ronde begint met een lege sessiecache. Zonder dat meet de tweede ronde het cache-pad en niet
# de keten, en dan lijkt de fan-out ineens gratis. Deze aanroep mag dus niet stil mislukken: een
# verkeerd adres of een niet-draaiend bedieningspaneel zou elke ronde ná de eerste onbruikbaar maken
# zonder dat er iets van te zien is.
vervalcache() {
    curl -sS -f -X POST "$CONSOLE/api/demo/sessie/verlopen" > /dev/null \
        || { echo "FOUT: de sessiecache legen mislukte via $CONSOLE; de meting zou het cache-pad meten." >&2; exit 1; }
}

# Eén ophaalronde: aanvraag, klok en ontleding in hetzelfde proces. Schrijft de ruwe stroom met een
# tijdstempel in milliseconden sinds het versturen van de aanvraag, en print de vijf getallen.
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
mislukt = len(voltooid) - len(geslaagd)

if compleet_gemeten:
    # Kruiscontrole tegen de eigen telling; loopt dat uiteen, dan mist de stroom events.
    if laatste.get("geslaagd") != len(geslaagd) or laatste.get("mislukt") != mislukt:
        print(
            f"WAARSCHUWING: het slotevent meldt {laatste.get('geslaagd')} geslaagd en "
            f"{laatste.get('mislukt')} mislukt, geteld zijn er {len(geslaagd)} en {mislukt}",
            file=sys.stderr,
        )
else:
    print(f"WAARSCHUWING: de stroom eindigde niet met 'ophalen-gereed' maar met '{laatste.get('event')}'", file=sys.stderr)

# Een leeg veld en geen nul: nul is de béste denkbare uitkomst en zou als zodanig in de mediaan
# meetellen, terwijl 'geen enkel bericht' juist de slechtste is.
eerste = min(geslaagd) if geslaagd else ""
compleet = regels[-1][0] if compleet_gemeten else ""

print(f"{gestart}\t{len(geslaagd)}\t{mislukt}\t{eerste}\t{compleet}")
PY
}

printf 'ondernemer\tronde\torganisaties\tbevraagd\tgeslaagd\tmislukt\teerste_ms\tcompleet_ms\n' > "$UITVOER"

echo "Meten over $RONDES ronde(s); uitvoer in $UITVOER, ruwe stromen in $STROMEN"

for regel in "${ONDERNEMERS[@]}"; do
    IFS='|' read -r naam ontvanger verwacht <<< "$regel"

    echo "  $naam ($verwacht organisaties)"

    for ronde in $(seq 1 "$RONDES"); do
        vervalcache

        # Eerst in een variabele: bij een `read <<<` uit een commando-substitutie blijft een
        # mislukte ronde onopgemerkt, want dan telt alleen de exitcode van `read`.
        uitkomst="$(meet "$ontvanger" "$STROMEN/$naam-$ronde.tsv")"

        IFS=$'\t' read -r bevraagd geslaagd mislukt eerste compleet <<< "$uitkomst"

        if [ "$bevraagd" -ne "$verwacht" ]; then
            echo "    WAARSCHUWING: $bevraagd organisaties bevraagd, verwacht $verwacht" >&2
        fi

        # `organisaties` is het verwachte aantal en `bevraagd` wat er werkelijk langskwam. Ze apart
        # houden: een gedegradeerde ronde zou anders het aantal van de hele ondernemer verzetten.
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$naam" "$ronde" "$verwacht" "$bevraagd" "$geslaagd" "$mislukt" "$eerste" "$compleet" >> "$UITVOER"

        echo "    ronde $ronde: eerste bericht na ${eerste:-—}ms, compleet na ${compleet:-—}ms ($geslaagd ok, $mislukt mislukt)"
    done
done

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
        naam, _, organisaties, _, geslaagd, mislukt, eerste, compleet = regel.rstrip("\n").split("\t")
        per_ondernemer[naam].append(
            (int(organisaties), int(geslaagd), int(mislukt), eerste, compleet)
        )


def mediaan(waardes):
    """Mediaan over de rondes die een getal opleverden; '—' als er geen enkele was."""
    getallen = [int(w) for w in waardes if w != ""]

    return f"{round(statistics.median(getallen))}" if getallen else "—"


print(f"{'ondernemer':22s} {'orgs':>5s} {'ok':>4s} {'mislukt':>8s} {'eerste':>9s} {'compleet':>10s}")

for naam, metingen in per_ondernemer.items():
    organisaties = metingen[0][0]
    print(
        f"{naam:22s} {organisaties:5d} "
        f"{round(statistics.median(m[1] for m in metingen)):4d} "
        f"{round(statistics.median(m[2] for m in metingen)):8d} "
        f"{mediaan([m[3] for m in metingen]):>7s}ms "
        f"{mediaan([m[4] for m in metingen]):>8s}ms"
    )
PY
