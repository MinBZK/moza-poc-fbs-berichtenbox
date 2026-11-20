#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Installeert de demo op een cluster van Fundament (fbs-demo plus vier releases van fbs-dienst) en
# controleert hem: via de Gateway, en de keten tot berichten uit meer dan één magazijn. Wat het
# cluster vooraf nodig heeft en wat er publiek staat: charts/fbs-demo/README.md.
#
#   NS=<namespace> TAG=main-<sha7> charts/demo-op-fundament.sh op
#   NS=<namespace> charts/demo-op-fundament.sh controleer
#
# Instelbaar:
#   KUBE_CONTEXT    de context; zonder de huidige van kubectl, en het script noemt hem
#   GATEWAY         de Gateway in de namespace (web), met een https-listener `https`
#   BASISDOMEIN     standaard fbs.<adres-met-streepjes>.sslip.io uit het adres van de Gateway
#   ISSUER          een Issuer in de namespace; dan maakt het script het Certificate GATEWAY_SECRET
#   GATEWAY_SECRET  het Secret van het certificaat op de listener (web-tls)
#   PROEFTUIN       de berichtenbox; standaard de pin uit compose.yaml
#   GATEWAY_ADRES, HTTPS_POORT, CA_BESTAND
#                   voor de controle als het basisdomein niet naar de Gateway wijst (de sandbox)
#   EXTRA_VALUES    een map met <release>.yaml, als laatste values-bestand per release
#
# Exitcodes: 0 in orde, 1 een stap of controle faalde, 2 een voorwaarde ontbreekt.
set -euo pipefail

CHARTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$CHARTS/.." && pwd)
# shellcheck source=charts/demo-op-fundament/lib.sh
source "$CHARTS/demo-op-fundament/lib.sh"

GATEWAY=${GATEWAY:-web}
GATEWAY_SECRET=${GATEWAY_SECRET:-web-tls}
HTTPS_POORT=${HTTPS_POORT:-443}
# Een persona met berichten in magazijn A én B (Garage Van Dijk B.V.).
KETEN_ONTVANGER=${KETEN_ONTVANGER:-KVK:90000014}

stap() { echo "== $* =="; }

stop() {
  local code=$1
  shift
  echo "FOUT: $*" >&2
  exit "$code"
}

kc() { kubectl ${KUBE_CONTEXT:+--context "$KUBE_CONTEXT"} --namespace "$NS" "$@"; }
hm() { helm ${KUBE_CONTEXT:+--kube-context "$KUBE_CONTEXT"} --namespace "$NS" "$@"; }

vereis_tools() {
  local t

  for t in "$@"; do
    command -v "$t" >/dev/null || stop 2 "$t ontbreekt op het PATH"
  done
}

wacht_tot() {
  local seconden=$1 wat=$2
  shift 2
  local einde=$((SECONDS + seconden))

  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$einde" ] || { echo "time-out na ${seconden}s: $wat" >&2; return 1; }
    sleep 5
  done
}

werkmap() {
  WERK=$(mktemp -d)
  trap 'rm -rf "$WERK"' EXIT
}

# --- Voorwaarden -------------------------------------------------------------------------------

voorwaarden() {
  : "${NS:?zet NS op de namespace uit de console van Fundament, bijvoorbeeld tnt-fbs--demo}"
  echo "context: ${KUBE_CONTEXT:-$(kubectl config current-context 2>/dev/null || echo onbekend)}, namespace: $NS"
  kc get namespace "$NS" >/dev/null 2>&1 || kc get serviceaccounts >/dev/null ||
    stop 2 "namespace $NS niet bereikbaar; maak hem in de console van Fundament"
  kc get gateway "$GATEWAY" >/dev/null || stop 2 "Gateway $GATEWAY ontbreekt in $NS; zie stap 2 in charts/fbs-demo/README.md"

  if [ -z "${BASISDOMEIN:-}" ]; then
    local adres
    adres=$(kc get gateway "$GATEWAY" -o jsonpath='{.status.addresses[0].value}')
    BASISDOMEIN=$(basisdomein_uit_adres "$adres") ||
      stop 2 "geen IPv4-adres op Gateway $GATEWAY ('$adres'); zet BASISDOMEIN"
  fi

  echo "basisdomein: $BASISDOMEIN"
}

# --- Opzetten ----------------------------------------------------------------------------------

certificaat() {
  [ -n "${ISSUER:-}" ] || { echo "INFO: geen ISSUER, het certificaat van de listener blijft zoals het is"; return 0; }

  stap "certificaat $GATEWAY_SECRET via Issuer $ISSUER"
  manifest_certificaat "$NS" "$BASISDOMEIN" "$ISSUER" "$GATEWAY_SECRET" | kc apply -f - >/dev/null
  wacht_tot 600 "certificaat $GATEWAY_SECRET" certificaat_klaar || stop 1 "certificaat $GATEWAY_SECRET is niet Ready; zie: kubectl -n $NS describe certificate $GATEWAY_SECRET"
}

certificaat_klaar() {
  [ "$(kc get certificate "$GATEWAY_SECRET" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = True ]
}

database_gezond() {
  [ "$(kc get cluster.postgresql.cnpg.io "$1" -o jsonpath='{.status.phase}')" = "Cluster in healthy state" ]
}

# Een Cluster dat er al is, blijft zoals het is: een kleinere opslag of andere labels zou CloudNativePG
# willen doorvoeren. Alleen het aantal verbindingen van db gaat omhoog.
databases() {
  stap "databases (CloudNativePG)"

  if kc get cluster.postgresql.cnpg.io db >/dev/null 2>&1; then
    kc patch cluster.postgresql.cnpg.io db --type merge \
      -p '{"spec":{"postgresql":{"parameters":{"max_connections":"200"}}}}' >/dev/null
  else
    manifest_cluster db "$NS" 200 | kc create -f - >/dev/null
  fi

  kc get cluster.postgresql.cnpg.io ldv-uitvraag >/dev/null 2>&1 || manifest_cluster ldv-uitvraag "$NS" | kc create -f - >/dev/null

  local db

  for db in db ldv-uitvraag; do
    wacht_tot 600 "database $db" database_gezond "$db" ||
      stop 1 "database $db is niet gezond; zie: kubectl -n $NS describe cluster.postgresql.cnpg.io $db"
  done

  echo "OK: db en ldv-uitvraag"
}

# Maakt een Secret alleen als hij er nog niet is: een tweede run mag geen nieuw wachtwoord zetten
# naast een Redis die het oude nog gebruikt. Met create, niet apply: apply zet de inhoud ook in een
# annotatie.
secret_eenmalig() {
  local naam=$1
  shift

  kc get secret "$naam" >/dev/null 2>&1 && return 0
  kc create secret generic "$naam" "$@" >/dev/null
  echo "OK: Secret $naam gemaakt"
}

geheimen() {
  stap "geheimen"
  # Als bestand, niet op de opdrachtregel: die is via ps zichtbaar voor andere gebruikers. Zonder
  # afsluitende regelovergang: die kwam anders in de env van de uitvraag, maar niet in de
  # --requirepass van Redis, en dan weigert Redis het wachtwoord.
  (umask 077 && openssl rand -hex 32 | tr -d '\n' >"$WERK/redis-wachtwoord" &&
    openssl rand -hex 32 | tr -d '\n' >"$WERK/beheer-token")
  secret_eenmalig fbs-demo-geheimen --from-file=REDIS_PASSWORD="$WERK/redis-wachtwoord" \
    --from-file=BEHEER_TOKEN="$WERK/beheer-token"

  # Het wachtwoord van het paneel staat naast het htpasswd in het Secret, zodat het team het terug
  # kan lezen. Op het fieldlab kan iedereen in de organisatie dat ook; het is een demo-wachtwoord.
  if ! kc get secret fbs-demo-bediening >/dev/null 2>&1; then
    (umask 077 && openssl rand -base64 24 | tr -d '\n' >"$WERK/paneel-wachtwoord")
    printf 'demo:%s\n' "$(openssl passwd -6 -stdin <"$WERK/paneel-wachtwoord")" >"$WERK/htpasswd"
    secret_eenmalig fbs-demo-bediening --from-file=htpasswd="$WERK/htpasswd" --from-file=wachtwoord="$WERK/paneel-wachtwoord"
  fi

  # Keystore en wachtwoord horen bij elkaar; ontbreekt er één, dan beide opnieuw. Een eigen sleutel
  # voor dit cluster: met die van een andere omgeving kan iedereen in de organisatie een
  # stelseldocument tekenen dat die omgeving vertrouwt.
  if ! kc get secret stelselregister-keystore stelselregister-geheimen >/dev/null 2>&1; then
    mkdir -p "$WERK/pki"
    cp "$REPO/demo/environment/stelselregister/pki/maak-keten.sh" "$WERK/pki/"
    (cd "$WERK/pki" && STELSELDOCUMENT_OMGEVING=demo ./maak-keten.sh >/dev/null)
    kc delete secret stelselregister-keystore stelselregister-geheimen --ignore-not-found >/dev/null
    secret_eenmalig stelselregister-keystore --from-file=keystore.p12="$WERK/pki/out/keystore.p12"
    secret_eenmalig stelselregister-geheimen --from-file=STELSELDOCUMENT_KEYSTORE_WACHTWOORD="$WERK/pki/out/wachtwoord"
  fi
}

# Het register en de set van de simulator, in een kopie: de generator schrijft altijd naar
# demo/generated/, en daar staat het register van een lokale compose-demo.
register() {
  mkdir -p "$WERK/repo/demo" "$WERK/repo/services/berichtenuitvraag/src/main/resources"
  cp "$REPO/demo/genereer-magazijnen.py" "$WERK/repo/demo/"
  cp "$REPO/services/berichtenuitvraag/src/main/resources/application.properties" \
    "$WERK/repo/services/berichtenuitvraag/src/main/resources/"
  # shellcheck disable=SC2016 # de uitdrukking is voor Quarkus, niet voor de shell
  SIMULATOR_URL='${MAGAZIJN_SIMULATOR_URL}' python3 -I "$WERK/repo/demo/genereer-magazijnen.py" >/dev/null
  REGISTER="$WERK/repo/demo/generated/magazijnen-register.properties"
  SIMULATOR_SET="$WERK/repo/demo/generated/magazijn-simulator.properties"
}

extra() {
  local bestand=${EXTRA_VALUES:-}/$1.yaml

  [ -n "${EXTRA_VALUES:-}" ] && [ -f "$bestand" ] && printf '%s\n' -f "$bestand"
  return 0
}

installeren() {
  : "${TAG:?zet TAG op een main-<sha7> waarvoor alle acht images bestaan}"
  local proeftuin=${PROEFTUIN:-$("$REPO/.github/scripts/proeftuin-image.sh")}
  local args

  stap "images op $TAG"
  "$REPO/.github/scripts/images-gepubliceerd.sh" ghcr.io/minbzk "$TAG" "${DEMO_IMAGES[@]}" ||
    stop 2 "niet alle images bestaan op $TAG; kies een main-<sha7> waarvoor de keten ze alle acht publiceerde"

  register

  stap "fbs-demo"
  mapfile -t args < <(extra fbs-demo)
  hm upgrade --install fbs-demo "$CHARTS/fbs-demo" --wait --timeout 10m \
    --set "basisdomein=$BASISDOMEIN" --set "cluster.gateway.name=$GATEWAY" \
    --set "images.tag=$TAG" --set "images.proeftuin=$proeftuin" \
    --set-file "magazijnenregister=$REGISTER" --set-file "simulatorSet=$SIMULATOR_SET" "${args[@]}" >/dev/null
  echo "OK: fbs-demo"

  # De uitvraag en het stelselregister lezen het register alleen bij het starten.
  local release dienst
  local stelsel=(--wait --timeout 10m --set "demo.basisdomein=$BASISDOMEIN" --set "dienst.image.tag=$TAG"
    --set dienst.tlsTermination=mesh --set "cluster.gateway.name=$GATEWAY")

  for release in "${STELSEL_RELEASES[@]}"; do
    stap "$release"
    dienst=${release%-[ab]}
    args=(-f "$CHARTS/fbs-dienst/diensten/$dienst.yaml")

    if [ "$dienst" = berichtenmagazijn ]; then
      args+=(-f "$CHARTS/voorbeelden/berichtenmagazijn-cnpg.yaml" -f "$CHARTS/fbs-demo/stelsel/berichtenmagazijn.yaml")
    fi

    args+=(-f "$CHARTS/fbs-demo/stelsel/$release.yaml")
    [ "$release" = berichtenuitvraag ] || args+=(--set "ingress.hostname=$release.$BASISDOMEIN")
    mapfile -t -O "${#args[@]}" args < <(extra "$release")

    hm upgrade --install "$release" "$CHARTS/fbs-dienst" "${stelsel[@]}" "${args[@]}" >/dev/null ||
      stop 1 "installeren van $release faalde; zie: kubectl -n $NS get pods -l app.kubernetes.io/instance=$release"
    echo "OK: $release"
  done

  kc rollout restart deployment/berichtenuitvraag deployment/stelselregister >/dev/null
  kc rollout status deployment/berichtenuitvraag --timeout=10m >/dev/null
  kc rollout status deployment/stelselregister --timeout=10m >/dev/null
}

# --- Controleren -------------------------------------------------------------------------------

fouten=0

fout() {
  fouten=$((fouten + 1))
  echo "FOUT: $*"
}

# curl via de Gateway naar <host>.<basisdomein>. Met GATEWAY_ADRES gaat het verkeer naar dat adres,
# met de hostnaam in SNI en Host; anders via DNS. Het certificaat wordt getoetst: een TLS-fout geeft
# 000, geen stille -k. Een latere --max-time in de argumenten gaat voor.
gw_curl() {
  local host=$1.$BASISDOMEIN
  shift

  curl -q -s --max-time 30 ${CA_BESTAND:+--cacert "$CA_BESTAND"} \
    ${GATEWAY_ADRES:+--resolve "$host:$HTTPS_POORT:$GATEWAY_ADRES"} "$@"
}

# De statuscode van een verzoek. $1 = host, $2 = verwachte code, $3 = beschrijving, rest = curl.
code() {
  local host=$1 verwacht=$2 wat=$3 gekregen
  shift 3

  gekregen=$(gw_curl "$host" -o /dev/null -w '%{http_code}' "$@" || true)

  if [ "$gekregen" = "$verwacht" ]; then
    echo "OK: $wat ($gekregen)"
  else
    fout "$wat: $gekregen, verwacht $verwacht"
  fi
}

url() { echo "https://$1.$BASISDOMEIN:$HTTPS_POORT$2"; }

controleer_rollouts() {
  stap "rollouts"
  local d

  for d in $(kc get deployments -o name -l 'app.kubernetes.io/instance in (fbs-demo,berichtenuitvraag,berichtenmagazijn-a,berichtenmagazijn-b,stelselregister)'); do
    if kc rollout status "$d" --timeout=5m >/dev/null 2>&1; then
      echo "OK: $d"
    else
      fout "$d is niet gereed; zie: kubectl -n $NS describe $d"
    fi
  done
}

controleer_routes() {
  stap "via de Gateway"
  code demo 200 "berichtenbox" "$(url demo /moza/berichtenbox/)"
  code demo 200 "personalijst zonder wachtwoord" "$(url demo /api/demo/personas)"
  code demo 401 "paneel vraagt een wachtwoord" "$(url demo /bediening/)"
  code demo 401 "legen vraagt een wachtwoord" -X POST "$(url demo /api/demo/legen)"
  code demo 403 "paneel weigert een andere site" -H 'Sec-Fetch-Site: cross-site' "$(url demo /bediening/)"
  code demo 403 "gereedschap weigert een andere origin" -X POST -H 'Origin: https://elders.example' "$(url demo /api/demo/legen)"
  code demo 404 "aanmeld-webhook niet via de berichtenbox" -X POST "$(url demo /api/v1/aanmeldingen)"
  code berichtenmagazijn-a 200 "magazijn A: OpenAPI-document" "$(url berichtenmagazijn-a /openapi.json)"
  code berichtenmagazijn-b 200 "magazijn B: OpenAPI-document" "$(url berichtenmagazijn-b /openapi.json)"
  code berichtenmagazijn-a 404 "magazijn A: aanleveren niet van buiten" -X POST "$(url berichtenmagazijn-a /api/v1/aanleveringen)"
  code berichtenmagazijn-a 404 "magazijn A: /q niet van buiten" "$(url berichtenmagazijn-a /q/health/ready)"
  code simulator 404 "simulator: aanleveren niet van buiten" -X POST "$(url simulator /magazijn/00000009000000000001/api/v1/aanleveringen)"
  code simulator 404 "simulator: beheer niet van buiten" "$(url simulator /beheer/magazijnen)"
  code stelselregister 200 "stelseldocument" "$(url stelselregister /openapi.json)"
}

# De keten zoals een bezoeker hem ziet: een sessie, een ophaalronde, en berichten uit meer dan één
# magazijn. Het paneel vult de magazijnen kort na het starten, dus een paar pogingen.
controleer_keten() {
  stap "keten: berichten uit meer dan één magazijn voor $KETEN_ONTVANGER"
  local antwoord sessie aantal=0

  for _ in $(seq 1 20); do
    antwoord=$(gw_curl demo -X POST -H "X-Ontvanger: $KETEN_ONTVANGER" "$(url demo /api/v1/sessies)" || true)

    if sessie=$(sessie_uit_antwoord "$antwoord"); then
      # De ophaalronde is een stream die eindigt als elk magazijn geantwoord heeft.
      # Het sessie-id via een header-bestand: een argument van curl is via ps zichtbaar.
      gw_curl demo -o /dev/null --max-time 120 -H "X-Ontvanger: $KETEN_ONTVANGER" \
        -H @<(printf 'X-Sessie: %s\n' "$sessie") -H 'Accept: text/event-stream' \
        "$(url demo /api/v1/berichten/_ophalen)" || true
      antwoord=$(gw_curl demo -H "X-Ontvanger: $KETEN_ONTVANGER" -H @<(printf 'X-Sessie: %s\n' "$sessie") \
        "$(url demo /api/v1/berichten)" || true)
      aantal=$(aantal_magazijnen "$antwoord" || echo 0)
      [ "$aantal" -ge 2 ] && break
    fi

    sleep 15
  done

  if [ "$aantal" -ge 2 ]; then
    echo "OK: berichten uit $aantal magazijnen"
  else
    fout "de berichtenlijst noemt $aantal magazijn(en), verwacht minstens 2; zie: kubectl -n $NS logs deploy/berichtenuitvraag"
  fi
}

controleer() {
  controleer_rollouts
  controleer_routes
  controleer_keten

  stap "uitkomst"

  if [ "$fouten" -eq 0 ]; then
    echo "OK: de demo draait op $(url demo /moza/berichtenbox/)"
    echo "    paneel: $(url demo /bediening/) (gebruiker demo; wachtwoord:"
    echo "    kubectl ${KUBE_CONTEXT:+--context $KUBE_CONTEXT }-n $NS get secret fbs-demo-bediening -o jsonpath='{.data.wachtwoord}' | base64 -d)"
    return 0
  fi

  echo "$fouten fout(en)"
  return 1
}

main() {
  case "${1:-}" in
    op)
      vereis_tools kubectl helm openssl python3 curl jq docker
      werkmap
      voorwaarden
      certificaat
      databases
      geheimen
      installeren
      controleer
      ;;
    controleer)
      vereis_tools kubectl curl jq
      voorwaarden
      controleer
      ;;
    *)
      echo "gebruik: NS=<namespace> [TAG=main-<sha7>] $0 op | controleer" >&2
      exit 2
      ;;
  esac
}

# Gesourcet (door de tests) draait er niets.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
