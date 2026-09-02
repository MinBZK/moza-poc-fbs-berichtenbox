#!/usr/bin/env bash
# Rookproef voor de demo-stack: levert een bericht aan bij magazijn A én B en controleert dat
# beide via de uitvraag terugkomen. Health-endpoints bewijzen alleen dat processen leven;
# deze proef rijdt de keten door.
#
# Beide magazijnen aanleveren is geen luxe: de uitvraag is by design degradatie-tolerant, dus
# met alleen A's bericht is de proef ook groen terwijl B onbereikbaar is. Daarom toetst stap 4
# ook de ophaal-stream zelf: geen foutstatus, en van beide magazijnen een geslaagde bevraging.
#
# Stap 5 toetst de breedte: de vier ondernemers horen bij 3, 15, 45 en 100 organisaties uit te komen.
# Dat is wat de gesimuleerde magazijnen toevoegen, en het gaat stil kapot — een profiel-stub die niet
# is meegegenereerd of een uitvraag die nog met het oude register draait, geeft gewoon een kleinere
# lijst.
#
# Vereist een draaiende stack:  docker compose --profile demo up -d
set -euo pipefail

MAGAZIJN_A="${MAGAZIJN_A:-http://localhost:8090}"
MAGAZIJN_B="${MAGAZIJN_B:-http://localhost:8091}"
UITVRAAG="${UITVRAAG:-http://localhost:8086}"
BSN="${BSN:-999993653}"
ONTVANGER="BSN:$BSN"

# Afzender-OIN's vast op de OIN's van magazijn A en B: de persona-stub
# (wiremock/demo-profiel/mappings/persona-pietersen-bsn.json) geeft voor precies deze twee een
# actieve OntvangViaBerichtenbox-voorkeur. Wijk hier niet van af zonder die mapping mee te
# veranderen, anders volgt een 403.
AFZENDER_OIN_A="${AFZENDER_OIN_A:-00000000000000100000}"
AFZENDER_OIN_B="${AFZENDER_OIN_B:-00000001823288444000}"

echo "1/5 health"
for url in "$MAGAZIJN_A" "$MAGAZIJN_B" "$UITVRAAG"; do
    curl -sf "$url/q/health/ready" > /dev/null \
        || { echo "FOUT: $url niet gezond"; exit 1; }
done

# Levert één bericht aan bij het opgegeven magazijn. Het magazijn kent het berichtId zelf toe;
# de aanlever-request bevat er geen. We herkennen ons bericht daarom aan een uniek onderwerp.
# Niet in een command-substitution aanroepen: een `exit` in een subshell stopt het script niet.
lever_aan() {
    local label="$1" basis="$2" oin="$3" onderwerp="$4"
    local respons="/tmp/smoke-aanlever-$label.json" status berichtId

    status=$(curl -s -o "$respons" -w '%{http_code}' \
        -X POST "$basis/api/v1/aanleveringen" \
        -H 'Content-Type: application/json' \
        -d "{
              \"afzender\": \"$oin\",
              \"ontvanger\": {\"type\": \"BSN\", \"waarde\": \"$BSN\"},
              \"onderwerp\": \"$onderwerp\",
              \"inhoud\": \"Aangemaakt door demo/smoke.sh\"
            }")

    if [[ "$status" != "201" ]]; then
        echo "FOUT: aanleveren bij magazijn $label gaf HTTP $status (verwacht 201)"
        cat "$respons"
        exit 1
    fi

    berichtId=$(grep -o '"berichtId"[[:space:]]*:[[:space:]]*"[^"]*"' "$respons" \
        | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    echo "    magazijn $label kende berichtId $berichtId toe"
}

stempel=$(date +%s)
onderwerp_a="Rookproef demo-stack A $stempel"
onderwerp_b="Rookproef demo-stack B $stempel"

echo "2/5 bericht aanleveren bij magazijn A"
lever_aan A "$MAGAZIJN_A" "$AFZENDER_OIN_A" "$onderwerp_a"

echo "3/5 bericht aanleveren bij magazijn B"
lever_aan B "$MAGAZIJN_B" "$AFZENDER_OIN_B" "$onderwerp_b"

echo "4/5 ophalen via uitvraag"
# De sessiecache vult zich pas na een ophaal-ronde; GET /berichten leest alleen de cache.
stream=/tmp/smoke-ophalen.txt
curl -sf -N --max-time 30 "$UITVRAAG/api/v1/berichten/_ophalen" \
    -H "X-Ontvanger: $ONTVANGER" > "$stream"

# Een 2xx op de stream zegt niets over de afzonderlijke magazijnen: die degraderen stilletjes.
# De uitvraag levert per magazijn een 'magazijn-bevraging-voltooid'-event met status
# OK/FOUT/TIMEOUT en sluit af met 'ophalen-gereed' + een 'mislukt'-telling.
if grep -Eq '"status"[[:space:]]*:[[:space:]]*"(FOUT|TIMEOUT)"' "$stream"; then
    echo "FOUT: minstens één magazijn rapporteerde een foutstatus in de ophaal-stream:"
    grep -E '"status"[[:space:]]*:[[:space:]]*"(FOUT|TIMEOUT)"' "$stream"
    exit 1
fi

if ! grep -Eq '"mislukt"[[:space:]]*:[[:space:]]*0[,}]' "$stream"; then
    echo "FOUT: ophalen sloot niet af met 0 mislukte magazijnen:"
    cat "$stream"
    exit 1
fi

# Nul mislukkingen is ook waar als er niets is bevraagd (lege magazijnenlijst uit de profiel-
# stub geeft totaalMagazijnen 0). Daarom per magazijn een geslaagde bevraging eisen.
for oin in "$AFZENDER_OIN_A" "$AFZENDER_OIN_B"; do
    grep "$oin" "$stream" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"OK"' \
        || { echo "FOUT: geen geslaagde bevraging van magazijn $oin in de ophaal-stream"; exit 1; }
done

# De berichtenbox-console leest deze velden rechtstreeks uit de stroom; ze staan in geen
# enkel API-contract, dus zonder deze controle valt een hernoeming pas op in de browser —
# waar de console stilletjes stopt met bijwerken in plaats van een fout te tonen.
for veld in '"event"[[:space:]]*:[[:space:]]*"magazijn-bevraging-gestart"' \
    '"event"[[:space:]]*:[[:space:]]*"magazijn-bevraging-voltooid"' \
    '"event"[[:space:]]*:[[:space:]]*"ophalen-gereed"' \
    '"magazijnId"' '"naam"' '"aantalBerichten"' '"totaalBerichten"' '"totaalMagazijnen"'; do
    grep -Eq "$veld" "$stream" \
        || { echo "FOUT: de ophaal-stream mist $veld, dat de berichtenbox-console wél verwacht"; exit 1; }
done

lijst=/tmp/smoke-berichten.json
curl -sf "$UITVRAAG/api/v1/berichten" -H "X-Ontvanger: $ONTVANGER" > "$lijst"

for onderwerp in "$onderwerp_a" "$onderwerp_b"; do
    grep -q "$onderwerp" "$lijst" \
        || { echo "FOUT: '$onderwerp' niet gevonden in de uitvraag"; exit 1; }
done

echo "5/5 fan-out van de vier ondernemers"

# De vier ondernemers halen op bij 3, 15, 45 en 100 organisaties; de kleinere zitten volledig in de
# grotere. Dat is de hele reden dat de simulator bestaat, en het is precies het soort ding dat stil
# kapot gaat: een profiel-stub die niet meegegenereerd is, een register dat niet is herladen, of een
# uitvraag die na een herstart nog met de oude set draait. Het aantal 'gestart'-regels in de stroom
# is wat de ondernemer daadwerkelijk bevraagt.
fanout() {
    local ontvanger="$1" verwacht="$2" naam="$3"
    local stroom="/tmp/smoke-fanout-$verwacht.txt"

    curl -sf -N --max-time 90 "$UITVRAAG/api/v1/berichten/_ophalen" \
        -H "X-Ontvanger: $ontvanger" -H "Accept: text/event-stream" > "$stroom" \
        || { echo "FOUT: ophalen voor $naam mislukte"; exit 1; }

    local gestart
    gestart=$(grep -Ec '"event"[[:space:]]*:[[:space:]]*"magazijn-bevraging-gestart"' "$stroom" || true)

    if [ "$gestart" -ne "$verwacht" ]; then
        echo "FOUT: $naam bevroeg $gestart organisaties, verwacht $verwacht"
        echo "      Is demo/genereer-magazijnen.py gedraaid en zijn uitvraag en simulator daarna herstart?"
        exit 1
    fi

    echo "    $naam: $gestart organisaties"
}

fanout "BSN:999993653" 3   "kleine eenmanszaak"
fanout "KVK:90000014"  15  "klein bedrijf"
fanout "KVK:90000001"  45  "grootbedrijf"
fanout "KVK:90000003"  100 "landelijk concern"

echo "OK: keten werkt end-to-end via beide magazijnen, en de vier ondernemers halen op bij 3/15/45/100 organisaties"
