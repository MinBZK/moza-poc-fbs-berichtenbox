#!/usr/bin/env bash
# Gedeelde helpers voor de lokale FSC-peer-harnessen onder demo/environment/<peer>/deploy/local/.
# Peer-identiteit (OIN's, servicenamen, cert-paden, MANAGER/CONTROLLER-URL's, TIMEOUT/INTERVAL,
# GROUP_ID) staat in elk script zelf — hier alleen het generieke idioom. bash 3.2-compatibel
# (macOS-default, zie smoke-services.sh): geen associative arrays, geen mapfile, geen ${var^^}.

# --- ERRLOG-levenscyclus --------------------------------------------------------------------

# fsc_errlog_init: mktemp + trap, zet de globale $ERRLOG. Aanroepen ná `set -euo pipefail`.
fsc_errlog_init() {
  ERRLOG=$(mktemp)
  trap 'rm -f "$ERRLOG"' EXIT
}

# fsc_scrub_errlog [bestand]: verwijdert de podman-external-compose-provider-banner (met zijn
# SGR-ANSI-omhulsel, bv. ESC[4m vóór de tekst) uit het gegeven bestand (default $ERRLOG), in
# place. ANSI eerst strippen (portable LC_ALL=C-vorm i.p.v. \x1b, een GNU-sed-extensie die
# BSD-sed/macOS niet kent), dan zonder regelanker filteren (de banner start niet op kolom 1
# door de ANSI-prefix), dan lege regels weggooien die overblijven na het strippen van de losse
# ESC[0m-regel.
# shellcheck disable=SC2120  # het bestand-argument is optioneel en defaultt op $ERRLOG; bewust
# behouden zodat een tweede logbestand geen signatuurwijziging vergt.
fsc_scrub_errlog() {
  local file="${1:-$ERRLOG}"

  # sed apart van de greps: `grep -v` geeft terecht 1 terug als álles weggefilterd wordt, maar dat
  # is niet te onderscheiden van een sed die het bestand niet kon lezen. Ging dat in één pijplijn,
  # dan zette `mv -f` een leeg bestand terug en was de reden achter elke latere FAIL-melding weg.
  LC_ALL=C sed -e $'s/\033\\[[0-9;]*m//g' "$file" > "${file}.a" 2>/dev/null || {
    rm -f "${file}.a"
    return 0
  }

  grep -v 'Executing external compose provider' < "${file}.a" \
    | grep -v '^[[:space:]]*$' > "${file}.f" 2>/dev/null || :
  rm -f "${file}.a"
  mv -f "${file}.f" "$file"
}

# fsc_warn_errlog "<prefix>": het poll-loop-idioom — scrub, en als er dan nog iets overblijft,
# print een WARN met de laatste regel en truncate. Retourneert altijd 0 (mag een poll-lus onder
# `set -e` nooit laten stoppen).
fsc_warn_errlog() {
  local prefix="$1"
  fsc_scrub_errlog
  if [ -s "$ERRLOG" ]; then
    echo "  WARN: ${prefix}: $(tail -n1 "$ERRLOG")" >&2
    : > "$ERRLOG"
  fi
  return 0
}

# fsc_last_error [n]: scrub + print de laatste n regels (default 1) van $ERRLOG. Voor gebruik
# in FAIL-strings (command substitution) of op de FAIL-paden. Print niets als leeg; faalt nooit.
fsc_last_error() {
  local n="${1:-1}"
  fsc_scrub_errlog
  tail -n "$n" "$ERRLOG" 2>/dev/null
  return 0
}

# --- curl-in-toolbox -------------------------------------------------------------------------

# fsc_tb <curl-args...>: curl binnen de toolbox-container, met de internal-client-cert van de
# caller. Leest $COMPOSE/$CERT/$KEY/$CA uit de caller-scope (peer-specifiek, hier niet gezet).
fsc_tb() {
  "${COMPOSE[@]}" exec -T toolbox curl -sS --fail-with-body \
    --cert "$CERT" --key "$KEY" --cacert "$CA" "$@" 2>"$ERRLOG"
}

# --- contract-bootstrap-helpers (consume-service.sh) ------------------------------------------

# fsc_new_iv: UUID v4. /proc is Linux-only; op macOS valt terug op uuidgen (lowercase).
fsc_new_iv() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]'
}

# fsc_validity: zet de globale $NBF/$NAF (created_at resp. not_after, +10 jaar geldig) met een
# skew-backdate — Docker Desktop (macOS) draait in een VM waarvan de klok op de host kan
# achterlopen; de manager weigert dan created_at "in the future" (HTTP 500). Op Linux is de
# skew ~0, dus onschadelijk.
fsc_validity() {
  NBF=$(( $(date -u +%s) - 60 ))
  # shellcheck disable=SC2034  # NBF/NAF worden door de aanroepende scripts gelezen.
  NAF=$((NBF + 315360000))
}

# fsc_have_jq: zet de globale $HAVE_JQ (1/0).
fsc_have_jq() {
  # shellcheck disable=SC2034  # HAVE_JQ wordt door de aanroepende scripts gelezen.
  HAVE_JQ=0
  command -v jq >/dev/null 2>&1 && HAVE_JQ=1
  return 0
}

# fsc_accept_state <json> <content_hash> <oin>: "yes"/"no"/"unknown" — draagt het contract de
# accept-handtekening van $oin?
fsc_accept_state() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg oin "$3" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | if ($c | length) == 0 then "unknown"
      elif ([ $c[] | .signatures?.accept? | objects ] | length) == 0 then "unknown"
      elif ($c | any((.signatures?.accept? // {}) | has($oin))) then "yes"
      else "no" end' 2>/dev/null || echo unknown
}

# fsc_contract_state <json> <content_hash>: manager-state (bv. "valid"), "unknown" zonder jq/match.
fsc_contract_state() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h) | .state?]
    | map(select(. != null)) | (first // "unknown") | ascii_downcase' 2>/dev/null || echo unknown
}

# fsc_grant_hash <json> <content_hash> <service_name> <outway_thumbprint>: het GRANT-hash uit
# content.grants[] (niet het contract-hash zelf) — matcht op service.name + outway-thumbprint
# zodat dit ook klopt zodra een contract ooit meer dan één grant draagt.
fsc_grant_hash() {
  [ "$HAVE_JQ" -eq 1 ] || { echo unknown; return; }
  printf '%s' "$1" | jq -r --arg h "$2" --arg svc "$3" --arg thumb "$4" '
    [.. | objects | select((.hash? // .content_hash? // .content?.content_hash?) == $h)] as $c
    | [$c[] | (.content?.grants? // [])[]
         | select(.service?.name == $svc and .outway?.identification?.public_key_thumbprint == $thumb)
         | .hash?] as $g
    | ($g[0] // "unknown")' 2>/dev/null || echo unknown
}

# fsc_zet_upstream <envdir> <peer> <upstream-url> [dienst]: publiceer een dienst van die peer
# (opnieuw) met deze upstream achter de inway. Zonder [dienst] gaat het om de standaarddienst van
# de peer. Idempotent — bestaat de dienst al met dezelfde upstream, dan doet publish-service.sh
# niets.
#
# Elke smoke die over het data-pad iets beweert, hoort dit zelf te zetten in plaats van het als
# voorwaarde op te schrijven: smoke-contract.sh toetst de echo van de stub, smoke-keten.sh het
# échte magazijn, en wie ze na elkaar draait zou anders de ene de andere zien omgooien.
fsc_zet_upstream() {
  local envdir="$1" peer="$2" upstream="$3" dienst="${4:-}"

  (
    export FSC_CONTROLLER="https://controller.${peer}.fsc-test.local:9444"
    export FSC_MANAGER="https://manager.${peer}.fsc-test.local:9443"
    export FSC_UPSTREAM_URL="$upstream"

    # Zonder vierde argument publiceert de peer zijn eigen standaarddienst — de default in zijn
    # publish-service.sh. Niet hier een naam verzinnen: dan zou deze functie moeten weten welke
    # peer welke dienst draagt.
    if [ -n "$dienst" ]; then
      export FSC_SERVICE_NAME="$dienst"
    fi

    "${envdir}/${peer}/deploy/local/publish-service.sh"
  )
}

# fsc_compose_env_waarde <waarde>: waarde zoals hij in een `env_file` van docker compose moet staan.
#
# Compose interpoleert een env_file: `$NAAM` wordt vervangen door een variabele uit de omgeving, en
# een onbekende naam door niets. FSC-grant-hashes hebben de vorm `$1$<n>$<base64url>`, dus het deel
# ná de derde `$` wordt opgeslokt zodra het met een letter of underscore begint — 53 van de 64
# base64url-tekens. Gemeten in een container: `$1$4$k4rwlWTsCM_j89Fc3nrbnQa9-KB43` komt aan als
# `$1$4-KB43`, en de outway antwoordt dan 400 UNKNOWN_GRANT_HASH_IN_HEADER.
#
# Verdubbelen is compose' eigen escape voor een letterlijke `$`. Dat maakt het bestand
# compose-specifiek: lees het niet met `set -a; . bestand`, want dan houd je de dubbele tekens.
fsc_compose_env_waarde() {
  printf '%s' "${1//\$/\$\$}"
}

# fsc_compose_env_lees <bestand> <naam>: de ONGE-escapete waarde van `naam` uit een compose-env_file.
#
# Tegenhanger van fsc_compose_env_waarde. Wie zo'n bestand met `sed`/`grep` uitleest krijgt de
# verdubbelde dollars mee en stuurt die door — bij een grant-hash levert dat een 400 op de outway,
# terwijl het bestand er goed uitziet. Altijd via deze functie lezen.
fsc_compose_env_lees() {
  local waarde
  waarde="$(sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n1)" || return 1
  printf '%s' "${waarde//\$\$/\$}"
}

# fsc_grant_bruikbaar <hash>: is dit een echt grant-hash?
#
# fsc_grant_hash levert bij een mislukking de string `unknown` in plaats van een lege waarde, zodat
# een aanroeper die 'm alleen toont iets leesbaars afdrukt. Een kale `[ -n "$hash" ]` is daardoor
# geen geldige controle: die slaagt óók op de sentinel, en dan reist `Fsc-Grant-Hash: unknown` mee
# naar de outway, die er een 400 op geeft. Vandaar één predicaat in plaats van de vergelijking op
# elke plek los over te typen.
fsc_grant_bruikbaar() {
  [ -n "${1:-}" ] && [ "$1" != unknown ]
}

# --- contract-matching (gedeeld door contracts/bootstrap.sh en federatie/smoke-contract.sh) ----
# Eén matcher voor "is dit contract geldig en van toepassing op deze serviceConnection", zodat
# beide scripts niet elk hun eigen (en dus potentieel afwijkende) criteria hanteren.

# fsc_outway_thumbprint <cert-pad>: SPKI-SHA256-thumbprint (64 lowercase hex) van een outway-
# groepscert op stdout; leeg + non-zero exit bij een ontbrekend/onleesbaar/corrupt certificaat.
# Vereist $ERRLOG (fsc_errlog_init).
fsc_outway_thumbprint() {
  local cert="$1" thumb
  [ -r "$cert" ] || return 1
  thumb="$(openssl x509 -in "$cert" -pubkey -noout 2>>"$ERRLOG" \
             | openssl pkey -pubin -outform DER 2>>"$ERRLOG" \
             | openssl dgst -sha256 -r 2>>"$ERRLOG" | cut -d' ' -f1)" || return 1
  case "$thumb" in
    [0-9a-f]*) [ "${#thumb}" -eq 64 ] || return 1 ;;
    *) return 1 ;;
  esac
  printf '%s' "$thumb"
}

# fsc_grant_actief <json> <service> <provider_oin> <consumer_oin> <outway_thumbprint>: hashes
# (één per regel) van niet-ingetrokken CONTRACT_STATE_VALID-contracten die de accept-handtekening
# van zowel provider als consumer dragen, voor precies deze serviceConnection-combinatie. Leeg bij
# geen match. De identiteit van een serviceConnection is service + provider + consumer-outway +
# thumbprint samen: op servicenaam alleen matchen zou ook het servicePublication-contract voor
# dezelfde dienst meetellen, dat op dezelfde manager staat en altijd aanwezig is.
fsc_grant_actief() {
  [ "$HAVE_JQ" -eq 1 ] || return 0
  printf '%s' "$1" | jq -r \
    --arg svc "$2" --arg prov "$3" --arg cons "$4" --arg thumb "$5" '
    [ .contracts[]?
      | select(.state == "CONTRACT_STATE_VALID" and (.has_revoked // false) == false)
      | select( ((.signatures.accept // {}) | has($prov))
                and ((.signatures.accept // {}) | has($cons)) )
      | select(any(.content.grants[]?;
            .type == "GRANT_TYPE_SERVICE_CONNECTION"
            and .service.name == $svc
            and .service.peer_id == $prov
            and .outway.peer_id == $cons
            and .outway.identification.public_key_thumbprint == $thumb))
      | .hash ] | .[]' 2>/dev/null
}

# --- federatie-helpers ------------------------------------------------------------------------
# Gebruikt door demo/environment/federatie/. Staan hier en niet in die map omdat ze door meerdere
# scripts én door de bash-unittests gedeeld worden.

# fsc_peer_var <peer>: peernaam als variabelen-achtervoegsel. Een koppelteken mag niet in een
# variabelenaam, dus `magazijn-a` wordt `magazijn_a`.
fsc_peer_var() { printf '%s' "$1" | tr '-' '_'; }

# fsc_peer_waarde <prefix> <peer>: leest `<prefix>_<peer>` uit peers.env, of leeg. Indirecte
# expansie via ${!naam} — geen eval nodig, en bash 3.2 kent die vorm al.
fsc_peer_waarde() {
  local naam
  naam="$1_$(fsc_peer_var "$2")"
  printf '%s' "${!naam:-}"
}

# fsc_alle_peers: gastheer + gasten uit peers.env, in opstartvolgorde.
fsc_alle_peers() { printf '%s %s' "$GASTHEER" "$GASTEN"; }

# fsc_manager_contracts <envdir> <peer> <adres>: de contracten van een peer via zijn INTERNE
# manager-API, met het internal-cert van diezelfde peer. De naam staat niet in /etc/hosts
# (`extra_hosts` geldt alleen binnen containers), vandaar `--resolve` op het meegegeven adres.
#
# Hier en niet in één van de aanroepers, omdat zowel de contract-bootstrap als de smokes deze
# lijst nodig hebben: twee kopieën zouden uiteen kunnen lopen in cert-pad of poort, en dan meet de
# smoke iets anders dan de bootstrap doet.
fsc_manager_contracts() {
  local envdir="$1" peer="$2" adres="$3" naam="manager.$2.fsc-test.local"

  _fsc_haal() {
    curl -sS --fail-with-body --noproxy '*' \
      --resolve "${naam}:9443:${adres}" \
      --cert "${envdir}/${peer}/pki/internal/${peer}/manager/cert.pem" \
      --key  "${envdir}/${peer}/pki/internal/${peer}/manager/key.pem" \
      --cacert "${envdir}/${peer}/pki/internal/${peer}/ca/root.pem" \
      "$1" 2>"$ERRLOG"
  }

  fsc_contracten_paginas _fsc_haal "https://${naam}:9443/v1/contracts?limit=1000"
}

# fsc_contracten_paginas <ophaler> <basis-url>: alle contracten over álle pagina's, als één
# `{"contracts":[...]}`-object op stdout. <ophaler> is de naam van een functie die één URL ophaalt
# en de body op stdout zet.
#
# WAAROM PAGINEREN. De manager (OpenFSC v2.5.2) zet `pagination.next_cursor` op élke pagina die
# rijen bevat — óók als die pagina de hele lijst is en de opgevraagde `limit` ruim gehaald wordt.
# De cursor betekent daar "er kán meer zijn", niet "er ís meer": de volgende pagina komt leeg terug
# mét een lege cursor. Wie de cursor als afkap-signaal leest, breekt dus af zodra er ook maar één
# contract bestaat. Vandaar doorlezen tot de cursor leeg is, in plaats van er conclusies aan te
# verbinden.
#
# De uitkomst is bewust weer een `{"contracts":[...]}`-object en geen kale array: alle jq erachter
# (fsc_contract_beoordeling, fsc_contract_voor_combinatie, fsc_grant_actief) toetst dat veld en de
# vorm ervan, en die controles horen te blijven gelden.
fsc_contracten_paginas() {
  local ophaler="$1" basis="$2" cursor="" url body pagina alle="[]" ronde=0

  while :; do
    if [ -n "$cursor" ]; then
      url="${basis}&cursor=$(printf '%s' "$cursor" | jq -sRr @uri)"
    else
      url="$basis"
    fi

    body="$("$ophaler" "$url")" || return 1

    # Geen jq-vangnet met `//`: een respons die geen contractenlijst is, moet hier hard stuk in
    # plaats van als lege pagina door te glippen. De aanroepers melden dat met hun eigen tekst.
    pagina="$(printf '%s' "$body" | jq -e '.contracts' 2>"$ERRLOG")" || return 1
    alle="$(jq -cn --argjson a "$alle" --argjson b "$pagina" '$a + $b')" || return 1

    cursor="$(printf '%s' "$body" | jq -r '.pagination.next_cursor? // .next_cursor? // ""')" || return 1
    [ -n "$cursor" ] || break

    # Bovengrens tegen een server die eindeloos een cursor blijft zetten: zonder deze rem zou een
    # bootstrap-lus hier blijven hangen zonder ooit iets te melden. Duizend rijen per pagina maakt
    # honderd rondes ruim genoeg voor elk realistisch deployment.
    ronde=$((ronde + 1))

    if [ "$ronde" -ge 100 ]; then
      echo "de contractenlijst geeft na ${ronde} pagina's nog een cursor — afgebroken" >"$ERRLOG"
      return 1
    fi
  done

  jq -cn --argjson c "$alle" '{contracts: $c}'
}

# fsc_component_adres <net> <component>: het adres van een component binnen het /24 van een peer,
# bv. `fsc_component_adres 127.20.2 inway` -> `127.20.2.4`. De octetten liggen vast en zijn voor
# élke peer gelijk, zodat een adres af te lezen is zonder de overlay erbij te halen.
#
# Faalt hard op een onbekende component in plaats van iets aannemelijks te verzinnen: een typefout
# zou anders een adres opleveren dat nergens luistert, en dat leest als een dode component.
fsc_component_adres() {
  local octet
  case "$2" in
    manager)       octet=1 ;;
    controller)    octet=2 ;;
    txlog)         octet=3 ;;
    inway)         octet=4 ;;
    outway)        octet=5 ;;
    stub-upstream) octet=6 ;;
    *) return 1 ;;
  esac

  [ -n "$1" ] || return 1

  printf '%s.%s' "$1" "$octet"
}

# fsc_compose_project <compose-bestand>: de projectnaam uit het `name:`-veld. Compose leidt die
# niet af zoals je zou raden (`magazijn-a` -> `fsc-magazijna`), dus lezen we 'm. Faalt hard bij een
# ontbrekend bestand of een ontbrekende `name:` — een lege projectnaam maakt elk `--filter
# label=com.docker.compose.project=` betekenisloos, en dus elke controle die daarop leunt stil.
fsc_compose_project() {
  local bestand="$1" naam
  [ -r "$bestand" ] || { echo "FAIL: compose-bestand niet leesbaar: ${bestand}" >&2; return 1; }
  naam="$(sed -n 's/^name:[[:space:]]*//p' "$bestand" | head -n1 | tr -d '"'"'"'')"
  [ -n "$naam" ] || { echo "FAIL: geen 'name:' in ${bestand}; projectnaam niet af te leiden." >&2; return 1; }
  printf '%s' "$naam"
}

# fsc_podman_api_dood <logbestand>: 0 als de log wijst op een onbereikbare podman-API-service.
# Aparte functie zodat de classificatie met fixtures te pinnen is in plaats van via netwerk-timing.
# Bewust verankerd op de bekende foutvormen van compose/podman zelf; een losse `connection refused`
# uit een containerlog (postgres die opstart) mag NIET als API-storing tellen — dat zou de retry
# overslaan en de gebruiker naar de verkeerde oorzaak sturen.
fsc_podman_api_dood() {
  grep -qE 'Cannot connect to the Docker daemon|error during connect|dial unix .*(connection refused|no such file)' "$1"
}
