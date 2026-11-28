#!/usr/bin/env bash
# Koppelt de ZAD-dienst `metrics-scraper` aan de drie diensten van de keten, en controleert dat die
# koppeling er staat. De tabel hieronder is de bron.
#
# Wat de dienst doet (RIG-Cluster `features/metrics-scraper.md` en `catalog/metrics_scraper/`):
# hij zet `prometheus.io/scrape|port|path` op de pod, waarna de Prometheus van het cluster
# `http://<pod-ip>:<port><path>` leest. Het token dat die Prometheus meestuurt, krijgt het component
# als `METRICS_AUTH_TOKEN`; de beheerpoort weigert alles zonder dat token (BeheerpoortBeveiliging).
#
# De koppeling en de poort/het pad hangen aan het COMPONENT in het projectbestand
# (`components[*]/services{metrics-scraper}`), niet aan een deployment. Elke deployment die het
# component draait — `test` en elke preview — rendert dus dezelfde annotaties, ook al neemt een
# preview bij het aanmaken alleen `{reference, image}` over. Dezelfde laag als de `health-check` uit
# demo/environment/zad-demo/gezondheidscontrole.sh, waar dat op `mpfb-8wh/pr-290` is nagemeten.
#
# Zonder poort scrapet de dienst de applicatiepoort, en daar geeft `/q/metrics` bewust een 404: de
# meetgegevens staan op de beheerpoort, de applicatiepoort met een 2 ervoor. Die poort staat niet in
# `ports.inbound` en hoeft dat niet: de scrape gaat rechtstreeks naar het pod-adres.
#
# Gebruik:
#   ZAD_API_KEY=... meetgegevens-scraper.sh controle <project>
#       Leest het projectbestand en muteert niets. Draait in deploy.yml na elke uitrol.
#   meetgegevens-scraper.sh plan|apply <basis> [project]
#       Voor een beheerder, met zadctl. Draait vanuit <basis>/<project>, waar een persoonlijk
#       projecttoken in .env.zadctl staat (zoals ~/.claude/skills/zadctl/zad-token.sh dat schrijft).
#       `plan` toont elke aanroep met --dry-run; `apply` zet ze door en rolt per project één keer uit.
#
# Draai `apply` niet terwijl er een uitrol loopt: OM vergrendelt op project
# (`gh run list --workflow "Deploy ZAD"`).

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'
readonly DIENST=metrics-scraper

# Overschrijfbaar zodat de unittests het verkeer kunnen onderscheppen zonder netwerk.
CURL=${ZAD_CURL:-curl}
ZADCTL=${ZAD_ZADCTL:-zadctl}

# De regels: project | component | poort | pad
#
# De poort is `quarkus.management.port=2${quarkus.http.port}` van de dienst. `magazijnb` draait op ZAD
# op 8090, net als `magazijna`; alleen lokaal in één netns krijgt hij 8091.
REGELS=(
    "mpfb-8wh|uitvraag|28086|/q/metrics"
    "mpfm-w3h|magazijna|28090|/q/metrics"
    "mpfm-w3h|magazijnb|28090|/q/metrics"
    "mpfs-rab|stelselregister|28094|/q/metrics"
)

gebruik() {
    echo "usage: meetgegevens-scraper.sh controle <project>" >&2
    echo "       meetgegevens-scraper.sh plan|apply <basis> [project]" >&2
    exit 64
}

# De regels van één project, of alle als het project leeg is.
regels_voor() {
    local filter=$1 r

    for r in "${REGELS[@]}"; do
        if [ -z "$filter" ] || [ "${r%%|*}" = "$filter" ]; then
            printf '%s\n' "$r"
        fi
    done
}

# Leest `GET /v2/projects/<p>/services` en vergelijkt per component poort en pad. Een afwijking is een
# waarschuwing en geen fout: een ontbrekende koppeling kost de meetgegevens, niet de dienst, en een
# uitrol tegenhouden zou van een gat in het beheer een storing in de keten maken. Een antwoord dat
# niet te lezen is, is wél exitcode 1: dan weet niemand of de koppeling er staat.
controle() {
    local project=$1 api_url=${ZAD_API_URL:-$STANDAARD_API_URL} antwoord status regels r afwijkingen=0

    [ -n "${ZAD_API_KEY:-}" ] || { echo "::error::ZAD_API_KEY ontbreekt" >&2; exit 1; }

    regels=$(regels_voor "$project")
    [ -n "$regels" ] || { echo "::error::Project '$project' staat niet in de tabel van meetgegevens-scraper.sh" >&2; exit 1; }

    # De key via een header-bestand, niet op de commandoregel: daar toont `ps` hem.
    antwoord=$(mktemp)
    trap 'rm -f "$antwoord"' RETURN

    status=$("$CURL" -sS -o "$antwoord" -w '%{http_code}' --max-time 30 \
        -H @<(printf 'X-API-Key: %s\n' "$ZAD_API_KEY") \
        "$api_url/v2/projects/$project/services") || status=000

    if [ "$status" != 200 ] || ! jq -e '.services | type == "array"' "$antwoord" >/dev/null 2>&1; then
        echo "::error::De diensten van $project zijn niet te lezen (HTTP $status); of $DIENST er staat, is onbekend" >&2
        exit 1
    fi

    while IFS='|' read -r _ component poort pad; do
        local gevonden

        gevonden=$(jq -c --arg d "$DIENST" --arg c "$component" '
            [.services[] | select(.name == $d) | .usages[]
             | select(.target == "component" and .component == $c)]
            | first // empty | .config // {}' "$antwoord")

        if [ -z "$gevonden" ]; then
            echo "::warning title=Geen meetgegevens::$project/$component draagt de dienst $DIENST niet; de Prometheus van het cluster leest dit component niet. Inrichten: .github/scripts/meetgegevens-scraper.sh apply <basis> $project"
            afwijkingen=$((afwijkingen + 1))
        elif [ "$(jq -r '"\(.port // "")|\(.path // "")"' <<<"$gevonden")" != "$poort|$pad" ]; then
            echo "::warning title=Meetgegevens op het verkeerde adres::$project/$component wordt gescrapet op $(jq -r '"poort \(.port // "applicatiepoort"), pad \(.path // "/metrics")"' <<<"$gevonden"), verwacht poort $poort, pad $pad"
            afwijkingen=$((afwijkingen + 1))
        else
            echo "$project/$component: $DIENST op poort $poort, pad $pad"
        fi
    done <<<"$regels"

    [ "$afwijkingen" -eq 0 ] || echo "$afwijkingen afwijking(en) in $project; de uitrol gaat door"
}

# Per project: binden, instellen, één uitrol. `service assign` is idempotent en selecteert de dienst
# ook op projectniveau. `service config set` schrijft het hele document, dus poort én pad horen er
# altijd allebei bij. `--no-rollout` op beide, zodat er geen moment is waarop een component de dienst
# draagt met de verkeerde poort; `project refresh` laat het daarna in één keer landen.
inrichten() {
    local modus=$1 basis=$2 filter=$3 project vorige="" component poort pad
    local -a droog=()

    [ "$modus" = plan ] && droog=(--dry-run)

    while IFS='|' read -r project component poort pad; do
        [ -f "$basis/$project/.env.zadctl" ] || {
            echo "geen $basis/$project/.env.zadctl; geef eerst een projecttoken uit (zad-token.sh uitgeven)" >&2
            exit 1
        }

        if [ -n "$vorige" ] && [ "$vorige" != "$project" ]; then
            ververs "$modus" "$basis" "$vorige"
        fi

        vorige=$project

        ( cd "$basis/$project" \
            && "$ZADCTL" --no-rollout service assign "$DIENST" -c "$component" "${droog[@]}" </dev/null \
            && "$ZADCTL" --no-rollout service config set "$DIENST" -c "$component" \
                --set "port=$poort" --set "path=$pad" --yes "${droog[@]}" </dev/null )
    done < <(regels_voor "$filter")

    [ -z "$vorige" ] || ververs "$modus" "$basis" "$vorige"
}

ververs() {
    local modus=$1 basis=$2 project=$3

    if [ "$modus" = plan ]; then
        echo "zou uitrollen: $project (zadctl project refresh)"
    else
        ( cd "$basis/$project" && "$ZADCTL" project refresh </dev/null )
    fi
}

[ "$#" -ge 1 ] || gebruik

case "$1" in
    controle)
        [ "$#" -eq 2 ] || gebruik
        controle "$2"
        ;;
    plan|apply)
        [ "$#" -ge 2 ] && [ "$#" -le 3 ] || gebruik
        [ -d "$2" ] || { echo "basismap '$2' bestaat niet" >&2; exit 1; }

        if [ -n "${3:-}" ] && [ -z "$(regels_voor "$3")" ]; then
            echo "project '$3' staat niet in de tabel" >&2
            exit 1
        fi

        inrichten "$1" "$2" "${3:-}"
        ;;
    *)
        gebruik
        ;;
esac
