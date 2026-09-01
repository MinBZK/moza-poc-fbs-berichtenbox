#!/usr/bin/env bash
# Zet de twee componenten die de berichtenbox naast het paneel mogelijk maken: `proeftuin` (het
# image van MinBZK/moza-poc) en `demopersonas` (onze personadienst). Eenmalig handwerk: de
# tag-updates lopen daarna via .github/workflows/deploy.yml, dat allebei in zijn componentenlijst
# noemt.
#
# Hoofdstuk 7 van README.md hiernaast beschrijft het waarom: de berichtenbox draait als eigen
# component in ons project zodat wij de versie bepalen, en het paneel toont hem in een frame.
#
# DRAAI DIT VÓÓR de merge van de PR die `proeftuin` in deploy.yml zet: die workflow noemt het
# component bij naam, en een verwijzing naar een component dat niet bestaat laat de uitrol falen.
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

# De personadienst is ons eigen image en draagt dus de tag van de deploy, niet een vaste pin. Die
# tag lezen we af van een component dat al in deze deployment draait: hetzelfde register, dezelfde
# eigenaar, dezelfde tag. Een verzonnen tag zou het component in ImagePullBackOff laten hangen tot
# de eerstvolgende uitrol.
PERSONAS_IMAGE="${PERSONAS_IMAGE:-$(
    zadctl deployment describe "$DEPLOYMENT" -o json 2>/dev/null |
        python3 -c "
import json, sys

componenten = {c['name']: c['image'] for c in json.load(sys.stdin).get('components', [])}
console = componenten.get('democonsole', '')
print(console.replace('fbs-demo-console', 'fbs-demo-personas') if console else '')
"
)}"
[ -n "$PERSONAS_IMAGE" ] || {
    echo "geen democonsole in deployment '$DEPLOYMENT' om de tag van af te lezen; zet PERSONAS_IMAGE zelf" >&2
    exit 1
}

UITVRAAG_HOST="uitvraag-\$DEPLOYMENT_NAME-${PROJECT_UITVRAAG}.${BASE_DOMAIN}"
PERSONAS_HOST="demopersonas-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"
PROEFTUIN_HOST="proeftuin-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"

echo "== ${PROJECT_MAGAZIJNEN}, deployment ${DEPLOYMENT}"
echo "   proeftuin:    ${IMAGE}"
echo "   demopersonas: ${PERSONAS_IMAGE}"
echo

# De personadienst draagt één leeslijst en geen enkele knop. Daarom géén authorization-wall: de
# nginx van de proeftuin haalt dit pad server-side op en heeft geen sessie, dus achter een muur
# krijgt hij 403 en meldt de berichtenbox dat het ophalen mislukt. Dat is precies het probleem dat
# deze dienst oplost. Het bedieningspaneel houdt zijn muur, want daar zit het legen achter.
zadctl component add demopersonas \
    --image "$PERSONAS_IMAGE" \
    --deployment "$DEPLOYMENT" \
    --port 8098 \
    --service publish-on-web "${DROOG[@]}"

# Vier aliassen en niet twee: de nginx van de proeftuin proxyt /api/v1/ naar de uitvraag en
# /api/demo/ naar de personadienst, en de ingress ervóór routeert op de Host-header. De browser-host
# doorgeven levert daar de verkeerde bestemming op, dus de servernaam gaat apart mee.
#
# Geen authorization-wall: die staat op het paneel, waar de legen-knop op zit. De berichtenbox
# leest alleen, en leest bij een uitvraag die hier toch al publiek bereikbaar is. Een muur zou hem
# bovendien onbruikbaar maken in een frame — de aanmeldpagina van Keycloak laat zich niet framen.
zadctl component add proeftuin \
    --image "$IMAGE" \
    --deployment "$DEPLOYMENT" \
    --port 8080 \
    --service publish-on-web \
    --aliases "
BACKEND_KETEN: https://${UITVRAAG_HOST}
BACKEND_KETEN_HOST: ${UITVRAAG_HOST}
BACKEND_DEMO: https://${PERSONAS_HOST}
BACKEND_DEMO_HOST: ${PERSONAS_HOST}
" "${DROOG[@]}"

# Het paneel toetst dit adres niet vooraf: een HEAD naar een ander component strandt op CORS, en
# die uitkomst is niet van onbereikbaar te onderscheiden. Staat de alias fout, dan blijft het frame
# dus leeg zonder dat iets dat meldt.
zadctl alias add --component democonsole \
    "BERICHTENBOX_URL=https://${PROEFTUIN_HOST}/moza/berichtenbox/" "${DROOG[@]}"

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
