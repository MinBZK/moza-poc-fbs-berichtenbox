#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Installeert de demo op een cluster van Fundament (fbs-demo plus vier releases van fbs-dienst) en
# controleert hem: via de Gateway, en de keten tot een ophaalronde waarin magazijn A en B berichten
# leveren. Wat het cluster vooraf nodig heeft en wat er publiek staat: charts/fbs-demo/README.md.
#
#   NS=<namespace> TAG=main-<sha7> charts/demo-op-fundament.sh op
#   NS=<namespace> charts/demo-op-fundament.sh controleer
#   NS=<namespace> charts/demo-op-fundament.sh aanmelden <regels> [<ontvanger>...]
#   NS=<namespace> charts/demo-op-fundament.sh afmelden <OIN>
#
# aanmelden toetst eerst of een magazijn dat er later bij komt (de plug-in berichtenmagazijn)
# antwoordt, zet het dan in het register van uitvraag en stelselregister, en geeft ontvangers
# (KVK:<nummer>, BSN:<nummer>) een opt-in ervoor in de profielstub. <regels> is een bestand met de twee regels uit het statusbericht van de
# plug-in, of - voor stdin. afmelden haalt het weer weg. Beide werken via de values van fbs-demo,
# met de chart uit deze checkout; `op` neemt ze daarna over. Zie charts/fbs-demo/README.md.
#
# Instelbaar:
#   KUBE_CONTEXT    de context; zonder de huidige van kubectl. Het script zet hem bij de start vast,
#                   zodat een wissel in een andere terminal halverwege niets naar elders stuurt
#   GATEWAY         de Gateway in de namespace (web), met een https-listener `https`
#   BASISDOMEIN     standaard fbs.<adres-met-streepjes>.sslip.io uit het adres van de Gateway
#   ISSUER          een Issuer in de namespace; dan maakt het script het Certificate GATEWAY_SECRET
#   GATEWAY_SECRET  het Secret van het certificaat op de listener (web-tls)
#   PORTAAL_IMAGE   de Berichtenbox; standaard de pin uit compose.yaml
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
# Een persona met berichten in magazijn A én B (Garage Van Dijk B.V.): de id in het portaal, en
# zijn nummer bij de keten.
KETEN_PERSONA=${KETEN_PERSONA:-proeftuin-garage}
KETEN_ONTVANGER=${KETEN_ONTVANGER:-KVK:90000014}
# Hoe lang de ketencontrole het probeert: het paneel vult de magazijnen kort na het starten.
KETEN_SECONDEN=${KETEN_SECONDEN:-300}

stap() { echo "== $* =="; }

stop() {
  local code=$1
  shift
  echo "FOUT: $*" >&2
  exit "$code"
}

kc() { kubectl --context "$KUBE_CONTEXT" --namespace "$NS" "$@"; }
hm() { helm --kube-context "$KUBE_CONTEXT" --namespace "$NS" "$@"; }

vereis_tools() {
  local t

  for t in "$@"; do
    command -v "$t" >/dev/null || stop 2 "$t ontbreekt op het PATH"
  done
}

# Wacht tot een opdracht slaagt. De foutuitvoer van de laatste poging komt in de melding: een
# weigering (forbidden) of een ontbrekende CRD leest anders na tien minuten als "niet gereed".
wacht_tot() {
  local seconden=$1 wat=$2
  shift 2
  local einde=$((SECONDS + seconden))

  until "$@" >/dev/null 2>"$WERK/wacht.err"; do
    if [ "$SECONDS" -ge "$einde" ]; then
      echo "time-out na ${seconden}s: $wat" >&2
      tail -3 "$WERK/wacht.err" >&2
      return 1
    fi

    sleep 5
  done
}

werkmap() {
  WERK=$(mktemp -d)
  trap opruimen EXIT
}

# Ook na Ctrl-C (bash draait de EXIT-trap ook bij INT, TERM en HUP): een sessie die openblijft, telt mee tot de inactiviteitstermijn verloopt, en de
# uitvraag staat er vijf per ontvanger toe.
opruimen() {
  [ -z "${SESSIE_OPEN:-}" ] || sessie_beeindigen stil
  rm -rf "$WERK"
}

# --- Voorwaarden -------------------------------------------------------------------------------

# De context één keer vastzetten: kc en hm lezen de kubeconfig anders bij elke aanroep opnieuw, en een
# run duurt lang genoeg voor een wissel in een andere terminal.
context_vastzetten() {
  if [ -z "${KUBE_CONTEXT:-}" ]; then
    KUBE_CONTEXT=$(kubectl config current-context 2>/dev/null) || stop 2 "geen huidige kube-context; zet KUBE_CONTEXT"
  fi

  readonly KUBE_CONTEXT
}

voorwaarden() {
  [ -n "${NS:-}" ] || stop 2 "zet NS op de namespace uit de console van Fundament, bijvoorbeeld tnt-fbs--demo"
  context_vastzetten
  echo "context: $KUBE_CONTEXT, namespace: $NS"

  if [ -n "${EXTRA_VALUES:-}" ]; then
    [ -d "$EXTRA_VALUES" ] || stop 2 "EXTRA_VALUES ($EXTRA_VALUES) is geen map"
    local bestand naam

    # Een verkeerd genoemd bestand viel anders stil weg.
    for bestand in "$EXTRA_VALUES"/*.yml; do
      [ -e "$bestand" ] || continue
      stop 2 "EXTRA_VALUES: $(basename "$bestand") heeft .yml; het script leest alleen <release>.yaml"
    done

    for bestand in "$EXTRA_VALUES"/*.yaml; do
      [ -e "$bestand" ] || continue
      naam=$(basename "$bestand" .yaml)
      [[ " fbs-demo ${STELSEL_RELEASES[*]} " == *" $naam "* ]] ||
        stop 2 "EXTRA_VALUES: $naam.yaml hoort bij geen release (fbs-demo ${STELSEL_RELEASES[*]})"
    done
  fi

  kc get serviceaccounts >/dev/null || stop 2 "namespace $NS niet bereikbaar; maak hem in de console van Fundament"
  kc get gateways.gateway.networking.k8s.io "$GATEWAY" >/dev/null ||
    stop 2 "Gateway $GATEWAY ontbreekt in $NS; zie stap 2 in charts/fbs-demo/README.md"

  if [ -z "${BASISDOMEIN:-}" ]; then
    local adres
    adres=$(kc get gateways.gateway.networking.k8s.io "$GATEWAY" -o jsonpath='{.status.addresses[0].value}')
    BASISDOMEIN=$(basisdomein_uit_adres "$adres") ||
      stop 2 "geen IPv4-adres op Gateway $GATEWAY ('$adres'); zet BASISDOMEIN"
  fi

  echo "basisdomein: $BASISDOMEIN"
}

# Alle acht images op de tag. Met docker manifest inspect, dat ook podman kent; de stderr van het
# eerste ontbrekende image staat in de melding, zodat een ontbrekende login niet als "bestaat niet"
# leest.
images_op_tag() {
  stap "images op $TAG"
  local image ontbreekt=()

  for image in "${DEMO_IMAGES[@]}"; do
    if docker manifest inspect "ghcr.io/minbzk/$image:$TAG" >/dev/null 2>"$WERK/image.err"; then
      echo "OK: $image"
    else
      [ "${#ontbreekt[@]}" -gt 0 ] || cp "$WERK/image.err" "$WERK/eerste.err"
      ontbreekt+=("$image")
    fi
  done

  if [ "${#ontbreekt[@]}" -gt 0 ]; then
    tail -3 "$WERK/eerste.err" >&2
    stop 2 "niet op $TAG: ${ontbreekt[*]}; kies een main-<sha7> waarvoor de keten ze alle acht publiceerde"
  fi
}

# --- Opzetten ----------------------------------------------------------------------------------

certificaat() {
  [ -n "${ISSUER:-}" ] || { echo "INFO: geen ISSUER, het certificaat van de listener blijft zoals het is"; return 0; }

  stap "certificaat $GATEWAY_SECRET via Issuer $ISSUER"
  manifest_certificaat "$NS" "$BASISDOMEIN" "$ISSUER" "$GATEWAY_SECRET" | kc apply -f - >/dev/null
  wacht_tot 600 "certificaat $GATEWAY_SECRET" certificaat_klaar ||
    stop 1 "certificaat $GATEWAY_SECRET is niet Ready; zie: kubectl --context $KUBE_CONTEXT -n $NS describe certificate $GATEWAY_SECRET"
}

certificaat_klaar() {
  [ "$(kc get certificate "$GATEWAY_SECRET" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = True ]
}

database_fase() { kc get cluster.postgresql.cnpg.io "$1" -o jsonpath='{.status.phase}'; }
database_gezond() { [ "$(database_fase "$1")" = "Cluster in healthy state" ]; }

# De waarde die PostgreSQL zelf gebruikt, niet die in de spec: CloudNativePG past hem pas toe na een
# herstart. Op de primary uit de status: na een nieuwe instantie heet die niet meer <cluster>-1.
max_connections_actief() {
  local primary
  primary=$(kc get cluster.postgresql.cnpg.io db -o jsonpath='{.status.currentPrimary}') && [ -n "$primary" ] || return 1
  [ "$(kc exec "$primary" -c postgres -- psql -tAc 'show max_connections' | tr -d '[:space:]')" -ge 200 ]
}

# Of een Cluster er is. Een fout van kubectl is een stop, geen "is er niet".
cluster_bestaat() {
  local uit
  uit=$(kc get cluster.postgresql.cnpg.io "$1" -o name --ignore-not-found) || stop 1 "Cluster $1 niet op te vragen"
  [ -n "$uit" ]
}

# Een Cluster dat er al is, blijft zoals het is: een kleinere opslag of andere labels zou CloudNativePG
# willen doorvoeren. Alleen het aantal connections van db gaat omhoog, nooit omlaag.
databases() {
  stap "databases (CloudNativePG)"

  if cluster_bestaat db; then
    local huidig
    huidig=$(kc get cluster.postgresql.cnpg.io db -o jsonpath='{.spec.postgresql.parameters.max_connections}')

    if [ "${huidig:-100}" -lt 200 ]; then
      kc patch cluster.postgresql.cnpg.io db --type merge \
        -p '{"spec":{"postgresql":{"parameters":{"max_connections":"200"}}}}' >/dev/null
      # CloudNativePG herstart de database voor deze parameter. Wachten tot PostgreSQL de waarde
      # gebruikt: anders valt de herstart midden in de installatie van de magazijnen, en lopen hun
      # 134 connections tegen de oude grens van 100.
      wacht_tot 600 "max_connections 200 in db" max_connections_actief ||
        stop 1 "db gebruikt max_connections 200 niet; zie: kubectl --context $KUBE_CONTEXT -n $NS describe cluster.postgresql.cnpg.io db"
    fi
  else
    manifest_cluster db "$NS" 200 | kc create -f - >/dev/null
  fi

  cluster_bestaat ldv-uitvraag || manifest_cluster ldv-uitvraag "$NS" | kc create -f - >/dev/null

  local db

  for db in db ldv-uitvraag; do
    wacht_tot 600 "database $db" database_gezond "$db" ||
      stop 1 "database $db is niet gezond; zie: kubectl --context $KUBE_CONTEXT -n $NS describe cluster.postgresql.cnpg.io $db"
  done

  echo "OK: db en ldv-uitvraag"
}

# Of een Secret er is. Een fout van kubectl (geen rechten, een time-out) is een stop, geen "is er
# niet": anders maakte het script een bestaand geheim, zoals de ondertekensleutel, stil opnieuw.
secret_bestaat() {
  local uit
  uit=$(kc get secret "$1" -o name --ignore-not-found) || stop 1 "Secret $1 niet op te vragen"
  [ -n "$uit" ]
}

# Maakt een Secret alleen als hij er nog niet is: een tweede run mag geen nieuw wachtwoord zetten
# naast een Redis die het oude nog gebruikt. Met create, niet apply: apply zet de inhoud ook in een
# annotatie.
secret_eenmalig() {
  local naam=$1
  shift

  ! secret_bestaat "$naam" || return 0
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

  # Eigen Secret, zodat een installatie van vóór het portaal hem er alsnog bij krijgt: een key
  # toevoegen aan fbs-demo-geheimen doet secret_eenmalig niet.
  (umask 077 && openssl rand -base64 32 | tr -d '\n' >"$WERK/auth-geheim")
  secret_eenmalig fbs-demo-portaal --from-file=AUTH_SECRET="$WERK/auth-geheim"

  # Het wachtwoord van het paneel staat naast het htpasswd in het Secret, zodat het team het terug
  # kan lezen. Op het fieldlab kan iedereen in de organisatie dat ook; het is een demo-wachtwoord.
  if ! secret_bestaat fbs-demo-bediening; then
    local hash
    (umask 077 && openssl rand -base64 24 | tr -d '\n' >"$WERK/paneel-wachtwoord")
    # Een openssl zonder -6 (LibreSSL) gaf anders een htpasswd zonder hash, en dat Secret maakt
    # een volgende run niet opnieuw.
    hash=$(openssl passwd -6 -stdin <"$WERK/paneel-wachtwoord") ||
      stop 2 "openssl passwd -6 faalde; gebruik OpenSSL 1.1.1 of nieuwer"
    [[ $hash == '$6$'* ]] || stop 2 "openssl passwd -6 gaf geen SHA-512-hash; gebruik OpenSSL 1.1.1 of nieuwer"
    printf 'demo:%s\n' "$hash" >"$WERK/htpasswd"
    secret_eenmalig fbs-demo-bediening --from-file=htpasswd="$WERK/htpasswd" --from-file=wachtwoord="$WERK/paneel-wachtwoord"
  fi

  # Keystore en wachtwoord horen bij elkaar; ontbreekt er één, dan beide opnieuw. Een eigen sleutel
  # voor dit cluster: met die van een andere omgeving kan iedereen in de organisatie een
  # stelseldocument tekenen dat die omgeving vertrouwt.
  if ! secret_bestaat stelselregister-keystore || ! secret_bestaat stelselregister-geheimen; then
    mkdir -p "$WERK/pki"
    cp "$REPO/demo/environment/stelselregister/pki/maak-keten.sh" "$WERK/pki/"
    (cd "$WERK/pki" && STELSELDOCUMENT_OMGEVING=demo ./maak-keten.sh >/dev/null)
    kc delete secret stelselregister-keystore stelselregister-geheimen --ignore-not-found >/dev/null
    kc create secret generic stelselregister-keystore --from-file=keystore.p12="$WERK/pki/out/keystore.p12" >/dev/null
    kc create secret generic stelselregister-geheimen \
      --from-file=STELSELDOCUMENT_KEYSTORE_WACHTWOORD="$WERK/pki/out/wachtwoord" >/dev/null
    echo "OK: keystore en wachtwoord van het stelselregister gemaakt"
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

# Het laatste values-bestand van een release uit EXTRA_VALUES, als `-f <bestand>` op twee regels.
extra() {
  local bestand=${EXTRA_VALUES:-}/$1.yaml

  if [ -n "${EXTRA_VALUES:-}" ] && [ -f "$bestand" ]; then
    echo "INFO: extra values voor $1: $bestand" >&2
    printf '%s\n' -f "$bestand"
  fi
}

# De aangemelde magazijnen uit de release, als values-bestand: een upgrade zonder gooit weg wat
# `aanmelden` zette. Zet AANMELD_ARGS op `-f <bestand>`, of leeg bij een eerste installatie.
aanmeldingen_behouden() {
  local values
  AANMELD_ARGS=()

  if ! values=$(hm get values fbs-demo -o json 2>"$WERK/values.err"); then
    grep -q 'not found' "$WERK/values.err" && return 0
    stop 1 "de values van fbs-demo zijn niet te lezen: $(tail -1 "$WERK/values.err")"
  fi

  jq -e '.aanmeldingen.register // "" | length > 0' <<<"$values" >/dev/null || return 0
  jq '{aanmeldingen}' <<<"$values" >"$WERK/aanmeldingen.json"
  echo "INFO: aangemelde magazijnen blijven staan: $(jq -r '.aanmeldingen.register | [scan("magazijnen\\.\"([0-9]{20})\"\\.url")[0]] | join(", ")' "$WERK/aanmeldingen.json")"
  AANMELD_ARGS=(-f "$WERK/aanmeldingen.json")
}

installeren() {
  local portaal args
  # portaal-image.sh leest PORTAAL_IMAGE zelf, met dezelfde vormcontrole als voor de pin.
  portaal=$("$REPO/.github/scripts/portaal-image.sh") || stop 2 "portaal-image.sh faalde"

  register
  aanmeldingen_behouden

  stap "fbs-demo"
  # Na de aanmeldingen: EXTRA_VALUES gaat voor.
  args=("${AANMELD_ARGS[@]}")
  mapfile -t -O "${#args[@]}" args < <(extra fbs-demo)
  hm upgrade --install fbs-demo "$CHARTS/fbs-demo" --wait --timeout 10m \
    --set "basisdomein=$BASISDOMEIN" --set "cluster.gateway.name=$GATEWAY" \
    --set "images.tag=$TAG" --set "images.portaal=$portaal" \
    --set-file "magazijnenregister=$REGISTER" --set-file "simulatorSet=$SIMULATOR_SET" "${args[@]}" >/dev/null ||
    stop 1 "installeren van fbs-demo faalde; zie: kubectl --context $KUBE_CONTEXT -n $NS get pods -l app.kubernetes.io/instance=fbs-demo"
  echo "OK: fbs-demo"

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
      stop 1 "installeren van $release faalde; zie: kubectl --context $KUBE_CONTEXT -n $NS get pods -l app.kubernetes.io/instance=$release"
    echo "OK: $release"
  done

  # De uitvraag en het stelselregister lezen het register alleen bij het starten; een upgrade zonder
  # wijziging in hun eigen values start ze niet opnieuw.
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

url() { echo "https://$1.$BASISDOMEIN:$HTTPS_POORT$2"; }

# curl via de Gateway naar <host>.<basisdomein>. Met GATEWAY_ADRES gaat het verkeer naar dat adres,
# met de hostnaam in SNI en Host; anders via DNS. Het certificaat wordt getoetst: een TLS-fout geeft
# 000, geen stille -k. Een latere --max-time in de argumenten gaat voor.
gw_curl() {
  local host=$1.$BASISDOMEIN
  shift

  curl -q -sS --max-time 30 ${CA_BESTAND:+--cacert "$CA_BESTAND"} \
    ${GATEWAY_ADRES:+--resolve "$host:$HTTPS_POORT:$GATEWAY_ADRES"} "$@"
}

# De statuscode van een verzoek. $1 = host, $2 = verwachte code, $3 = beschrijving, rest = curl.
code() {
  local host=$1 verwacht=$2 wat=$3 gekregen
  shift 3

  gekregen=$(gw_curl "$host" -o /dev/null -w '%{http_code}' "$@" 2>"$WERK/curl.err" || true)

  if [ "$gekregen" = "$verwacht" ]; then
    echo "OK: $wat ($gekregen)"
  else
    fout "$wat: $gekregen, verwacht $verwacht $(tail -1 "$WERK/curl.err" 2>/dev/null)"
  fi
}

controleer_rollouts() {
  stap "rollouts"
  local namen d

  namen=$(kc get deployments -o name) || { fout "Deployments in $NS niet op te vragen"; return; }

  for d in "${DEMO_DEPLOYMENTS[@]}" "${STELSEL_RELEASES[@]}"; do
    if ! grep -qx "deployment.apps/$d" <<<"$namen"; then
      fout "Deployment $d ontbreekt"
    elif kc rollout status "deployment/$d" --timeout=5m >/dev/null 2>"$WERK/rollout.err"; then
      echo "OK: $d"
    else
      fout "$d is niet gereed ($(tail -1 "$WERK/rollout.err")); zie: kubectl --context $KUBE_CONTEXT -n $NS describe deployment/$d"
    fi
  done
}

controleer_routes() {
  stap "via de Gateway"
  # Zonder persona-cookie stuurt het portaal naar de persona-keuze.
  code demo 307 "Berichtenbox van het portaal" "$(url demo /berichtenbox)"
  code demo 200 "personalijst zonder wachtwoord" "$(url demo /api/demo/personas)"
  code demo 401 "paneel vraagt een wachtwoord" "$(url demo /bediening/)"
  code demo 401 "legen vraagt een wachtwoord" -X POST "$(url demo /api/demo/legen)"
  code demo 403 "paneel weigert een andere site" -H 'Sec-Fetch-Site: cross-site' "$(url demo /bediening/)"
  code demo 403 "gereedschap weigert een andere origin" -X POST -H 'Origin: https://elders.example' "$(url demo /api/demo/legen)"
  # De demo-proxy heeft geen route naar de uitvraag; het portaal kent dit pad niet.
  code demo 404 "aanmeld-webhook niet via de Berichtenbox" -X POST "$(url demo /api/v1/aanmeldingen)"
  # De doorgifte van het portaal laat alleen de paden van de Berichtenbox door; een nieuwe versie die
  # ruimer doorgeeft, zou de webhook (zonder sessie) alsnog bereikbaar maken.
  code demo 404 "aanmeld-webhook niet via de ketenroute van het portaal" -X POST "$(url demo /api/keten/aanmeldingen)"

  # Met het wachtwoord uit het Secret, als header-bestand: een argument van curl is via ps zichtbaar.
  local wachtwoord
  wachtwoord=$(kc get secret fbs-demo-bediening -o jsonpath='{.data.wachtwoord}' | base64 -d) || wachtwoord=""

  if [ -z "$wachtwoord" ]; then
    # Met de hand gemaakt volgens de README staat het wachtwoord in de kluis, niet in het Secret.
    fout "Secret fbs-demo-bediening heeft geen key wachtwoord; het paneel met wachtwoord is niet getoetst"
  else
    code demo 200 "paneel met het wachtwoord" \
      -H @<(printf 'Authorization: Basic %s\n' "$(printf 'demo:%s' "$wachtwoord" | base64 | tr -d '\n')") "$(url demo /bediening/)"
  fi

  code berichtenmagazijn-a 200 "magazijn A: OpenAPI-document" "$(url berichtenmagazijn-a /openapi.json)"
  code berichtenmagazijn-b 200 "magazijn B: OpenAPI-document" "$(url berichtenmagazijn-b /openapi.json)"
  code berichtenmagazijn-a 404 "magazijn A: aanleveren niet van buiten" -X POST "$(url berichtenmagazijn-a /api/v1/aanleveringen)"
  code berichtenmagazijn-a 404 "magazijn A: /q niet van buiten" "$(url berichtenmagazijn-a /q/health/ready)"
  # Een 404 of 503 van Envoy zegt ook "geen route" of "geen pod". Een gesimuleerd magazijn zonder
  # X-Ontvanger geeft een 400 van de simulator zelf, en die maakt Envoy niet.
  code simulator 400 "simulator: berichten lezen bereikt de simulator" "$(url simulator "/magazijn/$SIMULATOR_OIN/api/v1/berichten")"
  code simulator 404 "simulator: aanleveren niet van buiten" -X POST "$(url simulator "/magazijn/$SIMULATOR_OIN/api/v1/aanleveringen")"
  code simulator 404 "simulator: beheer niet van buiten" "$(url simulator /beheer/magazijnen)"
  code stelselregister 200 "stelselregister: het stelseldocument" "$(url stelselregister /api/v1/stelseldocument)"
}

# De keten zoals een bezoeker hem ziet, via het portaal: een sessie, en ophaalrondes tot magazijn A
# en B berichten leveren. Eén sessie voor alle pogingen, en die wordt beëindigd: de uitvraag staat
# maar vijf sessies per ontvanger toe, en een achtergebleven sessie houdt een bezoeker met deze
# persona buiten.
controleer_keten() {
  stap "keten: ophaalronde voor $KETEN_PERSONA via het portaal"
  local sessie code_sessie status curl_code reden="" einde=$((SECONDS + KETEN_SECONDEN))

  : >"$WERK/sessie.kop"
  code_sessie=$(gw_curl demo -X POST -o /dev/null -D "$WERK/sessie.kop" -w '%{http_code}' \
    -H "Cookie: personaId=$KETEN_PERSONA" "$(url demo /api/keten/sessies)" 2>"$WERK/curl.err" || true)

  if [ "$code_sessie" != 201 ] || ! sessie=$(sessie_cookie_uit_kop "$WERK/sessie.kop"); then
    fout "geen sessie: POST /api/keten/sessies gaf $code_sessie$([ "$code_sessie" != 201 ] || echo ' zonder sessie-cookie'); $(tail -1 "$WERK/curl.err")"
    return
  fi

  # Het sessie-cookie via een header-bestand: een argument van curl is via ps zichtbaar.
  printf 'Cookie: personaId=%s; %s\n' "$KETEN_PERSONA" "$sessie" >"$WERK/sessie.hdr"
  SESSIE_OPEN=1

  while :; do
    # Leeg: bij een verbinding die niets oplevert, schrijft curl het bestand niet, en stond er
    # anders nog de body van de vorige ronde.
    : >"$WERK/ophalen.sse"
    curl_code=0
    status=$(gw_curl demo --max-time 120 -o "$WERK/ophalen.sse" -w '%{http_code}' \
      -H @"$WERK/sessie.hdr" -H 'Accept: text/event-stream' "$(url demo /api/keten/berichten/_ophalen)" \
      2>"$WERK/curl.err") || curl_code=$?

    if [ "$status" = 200 ] && reden=$(keten_oordeel "$(cat "$WERK/ophalen.sse")"); then
      echo "OK: magazijn A en B leverden berichten"
      break
    fi

    if [ "$status" != 200 ]; then
      reden="_ophalen gaf $status; $(jq -r '.detail? // empty' "$WERK/ophalen.sse" 2>/dev/null) $(tail -1 "$WERK/curl.err")"
    elif [ "$curl_code" -ne 0 ]; then
      reden="stream afgebroken ($(tail -1 "$WERK/curl.err")); $reden"
    fi

    # Een ongeldige sessie of een weigering wordt niet beter door te wachten.
    case "$status" in
      401 | 403) fout "keten: $reden"; break ;;
    esac

    [ "$SECONDS" -lt "$einde" ] || { fout "keten na ${KETEN_SECONDEN}s: $reden"; break; }
    sleep 15
  done

  sessie_beeindigen
  SESSIE_OPEN=
}

# $1 = stil: bij het opruimen, kort en zonder fout te tellen.
sessie_beeindigen() {
  local status
  status=$(gw_curl demo -o /dev/null -w '%{http_code}' ${1:+--max-time 10} -X DELETE \
    -H @"$WERK/sessie.hdr" "$(url demo /api/keten/sessies/huidige)" 2>"$WERK/curl.err" || true)
  [ -z "${1:-}" ] || return 0
  [ "$status" = 204 ] || fout "de sessie van de controle is niet beëindigd: DELETE gaf $status; $(tail -1 "$WERK/curl.err")"
}

controleer() {
  controleer_rollouts
  controleer_routes
  controleer_keten

  stap "uitkomst"

  if [ "$fouten" -eq 0 ]; then
    echo "OK: de demo draait op $(url demo /berichtenbox)"
    echo "    paneel: $(url demo /bediening/) (gebruiker demo; wachtwoord:"
    echo "    kubectl --context $KUBE_CONTEXT -n $NS get secret fbs-demo-bediening -o jsonpath='{.data.wachtwoord}' | base64 -d)"
    return 0
  fi

  echo "$fouten fout(en)"
  return 1
}

# --- Aanmelden ----------------------------------------------------------------------------------

huidige_aanmeldingen() {
  local values

  values=$(hm get values fbs-demo -o json 2>"$WERK/values.err") ||
    stop 2 "fbs-demo is niet te lezen ($(tail -1 "$WERK/values.err")); installeer de demo eerst met: $0 op"
  jq -c '.aanmeldingen // {} | {register: (.register // ""), optIns: (.optIns // [])}' <<<"$values"
}

# Het antwoord van de profielstub voor een ontvanger, zoals een magazijn het krijgt.
profiel_antwoord() {
  local ontvanger=$1 antwoord status

  antwoord=$(gw_curl profiel -X POST -w '\n%{http_code}' -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg s "${ontvanger%%:*}" --arg n "${ontvanger#*:}" '{identificatieType: $s, identificatieNummer: $n}')" \
    "$(url profiel /api/profielservice/v1/partij)" 2>"$WERK/curl.err" || true)
  status=${antwoord##*$'\n'}

  if [ "$status" != 200 ]; then
    echo "de profielstub gaf $status voor $ontvanger $(tail -1 "$WERK/curl.err")" >&2
    return 1
  fi

  echo "${antwoord%$'\n'*}"
}

# Zet de aanmeldingen in fbs-demo en start uitvraag en stelselregister opnieuw als het register
# veranderde: ze lezen het alleen bij het starten. De profielstub start Helm zelf opnieuw (checksum).
aanmeldingen_zetten() {
  local huidig=$1 nieuw=$2

  stap "fbs-demo bijwerken"
  jq '{aanmeldingen: .}' <<<"$nieuw" >"$WERK/aanmeldingen.json"
  # --reset-then-reuse-values: de values van de release, met de standaarden van deze chart eronder
  # (een sleutel die er sinds `op` bij kwam, is anders leeg).
  hm upgrade fbs-demo "$CHARTS/fbs-demo" --reset-then-reuse-values -f "$WERK/aanmeldingen.json" \
    --wait --timeout 10m >/dev/null || stop 1 "bijwerken van fbs-demo faalde"
  echo "OK: fbs-demo"

  if [ "$(jq -r .register <<<"$huidig")" != "$(jq -r .register <<<"$nieuw")" ]; then
    stap "uitvraag en stelselregister opnieuw starten"
    kc rollout restart deployment/berichtenuitvraag deployment/stelselregister >/dev/null
    kc rollout status deployment/berichtenuitvraag --timeout=10m >/dev/null || stop 1 "de uitvraag start niet opnieuw"
    kc rollout status deployment/stelselregister --timeout=10m >/dev/null || stop 1 "het stelselregister start niet opnieuw"
    echo "OK: uitvraag en stelselregister lezen het nieuwe register"
  fi
}

# Een verzoek aan een magazijn, los van gw_curl: het magazijn staat meestal niet onder het
# basisdomein van de demo. Apart, zodat test-script.sh het kan vervangen.
magazijn_curl() {
  curl -q -sS --max-time 30 "$@"
}

# Of het magazijn antwoordt zoals de uitvraag het straks aanroept: zijn OpenAPI-document op de url
# uit het register. Een host onder het basisdomein gaat, net als gw_curl, naar GATEWAY_ADRES met de
# CA van de demo; een andere host via DNS, met de publieke CA's.
magazijn_bereikbaar() {
  local host poort pad doel args=() status

  read -r host poort pad < <(url_delen "$1") || { echo "geen https-url zonder query: $1"; return 1; }
  doel="https://$host:$poort$pad/api/v1/openapi.json"

  if [[ $host == *".$BASISDOMEIN" ]]; then
    args=(${CA_BESTAND:+--cacert "$CA_BESTAND"})

    if [ -n "${GATEWAY_ADRES:-}" ]; then
      args+=(--resolve "$host:$HTTPS_POORT:$GATEWAY_ADRES")
      doel="https://$host:$HTTPS_POORT$pad/api/v1/openapi.json"
    fi
  fi

  status=$(magazijn_curl "${args[@]}" -o "$WERK/openapi.json" -w '%{http_code}' "$doel" 2>"$WERK/curl.err") || true

  if [ "$status" != 200 ]; then
    local melding
    melding=$(tail -1 "$WERK/curl.err" 2>/dev/null)
    echo "$doel gaf ${status:-geen antwoord}${melding:+ ($melding)}"
    return 1
  fi

  is_openapi "$(cat "$WERK/openapi.json")" || { echo "$doel gaf 200, maar geen OpenAPI-document"; return 1; }
}

# De url van een OIN in het stelseldocument zoals afnemers het ophalen, of niets.
url_in_stelseldocument() {
  local document payload

  document=$(gw_curl stelselregister "$(url stelselregister /api/v1/stelseldocument)" 2>"$WERK/curl.err") ||
    { fout "het stelseldocument is niet op te halen: $(tail -1 "$WERK/curl.err")"; return 1; }
  payload=$(stelseldocument_payload "$document") || { fout "het stelseldocument is geen JWS met JSON"; return 1; }
  stelseldocument_url "$payload" "$1"
}

aanmelden() {
  local bron=$1 aanmelding oin huidig ontvanger basis basissen='{}' nieuw gevonden
  shift

  [ "$bron" = - ] || [ -f "$bron" ] || stop 2 "$bron bestaat niet; geef het bestand met de regels, of - voor stdin"
  aanmelding=$(aanmelding_lezen "$bron") || stop 2 "geen aanmelding in $bron"
  oin=$(jq -r .oin <<<"$aanmelding")

  for ontvanger in "$@"; do
    [[ $ontvanger =~ $ONTVANGER_REGEL ]] || stop 2 "ontvanger $ontvanger is geen KVK:<nummer> of BSN:<nummer>"
  done

  # Eerst bereikbaar, dan in het register: een magazijn dat niet antwoordt, sloeg de uitvraag
  # anders bij elk ophalen over (use case 13, stap 7).
  stap "bereikbaarheid: $(jq -r .url <<<"$aanmelding")"
  local reden
  reden=$(magazijn_bereikbaar "$(jq -r .url <<<"$aanmelding")") ||
    stop 1 "het magazijn antwoordt niet: $reden; het komt pas in het register als het bereikbaar is"
  echo "OK: het magazijn geeft zijn OpenAPI-document"

  huidig=$(huidige_aanmeldingen)
  stap "aanmelden: $oin"

  # Voor een nieuwe ontvanger het antwoord van nu als basis. Een ontvanger met eerdere opt-ins
  # houdt zijn basis: het antwoord van nu bevat die opt-ins al.
  for ontvanger in "$@"; do
    jq -e --arg o "$ontvanger" 'any(.optIns[]; .ontvanger == $o)' <<<"$huidig" >/dev/null && continue
    basis=$(profiel_antwoord "$ontvanger") || stop 1 "geen antwoord van de profielstub voor $ontvanger"
    antwoord_heeft_voorkeur "$basis" ||
      stop 2 "$ontvanger heeft geen berichtenbox-voorkeur in de profielstub; kies een ontvanger uit de demo"
    basissen=$(jq -c --arg o "$ontvanger" --argjson b "$basis" '.[$o] = $b' <<<"$basissen")
  done

  nieuw=$(aanmeldingen_met "$huidig" "$aanmelding" "$basissen" "$@")
  aanmeldingen_zetten "$huidig" "$nieuw"

  stap "controle"
  gevonden=$(url_in_stelseldocument "$oin") || true

  if [ "$gevonden" = "$(jq -r .url <<<"$aanmelding")" ]; then
    echo "OK: het stelseldocument noemt $oin met $gevonden"
  else
    fout "het stelseldocument noemt $oin niet met $(jq -r .url <<<"$aanmelding")${gevonden:+ (wel met $gevonden)}"
  fi

  for ontvanger in "$@"; do
    if antwoord_heeft_optin "$(profiel_antwoord "$ontvanger" || true)" "$oin"; then
      echo "OK: $ontvanger heeft een opt-in voor $oin"
    else
      fout "de profielstub geeft $ontvanger geen opt-in voor $oin"
    fi
  done

  [ "$fouten" -eq 0 ] || return 1
  [ $# -gt 0 ] || echo "INFO: zonder ontvanger krijgt niemand berichten van $oin; geef er een mee, bijvoorbeeld $KETEN_ONTVANGER"
}

afmelden() {
  local oin=$1 huidig gevonden

  [[ $oin =~ ^[0-9]{20}$ ]] || stop 2 "$oin is geen OIN (20 cijfers)"
  huidig=$(huidige_aanmeldingen)
  aangemeld "$huidig" "$oin" || stop 2 "$oin is niet aangemeld met aanmelden"

  stap "afmelden: $oin"
  aanmeldingen_zetten "$huidig" "$(aanmeldingen_zonder "$huidig" "$oin")"

  stap "controle"
  gevonden=$(url_in_stelseldocument "$oin") || return 1

  if [ -z "$gevonden" ]; then
    echo "OK: het stelseldocument noemt $oin niet meer"
  else
    fout "het stelseldocument noemt $oin nog, met $gevonden"
    return 1
  fi
}

main() {
  case "${1:-}" in
    op)
      # Vóór iets schrijft: zonder tag stopte het pas na de databases en de geheimen.
      [ -n "${TAG:-}" ] || stop 2 "zet TAG op een main-<sha7> waarvoor alle acht images bestaan"
      vereis_tools kubectl helm openssl python3 curl jq docker base64
      werkmap
      voorwaarden
      images_op_tag
      certificaat
      databases
      geheimen
      installeren
      controleer
      ;;
    controleer)
      vereis_tools kubectl curl jq base64
      werkmap
      voorwaarden
      controleer
      ;;
    aanmelden)
      [ $# -ge 2 ] || stop 2 "gebruik: NS=<namespace> $0 aanmelden <regels> [<ontvanger>...]"
      vereis_tools kubectl helm curl jq base64
      werkmap
      voorwaarden
      aanmelden "${@:2}"
      ;;
    afmelden)
      [ $# -eq 2 ] || stop 2 "gebruik: NS=<namespace> $0 afmelden <OIN>"
      vereis_tools kubectl helm curl jq base64
      werkmap
      voorwaarden
      afmelden "$2"
      ;;
    *)
      echo "gebruik: NS=<namespace> [TAG=main-<sha7>] $0 op | controleer | aanmelden <regels> [<ontvanger>...] | afmelden <OIN>" >&2
      exit 2
      ;;
  esac
}

# Gesourcet (door test-script.sh) draait er niets.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
