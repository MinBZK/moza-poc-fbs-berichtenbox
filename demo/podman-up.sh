#!/usr/bin/env bash
# shellcheck source-path=SCRIPTDIR
# Start de demo-stack onder podman en kiest zelf de werkbare netwerkmodus (bridge of hostnet; zie
# demo/lib/podman-modus.sh voor wat ze betekenen en hoe de keuze valt).
#
# In hostnet komt er een derde compose-bestand bovenop de basis en de podman-overlay. Dat bestand
# heeft geen healthchecks (zie de reden daar), dus start die modus uitsluitend via dit script: een
# kale `up -d` meldt daar succes zodra de containers gestart zijn, ook als Postgres nog
# initialiseert. In bridge doen de healthchecks uit compose.yaml dat werk wél.
#
# Gebruik:
#   demo/podman-up.sh [up]                 # modus automatisch bepalen en starten
#   MODUS=hostnet demo/podman-up.sh        # forceren
#   DEMO_HOST=10.0.0.5 demo/podman-up.sh
#   demo/podman-up.sh down [-v]            # afbreken, met de bestanden van de laatste start
#   demo/podman-up.sh ps                   # status
#   demo/podman-up.sh compose <args…>      # elk ander compose-commando, bv. `logs -f berichtenuitvraag`
#
# De modus van de laatste start staat in demo/generated/.modus; `down`, `ps` en `compose` lezen
# hem terug, zodat geen vervolgcommando met minder compose-bestanden de stack half omzet.
#
# DEMO_HOST is het adres waarop je de demo benadert (voor de CORS-allowlist van de uitvraag);
# default localhost. De poorten zelf blijven op loopback — zie DEMO_BIND in compose.yaml, en de
# toelichting bij de laatste controle onderaan dit script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=lib/podman-modus.sh
. "$ROOT/demo/lib/podman-modus.sh"

MODUS_BESTAND="$ROOT/demo/generated/.modus"
COMMANDO="${1:-up}"
if [ "$#" -gt 0 ]; then shift; fi

case "$COMMANDO" in
    up|down|ps|compose) ;;
    *)
        echo "Onbekend commando '$COMMANDO'; kies up, down, ps of compose <args…>." >&2
        exit 2
        ;;
esac

if [ "$COMMANDO" = "up" ] && [ "$#" -gt 0 ]; then
    echo "\`up\` neemt geen argumenten; gebruik \`compose up …\` voor een gerichte start." >&2
    exit 2
fi

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

DEMO_IMAGES=(
    localhost/fbs-demo/fbs-berichtenmagazijn:demo
    localhost/fbs-demo/fbs-berichtenuitvraag:demo
    localhost/fbs-demo/fbs-demo-console:demo
    localhost/fbs-demo/fbs-magazijn-simulator:demo
    localhost/fbs-demo/fbs-stelselregister:demo
)

# --- gereedschap, engine en modus -------------------------------------------------------------

if [ "$COMMANDO" = "up" ]; then
    modus_gereedschap podman curl python3 openssl
else
    modus_gereedschap podman
fi

modus_engine
modus_compose_kiezen

if [ "$COMMANDO" = "up" ]; then
    echo "[1/4] netwerkmodus bepalen"
    modus_voor_start "$MODUS_BESTAND" demo/podman-up.sh bridge hostnet
else
    modus_teruglezen "$MODUS_BESTAND" demo/podman-up.sh bridge hostnet
fi

# De proeftuin-versie bepaalt de set compose-bestanden net zo goed als de modus. Zonder vastleggen
# zou een vervolgcommando in een nieuwe shell de berichtenbox stil terugzetten op de gepinde versie.
# Een expliciet gezette variabele wint en wordt het nieuwe vastgelegde punt; `down` wist hem.
PROEFTUIN_IMAGE="${PROEFTUIN_IMAGE:-$(modus_instelling "$MODUS_BESTAND" PROEFTUIN_IMAGE)}"
INSTELLINGEN=()

if [ -n "$PROEFTUIN_IMAGE" ]; then
    export PROEFTUIN_IMAGE
    INSTELLINGEN+=("PROEFTUIN_IMAGE=$PROEFTUIN_IMAGE")
fi

if [ "$COMMANDO" != "up" ] && [ "$COMMANDO" != "down" ] && [ -s "$MODUS_BESTAND" ]; then
    modus_onthouden "$MODUS_BESTAND" "${INSTELLINGEN[@]+"${INSTELLINGEN[@]}"}"
fi

C=("${COMPOSE_BIN[@]}" -f compose.yaml -f compose.podman.yaml)

if [ "$MODUS" = "hostnet" ]; then
    C+=(-f compose.podman-hostnet.yaml)
fi

# Dezelfde variabele als de deploy leest, zodat lokaal en ZAD op één naam te sturen zijn. De
# overlay alleen meegeven wanneer hij gezet is: hij draagt `:?` en zou een gewone start anders
# afbreken met de vraag om een waarde.
if [ -n "$PROEFTUIN_IMAGE" ]; then
    C+=(-f compose.proeftuin-versie.yaml)

    if [ "$COMMANDO" = "up" ]; then
        echo "[1/4] berichtenbox: $PROEFTUIN_IMAGE (PROEFTUIN_IMAGE overschrijft de pin)"
    fi
fi

C+=(--profile demo)

case "$COMMANDO" in
    down)
        "${C[@]}" down "$@"
        rm -f "$MODUS_BESTAND"
        exit 0
        ;;
    ps)
        exec "${C[@]}" ps "$@"
        ;;
    compose)
        exec "${C[@]}" "$@"
        ;;
esac

modus_render_controleren "${C[@]}"

echo "[2/4] artefacten genereren"
"$ROOT/demo/podman-prepare.sh" "$MODUS"
# De sleutelketen van het stelselregister. Maakt alleen wat ontbreekt; een bestaande root blijft
# staan, zodat een app die hem heeft vastgelegd na een herstart van de stack blijft werken.
"$ROOT/demo/environment/stelselregister/pki/maak-keten.sh"

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
        #
        # stdin dicht: met opgevangen uitvoer sluit `compose exec` (v5) pas af als stdin sluit, en
        # dat is hier de terminal. Hij negeert bovendien SIGTERM; zonder `-k` hing het script dan
        # eindeloos in plaats van na de wachttijd te stoppen.
        if uitvoer="$("$TIMEOUT_BIN" -k 5 10 "$@" 2>&1 </dev/null)"; then
            break
        else
            status=$?
        fi

        if [ "$SECONDS" -ge "$eind" ]; then
            echo "TIMEOUT: $naam kwam niet omhoog (laatste exit $status)." >&2

            if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
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
SERVICES=(berichtenmagazijn-a berichtenmagazijn-b magazijn-simulator berichtenuitvraag demo-console
          demo-personas stelselregister proeftuin demo-proxy)

# Vóór de eerste container: ook een start die halverwege faalt, moet met `down` op te ruimen zijn.
mkdir -p "$(dirname "$MODUS_BESTAND")"
modus_onthouden "$MODUS_BESTAND" "${INSTELLINGEN[@]+"${INSTELLINGEN[@]}"}"

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
PROXY_BRON="$ROOT/toxiproxy/proxies.json"

if [ "$MODUS" = "hostnet" ]; then
    PROXY_BRON="$ROOT/demo/generated/proxies-host.json"
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
wacht_op "stelselregister"     stelselregister     curl -sSf --max-time 3 http://127.0.0.1:8094/q/health/ready
wacht_op "console"             demo-console        curl -sSf --max-time 3 http://127.0.0.1:8095/
# 8080, niet 8096: de proeftuin-container kan zijn luisterpoort niet verzetten (zie de
# toelichting in compose.podman-hostnet.yaml).
wacht_op "proeftuin"           proeftuin           curl -sSf --max-time 3 http://127.0.0.1:8080/moza/berichtenbox/
# Via de proxy, want dat is het adres waarop de demo gegeven wordt; een probe op de container zelf
# zou een kapotte route naar de console of de uitvraag niet opmerken.
wacht_op "demo-proxy"          demo-proxy          curl -sSf --max-time 3 http://127.0.0.1:8097/api/demo/personas

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
