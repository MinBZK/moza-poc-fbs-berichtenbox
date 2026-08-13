#!/usr/bin/env bash
# Bouwt de lokale federatie op, breekt 'm af, of toont de staat.
#
#   ./federatie.sh up                  # gastheer, dan gasten; wacht tot alle peers aangemeld zijn
#   ./federatie.sh down                # afbreken INCLUSIEF volumes — de schone lei
#   ./federatie.sh stop                # containers stoppen, volumes behouden
#   ./federatie.sh start               # gestopte containers weer starten
#   ./federatie.sh restart <service>   # één service (bv. `router` na een haproxy-edit)
#   ./federatie.sh status              # containers + alle listeners in de netns
#
# `down` wist bewust de volumes: de directory-DB houdt peers vast die aan een group-CA hangen, dus
# na `deel-groep-ca.sh` moet die state weg. Voor het gewone itereren op een compose- of
# haproxy-wijziging is dat verspilling — gebruik dan `restart <service>`, dat scheelt initdb,
# zes migraties en de hele announce-dans.
#
# Voorwaarden (README.md beschrijft ze):
#   1. alle peers dragen dezelfde group-CA        -> ./deel-groep-ca.sh
#   2. de bron-peer heeft zijn eigen certs        -> <peer>/pki/issue.sh
#   3. elke peer heeft zijn deploy/local/.env
#   4. net.ipv4.ip_unprivileged_port_start = 0    -> anders faalt de router op `bind :443`
#
# Linux + podman: gebruikt `ss` (iproute2) en `podman`. Niet draaibaar op macOS.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=peers.env
. "${HERE}/peers.env"

fsc_errlog_init

ANNOUNCE_TIMEOUT="${ANNOUNCE_TIMEOUT:-120}"
ANNOUNCE_INTERVAL="${ANNOUNCE_INTERVAL:-5}"
UP_POGINGEN="${UP_POGINGEN:-3}"
# Ruimte voor een container om na `up -d` alsnog om te vallen; zonder deze pauze staat een
# crash-lus nog op `Up` en ziet dode_containers hem niet.
SETTLE_SECONDEN="${SETTLE_SECONDEN:-5}"

# verwacht_peers: het aantal rijen in peers.peers — elke peer plus de directory zelf.
verwacht_peers() {
  local n=1   # de directory
  for _ in $(fsc_alle_peers); do n=$((n + 1)); done
  echo "$n"
}

# compose_project <peer>: de projectnaam van die peer. Faalt hard bij een ontbrekende `name:` —
# een lege projectnaam maakt elk container-filter betekenisloos en dus elke controle die daarop
# leunt stil.
compose_project() { fsc_compose_project "${ENVDIR}/$1/deploy/local/docker-compose.yaml"; }

# dc <peer> <compose-commando...>: docker compose voor die peer, met de vier -f-bestanden.
dc() {
  local peer="$1" lokaal="${ENVDIR}/$1/deploy/local"; shift
  local f args=()

  for f in "${lokaal}/docker-compose.yaml" \
           "${lokaal}/docker-compose.podman.yaml" \
           "${lokaal}/docker-compose.podman-hostnet.yaml" \
           "${HERE}/compose/${peer}.yaml"; do
    [ -r "$f" ] || { echo "FAIL: compose-bestand ontbreekt of onleesbaar: $f" >&2; return 1; }
    args+=(-f "$f")
  done

  docker compose "${args[@]}" "$@"
}

# psql_directory <sql>: query de directory-DB in de gastheer-postgres. Onderscheidt een mislukte
# query van een lege uitkomst — anders leest "container bestaat niet" als "nog geen peers".
psql_directory() {
  local uit rc=0
  uit=$(podman exec "$(compose_project "$GASTHEER")-postgres-1" \
          psql -U postgres -d fsc_directory -tA -c "$1" 2>"$ERRLOG") || rc=$?

  if [ "$rc" -ne 0 ]; then
    fsc_warn_errlog "directory-DB niet bevraagbaar"
    return 1
  fi

  printf '%s' "$uit"
}

# --- up ------------------------------------------------------------------------------------------

# dode_containers <project>: namen+status van containers die niet gezond draaien. Leeg = alles goed.
#
# Filtert op de STATUSKOLOM en niet op de containernaam: `Exited (0)` is een migrate-job die zijn
# werk deed, `Exited (niet-0)` is een fout, en `Created` is een container die nooit gestart is —
# precies wat de ID-map-race achterlaat. Op de naam filteren (`-migrate`) zou een migrate-job die
# met exit 1 stierf onzichtbaar maken.
#
# `Restarting` staat er bewust NIET bij: podman kent die status niet (dat is docker-formulering),
# het levert `Up` of `Exited`. Een container die pas ná de settle omvalt ontsnapt daarmee aan deze
# meting; de announce-poll erna vangt dat alsnog.
dode_containers() {
  local project="$1" uit
  uit="$(podman ps -a --filter "label=com.docker.compose.project=${project}" \
           --format '{{.Names}}\t{{.Status}}' 2>"$ERRLOG")" || return 1

  # Return 2 bij nul rijen: het projectfilter selecteert dan niets — een verkeerde projectnaam, geen
  # gezonde stack. Een aparte code omdat dat een andere fout is dan "container gestopt", en de
  # aanroeper er een andere melding bij hoort te geven.
  if [ -z "$uit" ]; then
    return 2
  fi

  printf '%s\n' "$uit" | grep -E 'Exited \([^0]|Created' || true
}

# up_met_retry <peer>: `up -d` met een begrensd aantal pogingen.
#
# Podman kan bij een verse `up` twee transiënte fouten geven die niets met de configuratie te maken
# hebben, en beide laten containers op `Created` achter waardoor een simpele herhaling óók faalt:
#   - "container ID 0 cannot be mapped to a host ID" — twee containers maken tegelijk een ID-mapped
#     kopie van dezelfde image-laag; wie verliest, faalt. Na één geslaagde kopie is de laag gecached.
#   - de podman-API-service bezwijkt onder de gelijktijdige creates. Die moet je zelf herstarten;
#     dit script meldt dat en stopt, want retryen tegen een dode API is zinloos.
up_met_retry() {
  local peer="$1" project poging=1 log rc dood rc_status
  project="$(compose_project "$peer")"
  log=$(mktemp)

  while [ "$poging" -le "$UP_POGINGEN" ]; do
    rc=0; dc "$peer" up -d >"$log" 2>&1 || rc=$?

    if [ "$rc" -eq 0 ]; then
      # `up -d` exit 0 zodra de containers gestárt zijn, niet zodra ze gezond zijn. De overlay geeft
      # elke service `restart: on-failure:600`, dus een container die meteen omvalt wordt herstart.
      # Vandaar de settle: direct na `up` staat een crash-lussende container nog op `Up`, en pas na
      # een paar seconden op `Exited (1)`.
      sleep "$SETTLE_SECONDEN"

      rc_status=0; dood="$(dode_containers "$project")" || rc_status=$?

      if [ "$rc_status" -eq 1 ]; then
        echo "FAIL: containerstatus van '${peer}' niet opvraagbaar: $(fsc_last_error)" >&2
        rm -f "$log"; return 1
      fi

      if [ "$rc_status" -eq 2 ]; then
        echo "FAIL: geen enkele container met label com.docker.compose.project=${project} — klopt de projectnaam?" >&2
        rm -f "$log"; return 1
      fi

      if [ -n "$dood" ]; then
        echo "FAIL: '${peer}' kwam omhoog maar containers zijn gestopt of blijven herstarten:" >&2
        printf '%s\n' "$dood" >&2
        rm -f "$log"; return 1
      fi

      rm -f "$log"; return 0
    fi

    if fsc_podman_api_dood "$log"; then
      echo "FAIL: de podman-API-service is niet bereikbaar. Herstart 'm en probeer opnieuw:" >&2
      echo "  podman system service --time=0 unix://\${XDG_RUNTIME_DIR:-/tmp/podman-run-\$(id -u)}/podman/podman.sock &" >&2
      tail -n 5 "$log" >&2; rm -f "$log"; return 1
    fi

    if [ "$poging" -lt "$UP_POGINGEN" ]; then
      echo "  poging ${poging}/${UP_POGINGEN} faalde (transiënt?), restanten opruimen en opnieuw..." >&2
      tail -n 2 "$log" >&2
      opruimen "$project" "$log"
      sleep 2
    fi

    poging=$((poging + 1))
  done

  echo "FAIL: '${peer}' kwam niet omhoog in ${UP_POGINGEN} pogingen:" >&2
  tail -n 10 "$log" >&2; rm -f "$log"; return 1
}

# opruimen <project> <log>: verwijder containers die het niet gehaald hebben. Faalt dit stil, dan
# falen de volgende pogingen gegarandeerd identiek op "already exists" — dus melden.
opruimen() {
  local project="$1" log="$2" achterblijvers
  # Geen `|| true`: een falende `podman ps` zou anders als "niets op te ruimen" lezen, waarna de
  # volgende poging gegarandeerd identiek faalt op "already exists" — precies wat dit voorkomt.
  if ! achterblijvers="$(podman ps -aq --filter "label=com.docker.compose.project=${project}" \
                           --filter status=created --filter status=exited 2>"$ERRLOG")"; then
    echo "  WARN: achterblijvers niet opvraagbaar: $(fsc_last_error)" >&2
    return 0
  fi
  [ -n "$achterblijvers" ] || return 0

  # shellcheck disable=SC2086  # bewuste woordsplitsing: een lijst container-ID's.
  if ! podman rm -f $achterblijvers >"${log}.rm" 2>&1; then
    echo "  WARN: opruimen mislukte; de volgende poging faalt waarschijnlijk identiek:" >&2
    tail -n 3 "${log}.rm" >&2
  fi
  rm -f "${log}.rm"
}

# wacht_op_peers <aantal> <omschrijving>: pollt peers.peers tot er >= aantal op :443 staan.
# Echoot het bereikte aantal; return 1 bij timeout. Expliciete vlag i.p.v. afleiden uit `elapsed`,
# zodat een herordening van de lus een timeout niet stil in een succes verandert.
wacht_op_peers() {
  local doel="$1" wat="$2" elapsed=0 n gehaald=0 db_fouten=0
  while [ "$elapsed" -lt "$ANNOUNCE_TIMEOUT" ]; do
    if n="$(psql_directory "SELECT count(*) FROM peers.peers WHERE manager_address LIKE '%:443'")"; then
      db_fouten=0
    else
      # Blijft de DB onbereikbaar, dan is "0 peers aangemeld" de verkeerde diagnose: dan is de
      # gastheer-stack stuk, niet de announce-keten. Na drie pogingen zeggen we dat ook.
      n=0; db_fouten=$((db_fouten + 1))
      if [ "$db_fouten" -ge 3 ]; then
        echo "FAIL: de directory-DB is ${db_fouten} pogingen achtereen onbereikbaar — draait de gastheer-stack?" >&2
        printf '0'; return 1
      fi
    fi
    [ -n "$n" ] || n=0

    if [ "$n" -ge "$doel" ]; then gehaald=1; break; fi

    sleep "$ANNOUNCE_INTERVAL"; elapsed=$((elapsed + ANNOUNCE_INTERVAL))
    # Naar stderr: stdout draagt het resultaat en wordt door de command substitution gevangen.
    # Stond dit op stdout, dan zag de operator 120s lang niets en kwam de voortgang ín de
    # foutmelding terecht.
    echo "  ...${n}/${doel} ${wat} (${elapsed}s)" >&2
  done

  printf '%s' "${n:-0}"
  [ "$gehaald" -eq 1 ]
}

# diagnose_announce: wat je wilt zien als peers zich niet aanmelden. De meest voorkomende oorzaak
# is een router die niet op :443 kon binden (ip_unprivileged_port_start), niet de directory zelf.
diagnose_announce() {
  local project; project="$(compose_project "$GASTHEER")"
  echo "  --- router-logs (bind :443 faalt bij ip_unprivileged_port_start != 0) ---" >&2
  podman logs --tail=30 "${project}-router-1" >&2 2>/dev/null || echo "  (router-logs onbereikbaar)" >&2
  echo "  --- peers.peers ---" >&2
  podman exec "${project}-postgres-1" psql -U postgres -d fsc_directory \
    -c "SELECT id, name, manager_address FROM peers.peers ORDER BY id;" >&2 2>/dev/null \
    || echo "  (directory-DB onbereikbaar — draait de gastheer-stack?)" >&2
}

case "${1:-}" in
  up)
    echo "federatie: gastheer-stack (${GASTHEER}) — postgres, router, directory + peer..."
    up_met_retry "$GASTHEER"

    echo "federatie: wachten tot de directory zichzelf heeft geregistreerd..."
    if ! wacht_op_peers 1 "aangemeld" >/dev/null; then
      echo "FAIL: de directory kwam niet omhoog binnen ${ANNOUNCE_TIMEOUT}s." >&2
      diagnose_announce
      exit 1
    fi

    for gast in $GASTEN; do
      echo "federatie: gast-stack (${gast}) in dezelfde netns..."
      up_met_retry "$gast"
    done

    DOEL="$(verwacht_peers)"
    echo "federatie: wachten tot alle ${DOEL} peers aangemeld zijn (op :443)..."
    if ! N="$(wacht_op_peers "$DOEL" "aangemeld")"; then
      echo "FAIL: ${N}/${DOEL} peers aangemeld binnen ${ANNOUNCE_TIMEOUT}s." >&2
      diagnose_announce
      exit 1
    fi

    podman exec "$(compose_project "$GASTHEER")-postgres-1" \
      psql -U postgres -d fsc_directory \
      -c "SELECT id, name, manager_address FROM peers.peers ORDER BY id;" || true
    echo "FEDERATIE OP."
    ;;

  down)
    # Fouten tellen in plaats van slikken: een mislukte teardown laat het postgres-volume staan,
    # en de volgende `up` faalt dan op iets dat niets met afbreken te maken lijkt te hebben.
    RC=0
    for gast in $GASTEN; do
      echo "federatie: gast-stack (${gast}) afbreken..."
      dc "$gast" down -v || { echo "FAIL: afbreken van '${gast}' mislukt — volumes kunnen blijven staan." >&2; RC=1; }
    done

    echo "federatie: gastheer-stack (${GASTHEER}) afbreken..."
    dc "$GASTHEER" down -v || { echo "FAIL: afbreken van '${GASTHEER}' mislukt — de directory-DB is NIET gewist." >&2; RC=1; }

    [ "$RC" -eq 0 ] || {
      echo "FEDERATIE NIET SCHOON NEER — ruim handmatig op voor je opnieuw 'up' draait." >&2
      exit 1
    }
    echo "FEDERATIE NEER."
    ;;

  stop|start)
    # Goedkoop itereren: containers stoppen/starten zonder de volumes te wissen.
    ACTIE="$1"
    RC=0
    for peer in $(fsc_alle_peers); do
      echo "federatie: ${peer} ${ACTIE}..."
      dc "$peer" "$ACTIE" || { echo "FAIL: '${ACTIE}' mislukte voor '${peer}'." >&2; RC=1; }
    done
    [ "$RC" -eq 0 ] || exit 1
    echo "FEDERATIE $(printf '%s' "$ACTIE" | tr '[:lower:]' '[:upper:]')."
    ;;

  restart)
    # Bewust ÉÉN service, geen kale `restart`. `docker compose restart` respecteert `depends_on`
    # niet: een herstart van alles gooit postgres tegelijk met zijn afnemers om, waarna elke
    # manager sterft op `the database system is starting up`. Ze komen er via
    # `restart: on-failure` wel weer bovenop, maar traag en met een schrikbarend log.
    # Een volledige cyclus is dus `down` + `up`; dit verbum is voor het goedkope geval —
    # `restart router` na een haproxy-edit, `restart inway-magazijn-a` na een env-wijziging.
    SERVICE="${2:-}"
    [ -n "$SERVICE" ] || {
      echo "usage: $0 restart <service>   (voor een volledige cyclus: $0 down && $0 up)" >&2
      exit 2
    }

    GEVONDEN=0
    for peer in $(fsc_alle_peers); do
      # `config --services` en niet `ps --services`: die laatste toont alleen services met een
      # DRAAIENDE container, dus juist de gecrashte service die je wilt herstarten ontbreekt.
      # Stderr blijft zichtbaar: een ontbrekend compose-bestand hoort geen "service onbekend" te
      # worden.
      SERVICES="$(dc "$peer" config --services)" || {
        echo "FAIL: kon de services van '${peer}' niet uitlezen." >&2
        exit 1
      }
      printf '%s\n' "$SERVICES" | grep -qx "$SERVICE" || continue

      # `toolbox` en `stub-upstream` bestaan in élke peer-stack; die herstart dit dus overal.
      echo "federatie: ${peer} restart ${SERVICE}..."
      dc "$peer" restart "$SERVICE"
      GEVONDEN=1
    done

    [ "$GEVONDEN" -eq 1 ] || { echo "FAIL: geen enkele peer-stack kent de service '${SERVICE}'." >&2; exit 1; }
    echo "FEDERATIE HERSTART (${SERVICE})."
    ;;

  status)
    # Elke pipeline met `|| true`: onder `pipefail` geeft een `grep` zonder treffers een non-nul
    # status, en dan zou juist het diagnose-commando zwijgend afbreken.
    # Alleen de containers van deze federatie: een ongefilterde `ps -a` toont elke container op de
    # machine en maakt de uitvoer onbruikbaar op een ontwikkelmachine met ander werk erop.
    for peer in $(fsc_alle_peers); do
      # Projectnaam eerst apart ophalen: mislukt dat binnen het `--filter`-argument, dan blijft er
      # een leeg label over dat elke container selecteert.
      PROJ="$(compose_project "$peer")" || { echo "  (projectnaam van ${peer} onbekend)" >&2; continue; }
      podman ps -a --filter "label=com.docker.compose.project=${PROJ}" \
        --format '{{.Names}}\t{{.Status}}' 2>"$ERRLOG" | sort || true
      fsc_warn_errlog "podman ps faalde voor ${peer}"
    done
    echo
    echo "listeners in de gedeelde netns:"
    # Stderr apart houden: gevouwen in de lijst zou een foutregel hieronder als wildcard-bind lezen.
    ALLE="$(ss -ltnH 2>"$ERRLOG" | awk '{print $4}' | sort -u -t: -k2 -n || true)"
    fsc_warn_errlog "ss faalde"
    if [ -n "$ALLE" ]; then
      printf '%s\n' "$ALLE" | sed 's/^/  /'
    else
      echo "  (geen — federatie staat neer, of deze shell deelt de netns niet)"
    fi
    # Bewust ALLE listeners tonen en de afwijkers apart benoemen. Filteren op het federatie-prefix
    # zou juist een wildcard-bind onzichtbaar maken — precies de fout die je hier wilt zien. Elk
    # 127.x telt als loopback: de federatie zit op 127.20.x, maar podman's resolver (127.0.0.11) en
    # een lokale dev-server (127.0.0.1) horen hier evenmin als afwijker te verschijnen.
    echo "NIET op loopback (hoort leeg te zijn):"
    BUITEN="$(printf '%s\n' "$ALLE" | grep -vE '^(127\.[0-9]+\.[0-9]+\.[0-9]+|\[::1\]|\[::ffff:127\.[0-9]+\.[0-9]+\.[0-9]+\])(%[^:]*)?:' || true)"
    if [ -n "$BUITEN" ]; then
      printf '%s\n' "$BUITEN" | sed 's/^/  !! /'
    else
      echo "  (leeg)"
    fi
    ;;

  *)
    echo "usage: $0 <up|down|stop|start|restart <service>|status>" >&2
    exit 2
    ;;
esac
