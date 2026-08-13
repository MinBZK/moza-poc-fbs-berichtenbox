#!/usr/bin/env bash
# Controleert één gerenderde compose-merge van een FSC-peer-harness.
#
# Leest de merge-JSON (`docker compose config --format json`) van stdin en meldt elke afwijking
# van de eisen die in een GEDEELDE netns gelden. Print bevindingen op stdout en eindigt non-nul
# zodra er één is.
#
#   docker compose -f … config --format json | .github/scripts/merge-guard.sh <label> [--postgres]
#
#     <label>       naam in de meldingen, bv. `demo/environment/logius (federatie-merge)`
#     --postgres    eis dat er een postgres-service is met een expliciete listen_addresses.
#                   Alleen de stack die postgres daadwerkelijk draait; in de federatie-opstelling
#                   zetten de gasten hem in een inactief profiel en rendert hij dus niet mee.
#
# Bestaat als apart script omdat twee jobs dezelfde merge moeten toetsen: de drie-bestands-merge
# (peer standalone) en de vier-bestands-merge (peer in de federatie). Elke laag kan een listener
# introduceren, en alleen de LAATSTE merge is wat er draait — een controle die alleen de eerste
# merge ziet, mist per definitie alles wat de federatie-overlay toevoegt.
set -euo pipefail

LABEL="${1:?usage: merge-guard.sh <label> [--postgres]}"
shift
EIS_POSTGRES=0
# Expliciet afwijzen i.p.v. negeren: een verkeerd gespelde of verkeerd geplaatste vlag zou de
# postgres-eis anders zwijgend uitschakelen.
for arg in "$@"; do
  case "$arg" in
    --postgres) EIS_POSTGRES=1 ;;
    *) echo "FOUT: onbekend argument '$arg' voor merge-guard.sh." >&2; exit 2 ;;
  esac
done

MERGED="$(cat)"
[ -n "$MERGED" ] || { echo "FOUT: ${LABEL} — lege merge op stdin."; exit 1; }

totaal=$(jq -r '.services | length' <<<"$MERGED")
if [ -z "$totaal" ] || [ "$totaal" -eq 0 ]; then
  echo "FOUT: ${LABEL} levert geen services op — de merge is leeg of het JSON-schema is gewijzigd."
  exit 1
fi

# Elke bind-vorm die niet op loopback staat: `0.0.0.0:P`, `[::]:P`, `*:P` en de kale `:P`. Een
# dial-adres (`https://controller…:39443`, `postgres://…@host:5432/db`) matcht niet, want daar
# staat vóór de dubbele punt een teken dat geen `=`, spatie of regelbegin is. Bewust op de
# wáárde en niet op de naam: `LISTEN_ADDRESS`, `--bind-address=` en een positioneel adres zijn
# even gevaarlijk, en de volgende die er een toevoegt verzint gegarandeerd een andere naam.
LEK=':[0-9]{2,5}$'
LEK="(^|[= ])(0\\.0\\.0\\.0|\\[::\\]|\\*)?${LEK}"

fail=0
meld() {
  [ -n "$2" ] || return 0
  echo "FOUT: ${LABEL} — $1"
  printf '%s\n' "$2" | sed 's/^/  /'
  fail=1
}

geen_host=$(jq -r '.services | to_entries[]
  | select(.value.network_mode != "host") | .key' <<<"$MERGED")

geen_keepid=$(jq -r '.services | to_entries[]
  | select((.value.volumes // []) | any(.target == "/pki"))
  | select(.value.user != null)
  | select(.value.userns_mode != "keep-id") | .key' <<<"$MERGED")

# Namen die per definitie een listener zijn: die moeten expliciet op 127.0.0.1 staan, ook als het
# adres een andere vorm heeft dan LEK vangt (bv. een LAN-adres).
env_lek=$(jq -r '.services | to_entries[] | . as $s
  | ($s.value.environment // {}) | to_entries[]
  | select(.key | test("^(LISTEN_ADDRESS|MONITORING_ADDRESS)"))
  | select(.value == null or ((.value | tostring | startswith("127.0.0.1")) | not))
  | "\($s.key): \(.key)=\(.value // "<leeg: erft uit de shell van de aanroeper>")"' <<<"$MERGED")

# Naam-onafhankelijk: elke env-waarde en elk `command`/`entrypoint`-token dat eruitziet als een
# niet-loopback bind.
bind_lek=$(jq -r --arg re "$LEK" '.services | to_entries[] | . as $s
  | ( ($s.value.environment // {}) | to_entries[]
        | select(.value != null) | "\(.key)=\(.value)" ),
    ( (($s.value.command // []) + ($s.value.entrypoint // []))[]?
        | select(type == "string") )
  | select(test($re))
  | "\($s.key): \(.)"' <<<"$MERGED")

# De overlay hoort elke `ports:` uit de basis te resetten; een gepubliceerde poort in een gedeelde
# netns is betekenisloos en verraadt een vergeten `!reset`.
poorten=$(jq -r '.services | to_entries[]
  | select(((.value.ports // []) | length) > 0) | .key' <<<"$MERGED")

# Een onbegrensde herstartlus verbrandt onder podman (geen backoff) een core zolang de sessie
# duurt. `always` en `unless-stopped` doen dat net zo goed als een kale `on-failure`.
ongelimiteerd=$(jq -r '.services | to_entries[]
  | select(.value.restart == "on-failure" or .value.restart == "always" or .value.restart == "unless-stopped")
  | .key' <<<"$MERGED")

# Postgres drukt zijn bind niet uit als `adres:poort`, dus LEK ziet hem niet. Zonder override zet
# het image zelf `listen_addresses='*'`: de DB met harness-credentials op elke interface.
pg_lek=$(jq -r '.services | to_entries[] | . as $s
  | ($s.value.command // [])[]?
  | select(type == "string") | select(test("listen_addresses="))
  | select(test("listen_addresses=127\\.0\\.0\\.1") | not)
  | "\($s.key): \(.)"' <<<"$MERGED")

# De eis "expliciete listen_addresses" geldt zodra er een postgres in de merge zit — óók zonder
# --postgres. Die vlag stuurt alleen of postgres AANWEZIG moet zijn. Hing de eis aan de vlag, dan
# zou een gast die zijn postgres uit het inactieve profiel haalt het image-default
# `listen_addresses='*'` krijgen: de DB met harness-credentials op elke interface, en de guard groen.
pg_mist=$(jq -r '.services | to_entries[]
  | select(.key == "postgres")
  | select(((.value.command // []) | map(select(test("listen_addresses="))) | length) == 0)
  | .key' <<<"$MERGED")

if [ "$EIS_POSTGRES" -eq 1 ] && [ "$(jq -r '.services | has("postgres")' <<<"$MERGED")" != "true" ]; then
  echo "FOUT: ${LABEL} — geen postgres-service in de merge, terwijl --postgres is meegegeven."
  fail=1
fi

# haproxy opent zijn poort uit een gemounte configfile en ontsnapt dus aan elke controle op de
# merge. Lees het pad uit de merge in plaats van de bestandsnaam aan te nemen: verdwijnt de
# `volumes`-override, dan mount de router weer een config met `bind *:443` terwijl deze check
# groen blijft op een ongebruikt bestand.
haproxy_lek=""
if [ "$(jq -r '.services | has("router")' <<<"$MERGED")" = "true" ]; then
  cfg=$(jq -r '.services.router.volumes[]?
    | select(.target == "/usr/local/etc/haproxy/haproxy.cfg") | .source' <<<"$MERGED")

  if [ -z "$cfg" ] || [ ! -f "$cfg" ]; then
    echo "FOUT: ${LABEL} — router mount geen leesbare haproxy-config (${cfg:-<geen>})."
    fail=1
  else
    # Vloer onder het aantal bind-regels: verhuizen ze naar een `include` of naar een runtime
    # gegenereerde config, dan meet deze check niets meer terwijl hij groen blijft.
    binds=$(grep -cE '^[[:space:]]*bind[[:space:]]' "$cfg" || true)
    if [ "${binds:-0}" -eq 0 ]; then
      echo "FOUT: ${LABEL} — geen enkele bind-regel in ${cfg}; deze check meet niets."
      fail=1
    fi

    haproxy_lek=$(grep -nE '^[[:space:]]*bind[[:space:]]' "$cfg" \
      | grep -vE 'bind[[:space:]]+127\.0\.0\.1:' || true)
  fi
fi

meld "zonder network_mode host in de merge (voeg toe aan de overlay: network_mode, extra_hosts, botsvrije poort):" "$geen_host"
meld "leest /pki onder een vaste UID zonder keep-id (voeg toe aan docker-compose.podman.yaml):" "$geen_keepid"
meld "listener-env die niet op 127.0.0.1 staat:" "$env_lek"
meld "bind-adres dat niet op loopback staat:" "$bind_lek"
meld "nog een gepubliceerde poort in de merge (mist een 'ports: !reset []'):" "$poorten"
meld "onbegrensde herstartlus (zet een maximum in de overlay):" "$ongelimiteerd"
meld "postgres luistert niet uitsluitend op 127.0.0.1:" "$pg_lek"
meld "postgres zonder expliciete listen_addresses (het image kiest dan '*'):" "$pg_mist"
meld "bind-regel in de gemounte haproxy-config die niet op 127.0.0.1 staat:" "$haproxy_lek"

if [ "$fail" -eq 0 ]; then
  echo "${LABEL}: ${totaal}/${totaal} services op de gedeelde netns, alle listeners op loopback."
fi

exit "$fail"
