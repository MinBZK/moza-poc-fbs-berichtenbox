#!/usr/bin/env bash
# Rookproef voor de demo-stack: levert een bericht aan bij magazijn A én B en controleert dat
# beide via de uitvraag terugkomen. Health-endpoints bewijzen alleen dat processen leven;
# deze proef rijdt de keten door.
#
# Beide magazijnen aanleveren is geen luxe: de uitvraag is by design degradatie-tolerant, dus
# met alleen A's bericht is de proef ook groen terwijl B onbereikbaar is. Daarom toetst stap 4
# ook de ophaal-stream zelf: geen foutstatus, en van beide magazijnen een geslaagde bevraging.
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

echo "1/4 health"
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

echo "2/4 bericht aanleveren bij magazijn A"
lever_aan A "$MAGAZIJN_A" "$AFZENDER_OIN_A" "$onderwerp_a"

echo "3/4 bericht aanleveren bij magazijn B"
lever_aan B "$MAGAZIJN_B" "$AFZENDER_OIN_B" "$onderwerp_b"

echo "4/4 ophalen via uitvraag"
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

lijst=/tmp/smoke-berichten.json
curl -sf "$UITVRAAG/api/v1/berichten" -H "X-Ontvanger: $ONTVANGER" > "$lijst"

for onderwerp in "$onderwerp_a" "$onderwerp_b"; do
    grep -q "$onderwerp" "$lijst" \
        || { echo "FOUT: '$onderwerp' niet gevonden in de uitvraag"; exit 1; }
done

echo "OK: keten werkt end-to-end via beide magazijnen"
