#!/usr/bin/env bash
# Start de demo-stack onder podman en kiest zelf de werkbare netwerkmodus.
#
#   bridge   — normale podman (Linux rootless, podman machine op macOS/Windows). Adressering en
#              poorten gelijk aan de Docker-stack; alleen image-namen en SELinux-labels wijken af.
#   hostnet  — vangnet voor omgevingen zonder bruikbaar bridge-netwerk, bijvoorbeeld podman-in-een-
#              container: `/proc/sys` is daar read-only (netavark kan geen bridge opzetten) en
#              aardvark-dns ontbreekt vaak, dus container-DNS resolvet niet. Alle containers delen
#              dan de netns van de aanroeper en praten over 127.0.0.1.
#
# In hostnet komt er een derde compose-bestand bovenop de basis en de podman-overlay. Dat bestand
# heeft geen healthchecks (zie de reden daar), dus start die modus uitsluitend via dit script: een
# kale `up -d` meldt daar succes zodra de containers gestart zijn, ook als Postgres nog
# initialiseert. In bridge doen de healthchecks uit compose.yaml dat werk wél.
#
# Gebruik:
#   demo/podman-up.sh                 # modus automatisch bepalen
#   MODUS=hostnet demo/podman-up.sh   # forceren
#   DEMO_HOST=10.0.0.5 demo/podman-up.sh
#
# DEMO_HOST is het adres waarop je de demo benadert (voor de CORS-allowlist van de uitvraag);
# default localhost. De poorten zelf blijven op loopback — zie DEMO_BIND in compose.yaml, en de
# toelichting bij de laatste controle onderaan dit script.
set -euo pipefail

WORTEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$WORTEL"

export DEMO_HOST="${DEMO_HOST:-localhost}"

# DEMO_BIND stuurt op welk hostadres compose publiceert (zie compose.yaml). Alleen loopback of de
# wildcard: elke controle in dit script gaat over 127.0.0.1, en dat adres blijft bij beide
# bereikbaar. Bij een specifiek adres (`DEMO_BIND=10.0.0.5`) zouden die probes 180 seconden lang
# aflopen en de start laten falen terwijl de stack gezond draait.
case "${DEMO_BIND:-127.0.0.1}" in
    127.0.0.1|0.0.0.0) ;;
    *)
        echo "FOUT: DEMO_BIND='${DEMO_BIND}' wordt niet ondersteund; gebruik 127.0.0.1 (default) of 0.0.0.0." >&2
        echo "      De controles in dit script gaan over 127.0.0.1; een specifiek adres laat ze aflopen." >&2
        exit 2
        ;;
esac

# Doorgeven aan compose én aan de generator vanaf één plek; beide hebben hun eigen default. De
# grootste ondernemer heeft honderd aangesloten organisaties, waarvan twee echt, dus onder de 98
# weigert de generator — daar valt van die ondernemer niets te bouwen.
export DEMO_MAGAZIJNEN="${DEMO_MAGAZIJNEN:-98}"

PROBE_IMAGE=docker.io/library/alpine:3.20
DEMO_IMAGES=(
    localhost/fbs-demo/fbs-berichtenmagazijn:demo
    localhost/fbs-demo/fbs-berichtenuitvraag:demo
    localhost/fbs-demo/fbs-demo-console:demo
    localhost/fbs-demo/fbs-magazijn-simulator:demo
)

# --- gereedschap en podman-socket bepalen -----------------------------------------------------

for gereedschap in podman curl python3; do
    command -v "$gereedschap" >/dev/null 2>&1 || {
        echo "$gereedschap niet gevonden op PATH." >&2
        exit 1
    }
done

# macOS levert geen `timeout`; met coreutils heet hij `gtimeout`. Zonder deze check faalt elke
# probe pas na de volledige deadline op een 'command not found' die niemand verwacht.
TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

[ -n "$TIMEOUT_BIN" ] || {
    echo "timeout(1) ontbreekt (macOS: brew install coreutils)." >&2
    exit 1
}

if [ -z "${DOCKER_HOST:-}" ]; then
    SOCK="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"

    if [ ! -S "$SOCK" ]; then
        echo "Podman-API-socket ontbreekt ($SOCK) — starten…"
        mkdir -p "$(dirname "$SOCK")"

        # `--time=0` houdt de service draaien ná dit script, zodat een latere `… down` dezelfde
        # socket vindt. Het log blijft staan: zonder die uitvoer is een mislukte start (subuid,
        # storage.conf, bezette socket) niet te diagnosticeren.
        SERVICE_LOG="$(mktemp -t podman-service.XXXXXX)"
        podman system service --time=0 "unix://$SOCK" >"$SERVICE_LOG" 2>&1 &
        for _ in $(seq 1 20); do [ -S "$SOCK" ] && break; sleep 0.5; done

        [ -S "$SOCK" ] || {
            echo "podman system service kwam niet omhoog ($SOCK):" >&2
            tail -20 "$SERVICE_LOG" >&2
            exit 1
        }
    fi

    export DOCKER_HOST="unix://$SOCK"
fi

# `podman` leest DOCKER_HOST niet (dat is CONTAINER_HOST), compose wél. Wijst DOCKER_HOST naar een
# andere engine, dan zouden de podman-checks hieronder een andere machine beoordelen dan waarop de
# stack landt; ze worden dan overgeslagen in plaats van een verkeerd antwoord te geven.
PODMAN_RUNTIME=0
case "$DOCKER_HOST" in
    *podman*) PODMAN_RUNTIME=1 ;;
esac

if [ "$PODMAN_RUNTIME" -eq 0 ]; then
    echo "Let op: DOCKER_HOST=$DOCKER_HOST wijst niet naar een podman-socket."
    echo "  De image- en netwerkcontroles die podman rechtstreeks bevragen worden overgeslagen."
fi

# `docker compose` (v2) eerst: hostnet gebruikt `!reset`, dat pas v2.24.4 kent. Een los
# `docker-compose` op PATH kan nog de oude v1 zijn.
if docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(docker-compose)
elif command -v podman-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(podman-compose)
else
    echo "Geen compose-implementatie gevonden (docker compose, docker-compose of podman-compose)." >&2
    exit 1
fi

# --- modus bepalen ----------------------------------------------------------------------------

kan_bridge() {
    local net="fbs-netprobe-$$-${RANDOM}" log ok=1

    log="$(mktemp -t netprobe.XXXXXX)"

    probe_opruimen() {
        podman rm -f "${net}-doel" >/dev/null 2>&1 || true
        podman network rm "$net" >/dev/null 2>&1 || true
        rm -f "$log"
    }

    # Probe-netwerken en -containers stapelen zich anders op in de podman-state. De signaalvariant
    # sluit zelf af: zonder `exit` slikt de handler het signaal en loopt het script gewoon door.
    trap 'probe_opruimen; trap - RETURN INT TERM; exit 130' INT TERM
    trap 'probe_opruimen; trap - RETURN INT TERM' RETURN

    # Het probe-image apart binnenhalen: een registry-fout (rate limit, air-gapped, proxy) zegt
    # niets over de bruikbaarheid van de bridge, maar zou anders als 'geen bridge' tellen.
    if ! podman image exists "$PROBE_IMAGE" && ! podman pull -q "$PROBE_IMAGE" >"$log" 2>&1; then
        echo "  probe-image $PROBE_IMAGE niet beschikbaar: $(tail -1 "$log")" >&2
        echo "  Modus niet vast te stellen; kies expliciet MODUS=bridge of MODUS=hostnet." >&2
        probe_opruimen
        exit 1
    fi

    if ! podman network create "$net" >"$log" 2>&1; then
        echo "  bridge niet bruikbaar: $(tail -1 "$log")"
        return 1
    fi

    # Bridge én naamresolutie moeten werken: zonder aardvark-dns start een container wel, maar
    # resolvet geen enkele service-naam en faalt de hele stack pas ná het opstarten.
    if podman run -d --rm --name "${net}-doel" --network "$net" \
           "$PROBE_IMAGE" sleep 60 >"$log" 2>&1; then

        # Een paar pogingen: aardvark-dns publiceert het record niet altijd meteen, en één
        # vals-negatief zou de stack naar de riskantere modus duwen.
        for _ in 1 2 3; do
            podman run --rm --network "$net" "$PROBE_IMAGE" \
                getent hosts "${net}-doel" >/dev/null 2>&1 && { ok=0; break; }

            sleep 2
        done

        if [ "$ok" -ne 0 ]; then
            echo "  bridge staat, maar containernamen resolven niet (aardvark-dns?)"
        fi
    else
        echo "  bridge staat, maar een container starten erop lukt niet: $(tail -1 "$log")"
    fi

    return $ok
}

MODUS="${MODUS:-}"

if [ -z "$MODUS" ]; then
    echo "[1/4] netwerkmodus bepalen"

    # Buiten Linux draait podman in een VM; een gedeelde netns is dan die van de VM en niet die
    # van de host, zodat er zonder gepubliceerde poorten niets doorkomt. Hostnet is daar dus geen
    # bruikbaar vangnet en wordt nooit automatisch gekozen. Draait de stack op een andere engine
    # dan podman, dan geldt hetzelfde: hostnet bestaat alleen voor kapotte podman-netwerken, en de
    # probe hieronder zou een engine beoordelen die de stack niet draait.
    if [ "$(uname -s)" != "Linux" ] || [ "$PODMAN_RUNTIME" -eq 0 ]; then
        MODUS=bridge
    elif kan_bridge; then
        MODUS=bridge
    else
        MODUS=hostnet
    fi

    echo "  → $MODUS"
else
    echo "[1/4] netwerkmodus: $MODUS (opgegeven)"
fi

C=("${COMPOSE_BIN[@]}" -f compose.yaml -f compose.podman.yaml)

if [ "$MODUS" = "hostnet" ]; then
    C+=(-f compose.podman-hostnet.yaml)
fi

C+=(--profile demo)

# Toets de uitkomst, niet het versienummer en niet of het parset: `version --short` geeft bij een
# externe compose-provider een versie die niets over de ondersteunde spec zegt, en een onbekende
# YAML-tag wordt door sommige implementaties stilzwijgend genegeerd in plaats van geweigerd. Dus
# renderen en kijken of `!reset` écht is toegepast — blijft er een gepubliceerde poort staan naast
# `network_mode: host`, dan mislukt de start pas verderop en zonder bruikbare melding.
if ! RENDER="$("${C[@]}" config 2>&1)"; then
    echo "Deze compose kan de gestapelde bestanden niet verwerken:" >&2
    printf '%s\n' "$RENDER" >&2

    # Implementaties die `!reset` niet kennen struikelen al over de tag zelf en komen dus hier
    # terecht, niet in de assertie hieronder.
    if [ "$MODUS" = "hostnet" ]; then
        echo "hostnet vraagt compose v2.24.4 of nieuwer voor \`!reset\`; podman-compose kan dit niet." >&2
    fi

    exit 1
fi

if [ "$MODUS" = "hostnet" ] && printf '%s\n' "$RENDER" | grep -q 'published:'; then
    echo "Er blijft een gepubliceerde poort staan naast \`network_mode: host\`." >&2
    echo "Waarschijnlijk heeft een service in compose.yaml \`ports:\` gekregen zonder dat" >&2
    echo "compose.podman-hostnet.yaml die met \`ports: !reset []\` opruimt." >&2
    exit 1
fi

echo "[2/4] artefacten genereren"
"$WORTEL/demo/podman-prepare.sh" "$MODUS"

# --- starten ----------------------------------------------------------------------------------

ontbrekende_images() {
    local image

    for image in "${DEMO_IMAGES[@]}"; do
        podman image exists "$image" || echo "$image"
    done
}

if [ "$PODMAN_RUNTIME" -eq 1 ]; then
    ONTBREEKT=()

    # Geen `mapfile`: die bestaat pas vanaf bash 4 en macOS levert bash 3.2 als /bin/bash.
    while IFS= read -r image; do ONTBREEKT+=("$image"); done < <(ontbrekende_images)

    if [ "${#ONTBREEKT[@]}" -gt 0 ]; then
        echo "Deze demo-images ontbreken lokaal:" >&2
        printf '  %s\n' "${ONTBREEKT[@]}" >&2
        echo "Bouw ze eerst met jib (zie docs/demo-runbook.md §2)." >&2
        exit 1
    fi
fi

# Bewijst dat ónze container draait. Dat is in een gedeelde netns de enige betrouwbare controle:
# een poortprobe daar kan net zo goed beantwoord worden door een service die de gebruiker al had
# draaien, terwijl onze container op 'address already in use' is gestopt. Het containerlog noemt
# dan precies welke poort bezet was.
vereis_draaiend() {
    local svc uit fout

    fout="$(mktemp -t vereis-draaiend.XXXXXX)"

    for svc in "$@"; do
        # stderr apart houden: podman schrijft bij elke compose-aanroep een provider-banner naar
        # stderr, en die zou de leeg-test — de eigenlijke controle — altijd laten slagen.
        if ! uit="$("${C[@]}" ps -q --status running "$svc" 2>"$fout")"; then
            echo "FOUT: kon de status van '$svc' niet opvragen: $(tail -3 "$fout")" >&2
            rm -f "$fout"
            exit 1
        fi

        if [ -z "$uit" ]; then
            echo "FOUT: container '$svc' draait niet." >&2
            "${C[@]}" logs --tail=30 "$svc" >&2 || true
            rm -f "$fout"
            exit 1
        fi
    done

    rm -f "$fout"
}

# wacht_op <label> <compose-service|-> <commando…>
wacht_op() {
    local naam="$1" svc="$2" eind=$((SECONDS + 180)) uitvoer status
    shift 2

    while :; do
        # `status=$?` ná een `if` leest de status van het if-statement (altijd 0), niet die van
        # de conditie; vandaar de else-tak.
        if uitvoer="$("$TIMEOUT_BIN" 10 "$@" 2>&1)"; then
            break
        else
            status=$?
        fi

        if [ "$SECONDS" -ge "$eind" ]; then
            echo "TIMEOUT: $naam kwam niet omhoog (laatste exit $status)." >&2

            if [ "$status" -eq 124 ]; then
                echo "  de laatste poging liep in de timeout van 10s" >&2
            fi

            if [ -n "$uitvoer" ]; then
                printf '%s\n' "$uitvoer" >&2
            fi

            if [ "$svc" != "-" ]; then
                "${C[@]}" logs --tail=30 "$svc" >&2 || true
            fi

            exit 1
        fi

        sleep 2
    done

    echo "  ✓ $naam"
}

INFRA=(redis postgres-a postgres-b postgres-uitvraag postgres-simulator profiel-service
       magazijn-a magazijn-b aanmeld-stub notificatie-stub toxiproxy)
SERVICES=(berichtenmagazijn-a berichtenmagazijn-b magazijn-simulator berichtenuitvraag demo-console)

echo "[3/4] infra starten ($MODUS)"
"${C[@]}" up -d "${INFRA[@]}"

# `up -d` geeft 0 zodra een container gestart is, ook als hij een seconde later crasht; daarom
# eerst de containerstatus, dan pas de functionele probes.
vereis_draaiend "${INFRA[@]}"

# Postgres-b, het LDV-logboek van de uitvraag en de database van de simulator luisteren in hostnet
# op hun eigen PGPORT (5433, 5434 en 5435), in bridge intern gewoon op 5432; `pg_isready` draait
# binnen de container en volgt die keuze.
PGPORT_B=5432
PGPORT_UITVRAAG=5432
PGPORT_SIMULATOR=5432
[ "$MODUS" = "hostnet" ] && PGPORT_B=5433 && PGPORT_UITVRAAG=5434 && PGPORT_SIMULATOR=5435

wacht_op "redis"      redis      "${C[@]}" exec -T redis redis-cli ping
wacht_op "postgres-a" postgres-a "${C[@]}" exec -T postgres-a pg_isready -U berichtenmagazijn -d berichtenmagazijn
wacht_op "postgres-b" postgres-b "${C[@]}" exec -T postgres-b pg_isready -U berichtenmagazijn -d berichtenmagazijn -p "$PGPORT_B"
wacht_op "postgres-uitvraag" postgres-uitvraag "${C[@]}" exec -T postgres-uitvraag pg_isready -U ldv -d ldv_logging -p "$PGPORT_UITVRAAG"

# Deze twee draaien vanaf de host: in bridge via de gepubliceerde poort, in hostnet via diezelfde
# poort in de gedeelde netns. Zonder ze meldt het script succes terwijl register of
# profielvoorkeuren onbereikbaar zijn.
wacht_op "profiel-service" profiel-service curl -sSf --max-time 3 http://127.0.0.1:8089/__admin/mappings
wacht_op "postgres-simulator" postgres-simulator "${C[@]}" exec -T postgres-simulator \
    pg_isready -U magazijnsimulator -d magazijnsimulator -p "$PGPORT_SIMULATOR"

# Elke proxy afzonderlijk controleren, niet of de admin-API leeft en ook niet of één bekende naam
# er staat. Toxiproxy stopt namelijk bij de eerste listener die niet kan binden, laat de rest van
# het bestand ongeladen en blijft daarna gezond draaien met een status 200 op `GET /proxies` — dus
# noch de containerstatus noch een steekproef op één naam ziet het. In een gedeelde netns is een
# bezette proxy-poort een reëel geval, en de keten is dan stil kapot.
PROXY_BRON="$WORTEL/toxiproxy/proxies.json"

if [ "$MODUS" = "hostnet" ]; then
    PROXY_BRON="$WORTEL/demo/generated/proxies-host.json"
fi

# Niet alleen de namen vergelijken maar ook waar elke proxy luistert en naartoe stuurt. De
# demo-console maakt de proxies namelijk zélf aan en zet ze elke reconcile-ronde terug; staan haar
# adressen niet op deze modus ingesteld, dan houdt Toxiproxy dezelfde zes namen over met upstreams
# die hier niet bestaan. Op namen alleen is dat niet te zien, en de keten is dan stil kapot.
#
# Als tekst in een variabele en niet als shell-functie: `wacht_op` draait zijn commando onder
# timeout(1), en die kan geen functie aanroepen.
PROXY_CHECK='
import json, sys, urllib.request

def sleutel(proxy):
    # Toxiproxy geeft een listen op 0.0.0.0 terug als "[::]:<poort>"; alleen de poort vergelijken.
    return proxy["name"], proxy["listen"].rsplit(":", 1)[-1], proxy["upstream"]

verwacht = {sleutel(p) for p in json.load(open(sys.argv[1]))}
actief = {sleutel(p) for p in json.load(urllib.request.urlopen("http://127.0.0.1:8474/proxies", timeout=3)).values()}
afwijkend = verwacht - actief

if afwijkend:
    print("proxies wijken af van " + sys.argv[1] + ":", file=sys.stderr)

    for naam, poort, upstream in sorted(afwijkend):
        print(f"  verwacht {naam} op poort {poort} naar {upstream}", file=sys.stderr)

    print("  actief: " + ", ".join(f"{n}:{p}->{u}" for n, p, u in sorted(actief)), file=sys.stderr)
    sys.exit(1)
'

wacht_op "toxiproxy" toxiproxy python3 -c "$PROXY_CHECK" "$PROXY_BRON"

# Een poortprobe bewijst niet dat ónze container antwoordde: crasht hij op een bezette poort, dan
# neemt de bestaande host-service het antwoord over. Daarom na afloop opnieuw de status.
vereis_draaiend "${INFRA[@]}"

echo "[4/4] services starten en afwachten"
"${C[@]}" up -d "${SERVICES[@]}"
vereis_draaiend "${SERVICES[@]}"

wacht_op "berichtenmagazijn-a" berichtenmagazijn-a curl -sSf --max-time 3 http://127.0.0.1:8090/q/health/ready
wacht_op "berichtenmagazijn-b" berichtenmagazijn-b curl -sSf --max-time 3 http://127.0.0.1:8091/q/health/ready
wacht_op "magazijn-simulator"  magazijn-simulator  curl -sSf --max-time 3 http://127.0.0.1:8092/q/health/ready
wacht_op "uitvraag"            berichtenuitvraag   curl -sSf --max-time 3 http://127.0.0.1:8086/q/health/ready
wacht_op "console"             demo-console        curl -sSf --max-time 3 http://127.0.0.1:8095/

# Opnieuw, nu de console draait: die maakt de proxies zelf aan en overschrijft daarmee wat er uit
# het bestand geladen was. Wijken haar adressen af van deze modus, dan blijkt dat pas hier — de
# eerdere controle draaide voordat ze bestond.
wacht_op "toxiproxy (na de console)" demo-console python3 -c "$PROXY_CHECK" "$PROXY_BRON"

# Ook de infra opnieuw: die draagt tijdens het starten van de services de zwaarste last
# (migraties, vier tegelijk verbindende clients) en is sinds de vorige controle niet meer bekeken.
vereis_draaiend "${INFRA[@]}" "${SERVICES[@]}"

# Bewust GEEN probe op $DEMO_HOST: het bedieningspaneel bindt altijd op loopback (geen
# authenticatie, en POST /api/demo/legen doet een TRUNCATE op beide magazijn-databases). Een probe
# op een ander adres zou dus per definitie aflopen op een timeout en de start laten falen terwijl
# de stack gezond draait. DEMO_HOST stuurt alleen de CORS-allowlist en de URL's hieronder; om er
# van een andere machine bij te kunnen heb je een tunnel nodig, bijvoorbeeld
# `ssh -L 8095:127.0.0.1:8095 <host>`.
if [ "$DEMO_HOST" != "localhost" ]; then
    echo "Let op: het bedieningspaneel luistert alleen op 127.0.0.1. DEMO_HOST zet de CORS-allowlist;"
    echo "       tunnel poort 8095 (en 8086) als je er vanaf ${DEMO_HOST} bij wilt."
fi

echo
# Bewust de loopback-URL en niet ${DEMO_HOST}: het bedieningspaneel bindt alleen op 127.0.0.1, dus
# een kopieerbare regel met een ander adres wijst naar iets dat niet bestaat.
echo "Draait ($MODUS). Bedieningspaneel: http://127.0.0.1:8095/"
echo "                 Berichtenbox:    http://127.0.0.1:8095/berichtenbox.html"

if [ "$DEMO_HOST" != "localhost" ]; then
    echo "                 (vanaf ${DEMO_HOST}: tunnel eerst 8095 en 8086 naar deze machine)"
fi
