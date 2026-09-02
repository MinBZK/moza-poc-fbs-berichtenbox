#!/usr/bin/env bash
# Zet de twee componenten die de berichtenbox naast het paneel mogelijk maken: `proeftuin` (het
# image van MinBZK/moza-poc) en `demopersonas` (onze personadienst). Eenmalig handwerk: de
# tag-updates lopen daarna via .github/workflows/deploy.yml, dat allebei in zijn componentenlijst
# noemt.
#
# Hoofdstuk 7 van README.md hiernaast beschrijft het waarom — de aliassen, de afwezige muur op
# beide componenten, en waarom de berichtenbox als eigen component in dít project draait.
#
# DRAAI DIT VÓÓR de merge van de PR die `demopersonas` én `proeftuin` in deploy.yml zet: die
# workflow noemt ze bij naam, en een verwijzing naar een component dat niet bestaat laat de uitrol
# falen. Voor een component waarvan het image nog niet gebouwd is, geef je met PERSONAS_IMAGE een
# bestaand, onschadelijk image mee; de eerste uitrol vervangt het door de juiste tag.
#
# LET OP: poorten en diensten passen alleen bij het AANMAKEN van een component. Die twee bijstellen
# betekent het component verwijderen en opnieuw aanmaken — `zadctl component remove`, nooit de
# deployment: een deployment verwijderen in mpfm-w3h wist de database die de magazijnen, de
# simulator en het paneel delen. Aliassen zijn wél los bij te werken; daar is deze tweede aanroep op
# gebouwd. Draai eerst `plan`.
#
# Usage:
#   zadctl login && zadctl project use mpfm-w3h
#   demo/environment/zad-demo/proeftuin-component.sh plan  [deployment=test]
#   demo/environment/zad-demo/proeftuin-component.sh apply [deployment=test]

set -euo pipefail

# `mapfile` en een lege array onder `set -u` vragen allebei bash 4.4. De bash die macOS meelevert is
# 3.2 en zou hier struikelen op een melding die de oorzaak niet noemt.
if [ "${BASH_VERSINFO[0]}" -lt 4 ] || { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -lt 4 ]; }; then
    echo "dit script vraagt bash 4.4 of nieuwer; deze is ${BASH_VERSION}" >&2
    echo "op macOS: 'brew install bash' en opnieuw draaien" >&2
    exit 1
fi

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

for hulp in zadctl python3 sed; do
    command -v "$hulp" >/dev/null || {
        echo "$hulp niet gevonden; dit script heeft het nodig" >&2
        echo "zadctl: https://github.com/RijksICTGilde/zad-cli/releases/latest" >&2
        exit 1
    }
done

# Het project komt expliciet mee in plaats van uit .env.zadctl in de werkmap: die is per directory,
# en de runbooks ernaast schakelen tussenin naar mpfb-8wh. Zonder deze vlag belanden de componenten
# stil in het project dat toevallig actief is, terwijl de aliassen hieronder mpfm-w3h-adressen
# dragen. `--strict` erbij, want zonder die vlag telt "aangenomen, maar er ging iets mis" — een taak
# die door een gelijktijdige deploy overruled is — als succes.
ZAD=(zadctl --strict -p "$PROJECT_MAGAZIJNEN")

# Exitcode 2 is platform of netwerk en dus de moeite van opnieuw proberen waard; 1 en 3 niet. Dat
# onderscheid doorgeven scheelt zoeken in een configuratie waar niets mis mee is.
meld_zadctl_fout() {
    local status="$1" wat="$2"

    if [ "$status" -eq 2 ]; then
        echo "$wat: platform of netwerk (exit 2). Vaak een deploy die op dit project al loopt —" >&2
        echo "  kijk met 'gh run list --workflow \"Deploy ZAD\"' en draai daarna opnieuw." >&2
    else
        echo "$wat (exit $status); zie de melding hierboven" >&2
    fi

    exit "$status"
}

# De image staat in deploy.yml en niet hier: die waarde is de bron, want zij bepaalt wat elke
# preview draait. Deze creatie zet alleen de startwaarde voor `test`.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

# Precies één treffer eisen, en de vorm toetsen. De buurvariabele TOXIPROXY_IMAGE in hetzelfde
# env-blok staat tussen aanhalingstekens; krijgt PROEFTUIN_IMAGE ooit dezelfde stijl, dan zouden die
# quotes zonder deze bewerking in de image-naam belanden en het component in ImagePullBackOff laten
# hangen.
mapfile -t IMAGE_TREFFERS < <(
    sed -n 's/^[[:space:]]*PROEFTUIN_IMAGE:[[:space:]]*//p' "$REPO_ROOT/.github/workflows/deploy.yml"
)

[ "${#IMAGE_TREFFERS[@]}" -eq 1 ] || {
    echo "verwachtte precies één PROEFTUIN_IMAGE in .github/workflows/deploy.yml," >&2
    echo "  maar vond er ${#IMAGE_TREFFERS[@]}" >&2
    exit 1
}

IMAGE="${IMAGE_TREFFERS[0]}"
IMAGE="${IMAGE%%[[:space:]]#*}"
IMAGE="${IMAGE%"${IMAGE##*[![:space:]]}"}"
IMAGE="${IMAGE#[\'\"]}"
IMAGE="${IMAGE%[\'\"]}"

case "$IMAGE" in
    */*:*) ;;
    *) echo "PROEFTUIN_IMAGE '$IMAGE' ziet er niet uit als een image met een tag of digest" >&2; exit 1 ;;
esac

# Eén keer opvragen: hieruit komt zowel de tag van de personadienst als het antwoord op de vraag
# welke componenten er al staan. Geen 2>/dev/null: niet-ingelogd, verkeerd project of een lock bij
# OM zou anders als "niet gevonden" langskomen, en dan ga je een image-naam invullen terwijl je moet
# inloggen.
BESCHRIJVING="$("${ZAD[@]}" deployment describe "$DEPLOYMENT" -o json)" ||
    meld_zadctl_fout $? "zadctl kon deployment '$DEPLOYMENT' niet beschrijven"

# Gestructureerd lezen en niet greppen: een grep op `"name": "x"` hangt aan de opmaak die de CLI
# niet belooft, en matcht bovendien elke andere benoemde zaak in het antwoord — een dienst of
# attachment met dezelfde naam zou een component als "bestaat al" laten wegvallen.
COMPONENTEN="$(printf '%s' "$BESCHRIJVING" | python3 -c "
import json, sys

print('\n'.join(c['name'] for c in json.load(sys.stdin).get('components', [])))
")" || {
    echo "componentenlijst niet uit het describe-antwoord te lezen" >&2
    exit 1
}

component_bestaat() {
    printf '%s\n' "$COMPONENTEN" | grep -qxF "$1"
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
        echo "zet PERSONAS_IMAGE zelf, met een bestaand en onschadelijk image" >&2
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

component_add() {
    # Een bestaand component is geen fout: dit script wordt gedraaid op omgevingen die al half
    # ingericht zijn, en zonder deze tak sterft het onder `set -e` vóór de aliassen verderop.
    #
    # Alleen dát geval overslaan, en niet elke fout: niet-ingelogd, een OM-lock of een ongeldige
    # image-naam zou anders ook als "bestaat hij al" langskomen, waarna het script doorloopt en met
    # 0 eindigt terwijl het component er niet is. De uitrol faalt dan later, op een fout die hier
    # gemaakt is.
    if component_bestaat "$1"; then
        echo "component '$1' bestaat al in deployment '$DEPLOYMENT'; overgeslagen." >&2
        echo "  Poorten en diensten van een bestaand component verandert dit script niet — die" >&2
        echo "  passen alleen bij het aanmaken. Kloppen ze niet, dan is verwijderen en opnieuw" >&2
        echo "  aanmaken de route; de aliassen hieronder worden wél bijgewerkt." >&2

        return 0
    fi

    "${ZAD[@]}" component add "$@" "${DROOG[@]}"
}

component_add demopersonas \
    --image "$PERSONAS_IMAGE" \
    --deployment "$DEPLOYMENT" \
    --port 8098 \
    --service publish-on-web

component_add proeftuin \
    --image "$IMAGE" \
    --deployment "$DEPLOYMENT" \
    --port 8080 \
    --service publish-on-web

# Apart van het aanmaken en niet als `--aliases`: dit script draait juist ook op omgevingen die er
# al staan, en dan komt config-bij-creatie nooit langs. Verschuift er een variabelenaam aan de kant
# van de proeftuin, dan trekt een tweede aanroep hem hiermee recht.
alias_zet() {
    local component="$1"
    shift

    # In plan-modus is het component niet aangemaakt, dus is er niets om tegenaan te lezen. Zonder
    # deze tak zou juist de verse omgeving — het geval waarvoor `plan` bedoeld is — afbreken op een
    # component dat nog niet bestaat, en zag je de aliassen nooit.
    if [ "$MODE" = plan ] && ! component_bestaat "$component"; then
        echo "plan: '$component' bestaat nog niet; deze aliassen komen er bij het aanmaken op:" >&2
        printf '  %s\n' "$@" >&2

        return 0
    fi

    # `add` is POST en weigert een bestaande sleutel; `set` is PATCH en weigert een nieuwe. Geen van
    # beide is dus op zichzelf idempotent — vandaar per sleutel de vorm die past bij wat er staat.
    # `alias get` beantwoordt precies die vraag met zijn exitcode; stderr blijft open, zodat een
    # auth- of netwerkfout niet als "sleutel bestaat niet" wegvalt.
    local paar naam
    for paar in "$@"; do
        naam="${paar%%=*}"

        if "${ZAD[@]}" alias get "$naam" -c "$component" >/dev/null; then
            "${ZAD[@]}" alias set --component "$component" "$paar" "${DROOG[@]}"
        else
            "${ZAD[@]}" alias add --component "$component" "$paar" "${DROOG[@]}"
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

Verwacht 200. Een 502 of 503 betekent meestal dat er geen pod draait: kijk dan eerst naar replicas
in het gerenderde deployment.yaml, niet naar het image. Daarna het paneel openen en kijken of de
berichtenbox in het frame staat; dat vraagt wel een democonsole-image waarin BERICHTENBOX_URL
gelezen wordt.

Richt de health-check niet op /health: dat pad proxyt naar een chat-backend die in dit project niet
bestaat, en een probe erop herstart de pod anderhalve minuut later.
KLAAR
