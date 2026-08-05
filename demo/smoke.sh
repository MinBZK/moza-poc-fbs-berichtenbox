#!/usr/bin/env bash
# Rookproef voor de demo-stack: levert een bericht aan bij magazijn A en controleert dat
# het via de uitvraag terugkomt. Health-endpoints bewijzen alleen dat processen leven;
# deze proef rijdt de keten door.
#
# Vereist een draaiende stack:  docker compose --profile demo up -d
set -euo pipefail

MAGAZIJN_A="${MAGAZIJN_A:-http://localhost:8090}"
UITVRAAG="${UITVRAAG:-http://localhost:8086}"
BSN="${BSN:-999993653}"
ONTVANGER="BSN:$BSN"

# Afzender-OIN vast op het test-OIN: de profiel-stub geeft daarvoor een actieve
# OntvangViaBerichtenbox-voorkeur. Wijk hier niet van af zonder de mapping in
# wiremock/externe-stubs/mappings/ mee te veranderen, anders volgt een 403.
AFZENDER_OIN="${AFZENDER_OIN:-00000001003214345000}"

echo "1/3 health"
for url in "$MAGAZIJN_A" "$UITVRAAG"; do
    curl -sf "$url/q/health/ready" > /dev/null \
        || { echo "FOUT: $url niet gezond"; exit 1; }
done

echo "2/3 bericht aanleveren"
# Het magazijn kent het berichtId zelf toe; de aanlever-request bevat er geen. We
# herkennen ons bericht daarom aan een uniek onderwerp.
onderwerp="Rookproef demo-stack $(date +%s)"
status=$(curl -s -o /tmp/smoke-aanlever.json -w '%{http_code}' \
    -X POST "$MAGAZIJN_A/api/v1/berichten" \
    -H 'Content-Type: application/json' \
    -d "{
          \"afzender\": \"$AFZENDER_OIN\",
          \"ontvanger\": {\"type\": \"BSN\", \"waarde\": \"$BSN\"},
          \"onderwerp\": \"$onderwerp\",
          \"inhoud\": \"Aangemaakt door demo/smoke.sh\"
        }")

if [[ "$status" != "201" ]]; then
    echo "FOUT: aanleveren gaf HTTP $status (verwacht 201)"
    cat /tmp/smoke-aanlever.json
    exit 1
fi

bericht_id=$(grep -o '"berichtId"[[:space:]]*:[[:space:]]*"[^"]*"' /tmp/smoke-aanlever.json \
    | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
echo "    magazijn kende berichtId $bericht_id toe"

echo "3/3 ophalen via uitvraag"
# De sessiecache vult zich pas na een ophaal-ronde; GET /berichten leest alleen de cache.
curl -sf -N --max-time 30 "$UITVRAAG/api/v1/berichten/_ophalen" \
    -H "X-Ontvanger: $ONTVANGER" > /dev/null

curl -sf "$UITVRAAG/api/v1/berichten" -H "X-Ontvanger: $ONTVANGER" \
    | grep -q "$onderwerp" \
    || { echo "FOUT: '$onderwerp' niet gevonden in de uitvraag"; exit 1; }

echo "OK: keten werkt end-to-end"
