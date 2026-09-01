#!/usr/bin/env bash
# Zet het proeftuin-component (de berichtenbox) in het magazijnen-project en wijst het
# bedieningspaneel ernaartoe. Eenmalig handwerk: de tag-updates lopen daarna via
# .github/workflows/deploy.yml, dat `proeftuin` in zijn componentenlijst noemt.
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

UITVRAAG_HOST="uitvraag-\$DEPLOYMENT_NAME-${PROJECT_UITVRAAG}.${BASE_DOMAIN}"
CONSOLE_HOST="democonsole-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"
PROEFTUIN_HOST="proeftuin-\$DEPLOYMENT_NAME-${PROJECT_MAGAZIJNEN}.${BASE_DOMAIN}"

echo "== proeftuin-component in ${PROJECT_MAGAZIJNEN}, deployment ${DEPLOYMENT}"
echo "   image: ${IMAGE}"
echo

# Vier aliassen en niet twee: de nginx van de proeftuin proxyt /api/v1/ naar de uitvraag en
# /api/demo/ naar het paneel, en de ingress ervóór routeert op de Host-header. De browser-host
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
BACKEND_DEMO: https://${CONSOLE_HOST}
BACKEND_DEMO_HOST: ${CONSOLE_HOST}
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
