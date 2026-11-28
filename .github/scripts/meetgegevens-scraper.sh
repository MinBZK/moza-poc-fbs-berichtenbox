#!/usr/bin/env bash
# Koppelt de ZAD-dienst `metrics-scraper` aan de drie diensten van de keten, en controleert dat die
# koppeling er staat. De tabel hieronder is de bron.
#
# Wat de dienst doet (`zadctl service describe metrics-scraper`): hij zet
# `prometheus.io/scrape|port|path` op de pod, waarna de Prometheus van het cluster
# `http://<pod-ip>:<port><path>` leest. Het token dat die Prometheus meestuurt, krijgt het component
# als `METRICS_AUTH_TOKEN`; de beheerpoort weigert alles zonder dat token (BeheerpoortBeveiliging).
#
# De koppeling en de poort/het pad hangen aan het COMPONENT in het projectbestand
# (`components[*]/services{metrics-scraper}`; `config targets: component`), niet aan een deployment.
# Elke deployment die het component draait — `test` en elke preview — rendert dus dezelfde
# annotaties, ook al neemt een preview bij het aanmaken alleen `{reference, image}` over. Dezelfde
# laag als de `health-check` uit demo/environment/zad-demo/gezondheidscontrole.sh, waar dat op
# `mpfb-8wh/pr-290` is nagemeten.
#
# Zonder poort scrapet de dienst de applicatiepoort, en daar geeft `/q/metrics` bewust een 404: de
# meetgegevens staan op de beheerpoort, de applicatiepoort met een 2 ervoor. Die poort staat niet in
# `ports.inbound` en hoeft dat niet: de scrape gaat rechtstreeks naar het pod-adres.
#
# Gebruik:
#   ZAD_API_KEY=... meetgegevens-scraper.sh controle <project>
#       Leest het projectbestand en muteert niets. Draait in deploy.yml bij elke run die uitrolt,
#       naast de uitrol: hij leest de koppeling, niet het cluster.
#   meetgegevens-scraper.sh plan|apply <basis> [project]
#       Voor een beheerder, met zadctl. Draait vanuit <basis>/<project>, waar een persoonlijk
#       projecttoken in .env.zadctl staat (uit te geven met zadctl; zie docs/operations/zad-gitops.md).
#       `plan` toont elke aanroep met --dry-run; `apply` zet ze door en rolt per project één keer uit.
#
# `apply` weigert terwijl er een "Deploy ZAD"-run loopt: OM vergrendelt op project, en `project
# refresh` zou de wachtstap van die uitrol overnemen. MEETGEGEVENS_FORCEER=1 slaat die controle over.

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'
readonly DIENST=metrics-scraper

# Overschrijfbaar zodat de unittests het verkeer kunnen onderscheppen zonder netwerk.
CURL=${ZAD_CURL:-curl}
ZADCTL=${ZAD_ZADCTL:-zadctl}
GH=${ZAD_GH:-gh}

# De regels: project | component | poort | pad
#
# De poort is `quarkus.management.port=2${quarkus.http.port}` van de dienst. `magazijnb` draait op ZAD
# op 8090, net als `magazijna`; alleen lokaal in één netns krijgt hij 8091. De project-ids moeten
# gelijk zijn aan de PROJECT_*-waarden in deploy.yml; test-meetgegevens-scraper.sh toetst dat.
REGELS=(
    "mpfb-8wh|uitvraag|28086|/q/metrics"
    "mpfm-w3h|magazijna|28090|/q/metrics"
    "mpfm-w3h|magazijnb|28090|/q/metrics"
    "mpfs-rab|stelselregister|28094|/q/metrics"
)

gebruik() {
    echo "gebruik: meetgegevens-scraper.sh controle <project>" >&2
    echo "         meetgegevens-scraper.sh plan|apply <basis> [project]" >&2
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

ANTWOORD=""
trap '[ -z "$ANTWOORD" ] || rm -f "$ANTWOORD"' EXIT

# Leest `GET /v2/projects/<p>/services` en vergelijkt per component poort en pad. Een afwijking is een
# waarschuwing en geen fout: een ontbrekende koppeling kost de meetgegevens, niet de dienst, en een
# uitrol tegenhouden zou van een gat in het beheer een storing in de keten maken. Een antwoord dat
# niet te lezen is, is wél exitcode 1: dan weet niemand of de koppeling er staat.
controle() {
    local project=$1 api_url=${ZAD_API_URL:-$STANDAARD_API_URL} status regels afwijkingen=0 wachtend

    [ -n "${ZAD_API_KEY:-}" ] || { echo "::error::ZAD_API_KEY ontbreekt" >&2; exit 1; }

    regels=$(regels_voor "$project")
    [ -n "$regels" ] || { echo "::error::Project '$project' staat niet in de tabel van meetgegevens-scraper.sh" >&2; exit 1; }

    ANTWOORD=$(mktemp)

    # De key via een header-bestand, niet op de commandoregel: daar toont `ps` hem.
    status=$("$CURL" -sS -o "$ANTWOORD" -w '%{http_code}' --max-time 30 \
        -H @<(printf 'X-API-Key: %s\n' "$ZAD_API_KEY") \
        "$api_url/v2/projects/$project/services") || status=000

    # De vorm hoort bij "leesbaar": een veld dat ontbreekt of van type wisselt, zou anders als een
    # ontbrekende koppeling lezen, elke run opnieuw, tot iedereen de waarschuwing negeert.
    if [ "$status" != 200 ] || ! jq -e '
        (.services | type == "array")
        and all(.services[]; (.name | type == "string") and (.usages | type == "array"))' "$ANTWOORD" >/dev/null 2>&1; then
        echo "::error::De diensten van $project zijn niet te lezen (HTTP $status); of $DIENST er staat, is onbekend" >&2
        exit 1
    fi

    while IFS='|' read -r _ component poort pad; do
        local gevonden

        gevonden=$(jq -c --arg d "$DIENST" --arg c "$component" '
            [.services[] | select(.name == $d) | .usages[]
             | select(.target == "component" and .component == $c)]
            | first // empty | .config // {}' "$ANTWOORD")

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

    # Het antwoord beschrijft het projectbestand, niet het cluster: een wijziging die met
    # --no-rollout is opgeslagen en nooit uitgerold, staat er al in. Een lopende uitrol is geen gat.
    wachtend=$(jq -r 'if (.pending_rollout.count // 0) > 0 and (.pending_rollout.rollout_in_progress // false | not)
        then "\(.pending_rollout.count)|\(.pending_rollout.since // "onbekend")" else "" end' "$ANTWOORD")

    if [ -n "$wachtend" ]; then
        echo "::warning title=Niet uitgerold::$project heeft ${wachtend%%|*} opgeslagen wijziging(en) die niet op het cluster staan (sinds ${wachtend#*|}); de koppeling hierboven is dan mogelijk nog niet actief. Uitrollen: zadctl project refresh"
        afwijkingen=$((afwijkingen + 1))
    fi

    [ "$afwijkingen" -eq 0 ] || echo "$afwijkingen afwijking(en) in $project; de uitrol gaat door"
}

# Welk project wijzigingen heeft staan die nog niet zijn uitgerold, voor de melding als iets faalt.
OPEN_PROJECT=""
OPEN_STAP=""

meld_open_project() {
    local status=$?

    if [ "$status" -ne 0 ] && [ -n "$OPEN_PROJECT" ]; then
        echo "apply afgebroken bij $OPEN_STAP in $OPEN_PROJECT (exit $status)." >&2
        echo "  $OPEN_PROJECT kan opgeslagen wijzigingen hebben die niet zijn uitgerold, met een component dat de" >&2
        echo "  dienst draagt zonder de goede poort. Bekijk ze met 'zadctl project pending' vanuit de projectmap," >&2
        echo "  en draai daarna apply opnieuw voor $OPEN_PROJECT: elke stap is idempotent." >&2
    fi
}

# Per project: binden, instellen, één uitrol. `service assign` is idempotent en selecteert de dienst
# ook op projectniveau. `service config set` schrijft het hele document, dus poort én pad horen er
# altijd allebei bij. `--no-rollout` op beide, zodat een geslaagde reeks in één uitrol landt; faalt
# er een stap, dan meldt meld_open_project welk project half staat.
inrichten() {
    local modus=$1 basis=$2 filter=$3 project vorige="" component poort pad
    local -a droog=()

    [ "$modus" = plan ] && droog=(--dry-run)

    # Eerst alle tokens, dan pas de eerste mutatie: een ontbrekend token halverwege laat anders een
    # vorig project half ingericht achter.
    while IFS='|' read -r project _; do
        [ -f "$basis/$project/.env.zadctl" ] || {
            echo "geen $basis/$project/.env.zadctl; geef eerst een persoonlijk projecttoken uit met zadctl" >&2
            exit 1
        }
    done < <(regels_voor "$filter")

    if [ "$modus" = apply ] && [ "${MEETGEGEVENS_FORCEER:-0}" != 1 ]; then
        geen_lopende_uitrol
    fi

    trap 'meld_open_project; [ -z "$ANTWOORD" ] || rm -f "$ANTWOORD"' EXIT

    while IFS='|' read -r project component poort pad; do
        if [ -n "$vorige" ] && [ "$vorige" != "$project" ]; then
            ververs "$modus" "$basis" "$vorige"
        fi

        vorige=$project
        [ "$modus" = apply ] && OPEN_PROJECT=$project

        # `${droog[@]+...}`: een lege array onder `set -u` breekt bash ouder dan 4.4 (macOS).
        OPEN_STAP="service assign $component"
        ( cd "$basis/$project" && "$ZADCTL" --no-rollout service assign "$DIENST" -c "$component" ${droog[@]+"${droog[@]}"} </dev/null )

        OPEN_STAP="service config set $component"
        ( cd "$basis/$project" && "$ZADCTL" --no-rollout service config set "$DIENST" -c "$component" \
            --set "port=$poort" --set "path=$pad" --yes ${droog[@]+"${droog[@]}"} </dev/null )
    done < <(regels_voor "$filter")

    [ -z "$vorige" ] || ververs "$modus" "$basis" "$vorige"
}

ververs() {
    local modus=$1 basis=$2 project=$3

    if [ "$modus" = plan ]; then
        echo "zou uitrollen: $project (zadctl project refresh)"
    else
        OPEN_STAP="project refresh"
        ( cd "$basis/$project" && "$ZADCTL" project refresh </dev/null )
        OPEN_PROJECT=""
        echo "$project: uitgerold"
    fi
}

# Een lopende of wachtende "Deploy ZAD"-run; zonder gh is dat niet na te gaan, en dan weigert apply.
geen_lopende_uitrol() {
    local status aantal

    command -v "$GH" >/dev/null || {
        echo "gh niet gevonden; of er een uitrol loopt, is niet na te gaan. MEETGEGEVENS_FORCEER=1 slaat dit over." >&2
        exit 1
    }

    for status in in_progress queued; do
        aantal=$("$GH" run list --repo MinBZK/moza-poc-fbs-berichtenbox --workflow "Deploy ZAD" --status "$status" \
            --json databaseId --jq 'length') || {
            echo "kon de lopende uitrollen niet opvragen; MEETGEGEVENS_FORCEER=1 slaat dit over." >&2
            exit 1
        }

        [ "$aantal" = 0 ] || {
            echo "er staat een 'Deploy ZAD'-run op $status; wacht tot hij klaar is (OM vergrendelt op project)." >&2
            exit 1
        }
    done
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
