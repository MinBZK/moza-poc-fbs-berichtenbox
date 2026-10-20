# shellcheck shell=bash
# Kiest de werkbare netwerkmodus voor een lokale testopstelling en onthoudt die tussen commando's.
# Wordt gesourcet door demo/podman-up.sh (de demo-stack) en demo/environment/lib/peer-stack.sh (de
# FSC-peer-harnessen); welke compose-bestanden bij een modus horen, beslist het script dat sourcet.
#
#   docker   — Docker-engine. Alleen de basis; de podman-overlays zijn daar niet nodig of zelfs
#              ongeldig (`userns_mode: keep-id` weigert Docker).
#   bridge   — normale podman (Linux rootless, podman machine op macOS/Windows). Adressering en
#              poorten gelijk aan Docker; de podman-overlay regelt keep-id, SELinux-labels en
#              image-namen.
#   hostnet  — vangnet voor omgevingen zonder bruikbaar bridge-netwerk, bijvoorbeeld podman-in-een-
#              container: `/proc/sys` is daar read-only (netavark kan geen bridge opzetten) en
#              aardvark-dns ontbreekt vaak, dus container-DNS resolvet niet. Ook zonder systemd
#              (geen lopende healthchecks) is dit de werkbare modus. Alle containers delen dan de
#              netns van de aanroeper en praten over 127.0.0.1.
#
# `MODUS=<modus>` forceert. Kan de gevraagde modus op deze machine niet werken, dan stopt het met de
# oorzaak in plaats van terug te vallen: een stille terugval levert een stack op die pas later, en
# zonder bruikbare melding, omvalt.
#
# Bash 3.2-compatibel (macOS): geen `mapfile`, geen associatieve arrays.

MODUS_PROBE_IMAGE="${MODUS_PROBE_IMAGE:-docker.io/library/alpine:3.20}"

# modus_gereedschap <commando…>: elk commando moet op PATH staan. Zet ook TIMEOUT_BIN: macOS levert
# geen `timeout`, met coreutils heet hij `gtimeout`. Zonder die check faalt elke probe pas na de
# volledige deadline op een 'command not found' die niemand verwacht.
modus_gereedschap() {
    local gereedschap

    for gereedschap in "$@"; do
        command -v "$gereedschap" >/dev/null 2>&1 || {
            echo "$gereedschap niet gevonden op PATH." >&2
            exit 1
        }
    done

    TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

    [ -n "$TIMEOUT_BIN" ] || {
        echo "timeout(1) ontbreekt (macOS: brew install coreutils)." >&2
        exit 1
    }
}

# modus_pause_pid_verweesd <bestand>: 0 als het bestand bestaat maar niet naar het pauzeproces van
# rootless podman wijst. Dat proces houdt de user namespace vast; elke podman-aanroep probeert zich
# erbij te voegen. Overleeft het bestand een herstart van de container, dan faalt dat met `cannot
# re-exec process to join the existing user namespace` — een melding die niet naar het bestand
# wijst. Verweesd is: geen geldige pid, een pid die niet bestaat, of een proces dat niet van deze
# gebruiker is of geen podman-proces (een hergebruikte pid). Ruim in twijfel níét op: een levende
# pause.pid weghalen zet een draaiende stack in een user namespace die podman niet meer terugvindt.
# Daarom telt elk eigen proces waarvan de naam met `podman` begint als levend, ook als het niet
# precies `podman pause` heet.
modus_pause_pid_verweesd() {
    local pid comm

    [ -f "$1" ] || return 1

    pid="$(head -n1 "$1" 2>/dev/null || true)"

    case "$pid" in
        '' | *[!0-9]*) return 0 ;;
    esac

    [ -d "/proc/$pid" ] || return 0

    [ "$(stat -c %u "/proc/$pid" 2>/dev/null || true)" = "$(id -u)" ] || return 0

    comm="$(cat "/proc/$pid/comm" 2>/dev/null || true)"

    case "$comm" in
        podman*) return 1 ;;
    esac

    return 0
}

# modus_runtime_dir: de runtime-map van rootless podman, met dezelfde keuze als podman zelf:
# XDG_RUNTIME_DIR, anders /run/user/<uid> als die schrijfbaar is, anders /tmp/podman-run-<uid>.
# Eén plek voor de pause.pid én de socket; kiezen ze elk hun eigen map, dan zoekt dit script de
# socket op een plek waar podman hem nooit neerzet.
modus_runtime_dir() {
    if [ -n "${XDG_RUNTIME_DIR:-}" ]; then
        printf '%s' "$XDG_RUNTIME_DIR"
    elif [ -w "/run/user/$(id -u)" ]; then
        printf '%s' "/run/user/$(id -u)"
    else
        printf '%s' "/tmp/podman-run-$(id -u)"
    fi
}

# modus_pause_pid_opruimen: verwijdert een verweesde pause.pid. Alleen op Linux: buiten Linux
# draait podman in een VM en ligt het bestand daar.
modus_pause_pid_opruimen() {
    local bestand
    bestand="$(modus_runtime_dir)/libpod/tmp/pause.pid"

    if modus_pause_pid_verweesd "$bestand"; then
        echo "Verweesde $bestand opgeruimd (pid $(head -n1 "$bestand" 2>/dev/null || true) is niet het pauzeproces van podman)."
        rm -f "$bestand"
    fi
}

# modus_socket_status <pad>: 0 als er een API antwoordt, 1 als er niemand luistert (geen socket, of
# de verbinding wordt geweigerd), 2 als dat niet vast te stellen is (timeout, onverwacht antwoord).
# Alleen bij 1 is de socket veilig op te ruimen: een bezette service die te traag antwoordt, leeft
# nog, en zonder zijn socket draait hij onbereikbaar door. Een socketbestand zonder service blijft
# na een herstart van de container staan; zonder deze controle faalt pas de eerste compose-aanroep,
# met een melding die de oorzaak overstemt. Zonder curl telt het bestaan van het bestand.
modus_socket_status() {
    local antwoord rc=0

    [ -S "$1" ] || return 1

    command -v curl >/dev/null 2>&1 || return 0

    antwoord="$(curl -sS --max-time 5 --unix-socket "$1" http://d/_ping 2>/dev/null)" || rc=$?

    if [ "$rc" -eq 0 ] && [ "$antwoord" = "OK" ]; then
        return 0
    fi

    # curl exit 7: verbinding geweigerd — er luistert niemand.
    [ "$rc" -eq 7 ] && return 1

    return 2
}

# modus_engine_is_podman <pad>: vraagt het de engine zelf. Het pad zegt niets: podman-docker
# publiceert podman op /run/docker.sock, en een symlink kan elke naam dragen. Zonder curl blijft
# alleen het pad over. De uitvoer eerst vangen en dan matchen: in een pijp naar `grep -q` kan de
# schrijver onder `pipefail` op SIGPIPE falen, en dan valt de uitkomst willekeurig om.
modus_engine_is_podman() {
    local versie

    if command -v curl >/dev/null 2>&1; then
        versie="$(curl -sS --max-time 5 --unix-socket "$1" http://d/version 2>/dev/null || true)"

        case "$versie" in
            *'"Podman Engine"'*) return 0 ;;
        esac

        return 1
    fi

    case "$1" in
        *podman*) return 0 ;;
    esac

    return 1
}

# modus_eigen_socket <pad>: 0 als dit de rootless podman-socket van deze gebruiker is. Alleen die mag
# dit script herstarten. Een ander pad met "podman" erin kan een rootful socket zijn (systemd
# socket-activation), en curl meldt een geweigerde toegang met dezelfde exit 7 als "niemand
# luistert": die opruimen zou een levende socket van een ander weghalen.
modus_eigen_socket() {
    [ "$1" = "$(modus_runtime_dir)/podman/podman.sock" ] || return 1

    # Een bestand dat er staat, moet van deze gebruiker zijn; anders is het niet ons om weg te halen.
    [ ! -e "$1" ] || [ -O "$1" ]
}

# modus_podman_service <pad>: start `podman system service` op een socket waarop niemand luistert, en
# wacht tot hij antwoordt. `--time=0` houdt de service draaien ná dit script, zodat een latere
# `… down` dezelfde socket vindt. `setsid` maakt hem los van de aanroepende shell, die hem anders
# bij afsluiten meeneemt. Het log blijft staan: zonder die uitvoer is een mislukte start (subuid,
# storage.conf, bezette socket) niet te diagnosticeren.
modus_podman_service() {
    local sock="$1" service_log

    echo "Podman-API antwoordt niet op $sock — service starten…"
    mkdir -p "$(dirname "$sock")"
    # Een socketbestand zonder luisteraar laat `system service` falen op `address already in use`.
    # Veilig, want de aanroeper heeft vastgesteld dat er niemand luistert (modus_socket_status 1) en
    # dat het de eigen socket is (modus_eigen_socket).
    rm -f "$sock"

    service_log="$(mktemp -t podman-service.XXXXXX)"

    if command -v setsid >/dev/null 2>&1; then
        setsid podman system service --time=0 "unix://$sock" >"$service_log" 2>&1 &
    else
        podman system service --time=0 "unix://$sock" >"$service_log" 2>&1 &
    fi

    for _ in $(seq 1 20); do
        modus_socket_status "$sock" && return 0
        sleep 0.5
    done

    echo "podman system service kwam niet omhoog ($sock):" >&2
    tail -20 "$service_log" >&2
    exit 1
}

# modus_engine: bepaalt op welke engine compose landt en zet DOCKER_HOST en PODMAN_RUNTIME.
#
# Zonder DOCKER_HOST gaat podman voor: staat het op PATH, dan wordt zijn API-socket gezocht en zo
# nodig gestart. Pas zonder podman valt de keuze op de Docker-engine — en alleen als het script dat
# sourcet die toestaat (MODUS_DOCKER_TOEGESTAAN=1). Wie beide heeft en Docker wil, wijst er met
# DOCKER_HOST naar (`unix:///var/run/docker.sock`).
#
# Buiten Linux draait podman in een VM (podman machine): de socket hoort dan bij die VM, en
# `podman system service` bestaat op de client daar niet.
#
# Bijwerkingen, en daarom alleen aanroepen vóór een commando dat de engine echt nodig heeft: een
# verweesde pause.pid wordt opgeruimd en een eigen podman-socket zonder luisteraar wordt gestart.
modus_engine() {
    local sock status=0

    if [ -z "${DOCKER_HOST:-}" ] && ! command -v podman >/dev/null 2>&1; then
        if [ "${MODUS_DOCKER_TOEGESTAAN:-0}" -eq 1 ] && command -v docker >/dev/null 2>&1; then
            PODMAN_RUNTIME=0
            return 0
        fi

        echo "podman niet gevonden op PATH." >&2
        exit 1
    fi

    if [ "$(uname -s)" = "Linux" ] && command -v podman >/dev/null 2>&1; then
        modus_pause_pid_opruimen
    fi

    if [ -z "${DOCKER_HOST:-}" ]; then
        if [ "$(uname -s)" != "Linux" ]; then
            sock="$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null | head -n1 || true)"

            if [ -z "$sock" ] || [ ! -S "$sock" ]; then
                echo "Geen socket van podman machine gevonden${sock:+ ($sock)}; start hem met \`podman machine start\`." >&2
                exit 1
            fi
        else
            sock="$(modus_runtime_dir)/podman/podman.sock"
        fi

        export DOCKER_HOST="unix://$sock"
    fi

    case "$DOCKER_HOST" in
        unix://*)
            sock="${DOCKER_HOST#unix://}"
            modus_socket_status "$sock" || status=$?

            # Een dode engine is niet te vragen wat hij is; herstarten doet dit alleen voor de eigen
            # rootless podman-socket op Linux, ook als DOCKER_HOST er expliciet naar wijst.
            if [ "$status" -eq 1 ] && [ "$(uname -s)" = "Linux" ] && command -v podman >/dev/null 2>&1 \
                && modus_eigen_socket "$sock"; then
                modus_podman_service "$sock"
                status=0
            fi

            case "$status" in
                1)
                    echo "Geen engine die antwoordt op DOCKER_HOST=$DOCKER_HOST; start die eerst." >&2
                    exit 1
                    ;;
                2)
                    echo "De engine op DOCKER_HOST=$DOCKER_HOST antwoordt niet binnen 5 seconden; is hij bezet?" >&2
                    echo "  De socket blijft staan. Probeer het zo opnieuw." >&2
                    exit 1
                    ;;
            esac

            if modus_engine_is_podman "$sock"; then
                PODMAN_RUNTIME=1
            else
                PODMAN_RUNTIME=0
            fi
            ;;
        *)
            # tcp:// en ssh:// worden niet bevraagd; dan blijft alleen het adres over.
            case "$DOCKER_HOST" in
                *podman*) PODMAN_RUNTIME=1 ;;
                *) PODMAN_RUNTIME=0 ;;
            esac
            ;;
    esac

    if [ "$PODMAN_RUNTIME" -eq 0 ] && [ "${MODUS_DOCKER_TOEGESTAAN:-0}" -ne 1 ]; then
        echo "Let op: DOCKER_HOST=$DOCKER_HOST wijst niet naar een podman-engine."
        echo "  De image- en netwerkcontroles die podman rechtstreeks bevragen worden overgeslagen."
    fi
}

# modus_compose_is_v2 <compose-commando…>: 0 als dit docker compose v2 is. Gevraagd aan `version`,
# niet afgeleid uit `--help`: podman-compose kent ook een `--parallel`. v1 meldt zich als
# `docker-compose version 1…`, podman-compose als `podman-compose version …`. De uitkomst wordt per
# proces onthouden: een federatie roept dit per peer en per poging aan.
modus_compose_is_v2() {
    local versie

    if [ -z "${MODUS_COMPOSE_V2:-}" ]; then
        versie="$("$@" version 2>/dev/null || true)"

        case "$versie" in
            *"Docker Compose version"*) MODUS_COMPOSE_V2=1 ;;
            *) MODUS_COMPOSE_V2=0 ;;
        esac
    fi

    [ "$MODUS_COMPOSE_V2" -eq 1 ]
}

# modus_compose_up <compose-commando met alle -f's…>: `up -d`, op podman met de containers één voor
# één aangemaakt.
#
# De podman-overlays zetten de services op `userns_mode: keep-id`. Kan de storage-driver een laag
# niet ID-mapped mounten (vfs altijd; overlay alleen met kernelsteun), dan maakt podman bij de
# eerste container per laag een ID-mapped kopie, die met de laatste container weer verdwijnt. Maakt
# compose containers met een gedeelde basislaag tegelijk aan, dan doen ze dat allebei en faalt de
# verliezer op `container ID 0 cannot be mapped to a host ID`; een volgende `up` naast de
# achtergebleven `Created`-container geeft `write /proc/<pid>/uid_map: Operation not permitted`.
# Of de driver kopieert, is vooraf niet betrouwbaar vast te stellen, dus dit geldt voor elke podman.
#
# Serieel door per service `create` aan te roepen, in de volgorde van `config --services` (die van de
# afhankelijkheden): elke aanroep vindt zijn afhankelijkheden dan al en maakt precies één container.
# Zonder `--no-recreate`: een container met gewijzigde configuratie moet hier opnieuw worden
# aangemaakt, anders doet `up -d` dat daarna voor alle gewijzigde tegelijk. `up -d` start daarna
# alleen nog. COMPOSE_PARALLEL_LIMIT=1 en `--parallel 1` beperken het aanmaken bij `up` in docker
# compose v2.32 níét — gemeten, de containers stonden toch tegelijk op `Creating`. Het ophalen raakt
# de mapping niet en blijft parallel; `--policy missing` houdt een start met aanwezige images
# offline. Docker kent de race niet.
#
# Vraagt docker compose v2 (`pull --policy`, `create` per service). Een andere compose valt terug op
# een gewone `up -d`, en dit zegt waarom.
modus_compose_up() {
    local services service

    if [ "${PODMAN_RUNTIME:-0}" -ne 1 ]; then
        "$@" up -d
        return
    fi

    if ! modus_compose_is_v2 "$@"; then
        echo "Let op: dit is geen docker compose v2; de containers worden tegelijk aangemaakt en" >&2
        echo "  kunnen botsen op 'container ID 0 cannot be mapped'." >&2
        "$@" up -d
        return
    fi

    "$@" pull --policy missing --quiet || return
    services="$("$@" config --services)" || return

    for service in $services; do
        "$@" create "$service" || return
    done

    "$@" up -d
}

# modus_compose_kiezen: zet COMPOSE_BIN. `docker compose` (v2) eerst: hostnet gebruikt `!reset`,
# dat pas v2.24.4 kent. Een los `docker-compose` op PATH kan nog de oude v1 zijn.
# shellcheck disable=SC2034  # COMPOSE_BIN is de uitvoer; het script dat sourcet leest hem.
modus_compose_kiezen() {
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
}

# modus_probe_image: haalt het probe-image op als het er nog niet is. Een registry-fout (rate limit,
# air-gapped, proxy) zegt niets over bridge of healthchecks, maar zou anders als 'werkt niet' tellen
# en de gebruiker naar de verkeerde modus sturen. Daarom stopt dit met die oorzaak.
modus_probe_image() {
    local log

    podman image exists "$MODUS_PROBE_IMAGE" && return 0

    log="$(mktemp -t probe-image.XXXXXX)"

    if ! podman pull -q "$MODUS_PROBE_IMAGE" >"$log" 2>&1; then
        echo "  probe-image $MODUS_PROBE_IMAGE niet beschikbaar: $(tail -1 "$log")" >&2
        echo "  Modus niet vast te stellen; kies expliciet MODUS=bridge of MODUS=hostnet." >&2
        rm -f "$log"
        exit 1
    fi

    rm -f "$log"
}

# modus_kan_bridge: 0 als podman hier een bridge-netwerk mét werkende naamresolutie kan opzetten,
# en healthchecks vanzelf uitvoert (zie modus_healthchecks_lopen).
modus_kan_bridge() {
    local net="fbs-netprobe-$$-${RANDOM}" log ok=1

    modus_probe_image
    log="$(mktemp -t netprobe.XXXXXX)"

    probe_opruimen() {
        podman rm -f -t 0 "${net}-doel" >/dev/null 2>&1 || true
        podman network rm "$net" >/dev/null 2>&1 || true
        rm -f "$log"
    }

    # Probe-netwerken en -containers stapelen zich anders op in de podman-state. De signaalvariant
    # sluit zelf af: zonder `exit` slikt de handler het signaal en loopt het script gewoon door.
    trap 'probe_opruimen; trap - RETURN INT TERM; exit 130' INT TERM
    trap 'probe_opruimen; trap - RETURN INT TERM' RETURN

    if ! podman network create "$net" >"$log" 2>&1; then
        echo "  bridge niet bruikbaar: $(tail -1 "$log")"
        return 1
    fi

    # Bridge én naamresolutie moeten werken: zonder aardvark-dns start een container wel, maar
    # resolvet geen enkele service-naam en faalt de hele stack pas ná het opstarten.
    if podman run -d --rm --name "${net}-doel" --network "$net" \
           --health-cmd true --health-interval 1s \
           "$MODUS_PROBE_IMAGE" sleep 60 >"$log" 2>&1; then

        # Een paar pogingen: aardvark-dns publiceert het record niet altijd meteen, en één
        # vals-negatief zou de stack naar de riskantere modus duwen.
        for _ in 1 2 3; do
            podman run --rm --network "$net" "$MODUS_PROBE_IMAGE" \
                getent hosts "${net}-doel" >/dev/null 2>&1 && { ok=0; break; }

            sleep 2
        done

        if [ "$ok" -ne 0 ]; then
            echo "  bridge staat, maar containernamen resolven niet (aardvark-dns?)"
        elif ! modus_healthchecks_lopen "${net}-doel"; then
            ok=1
        fi
    else
        echo "  bridge staat, maar een container starten erop lukt niet: $(tail -1 "$log")"
    fi

    return $ok
}

# modus_healthchecks_lopen [container]: 0 als podman healthchecks vanzelf uitvoert. Met een
# container (gestart met `--health-cmd`) wordt die bekeken; zonder start dit een eigen probe.
#
# Podman plant ze via systemd-timers. Zonder systemd (podman in een container) draait er nooit één:
# elke container blijft op `starting`, en een `up -d` met `depends_on: service_healthy` wacht dan
# zonder einde. De bridge kan daar wél werken, dus de netwerkprobe alleen ziet dit niet. De
# hostnet-overlays halen de healthchecks juist weg, en zijn daar dan de werkbare modus.
modus_healthchecks_lopen() {
    local naam="${1:-}" eigen=0 status=""

    if [ -z "$naam" ]; then
        naam="fbs-hcprobe-$$-${RANDOM}"
        eigen=1
        modus_probe_image

        if ! podman run -d --rm --name "$naam" --health-cmd true --health-interval 1s \
               "$MODUS_PROBE_IMAGE" sleep 30 >/dev/null 2>&1; then
            echo "  healthcheck-probe start niet; healthchecks niet vast te stellen"
            return 1
        fi
    fi

    for _ in 1 2 3 4 5 6 7 8 9 10; do
        status="$(podman inspect "$naam" --format '{{.State.Health.Status}}' 2>/dev/null || true)"
        [ "$status" = "healthy" ] && break
        sleep 1
    done

    if [ "$eigen" -eq 1 ]; then
        podman rm -f -t 0 "$naam" >/dev/null 2>&1 || true
    fi

    if [ "$status" != "healthy" ]; then
        echo "  podman voert healthchecks niet vanzelf uit (status '${status:-onbekend}' na 10s; geen systemd?)"
        return 1
    fi
}

# modus_geldig <modus> <toegestane modus…>: stopt als de modus niet in de lijst staat. Zonder deze
# controle valt een tikfout (`hostnett`) door elke `case` heen en krijgt compose alleen de basis.
modus_geldig() {
    local modus="$1"
    shift

    case " $* " in
        *" $modus "*) ;;
        *)
            echo "FOUT: MODUS='$modus' bestaat hier niet; kies uit:$(printf ' %s' "$@")." >&2
            exit 2
            ;;
    esac
}

# modus_bepalen <toegestane modus…>: zet MODUS — opgegeven en gecontroleerd, of gedetecteerd.
# Vraagt modus_engine vooraf. Print alleen ingesprongen regels; de kop is aan de aanroeper.
modus_bepalen() {
    local toegestaan=" $* "

    if [ -n "${MODUS:-}" ]; then
        modus_geldig "$MODUS" "$@"
        modus_past_op_engine

        # Een bridge zonder lopende healthchecks start wel, maar `up` wacht daarna eindeloos op de
        # eerste `service_healthy`. Dat hier melden en niet pas als niets meer beweegt.
        if [ "$MODUS" = "bridge" ] && [ "$PODMAN_RUNTIME" -eq 1 ] && [ "$(uname -s)" = "Linux" ] \
               && ! modus_healthchecks_lopen; then
            echo "FOUT: MODUS=bridge, maar zonder lopende healthchecks blijft de start hangen op de" >&2
            echo "      eerste afhankelijkheid. Laat MODUS weg, of kies MODUS=hostnet." >&2
            exit 2
        fi

        echo "  → $MODUS (opgegeven)"
        return 0
    fi

    # Buiten Linux draait podman in een VM; een gedeelde netns is dan die van de VM en niet die
    # van de host, zodat er zonder gepubliceerde poorten niets doorkomt. Hostnet is daar dus geen
    # bruikbaar vangnet en wordt nooit automatisch gekozen. Draait de stack op een andere engine
    # dan podman, dan geldt hetzelfde: hostnet bestaat alleen voor kapotte podman-netwerken, en de
    # probe zou een engine beoordelen die de stack niet draait.
    if [ "$PODMAN_RUNTIME" -eq 0 ]; then
        case "$toegestaan" in
            *" docker "*) MODUS=docker ;;
            *) MODUS=bridge ;;
        esac
    elif [ "$(uname -s)" != "Linux" ]; then
        MODUS=bridge
    elif modus_kan_bridge; then
        MODUS=bridge
    else
        MODUS=hostnet
    fi

    echo "  → $MODUS"
}

# modus_past_op_engine: weigert een opgegeven modus die op deze engine of dit OS niet kan werken.
modus_past_op_engine() {
    case "$MODUS" in
        docker)
            if [ "$PODMAN_RUNTIME" -eq 1 ]; then
                echo "FOUT: MODUS=docker, maar compose landt op podman ($DOCKER_HOST)." >&2
                echo "      Zonder de podman-overlay faalt elke container die de privékeys leest; kies bridge of hostnet." >&2
                exit 2
            fi
            ;;
        bridge|hostnet)
            if [ "$PODMAN_RUNTIME" -eq 0 ] && [ "${MODUS_DOCKER_TOEGESTAAN:-0}" -eq 1 ]; then
                echo "FOUT: MODUS=$MODUS is een podman-modus, maar compose landt op Docker." >&2
                echo "      Kies MODUS=docker, of laat MODUS weg." >&2
                exit 2
            fi
            ;;
    esac

    if [ "$MODUS" = "hostnet" ] && [ "$(uname -s)" != "Linux" ]; then
        echo "FOUT: MODUS=hostnet werkt alleen op Linux; elders draait podman in een VM en deelt de" >&2
        echo "      stack de netns van die VM, niet die van deze machine." >&2
        exit 2
    fi
}

# modus_render_controleren <compose-commando met alle -f's…>: rendert de merge en eist dat hij in
# deze modus kan draaien.
#
# Toets de uitkomst, niet het versienummer en niet of het parset: `version --short` geeft bij een
# externe compose-provider een versie die niets over de ondersteunde spec zegt, en een onbekende
# YAML-tag wordt door sommige implementaties stilzwijgend genegeerd in plaats van geweigerd. Dus
# renderen en kijken of `!reset` écht is toegepast — blijft er een gepubliceerde poort staan naast
# `network_mode: host`, dan mislukt de start pas verderop en zonder bruikbare melding.
modus_render_controleren() {
    local render

    if ! render="$("$@" config 2>&1)"; then
        echo "Deze compose kan de gestapelde bestanden niet verwerken:" >&2
        printf '%s\n' "$render" >&2

        # Implementaties die `!reset` niet kennen struikelen al over de tag zelf en komen dus hier
        # terecht, niet in de assertie hieronder.
        if [ "$MODUS" = "hostnet" ]; then
            echo "hostnet vraagt compose v2.24.4 of nieuwer voor \`!reset\`; podman-compose kan dit niet." >&2
        fi

        exit 1
    fi

    if [ "$MODUS" = "hostnet" ] && printf '%s\n' "$render" | grep -q 'published:'; then
        echo "Er blijft een gepubliceerde poort staan naast \`network_mode: host\`." >&2
        echo "Waarschijnlijk heeft een service in de basis \`ports:\` gekregen zonder dat de" >&2
        echo "hostnet-overlay die met \`ports: !reset []\` opruimt." >&2
        exit 1
    fi
}

# modus_onthouden <bestand> [NAAM=waarde…]: legt MODUS vast, plus de instellingen die de set
# compose-bestanden mede bepalen. Een `down` of `up --force-recreate` met minder bestanden rendert de
# services opnieuw uit de basis en zet de stack stilletjes in een andere modus of versie.
modus_onthouden() {
    local bestand="$1"
    shift

    {
        printf '%s\n' "$MODUS"

        if [ "$#" -gt 0 ]; then
            printf '%s\n' "$@"
        fi
    } >"$bestand"
}

# modus_instelling <bestand> <NAAM>: de vastgelegde waarde van NAAM, of leeg.
modus_instelling() {
    [ -r "$1" ] || return 0
    sed -n "2,\$ s/^$2=//p" "$1" | head -n1
}

# modus_teruglezen <bestand> <start-commando> <toegestane modus…>: zet MODUS uit het bestand van de
# laatste start.
#
# Een opgegeven MODUS die afwijkt, wordt geweigerd: de stack draait in de vastgelegde modus, en een
# commando met de bestanden van een andere zou hem half omzetten. Zonder bestand — gestart buiten
# dit script, of al afgebroken — is alleen een opgegeven MODUS bruikbaar. Beide waarden gaan langs
# dezelfde controle als bij de start.
modus_teruglezen() {
    local bestand="$1" start="$2" vastgelegd=""
    shift 2

    if [ -r "$bestand" ]; then
        vastgelegd="$(head -n1 "$bestand")"
    fi

    if [ -z "$vastgelegd" ]; then
        if [ -z "${MODUS:-}" ]; then
            echo "FOUT: niet vast te stellen in welke modus deze stack draait ($bestand ontbreekt)." >&2
            echo "      Start hem met \`$start\`, of geef de modus op waarin hij draait met MODUS=<modus>." >&2
            exit 2
        fi

        modus_geldig "$MODUS" "$@"
        modus_past_op_engine
        return 0
    fi

    if [ -n "${MODUS:-}" ] && [ "$MODUS" != "$vastgelegd" ]; then
        echo "FOUT: MODUS=$MODUS, maar deze stack is gestart in $vastgelegd ($bestand)." >&2
        echo "      Laat MODUS weg, of breek de stack eerst af met \`$start down\`." >&2
        echo "      Draait hij niet meer, verwijder dan $bestand." >&2
        exit 2
    fi

    modus_geldig "$vastgelegd" "$@"
    MODUS="$vastgelegd"
    modus_past_op_engine
}

# modus_voor_start <bestand> <start-commando> <toegestane modus…>: de modus voor `up`. Is er al een
# vastgelegde modus, dan geldt die (en weigert een afwijkende MODUS), zodat een tweede `up` naast een
# draaiende stack hem niet half omzet. Anders wordt hij bepaald.
modus_voor_start() {
    local bestand="$1" start="$2"
    shift 2

    if [ -s "$bestand" ]; then
        modus_teruglezen "$bestand" "$start" "$@"
        echo "  → $MODUS (vastgelegd bij de vorige start, $bestand)"
    else
        modus_bepalen "$@"
    fi
}
