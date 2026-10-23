#!/usr/bin/env bash
# Fixture-tests voor de federatie-helpers uit fsc-harness.sh en voor `leaves_ketenen` uit
# deel-groep-ca.sh. Alle drie beslissen ze iets waarvan een stille fout duur is:
#
#   - fsc_compose_project levert de projectnaam waarop de container-filters staan; leeg betekent
#     dat élke `--filter label=com.docker.compose.project=` niets meer selecteert, en dus dat de
#     controles die daarop leunen zwijgend uitgeschakeld zijn;
#   - fsc_podman_api_dood en fsc_pull_limiet_bereikt beslissen of `federatie.sh up` retryt of stopt
#     met een instructie;
#   - leaves_ketenen beslist of een DESTRUCTIEVE CA-operatie als "al gedaan" wordt overgeslagen.
#
# Daarnaast de bedrading in federatie.sh zelf: of compose en de podman-CLI dezelfde engine zien,
# en of `up_met_retry` die beslissingen ook opvolgt.
#
# Geen containers, geen netwerk: openssl plus een tmpdir.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=fsc-harness.sh
source "$HERE/fsc-harness.sh"

fails=0
ok()   { echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERK="$(mktemp -d)"
trap 'rm -rf "$WERK"' EXIT

# --- fsc_peer_var / fsc_peer_waarde -------------------------------------------------------------
[ "$(fsc_peer_var magazijn-a)" = "magazijn_a" ] \
  && ok "peer_var vervangt het koppelteken" || fout "peer_var vervangt het koppelteken niet"
[ "$(fsc_peer_var logius)" = "logius" ] \
  && ok "peer_var laat een naam zonder koppelteken ongemoeid" || fout "peer_var muteert een naam zonder koppelteken"

# shellcheck disable=SC2034  # wordt indirect gelezen door fsc_peer_waarde.
NET_magazijn_a=127.20.2
[ "$(fsc_peer_waarde NET magazijn-a)" = "127.20.2" ] \
  && ok "peer_waarde leest via het genormaliseerde achtervoegsel" || fout "peer_waarde leest niet via het genormaliseerde achtervoegsel"
[ -z "$(fsc_peer_waarde NET bestaat-niet)" ] \
  && ok "peer_waarde levert leeg voor een onbekende peer" || fout "peer_waarde levert niet-leeg voor een onbekende peer"

# --- fsc_component_adres ------------------------------------------------------------------------
# De octetten liggen vast; verschuift er één, dan wijst een `extra_hosts`-regel naar een component
# die er niet is en faalt de federatie pas bij `up`, op TLS.
[ "$(fsc_component_adres 127.20.2 manager)" = "127.20.2.1" ] \
  && ok "component_adres plakt het manager-octet" || fout "component_adres levert het verkeerde manager-adres"
[ "$(fsc_component_adres 127.20.1 outway)" = "127.20.1.5" ] \
  && ok "component_adres plakt het outway-octet" || fout "component_adres levert het verkeerde outway-adres"

# Twee componenten in hetzelfde /24 mogen nooit hetzelfde adres krijgen: dan botsen ze alsnog op
# hun standaardpoort, precies wat de adresscheiding moet uitsluiten.
ADRESSEN="$(for c in manager controller txlog inway outway stub-upstream; do
              fsc_component_adres 127.20.1 "$c"
            done | sort)"
if [ "$(printf '%s\n' "$ADRESSEN" | wc -l)" -eq "$(printf '%s\n' "$ADRESSEN" | sort -u | wc -l)" ]; then
  ok "component_adres geeft elke component een uniek adres binnen het /24"
else
  fout "component_adres geeft twee componenten hetzelfde adres — ze botsen dan op hun standaardpoort"
fi

if fsc_component_adres 127.20.1 kachel >/dev/null 2>&1; then
  fout "component_adres slaagt op een onbekende component (levert een adres waar niets luistert)"
else
  ok "component_adres faalt hard op een onbekende component"
fi

if fsc_component_adres "" manager >/dev/null 2>&1; then
  fout "component_adres slaagt op een leeg net (levert '.1', een adres dat nergens bestaat)"
else
  ok "component_adres faalt hard op een leeg net"
fi

# --- fsc_grant_bruikbaar ------------------------------------------------------------------------
# De sentinel is het hele punt: fsc_grant_hash geeft `unknown` terug in plaats van leeg, en een
# aanroeper die op leegte toetst schrijft die string door naar de Fsc-Grant-Hash-header.
fsc_grant_bruikbaar '$1$3$abc' \
  && ok "grant_bruikbaar accepteert een echt hash" || fout "grant_bruikbaar verwerpt een echt hash"

if fsc_grant_bruikbaar unknown; then
  fout "grant_bruikbaar accepteert de sentinel 'unknown' (die belandt dan op de header)"
else
  ok "grant_bruikbaar verwerpt de sentinel 'unknown'"
fi

if fsc_grant_bruikbaar ""; then
  fout "grant_bruikbaar accepteert een lege waarde"
else
  ok "grant_bruikbaar verwerpt een lege waarde"
fi

if fsc_grant_bruikbaar; then
  fout "grant_bruikbaar accepteert een ontbrekend argument"
else
  ok "grant_bruikbaar verwerpt een ontbrekend argument"
fi

# --- fsc_compose_env_waarde ---------------------------------------------------------------------
# Een kale `$` in een env_file wordt door compose als variabele-verwijzing gelezen; bij een
# grant-hash (`$1$<n>$<base64url>`) verdwijnt daardoor het hele middenstuk en antwoordt de outway
# 400. Gemeten in een container: `$1$4$k4rw…` kwam aan als `$1$4-KB43`.
[ "$(fsc_compose_env_waarde '$1$4$k4rwlWTsCM_j89Fc3nrbnQa9-KB43')" = '$$1$$4$$k4rwlWTsCM_j89Fc3nrbnQa9-KB43' ] \
  && ok "compose_env_waarde verdubbelt elke dollar in een grant-hash" \
  || fout "compose_env_waarde verdubbelt de dollars niet (compose vreet dan het hash op)"

[ "$(fsc_compose_env_waarde 'geen-dollars-hier')" = 'geen-dollars-hier' ] \
  && ok "compose_env_waarde laat een waarde zonder dollars ongemoeid" \
  || fout "compose_env_waarde muteert een waarde zonder dollars"

[ "$(fsc_compose_env_waarde '')" = '' ] \
  && ok "compose_env_waarde levert leeg op leeg" || fout "compose_env_waarde muteert een lege waarde"

# Heen en terug: wat de schrijffunctie erin zet, moet de leesfunctie er ongeschonden uit halen.
# Zonder die symmetrie stuurt een lezer de verdubbelde dollars door en krijgt hij een 400.
printf 'MAGAZIJN_A_GRANT_HASH=%s\n' "$(fsc_compose_env_waarde '$1$3$_AKXojkq-Nn07')" > "$WERK/grants.env"
[ "$(fsc_compose_env_lees "$WERK/grants.env" MAGAZIJN_A_GRANT_HASH)" = '$1$3$_AKXojkq-Nn07' ] \
  && ok "compose_env_lees haalt eruit wat compose_env_waarde erin zette" \
  || fout "compose_env_waarde/-lees zijn niet symmetrisch (de lezer stuurt dan dubbele dollars door)"

[ -z "$(fsc_compose_env_lees "$WERK/grants.env" BESTAAT_NIET)" ] \
  && ok "compose_env_lees levert leeg voor een onbekende naam" \
  || fout "compose_env_lees levert niet-leeg voor een onbekende naam"

# --- fsc_compose_project ------------------------------------------------------------------------
printf 'name: fsc-magazijna\nservices:\n  x: {}\n' > "$WERK/goed.yaml"
[ "$(fsc_compose_project "$WERK/goed.yaml")" = "fsc-magazijna" ] \
  && ok "compose_project leest name:" || fout "compose_project leest name: niet"

printf 'name: "fsc-logius"\n' > "$WERK/gequote.yaml"
[ "$(fsc_compose_project "$WERK/gequote.yaml")" = "fsc-logius" ] \
  && ok "compose_project strippt aanhalingstekens" || fout "compose_project laat aanhalingstekens staan (die belanden in de containernaam)"

printf 'services:\n  x: {}\n' > "$WERK/geen-naam.yaml"
if fsc_compose_project "$WERK/geen-naam.yaml" >/dev/null 2>&1; then
  fout "compose_project slaagt zonder name: (levert leeg op, dat schakelt filters stil uit)"
else
  ok "compose_project faalt hard zonder name:"
fi

if fsc_compose_project "$WERK/bestaat-niet.yaml" >/dev/null 2>&1; then
  fout "compose_project slaagt op een ontbrekend bestand"
else
  ok "compose_project faalt hard op een ontbrekend bestand"
fi

# --- fsc_pull_limiet_bereikt --------------------------------------------------------------------
limiet_geval() {  # <verwacht 0/1> <omschrijving> <loginhoud>
  printf '%s\n' "$3" > "$WERK/log"
  if fsc_pull_limiet_bereikt "$WERK/log"; then gemeten=0; else gemeten=1; fi
  [ "$gemeten" -eq "$1" ] && ok "$2" || fout "$2 (verwacht $1, kreeg $gemeten)"
}

limiet_geval 0 "herkent de rate-limit-melding van Docker Hub" \
  'outway-logius Error {"message":"toomanyrequests: You have reached your unauthenticated pull rate limit. https://www.docker.com/increase-rate-limit"}'
limiet_geval 0 "herkent de kale HTTP-status zoals podman die doorgeeft" \
  'Error: initializing source docker://ghcr.io/minbzk/fsc:1.0: reading manifest 1.0 in ghcr.io/minbzk/fsc: invalid status code from registry 429 (Too Many Requests)'
limiet_geval 1 "een gewone pull-fout is geen rate limit" \
  'Error response from daemon: manifest unknown: manifest unknown'
limiet_geval 1 "een ID-mapping-fout is geen rate limit" \
  'creating an ID-mapped copy of layer "8473d9e6": container ID 0 cannot be mapped to a host ID'

# --- fsc_podman_api_dood ------------------------------------------------------------------------
api_geval() {  # <verwacht 0/1> <omschrijving> <loginhoud>
  printf '%s\n' "$3" > "$WERK/log"
  if fsc_podman_api_dood "$WERK/log"; then gemeten=0; else gemeten=1; fi
  [ "$gemeten" -eq "$1" ] && ok "$2" || fout "$2 (verwacht $1, kreeg $gemeten)"
}

api_geval 0 "herkent 'Cannot connect to the Docker daemon'" \
  'Cannot connect to the Docker daemon at unix:///tmp/podman-run-1000/podman/podman.sock.'
api_geval 0 "herkent 'error during connect'" \
  'service:toolbox:1 error during connect: Post "http://%2Ftmp%2F.../containers/create": EOF'
api_geval 0 "herkent een dode unix-socket" \
  'dial unix /tmp/podman-run-1000/podman/podman.sock: connect: connection refused'

# Een containerlog waarin postgres nog opstart bevat óók 'connection refused'. Dat is een gewone
# transiënte fout waarop juist WÉL geretryd moet worden, geen onbereikbare API — vandaar dat de
# classificatie op compose/podman-eigen foutvormen verankerd is en niet op de losse tekst.
api_geval 1 "ziet postgres-opstartruis NIET als API-storing" \
  'manager-magazijn-a-1  | failed to connect to `user=postgres`: 127.0.0.1:5432: connection refused'
api_geval 1 "ziet de ID-map-race NIET als API-storing" \
  'Error response from daemon: container create: creating an ID-mapped copy of layer "abc": container ID 0 cannot be mapped to a host ID'

# --- federatie.sh: zelfde_engine, engine_voor_compose, up_met_retry ------------------------------
# federatie.sh voert alleen bij directe aanroep een commando uit, dus sourcen levert de functies.
# Elk geval in een subshell: de functies eindigen met `exit`, en federatie.sh zet zijn eigen
# EXIT-trap.
FED="$HERE/../federatie/federatie.sh"

federatie_geval() {  # <script>: zet STATUS en UITVOER
  STATUS=0
  UITVOER="$(
    (
      unset MODUS DOCKER_HOST CONTAINER_HOST
      # shellcheck source=../federatie/federatie.sh
      . "$FED"
      eval "$1"
    ) 2>&1
  )" || STATUS=$?
}

federatie_verwacht() {  # <omschrijving> <status> <naald>
  if [ "$STATUS" -ne "$2" ]; then
    fout "$1 — exit $STATUS, verwacht $2. Uitvoer: $UITVOER"
  else
    case "$UITVOER" in
      *"$3"*) ok "$1" ;;
      *) fout "$1 — '$3' ontbreekt in: $UITVOER" ;;
    esac
  fi
}

federatie_geval 'zelfde_engine; echo "CH=${CONTAINER_HOST:-leeg}"'
federatie_verwacht "zonder DOCKER_HOST blijft CONTAINER_HOST onaangeroerd" 0 "CH=leeg"

federatie_geval 'DOCKER_HOST=unix:///a.sock; zelfde_engine; echo "CH=$CONTAINER_HOST"'
federatie_verwacht "de podman-CLI krijgt de socket van compose" 0 "CH=unix:///a.sock"

federatie_geval 'DOCKER_HOST=unix:///a.sock; CONTAINER_HOST=unix:///a.sock; zelfde_engine; echo GELIJK'
federatie_verwacht "een gelijke CONTAINER_HOST wordt geaccepteerd" 0 "GELIJK"

federatie_geval 'DOCKER_HOST=unix:///a.sock; CONTAINER_HOST=unix:///b.sock; zelfde_engine; echo DOORGELOPEN'
federatie_verwacht "een afwijkende CONTAINER_HOST wordt geweigerd" 1 "wijzen naar verschillende engines"

case "$UITVOER" in
  *DOORGELOPEN*) fout "na een afwijkende CONTAINER_HOST loopt het script door — $UITVOER" ;;
  *) ok "na een afwijkende CONTAINER_HOST stopt het script" ;;
esac

federatie_geval 'modus_engine() { PODMAN_RUNTIME=0; DOCKER_HOST=unix:///docker.sock; }; engine_voor_compose; echo DOORGELOPEN'
federatie_verwacht "op een andere engine dan podman weigert de federatie" 1 "de federatie vraagt podman"

federatie_geval 'modus_engine() { PODMAN_RUNTIME=1; DOCKER_HOST=unix:///podman.sock; }; engine_voor_compose; echo "CH=$CONTAINER_HOST"'
federatie_verwacht "op podman praat de CLI daarna via dezelfde socket" 0 "CH=unix:///podman.sock"

# up_met_retry met een modus_compose_up die FAALREGEL logt en faalt, of slaagt als die leeg is. De
# telling bewijst of er geretryd is; de rest van de omgeving is stil.
RETRY_STUBS='
  aanroepen=0
  UP_POGINGEN=3
  compose_project() { echo fsc-test; }
  dode_containers() { :; }
  opruimen() { :; }
  sleep() { :; }
  modus_compose_up() {
    aanroepen=$((aanroepen + 1))
    [ -n "$FAALREGEL" ] || return 0
    printf "%s\n" "$FAALREGEL"
    return 1
  }
'

retry_geval() {  # <faalregel, of leeg voor een geslaagde up>
  # shellcheck disable=SC2034  # gelezen door modus_compose_up in RETRY_STUBS, via eval.
  FAALREGEL="$1"
  federatie_geval "$RETRY_STUBS"'up_met_retry logius || echo "RC=$?"; echo "AANROEPEN=$aanroepen"'
}

retry_geval ""
federatie_verwacht "een geslaagde up: één aanroep" 0 "AANROEPEN=1"

case "$UITVOER" in
  *RC=*) fout "een geslaagde up meldt een fout — $UITVOER" ;;
  *) ok "een geslaagde up meldt geen fout" ;;
esac

retry_geval 'Error response from daemon: toomanyrequests: You have reached your unauthenticated pull rate limit.'
federatie_verwacht "het pull-limiet stopt na één poging" 0 "RC=1
AANROEPEN=1"
federatie_verwacht "het advies noemt geen vaste registry" 0 "docker login <registry>"

retry_geval 'Error: reading manifest 1.0 in ghcr.io/minbzk/fsc: invalid status code from registry 429 (Too Many Requests)'
federatie_verwacht "een 429 van de registry stopt ook na één poging" 0 "RC=1
AANROEPEN=1"

retry_geval 'error during connect: Post "http://d/v1.41/containers/create": EOF'
federatie_verwacht "een weggevallen API stopt na één poging" 0 "AANROEPEN=1"
federatie_verwacht "een weggevallen API noemt de oorzaak" 0 "podman-API-service is onderweg weggevallen"

retry_geval 'Error: creating container: container ID 0 cannot be mapped to a host ID'
federatie_verwacht "een gewone fout wordt UP_POGINGEN keer geprobeerd" 0 "RC=1
AANROEPEN=3"
federatie_verwacht "na de laatste poging volgt de eindmelding" 0 "kwam niet omhoog in 3 pogingen"

# --- leaves_ketenen -----------------------------------------------------------------------------
# Functie uit deel-groep-ca.sh; die sourcen zou het script uitvoeren, dus we halen 'm eruit.
FEDSH="$HERE/../federatie/deel-groep-ca.sh"
if [ -r "$FEDSH" ]; then
  eval "$(sed -n '/^leaves_ketenen()/,/^}/p' "$FEDSH")"

  # Zonder deze controle slagen de NEGATIEVE asserts hieronder op "command not found": schrijft
  # iemand `function leaves_ketenen` of zet er een spatie voor de haakjes, dan extraheert sed niets
  # en `eval ""` slaagt gewoon.
  if ! declare -f leaves_ketenen >/dev/null; then
    fout "leaves_ketenen niet uit ${FEDSH} te extraheren — is de definitievorm gewijzigd?"
    FEDSH=""
  fi

  if [ -z "$FEDSH" ]; then
    :
  elif ! command -v openssl >/dev/null 2>&1; then
    # Hard falen i.p.v. overslaan: deze vijf asserts bewaken een DESTRUCTIEVE CA-operatie, en een
    # stille skip zou "ALLE ASSERTS GROEN" opleveren over 60% van wat het bestand belooft.
    fout "openssl ontbreekt — de leaves_ketenen-asserts kunnen niet draaien"
  else
    # Twee onafhankelijke CA's + leaves, zodat we een gedeelde en een vreemde keten kunnen bouwen.
    maak_ca() {  # <map>
      mkdir -p "$1"
      openssl req -x509 -newkey rsa:2048 -nodes -keyout "$1/root-key.pem" -out "$1/root.pem" \
        -days 2 -subj "/CN=test-root-$(basename "$1")" >/dev/null 2>&1
      cp "$1/root.pem" "$1/intermediate.pem"
      cp "$1/root-key.pem" "$1/intermediate-key.pem"
    }
    # Het aantal verwachte leaves leidt leaves_ketenen af uit pki/peers/<peer>/<endpoint>/csr.json,
    # dus die moeten meegroeien met de leaves — anders meet de test een andere invariant dan het
    # script hanteert.
    maak_leaf() {  # <ca-map> <doel-map> <endpoint>
      mkdir -p "$2" "$PKI/peers/peer/$3"
      printf '{}' > "$PKI/peers/peer/$3/csr.json"
      openssl req -newkey rsa:2048 -nodes -keyout "$2/key.pem" -out "$2/csr.pem" \
        -subj "/CN=leaf" >/dev/null 2>&1
      openssl x509 -req -in "$2/csr.pem" -CA "$1/root.pem" -CAkey "$1/root-key.pem" \
        -CAcreateserial -out "$2/cert.pem" -days 1 >/dev/null 2>&1
      rm -f "$2/csr.pem"
    }

    PKI="$WERK/pki"; VREEMD="$WERK/vreemd"
    maak_ca "$PKI/ca"; maak_ca "$VREEMD/ca"

    # 1. lege out/ -> geen bewijs, dus niet "al gedaan"
    mkdir -p "$PKI/out"
    if leaves_ketenen "$PKI"; then fout "leaves_ketenen slaagt op een lege out/"; else ok "leaves_ketenen faalt op een lege out/"; fi

    # 2. één ketenende leaf
    maak_leaf "$PKI/ca" "$PKI/out/peer/manager" manager
    if leaves_ketenen "$PKI"; then ok "leaves_ketenen slaagt op één ketenende leaf"; else fout "leaves_ketenen faalt op één ketenende leaf"; fi

    # 3. meerdere ketenende leaves
    maak_leaf "$PKI/ca" "$PKI/out/peer/inway" inway
    maak_leaf "$PKI/ca" "$PKI/out/peer/txlog" txlog
    if leaves_ketenen "$PKI"; then ok "leaves_ketenen slaagt op meerdere ketenende leaves"; else fout "leaves_ketenen faalt op meerdere ketenende leaves"; fi

    # 4. halve set: het csr.json bestaat, de leaf niet. Dat is de afgebroken her-uitgifte waarbij
    #    de geschreven leaves toevallig wél allemaal ketenen — zonder de telling zou dat als
    #    "al gedaan" lezen en het script de peer half laten staan.
    mkdir -p "$PKI/peers/peer/ontbreekt"; printf '{}' > "$PKI/peers/peer/ontbreekt/csr.json"
    if leaves_ketenen "$PKI"; then
      fout "leaves_ketenen slaagt terwijl er een leaf ontbreekt t.o.v. het aantal endpoints"
    else
      ok "leaves_ketenen faalt bij een halve set leaves"
    fi
    rm -rf "$PKI/peers/peer/ontbreekt"

    # 5. de regressie die telt: één van de drie ketent niet. Wie de lus 'optimaliseert' naar
    #    alleen het eerste certificaat, breekt precies dit geval — en dat is de afgebroken
    #    her-uitgifte die het script zegt te detecteren.
    maak_leaf "$VREEMD/ca" "$PKI/out/peer/outway" outway
    if leaves_ketenen "$PKI"; then
      fout "leaves_ketenen slaagt terwijl één van vier leaves niet ketent"
    else
      ok "leaves_ketenen faalt als één van meerdere leaves niet ketent"
    fi
  fi
else
  fout "deel-groep-ca.sh niet leesbaar op $FEDSH"
fi

if [ "$fails" -eq 0 ]; then
  echo "ALLE ASSERTS GROEN"
  exit 0
else
  echo "FAIL: $fails assert(s) gefaald" >&2
  exit 1
fi
