#!/usr/bin/env bash
# PostToolUse: meld welke paden uit een OpenAPI-spec nog geen Bruno-request hebben.
#
# De collectie onder bruno/<service>/ hoort een levend exempel van de spec te zijn. Dat is precies
# het soort afspraak dat je vergeet en die pas opvalt als de collectie is doodgebloed — er is geen
# build-stap die het merkt.
#
# Vergelijking op vorm, niet op tekst: een spec-pad /berichten/{berichtId} en een Bruno-url
# {{baseUrl}}/berichten/{{berichtId}} horen bij elkaar, dus beide worden teruggebracht tot
# /berichten/#. Dat matcht ook een pad dat in een andere map van de collectie staat.
#
# Contract: hook-input is JSON op stdin. Puur informatief; deze hook blokkeert nooit (exitcode 0).

set -uo pipefail

pad=$(jq -r '.tool_input.file_path // .tool_input.path // empty')

if [[ -z "$pad" ]]; then
    exit 0
fi

relatief="${pad#"${CLAUDE_PROJECT_DIR:-}"/}"

if [[ "$relatief" != */src/main/resources/openapi/*-api.yaml ]]; then
    exit 0
fi

service="${relatief#services/}"
service="${service%%/*}"
collectie="bruno/$service"

if [[ ! -d "$collectie" ]]; then
    echo "Let op: $service heeft een OpenAPI-spec maar geen Bruno-collectie onder $collectie/."
    echo "Per service met een spec hoort er één, met bruno.json, environments/lokaal.bru en"
    echo "requests per functioneel pad."
    exit 0
fi

if [[ ! -f "$relatief" ]]; then
    exit 0
fi

# Alle url-regels uit de collectie, teruggebracht tot hun vorm: {{...}} wordt #, en het
# baseUrl-voorvoegsel valt weg omdat dat zelf een {{...}} is.
bruno_vormen=$(grep -rhoE 'url: [^[:space:]]+' "$collectie" 2>/dev/null |
    sed 's/^url: //; s/?.*$//; s/{{[^}]*}}/#/g; s/^#*//')

ontbreekt=()

# De pad-sleutels staan als enige op precies twee spaties inspringing onder `paths:`.
while IFS= read -r spec_pad; do
    vorm=$(sed 's/{[^}]*}/#/g' <<<"$spec_pad")

    if ! grep -qxF -- "$vorm" <<<"$bruno_vormen"; then
        ontbreekt+=("$spec_pad")
    fi
done < <(awk '/^paths:/{in_paths=1; next} /^[^[:space:]#]/{in_paths=0} in_paths && /^  \//{sub(/:[[:space:]]*$/, ""); sub(/^  /, ""); print}' "$relatief")

if [[ ${#ontbreekt[@]} -eq 0 ]]; then
    exit 0
fi

echo "Deze paden uit $relatief hebben nog geen request in $collectie/:"

for spec_pad in "${ontbreekt[@]}"; do
    echo "  $spec_pad"
done

echo "Een nieuw endpoint krijgt direct een .bru ernaast, zo blijft de collectie de spec volgen."
exit 0
