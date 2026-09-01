#!/usr/bin/env bash
# Zet de twee componenten die de berichtenbox naast het paneel mogelijk maken: `proeftuin` (het
# image van MinBZK/moza-poc) en `demopersonas` (onze personadienst). Eenmalig handwerk: de
# tag-updates lopen daarna via .github/workflows/deploy.yml, dat allebei in zijn componentenlijst
# noemt.
#
# Hoofdstuk 7 van README.md hiernaast beschrijft het waarom: de berichtenbox draait als eigen
# component in ons project zodat wij de versie bepalen, en het paneel toont hem in een frame.
#
# DRAAI DIT VÓÓR de merge van de PR die deze componenten in deploy.yml zet: die workflow noemt ze
# bij naam, en een verwijzing naar een component dat niet bestaat laat de uitrol falen. Voor een
# component waarvan het image nog niet gebouwd is, geef je met PERSONAS_IMAGE een bestaand,
# onschadelijk image mee; de eerste uitrol vervangt het door de juiste tag.
#
# LET OP: ZAD past component-config (aliassen, poorten, diensten) alleen toe bij het AANMAKEN van
# een component. Een tweede aanroep op een bestaand component laat die config staan; aanpassen
# betekent het component eerst verwijderen en opnieuw aanmaken. Draai daarom eerst `plan`.
#
# Usage:
#   zadctl login && zadctl project use mpfm-w3h
#   demo/environment/zad-demo/proeftuin-component.sh plan  [deployment=test]
#   demo/environment/zad-demo/proeftuin-component.sh apply [deployment=test]

set -euo pipefail

MODE="${1:?usage: proeftuin-component.sh <plan|apply> [deployment=test]}"
DEPLOYMENT="${2:-test}"
BASE_DOMAIN="${ZAD_BASE_DOMAIN:-rig.prd1.gn2.quattro.rijksapps.nl}"
PROJECT_MAGAZIJNEN="${ZAD_PROJECT_MAGAZIJNEN:-mpfm-w3h}"
PROJECT_UITVRAAG="${ZAD_PROJECT_UITVRAAG:-mpfb-8wh}"

case "$MODE" in
    plan) DROOG=(--dry-run) ;;
    apply) DROOG=() ;;
    *) echo "onbekende modus '$MODE'; gebruik plan of apply" >&2; exit 1 ;;
esac

command -v zadctl >/dev/null || { echo "zadctl niet gevonden; zie CLAUDE.md voor de installatie" >&2; exit 1; }

# De image staat in deploy.yml en niet hier: die waarde is de bron, want zij bepaalt wat elke
# preview draait. Deze creatie zet alleen de startwaarde voor `test`.
WORTEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
IMAGE="$(sed -n 's/^ *PROEFTUIN_IMAGE: *//p' "$WORTEL/.github/workflows/deploy.yml")"
[ -n "$IMAGE" ] || { echo "geen PROEFTUIN_IMAGE gevonden in .github/workflows/deploy.yml" >&2; exit 1; }

# Eén keer opvragen: hieruit komt zowel de tag van de personadienst als het antwoord op de vraag
# welke componenten er al staan. Geen 2>/dev/null: niet-ingelogd, verkeerd project of een lock bij
# OM zou anders als "niet gevonden" langskomen, en dan ga je een image-naam invullen terwijl je moet
# inloggen.
BESCHRIJVING="$(zadctl deployment describe "$DEPLOYMENT" -o json)" || {
    echo "zadctl kon deployment '$DEPLOYMENT' niet beschrijven; zie de melding hierboven" >&2
    exit 1
}

# De personadienst is ons eigen image en draagt dus de tag van de deploy, niet een vaste pin. Die
# tag lezen we af van een component dat al in deze deployment draait: hetzelfde register, dezelfde
# eigenaar, dezelfde tag. Een verzonnen tag zou het component in ImagePullBackOff laten hangen tot
# de eerstvolgende uitrol.
if [ -z "${PERSONAS_IMAGE:-}" ]; then
    PERSONAS_IMAGE="$(printf '%s' "$BESCHRIJVING" | python3 -c "
import json, sys

componenten = {c['name']: c['image'] for c in json.load(sys.stdin).get('components', [])}
console = componenten.get('democonsole', '')

# Hard toetsen in plaats van een blinde replace: matcht de naam niet, dan zou PERSONAS_IMAGE het
# console-image worden. Dat luistert op een andere poort, dus de pod herstart zich eeuwig — en het
# draagt de legen-knop, op een component dat bewust geen muur krijgt.
if 'fbs-demo-console' not in console:
    sys.exit(f\"democonsole draait op '{console}'; daar is de personas-tag niet uit af te leiden\")

print(console.replace('fbs-demo-console', 'fbs-demo-personas'))
")" || {
        echo "zet PERSONAS_IMAGE zelf" >&2
        exit 1
    }
fi

UITVRAAG_HOST="uitvraag-\$DEPLOYMENT_NAME-${PROJECT_UITVRAAG}.${BASE_DOMAIN}"
PERSONAS_HOST="demopersonas-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"
CONSOLE_HOST="democonsole-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"
PROEFTUIN_HOST="proeftuin-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"

echo "== ${PROJECT_MAGAZIJNEN}, deployment ${DEPLOYMENT}"
echo "   proeftuin:    ${IMAGE}"
echo "   demopersonas: ${PERSONAS_IMAGE}"
echo

# De personadienst draagt één leeslijst en geen enkele knop. Daarom géén authorization-wall: de
# nginx van de proeftuin haalt dit pad server-side op en heeft geen sessie, dus achter een muur
# krijgt hij 403 en meldt de berichtenbox dat het ophalen mislukt. Dat is precies het probleem dat
# deze dienst oplost. Het bedieningspaneel houdt zijn muur, want daar zit het legen achter.
component_add() {
    # Een bestaand component is geen fout: dit script wordt gedraaid op omgevingen die al half
    # ingericht zijn, en zonder deze tak sterft het onder `set -e` vóór de aliassen verderop.
    # Config van een bestaand component verandert een tweede `add` toch niet — dat kan alleen door
    # het te verwijderen en opnieuw aan te maken.
    #
    # Alleen dát geval overslaan, en niet elke fout: niet-ingelogd, een OM-lock of een ongeldige
    # image-naam zou anders ook als "bestaat hij al" langskomen, waarna het script doorloopt en met
    # 0 eindigt terwijl het component er niet is. De uitrol faalt dan later, op een fout die hier
    # gemaakt is.
    if printf '%s' "$BESCHRIJVING" | grep -q "\"name\": \"$1\""; then
        echo "component '$1' bestaat al in deployment '$DEPLOYMENT'; overgeslagen" >&2

        return 0
    fi

    zadctl component add "$@" "${DROOG[@]}"
}

component_add demopersonas \
    --image "$PERSONAS_IMAGE" \
    --deployment "$DEPLOYMENT" \
    --port 8098 \
    --service publish-on-web

# Zes aliassen: de nginx van de proeftuin proxyt /api/v1/ naar de uitvraag, /api/demo/personas naar
# de personadienst en de rest van /api/demo/ naar het paneel, en de ingress ervóór routeert op de
# Host-header. De browser-host doorgeven levert daar de verkeerde bestemming op, dus de servernaam
# gaat per bestemming apart mee.
#
# Geen authorization-wall: die staat op het paneel, waar de legen-knop op zit. De berichtenbox
# leest alleen, en leest bij een uitvraag die hier toch al publiek bereikbaar is. Een muur zou hem
# bovendien onbruikbaar maken in een frame — de aanmeldpagina van Keycloak laat zich niet framen.
component_add proeftuin \
    --image "$IMAGE" \
    --deployment "$DEPLOYMENT" \
    --port 8080 \
    --service publish-on-web

# Apart van het aanmaken en niet als `--aliases`: config bij creatie geldt alleen voor een component
# dat nog niet bestaat, en dit script draait juist ook op omgevingen die er al staan. Verschuift er
# een variabelenaam aan de kant van de proeftuin, dan trekt een tweede aanroep hem hiermee recht.
alias_zet() {
    local component="$1"
    shift

    # `add` is POST en weigert een bestaande sleutel; `set` is PATCH en weigert een nieuwe. Geen van
    # beide is dus op zichzelf idempotent — vandaar per sleutel de vorm die past bij wat er staat.
    local bestaand
    bestaand="$(zadctl alias list --component "$component" -o json)" || {
        echo "kon de aliassen van '$component' niet lezen; zie de melding hierboven" >&2
        exit 1
    }

    local paar naam
    for paar in "$@"; do
        naam="${paar%%=*}"

        if printf '%s' "$bestaand" | grep -q "\"$naam\":"; then
            zadctl alias set --component "$component" "$paar" "${DROOG[@]}"
        else
            zadctl alias add --component "$component" "$paar" "${DROOG[@]}"
        fi
    done
}

alias_zet proeftuin \
    "BACKEND_KETEN=https://${UITVRAAG_HOST}" \
    "BACKEND_KETEN_HOST=${UITVRAAG_HOST}" \
    "BACKEND_PERSONAS=https://${PERSONAS_HOST}" \
    "BACKEND_PERSONAS_HOST=${PERSONAS_HOST}" \
    "BACKEND_DEMO=https://${CONSOLE_HOST}" \
    "BACKEND_DEMO_HOST=${CONSOLE_HOST}"

# Het paneel toetst dit adres niet vooraf: een HEAD naar een ander component strandt op CORS, en
# die uitkomst is niet van onbereikbaar te onderscheiden. Staat de alias fout, dan blijft het frame
# dus leeg zonder dat iets dat meldt.
alias_zet democonsole "BERICHTENBOX_URL=https://${PROEFTUIN_HOST}/moza/berichtenbox/"

if [ "$MODE" = "plan" ]; then
    echo
    echo "Dit was een plan; niets gewijzigd. Draai 'apply' om het door te zetten."
    exit 0
fi

cat <<KLAAR

Klaar. Verifiëren (stap 7 van verify-zad.md):

  curl -sS -o /dev/null -w '%{http_code}\\n' \\
    "https://proeftuin-${DEPLOYMENT}-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}/moza/berichtenbox/"

Verwacht 200. Daarna het paneel openen en kijken of de berichtenbox in het frame staat; dat vraagt
wel een democonsole-image waarin BERICHTENBOX_URL gelezen wordt.

Richt de health-check niet op /health: dat pad proxyt naar een chat-backend die in dit project niet
bestaat, en een probe erop herstart de pod anderhalve minuut later.
KLAAR
