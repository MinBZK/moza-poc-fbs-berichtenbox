#!/usr/bin/env bash
# Meet hoe lang een ondernemer op zijn berichten wacht, per aantal aangesloten organisaties.
#
# Twee getallen per ondernemer:
#
#   * tijd tot het eerste bericht — het eerste magazijn dat een geslaagd antwoord geeft. Dit is wat
#     iemand ervaart als "de lijst begint te vullen".
#   * tijd tot compleet           — het laatste event van de ronde. Dit is wanneer hij er zeker van
#     kan zijn dat hij alles heeft gezien.
#
# Beide komen uit de SSE-stroom van de uitvraag zelf, met een tijdstempel per regel. Dat is bewust:
# een stopwatch meet de beleving van degene die klikt, niet de keten, en is niet te herhalen. Deze
# meting is dat wel, en dat is het punt — een volgende ronde moet met deze te vergelijken zijn.
#
# Vereist een draaiende demo-stack:  docker compose --profile demo up -d
# Gebruik:  demo/meet-fanout.sh [aantal-rondes]
set -euo pipefail

UITVRAAG="${UITVRAAG:-http://localhost:8086}"
RONDES="${1:-3}"
UITVOER="${UITVOER:-/tmp/fanout-meting.tsv}"

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
# de keten, en dan lijkt de fan-out ineens gratis.
vervalcache() {
    curl -sf -X POST "${CONSOLE:-http://localhost:8095}/api/demo/sessie/verlopen" > /dev/null 2>&1 || true
}

# Eén ophaalronde, met een tijdstempel per regel. `--no-buffer` is nodig: zonder dat levert curl de
# stroom pas aan het eind af en meet elke regel hetzelfde moment.
meet() {
    local ontvanger="$1" bestand="$2"

    curl -sf -N --no-buffer --max-time 180 "$UITVRAAG/api/v1/berichten/_ophalen" \
        -H "X-Ontvanger: $ontvanger" -H "Accept: text/event-stream" \
        | while IFS= read -r regel; do
            printf '%s\t%s\n' "$(date +%s%3N)" "$regel"
        done > "$bestand"
}

# De twee getallen uit één gemeten stroom.
ontleed() {
    python3 - "$1" <<'PY'
import json, re, sys

regels = []

with open(sys.argv[1], encoding="utf-8") as bestand:
    for regel in bestand:
        tijd, _, rest = regel.partition("\t")

        if not rest.startswith("data:"):
            continue

        try:
            regels.append((int(tijd), json.loads(rest[len("data:"):].strip())))
        except (ValueError, json.JSONDecodeError):
            continue

if not regels:
    print("0\t0\t0\t0\t0")
    sys.exit()

begin = regels[0][0]
gestart = sum(1 for _, d in regels if d.get("event") == "magazijn-bevraging-gestart")
geslaagd = [t for t, d in regels
            if d.get("event") == "magazijn-bevraging-voltooid" and d.get("status") == "OK"]
mislukt = sum(1 for _, d in regels
              if d.get("event") == "magazijn-bevraging-voltooid" and d.get("status") != "OK")

eerste = (min(geslaagd) - begin) if geslaagd else 0
compleet = regels[-1][0] - begin

print(f"{gestart}\t{len(geslaagd)}\t{mislukt}\t{eerste}\t{compleet}")
PY
}

printf 'ondernemer\tronde\torganisaties\tgeslaagd\tmislukt\teerste_ms\tcompleet_ms\n' > "$UITVOER"

echo "Meten over $RONDES ronde(s); uitvoer in $UITVOER"

for regel in "${ONDERNEMERS[@]}"; do
    IFS='|' read -r naam ontvanger verwacht <<< "$regel"

    echo "  $naam ($verwacht organisaties)"

    for ronde in $(seq 1 "$RONDES"); do
        vervalcache

        stroom="/tmp/fanout-$naam-$ronde.tsv"
        meet "$ontvanger" "$stroom"

        IFS=$'\t' read -r gestart geslaagd mislukt eerste compleet <<< "$(ontleed "$stroom")"

        if [ "$gestart" -ne "$verwacht" ]; then
            echo "    WAARSCHUWING: $gestart organisaties bevraagd, verwacht $verwacht" >&2
        fi

        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$naam" "$ronde" "$gestart" "$geslaagd" "$mislukt" "$eerste" "$compleet" >> "$UITVOER"

        echo "    ronde $ronde: eerste bericht na ${eerste}ms, compleet na ${compleet}ms ($geslaagd ok, $mislukt mislukt)"
    done
done

echo
echo "Samenvatting (mediaan over $RONDES ronde(s)):"

python3 - "$UITVOER" <<'PY'
import statistics, sys
from collections import defaultdict

perOndernemer = defaultdict(list)

with open(sys.argv[1], encoding="utf-8") as bestand:
    next(bestand)

    for regel in bestand:
        naam, _, organisaties, geslaagd, mislukt, eerste, compleet = regel.rstrip("\n").split("\t")
        perOndernemer[naam].append((int(organisaties), int(geslaagd), int(mislukt), int(eerste), int(compleet)))

print(f"{'ondernemer':22s} {'orgs':>5s} {'ok':>4s} {'mislukt':>8s} {'eerste':>9s} {'compleet':>10s}")

for naam, metingen in perOndernemer.items():
    organisaties = metingen[0][0]
    print(
        f"{naam:22s} {organisaties:5d} "
        f"{int(statistics.median(m[1] for m in metingen)):4d} "
        f"{int(statistics.median(m[2] for m in metingen)):8d} "
        f"{int(statistics.median(m[3] for m in metingen)):7d}ms "
        f"{int(statistics.median(m[4] for m in metingen)):8d}ms"
    )
PY
