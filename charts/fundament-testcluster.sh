#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Zet de plugin-sandbox van Fundament op als fieldlab-cluster (Kubernetes 1.36, de plug-ins via de
# echte plugin-controller), installeert de drie diensten met de chart en controleert dat ze gezond
# starten en via de Gateway bereikbaar zijn. Het ontwerp staat in
# docs/plans/2026-11-11-lokaal-fundament-testcluster.md.
#
#   TAG=main-<sha7> charts/fundament-testcluster.sh op        opzetten, installeren, controleren
#   ... op --rol beheerder|projectlid|beide                    één rol, of beide na elkaar (default)
#   charts/fundament-testcluster.sh controleer                 alleen controleren
#   charts/fundament-testcluster.sh af [--met-platform]        sandbox weg; het platform alleen op verzoek
#
# Leunt op een clone van fundament-oss/fundament (FUNDAMENT_REPO) en draait die op een vaste commit
# (FUNDAMENT_REF) in een eigen worktree, met de tools uit hun mise.toml: het voert hun code en
# recipes uit, met de rechten van Docker. Voor het publiceren van de plug-ins maakt het zelf een
# API-key met het lokale seed-account; FUNCTL_API_KEY gaat voor.
#
# Elke kubectl- en helm-aanroep noemt de context en de kubeconfig van de worktree; daar zetten
# Fundaments recipes beide contexten. In ~/.kube/config kan een context met dezelfde naam naar een
# ander cluster wijzen, en de proefinstallatie heeft een onveilige klep (Redis zonder TLS).
#
# Exitcodes: 0 in orde, 1 een controle faalde, 2 een voorwaarde ontbreekt.
set -euo pipefail

# Met deze variabele schrijven Fundaments recipes hun contexten in de kubeconfig van de aanroeper
# (zonder KUBECONFIG ~/.kube/config) en wisselen ze daar de huidige context. Hij is voor hun test-VM.
unset FUNDAMENT_TEST_ENV
# Deze gaan in functl vóór zijn configuratie in FUNCTL_CONFIG_DIR. Uit de shell van de gebruiker
# zouden ze een key van een echt platform meegeven, of functl daarheen sturen; en een geweigerde key
# uit de omgeving blijft gelden na een nieuwe login. FUNCTL_URL zet hun mise.toml zelf.
unset FUNDAMENT_API_KEY FUNCTL_API_ENDPOINT FUNCTL_AUTHN_URL FUNCTL_REGISTRY_URL

CHARTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$CHARTS/.." && pwd)
readonly CHARTS REPO

# shellcheck source=charts/fundament-testcluster/lib.sh
source "$CHARTS/fundament-testcluster/lib.sh"

readonly SANDBOX_CONTEXT=k3d-fundament-plugin
readonly PLATFORM_CONTEXT=k3d-fundament
readonly NS=tnt-fbs--fbs
HTTPS_POORT=${HTTPS_POORT:-9443}
FUNDAMENT_REPO=${FUNDAMENT_REPO:-$HOME/dev/fundament}
FUNDAMENT_REF=${FUNDAMENT_REF:-b363604dbed1d3d389504e5bb9e1b48e800618b6}
# Fundament neemt anders 172.19.0.0/16, alleen om 172.18 (Gardener) te ontwijken; op een machine met
# meer compose-projecten is dat subnet vaak al bezet. Hun cluster-create leest dezelfde variabele.
export FUNDAMENT_K3D_SUBNET=${FUNDAMENT_K3D_SUBNET:-172.29.0.0/16}
# Twee replica's: zo toetst de proef ook wat één pod verbergt (een tweede pod die niet start,
# twee pods die tegelijk migreren). Niet voor een dienst die de chart op één replica vastzet
# (singleReplica).
REPLICAS=${REPLICAS:-2}
readonly HTTPS_POORT FUNDAMENT_REPO FUNDAMENT_REF FUNDAMENT_K3D_SUBNET REPLICAS
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/fbs-fundament-testcluster
WORKTREE=$CACHE/fundament-${FUNDAMENT_REF:0:8}
# Hier schrijven Fundaments recipes de contexten van beide clusters, en wisselen ze die ook.
KUBECONFIG_LOKAAL=$WORKTREE/.dev/kubeconfig
readonly CACHE WORKTREE KUBECONFIG_LOKAAL

readonly CURL_IMAGE=docker.io/curlimages/curl:8.17.0@sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40
readonly REDIS_IMAGE=docker.io/redis/redis-stack-server:7.4.0-v3@sha256:7d8e657d60d525534d6abe96e7645ab30ef1b4f1ffedc6eeb2d4d4283b3a49b9
readonly K3S_IMAGE=docker.io/rancher/k3s:v1.36.6-k3s1@sha256:88d3fac10eb4a429d3c5275f4868fe4b13620f6bbca5bdb5dca0627ac76ae2a0

# De organisatie system publiceert de first-party plug-ins (seeddata van Fundament).
readonly SYSTEM_ORGANISATIE_ID=019b4000-0000-7000-8000-000000000000
# <pad onder plugins/>:<metadata.name uit definition.yaml>. Envoy Gateway eerst: cert-manager zet
# zijn Gateway-ondersteuning alleen aan als de Gateway-API-CRD's er bij zijn installatie al zijn.
readonly PLUGINS=(gateway-api/envoy-gateway:gateway-api-envoy cert-manager:cert-manager cloudnativepg:cloudnativepg)
# Een geseede DCIM-gebruiker van Fundament die versies mag goedkeuren (zoals in test-env).
readonly REVIEWER=alice@acme-corp.com

# Een eigen functl-configuratie: de sessie van deze opstelling mag die van de gebruiker niet
# overschrijven.
export FUNCTL_CONFIG_DIR=$CACHE/functl

kc() { command kubectl --kubeconfig "$KUBECONFIG_LOKAAL" --context "$SANDBOX_CONTEXT" "$@"; }
kcp() { command kubectl --kubeconfig "$KUBECONFIG_LOKAAL" --context "$PLATFORM_CONTEXT" "$@"; }
hm() { command helm --kubeconfig "$KUBECONFIG_LOKAAL" --kube-context "$SANDBOX_CONTEXT" "$@"; }

# Een kale aanroep zou de huidige context van de gebruiker gebruiken, en dat kan een echt cluster
# zijn. Elke aanroep loopt via kc, kcp of hm; deze vangen wat daar toch omheen gaat.
kubectl() {
  echo "FOUT: kubectl zonder vaste context; gebruik kc of kcp" >&2
  exit 2
}

helm() {
  echo "FOUT: helm zonder vaste context; gebruik hm" >&2
  exit 2
}

# Een recipe van Fundament, in de worktree en met de tools uit hun mise.toml.
fj() { (cd "$WORKTREE" && mise exec -- just "$@"); }

# Voor de meldingen: wat de gebruiker zelf intypt, moet dezelfde kubeconfig noemen.
readonly KC_SANDBOX="kubectl --kubeconfig $KUBECONFIG_LOKAAL --context $SANDBOX_CONTEXT"
readonly KC_PLATFORM="kubectl --kubeconfig $KUBECONFIG_LOKAAL --context $PLATFORM_CONTEXT"

# In hun test-VM (dit bestand) volgen hun recipes de kubeconfig van de shell, ook als
# FUNDAMENT_TEST_ENV niet gezet is.
in_test_vm() { [ -e /etc/fundament-test-env ]; }

geen_test_vm() {
  ! in_test_vm || stop 2 "dit is de test-VM van Fundament (/etc/fundament-test-env); daar schrijven hun recipes in ~/.kube/config. Draai de opstelling op de host"
}

stap() { echo "== $* =="; }

stop() {
  local code=$1
  shift
  echo "FOUT: $*" >&2
  exit "$code"
}

# Wacht tot een opdracht slaagt: wacht_tot <seconden> <omschrijving> <opdracht...>.
wacht_tot() {
  local seconden=$1 omschrijving=$2
  shift 2

  local einde=$((SECONDS + seconden))

  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$einde" ] || return 1
    sleep 5
  done

  echo "OK: $omschrijving"
}

vereis_tools() {
  local ontbreekt=()

  for gereedschap in "$@"; do
    command -v "$gereedschap" >/dev/null || ontbreekt+=("$gereedschap")
  done

  [ "${#ontbreekt[@]}" -eq 0 ] || stop 2 "ontbreekt op het PATH: ${ontbreekt[*]}"
}

# Geen umask voor het hele proces: die geldt dan ook voor de checkout van Fundament, en Docker
# kopieert die bestandsrechten in hun images. mktemp -d maakt de map al alleen voor de eigenaar.
werkmap() {
  WERK=$(mktemp -d)
  trap 'rm -rf "$WERK"' EXIT
}

# --- Fundament -----------------------------------------------------------------------------------

# Fundament op FUNDAMENT_REF in een eigen worktree, zodat de clone zelf niet verandert en de
# opstelling niet meebeweegt met wat daar toevallig uitgecheckt staat.
fundament_worktree() {
  is_commit_sha "$FUNDAMENT_REF" || stop 2 "FUNDAMENT_REF moet een volledige commit-SHA zijn, geen branch of korte SHA"
  [ -d "$FUNDAMENT_REPO/.git" ] || stop 2 "geen clone van fundament-oss/fundament in $FUNDAMENT_REPO (zet FUNDAMENT_REPO)"
  git -C "$FUNDAMENT_REPO" cat-file -e "$FUNDAMENT_REF^{commit}" 2>/dev/null ||
    stop 2 "$FUNDAMENT_REF bestaat niet in $FUNDAMENT_REPO (git -C $FUNDAMENT_REPO fetch)"

  if [ -d "$WORKTREE" ]; then
    [ "$(git -C "$WORKTREE" rev-parse HEAD)" = "$FUNDAMENT_REF" ] ||
      stop 2 "$WORKTREE staat niet op $FUNDAMENT_REF; ruim hem op met: $0 af"
  else
    stap "Fundament ${FUNDAMENT_REF:0:8} in $WORKTREE"
    mkdir -p "$CACHE"
    git -C "$FUNDAMENT_REPO" worktree add --detach "$WORKTREE" "$FUNDAMENT_REF"
  fi

  (cd "$WORKTREE" && mise trust --quiet && mise install --quiet)
}

platform_bestaat() { docker inspect k3d-fundament-server-0 >/dev/null 2>&1; }
sandbox_bestaat() { docker inspect k3d-fundament-plugin-server-0 >/dev/null 2>&1; }

# Een Deployment die bestaat maar niet start (een image dat niet op te halen is) telt niet als
# een draaiend platform.
platform_gereed() {
  [ "$(kcp -n fundament get deployment organization-api -o jsonpath='{.status.availableReplicas}' 2>/dev/null)" -ge 1 ] 2>/dev/null
}

# De poortbindingen van alles op de twee netwerken van deze opstelling, ook van gestopte
# containers, als "<container>|<bindingen>". Faalt als docker faalt: een lege lijst mag dan niet
# als "in orde" lezen.
docker_poorten() {
  local netwerk lijst id ids=()

  # Geen < <(...): de exitcode van een process substitution gaat verloren.
  for netwerk in k3d-fundament k3d-fundament-plugin; do
    lijst=$(docker ps -aq --filter "network=$netwerk") || return 1
    while IFS= read -r id; do
      [ -z "$id" ] || ids+=("$id")
    done <<<"$lijst"
  done

  [ "${#ids[@]}" -gt 0 ] || return 0
  docker inspect "${ids[@]}" | poortbindingen_uit_inspect | sort -u
}

# Slaagt als geen container van de opstelling, ook geen gestopte, een poort op alle adressen
# publiceert. Fundament bindt alles aan loopback; dit vangt een platform dat nog met een oudere
# versie is gemaakt, of een k3d-config die weer een poort openzet.
alleen_loopback() {
  local poorten open

  poorten=$(docker_poorten) || { echo "docker ps faalde" >&2; return 1; }
  open=$(poorten_naar_buiten <<<"$poorten")
  [ -z "$open" ] && return 0

  echo "Op alle adressen:" >&2
  echo "$open" >&2
  return 1
}

# De map met de lokale CA van mkcert. Een mise-shim van mkcert werkt alleen in een map waar mise
# een versie voor kent; die kent de worktree van Fundament, buiten die map faalt hij.
mkcert_caroot() {
  mkcert -CAROOT 2>/dev/null || mise exec -C "$WORKTREE" -- mkcert -CAROOT
}

console_antwoordt() {
  curl -sf -o /dev/null --max-time 5 --cacert "$(mkcert_caroot)/rootCA.pem" https://console.fundament.localhost:8443/
}

# organization-api heeft een circuit breaker vóór de authenticatie: die peilt om de 2 s de oudste
# open rij in de authz-outbox, en weigert elk verzoek met unavailable zolang die ouder is dan 5 s.
# Een verzoek zonder token krijgt bij een dichte breaker unauthenticated.
organisatie_api_antwoord() {
  curl -sS --max-time 5 --cacert "$(mkcert_caroot)/rootCA.pem" -X POST \
    https://organization.fundament.localhost:8443/organization.v1.OrganizationService/ListOrganizations \
    -H 'Content-Type: application/json' -d '{}'
}

# Het laatst geziene antwoord, voor de melding als het wachten opgeeft.
ORG_API_LAATSTE=""

organisatie_api_open() {
  ORG_API_LAATSTE=$(organisatie_api_antwoord 2>&1) || return 1
  [ "$(connect_code "$ORG_API_LAATSTE")" = unauthenticated ]
}

# Van de pods, niet van de Deployment: die toont na een hervatting nog de stand van vóór de pauze.
# De worker wordt pas gereed nadat hij OpenFGA bereikte, vlak voordat hij mislukte outbox-rijen
# terugzet op pending.
authz_worker_gereed() {
  alle_pods_gereed "$(kcp -n fundament get pods -l app.kubernetes.io/name=authz-worker \
    -o jsonpath='{range .items[*]}{.metadata.name} - {.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null)"
}

# Een dichte breaker op één moment zegt na een hervatting niet genoeg. Zodra de authz-worker OpenFGA
# bereikt, zet hij mislukte rijen terug op pending met hun oude tijd, en gaat de breaker binnen een
# peiling alsnog open. Daarom eerst een gereede worker, en dan vier peilingen over 15 s, waarin
# worker en breaker samen ruim de tijd hebben om te reageren.
organisatie_api_stabiel() {
  local poging

  for poging in 1 2 3 4; do
    [ "$poging" = 1 ] || sleep 5
    authz_worker_gereed || { ORG_API_LAATSTE="(authz-worker niet gereed)"; return 1; }
    organisatie_api_open || return 1
  done
}

platform_maken() {
  fj cluster-create || stop 2 "het platform aanmaken faalde (melding hierboven)"
  # Meteen, niet pas na de uitrol: die bouwt de eerste keer lang, met een eventueel open poort.
  alleen_loopback ||
    stop 2 "het nieuwe platform publiceert poorten op alle adressen (hierboven); ruim het op met: $0 af --met-platform"
}

# Het platform. Uitrollen alleen als het er nog niet is: elke uitrol wist zijn databases, en
# daarmee de API-keys en de gepubliceerde plug-ins. Een platform dat net is herstart, krijgt de tijd.
platform_op() {
  stap "Fundament-platform ($PLATFORM_CONTEXT)"

  if platform_bestaat; then
    fj cluster-start >/dev/null || stop 1 "het platform starten faalde (melding hierboven); opnieuw opzetten kan met: $0 af --met-platform en $0 op"
  else
    platform_maken
  fi

  # Na een herstart antwoordt de API pas na een poos. Pas daarna zegt een mislukte get iets.
  wacht_tot 180 "Kubernetes-API van het platform" kcp get --raw=/readyz ||
    stop 1 "de Kubernetes-API van het platform antwoordt niet; zie: docker ps --filter network=k3d-fundament"

  local uitvoer

  if uitvoer=$(kcp -n fundament get deployment organization-api -o name 2>&1); then
    wacht_tot 600 "platform gereed" platform_gereed ||
      stop 1 "het platform draait maar wordt niet gereed; zie: $KC_PLATFORM -n fundament get pods. Opnieuw uitrollen wist het: $0 af --met-platform en $0 op"
  elif ! kubectl_niet_gevonden "$uitvoer"; then
    # Uitrollen over een bestaand platform wist zijn databases; alleen bij een zekere NotFound.
    echo "$uitvoer" >&2
    stop 1 "kon niet vaststellen of het platform al is uitgerold"
  else
    echo "Platform uitrollen; de eerste keer bouwt dit alle images van Fundament en duurt het lang."
    fj deploy local
  fi

  wacht_tot 900 "console bereikbaar op https://console.fundament.localhost:8443" console_antwoordt ||
    stop 1 "de console antwoordt niet; zie: $KC_PLATFORM -n fundament get pods"
  wacht_tot 300 "organization-api handelt verzoeken af" organisatie_api_stabiel || {
    echo "Laatste peiling van organization-api, zonder token: ${ORG_API_LAATSTE:0:300}" >&2
    stop 1 "organization-api handelt geen verzoeken af (een open circuit breaker geeft unavailable); zie: $KC_PLATFORM -n fundament logs deploy/organization-api, en $KC_PLATFORM -n fundament logs deploy/authz-worker"
  }

  alleen_loopback ||
    stop 2 "het platform publiceert poorten op alle adressen (hierboven); ruim het op met: $0 af --met-platform"
}

# Een API-key voor functl, zoals de e2e-suite van Fundament die maakt: inloggen met het
# wachtwoord van het seed-account platform-admin (charts/fundament/values-local.yaml van
# Fundament; bestaat alleen lokaal) en CreateAPIKey aanroepen. Het toegangstoken gaat via een
# bestand naar curl, niet op de commandoregel: die is via ps zichtbaar voor andere gebruikers.
#
# Elke key een eigen naam: een verlopen key houdt zijn naam bezet, en het platform weigert een
# tweede met dezelfde (already_exists).
api_key_maken() {
  local ca token antwoord naam
  ca=$(mkcert_caroot) || { echo "mkcert vindt zijn CA niet (mkcert -CAROOT)" >&2; return 1; }
  ca=$ca/rootCA.pem
  naam=fbs-fundament-testcluster-$(date -u +%Y%m%dT%H%M%S)-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')

  # Met de melding van curl en het foutantwoord: zonder sessie vraagt `functl auth status` het
  # platform niets, dus dit is de eerste aanroep die merkt dat authn niet antwoordt.
  antwoord=$(curl -sS --fail-with-body --max-time 30 --cacert "$ca" -X POST \
    https://authn.fundament.localhost:8443/login/password -H 'Content-Type: application/json' \
    -d '{"email":"platform-admin@fundament.io","password":"password"}') || {
    # curl kan ook na een geslaagd antwoord falen (timeout tijdens de body, verbinding weg).
    # Op tekst, niet als JSON: ook een afgekapt antwoord kan het token al bevatten.
    case "$antwoord" in
      *access_token*) echo "login/password: antwoord met token niet getoond" >&2 ;;
      *) printf '%s\n' "$antwoord" >&2 ;;
    esac
    return 1
  }
  # Een geslaagd antwoord niet tonen: daar staat een token in.
  token=$(jq -er .access_token <<<"$antwoord" 2>/dev/null) || { echo "login/password: geen access_token in het antwoord" >&2; return 1; }

  printf 'Authorization: Bearer %s\n' "$token" >"$WERK/auth-header"
  antwoord=$(curl -sS --max-time 30 --cacert "$ca" -X POST \
    https://organization.fundament.localhost:8443/organization.v1.APIKeyService/CreateAPIKey \
    -H 'Content-Type: application/json' -H @"$WERK/auth-header" \
    -H "Fun-Organization: $SYSTEM_ORGANISATIE_ID" -H "Idempotency-Key: $(nieuw_uuid)" \
    -d "{\"name\":\"$naam\",\"expiresIn\":\"1h\"}")
  rm -f "$WERK/auth-header"

  # Alleen code en melding: een antwoord dat de parser niet herkent, kan toch een key bevatten.
  api_key_uit_antwoord "$antwoord" || {
    jq -er '"CreateAPIKey: \(.code // "?"): \(.message // "")"' <<<"$antwoord" >&2 2>/dev/null ||
      echo "CreateAPIKey: geen JSON-antwoord" >&2
    return 1
  }
}

# functl inloggen voor de organisatie system. Een eerdere sessie blijft geldig tot de key
# verloopt of het platform opnieuw is uitgerold; dan komt er een nieuwe key. FUNCTL_API_KEY gaat
# voor, voor wie een eigen key wil gebruiken. De key gaat via stdin naar functl.
functl_inloggen() {
  stap "functl"

  local uitvoer sessie key=${FUNCTL_API_KEY:-}

  if [ -z "$key" ]; then
    # Alleen stdout is JSON; mise zet FUNCTL_DEBUG aan, en de debuglog gaat naar stderr. Een exit
    # die niet 0 is, betekent dat functl het niet kon controleren (platform onbereikbaar, config
    # onleesbaar) of zelf niet draaide: dan geen nieuwe key, want die lost dat niet op en verbergt
    # de oorzaak.
    uitvoer=$(fj functl auth status -o json 2>"$WERK/functl-status.err") || {
      cat "$WERK/functl-status.err" >&2
      stop 1 "functl kon de sessie niet controleren (melding hierboven)"
    }
    sessie=$(functl_sessie "$uitvoer") || {
      cat "$WERK/functl-status.err" >&2
      printf '%s\n' "$uitvoer" >&2
      stop 1 "onbekend antwoord van functl auth status -o json (hierboven); is FUNDAMENT_REF van vóór fundament-oss/fundament#661?"
    }

    if [ "$sessie" = ingelogd ]; then
      # De sessie is hierboven getoetst; dit kiest alleen de organisatie.
      uitvoer=$(fj functl org set "$SYSTEM_ORGANISATIE_ID" 2>&1) || { echo "$uitvoer" >&2; stop 1 "functl org set faalde"; }
      echo "OK: functl was al ingelogd"
      return
    fi

    echo "INFO: geen geldige functl-sessie ($(jq -r '.message // .reason' <<<"$uitvoer")); een nieuwe API-key"
    key=$(api_key_maken) || stop 1 "kon geen API-key maken met het seed-account platform-admin@fundament.io (melding hierboven)"
  fi

  uitvoer=$(printf '%s\n' "$key" | fj functl auth login 2>&1) || {
    echo "$uitvoer" >&2
    [ -z "${FUNCTL_API_KEY:-}" ] || stop 1 "functl auth login faalde met FUNCTL_API_KEY; na een nieuwe uitrol van het platform is een oude key ongeldig"
    stop 1 "functl auth login faalde met een net gemaakte key"
  }
  uitvoer=$(fj functl org set "$SYSTEM_ORGANISATIE_ID" 2>&1) || { echo "$uitvoer" >&2; stop 1 "functl org set faalde"; }
  echo "OK: functl ingelogd voor de organisatie system"
}

# Slaagt als de bestaande sandbox van deze opstelling kan zijn (zie sandbox_past). Uit de
# containerconfiguratie en niet via de API: zo is ook een gestopte sandbox te herkennen, en een die
# nog in de kubeconfig van een vorige FUNDAMENT_REF staat.
sandbox_van_ons() {
  local image mappings versie

  image=$(docker inspect -f '{{.Config.Image}}' k3d-fundament-plugin-server-0 2>/dev/null) ||
    { echo "docker inspect k3d-fundament-plugin-server-0 faalde." >&2; return 1; }
  mappings=$(docker inspect k3d-fundament-plugin-serverlb 2>/dev/null | mappings_uit_inspect) ||
    { echo "docker inspect k3d-fundament-plugin-serverlb faalde; is de load balancer van de sandbox weg?" >&2; return 1; }
  versie=$(k3s_versie_uit_image "$image") || versie="onbekend (image $image)"
  sandbox_past "$versie" "$mappings" "$HTTPS_POORT"
}

# Weigert vóór er iets wordt aangemaakt of gestart: een sandbox die niet van ons is, een bezette
# poort, of een platform of sandbox met poorten op alle adressen (van een oudere versie van
# Fundament).
voorcontrole() {
  # Ook zonder platform of sandbox: een afgebroken create van een oudere versie laat bijvoorbeeld
  # een registry op alle adressen achter.
  alleen_loopback ||
    stop 2 "op de netwerken van deze opstelling publiceert een container poorten op alle adressen (hierboven), ook als hij nu gestopt is. Is de opstelling met een oudere versie van Fundament gemaakt, ruim hem dan op met: $0 af --met-platform"

  if sandbox_bestaat; then
    sandbox_van_ons || stop 2 "deze sandbox is niet van deze opstelling; ruim hem op met: $0 af"
  else
    poort_vrij "$HTTPS_POORT" || stop 2 "poort 127.0.0.1:$HTTPS_POORT is bezet (zet HTTPS_POORT)"
  fi
}

# Herstarten via k3d, zoals Fundaments eigen cluster-start, niet met docker restart: k3d zet bij het
# starten ook de adressen van het netwerk opnieuw in CoreDNS.
readonly K3D_HERSTART="(cd $WORKTREE && mise exec -- k3d cluster stop fundament-plugin fundament && mise exec -- k3d cluster start fundament fundament-plugin)"

# De node moet de registry van de images kunnen opzoeken: de chart haalt ze bij elke start
# (pullPolicy Always). Docker legt de DNS-server van de host vast bij het starten van een
# container. Na een wissel van netwerk, zoals op het fieldlab, vraagt de node het dus nog aan de
# server van het vorige netwerk. Ook na een herstart van de machine, waarna Docker de nodes zelf
# start (restart-policy unless-stopped), kon de node ghcr.io niet opzoeken. Een pod die zijn image
# moet ophalen, zoals elke dienst (pullPolicy Always), blijft dan in ImagePullBackOff hangen.
sandbox_dns_ok() {
  wacht_tot 30 "DNS in de sandbox-node" docker exec k3d-fundament-plugin-server-0 nslookup ghcr.io
}

readonly DNS_MELDING="de sandbox-node kan ghcr.io niet opzoeken, dus pods die hun image ophalen blijven in ImagePullBackOff hangen. Na een herstart van de machine of een wissel van netwerk helpt een herstart van beide clusters via k3d: $K3D_HERSTART"

sandbox_dns_werkt() {
  sandbox_dns_ok || stop 1 "$DNS_MELDING"
}

# De sandbox van Fundament, maar op Kubernetes 1.36 en met de https-poort van de Gateway op
# loopback: hun `just plugins cluster-create` kent geen image of extra poort. De context komt in de
# kubeconfig van de worktree, waar hun recipes hem verwachten.
sandbox_op() {
  stap "sandbox ($SANDBOX_CONTEXT, Kubernetes 1.36)"

  if sandbox_bestaat; then
    sandbox_van_ons || stop 2 "deze sandbox is niet van deze opstelling; ruim hem op met: $0 af"
    # Start hem als hij gepauzeerd is, en zet de context in de kubeconfig van de worktree.
    fj plugins cluster-start >/dev/null || stop 1 "de sandbox starten faalde (melding hierboven); ruim hem op met: $0 af"
    echo "OK: sandbox bestaat al"
  else
    (cd "$WORKTREE/plugins" && PWD=$WORKTREE/plugins KUBECONFIG=$KUBECONFIG_LOKAAL mise exec -- \
      k3d cluster create --config=sandbox/k3d-config.yaml --image "$K3S_IMAGE" \
      -p "127.0.0.1:$HTTPS_POORT:443@loadbalancer" --kubeconfig-switch-context=false --wait)
    alleen_loopback || stop 2 "de nieuwe sandbox publiceert poorten op alle adressen (hierboven); ruim hem op met: $0 af"
  fi

  sandbox_dns_werkt
  fj plugins deploy
  fj plugin-sandbox-kubeconfig
  # De relays naar de catalogus en de inlog van het platform, zonder hostpoort.
  fj plugins sandbox-catalog

  alleen_loopback || stop 2 "de sandbox publiceert poorten op alle adressen (hierboven); ruim hem op met: $0 af"
}

installatie_klaar() { plugin_staat_klaar "$(kc get plugininstallation "$1" -o json 2>/dev/null)"; }

# Publiceren, indienen en goedkeuren, zoals test-env van Fundament dat doet: een concept is alleen
# zichtbaar voor de organisatie die het publiceerde, en de plugin-controller van de sandbox leest
# als acme-corp. Print "<versie> <hash>".
plugin_publiceren() {
  local pad=$1 uitvoer

  uitvoer=$(PLUGIN_REGISTRY=localhost:5112 fj plugins publish "$pad" --submit 2>&1) || {
    [ "$(functl_fout_soort "$uitvoer")" = geen-listing ] || { echo "$uitvoer"; return 1; }
    uitvoer=$(PLUGIN_REGISTRY=localhost:5112 fj plugins publish "$pad" --submit --create 2>&1) || { echo "$uitvoer"; return 1; }
  }

  local versie hash id
  read -r versie hash < <(publicatie_lees "$uitvoer") || { echo "$uitvoer" >&2; stop 1 "geen versie en hash in de uitvoer van publish voor $pad"; }
  id=$(publicatie_plugin_id "$uitvoer") || { echo "$uitvoer" >&2; stop 1 "geen plugin-id in de uitvoer van publish voor $pad"; }

  fj funops plugin approve "$id" "$versie" --reviewer "$REVIEWER" >&2 || stop 1 "goedkeuren van $pad $versie faalde"
  echo "$versie $hash"
}

# Een versie die al bestaat: na `af` blijft de catalogus van het platform staan, en versies zijn
# create-only. De hash staat dan niet meer in een publish-uitvoer; de catalogus geeft hem voor een
# goedgekeurde versie. Print "<versie> <hash>".
plugin_hervatten() {
  local naam=$1 uitvoer=$2 versie status id

  versie=$(bestaande_versie "$uitvoer") || { echo "$uitvoer" >&2; stop 1 "publiceren van $naam faalde"; }
  read -r status id < <(versie_uit_catalogus "$(fj funops plugin list --status all -o json 2>/dev/null)" "$naam" "$versie") ||
    stop 1 "versie $versie van $naam staat niet in de catalogus"

  case "$status" in
    approved) ;;
    pending) fj funops plugin approve "$id" "$versie" --reviewer "$REVIEWER" >&2 || stop 1 "goedkeuren van $naam $versie faalde" ;;
    *)
      stop 1 "versie $versie van $naam staat op $status en is niet meer in te dienen; rol het platform opnieuw uit: $0 af --met-platform en $0 op"
      ;;
  esac

  # De catalogus is anoniem, en het platform publiceert hem op zijn eigen Gateway.
  local ca antwoord hash image
  ca=$(mkcert_caroot) || stop 2 "mkcert vindt zijn CA niet (mkcert -CAROOT)"
  # mkcert -CAROOT noemt de map ook als er nog geen CA in staat.
  [ -f "$ca/rootCA.pem" ] || stop 2 "geen CA van mkcert in $ca; installeer hem met: mkcert -install"
  antwoord=$(curl -sS --fail-with-body --max-time 10 --cacert "$ca/rootCA.pem" -X POST \
    https://marketplace-catalog-api.fundament.localhost:8443/catalog.v1.CatalogService/GetPluginDefinition \
    -H 'Content-Type: application/json' \
    -d "{\"name\":{\"organizationName\":\"system\",\"pluginName\":\"$naam\"},\"version\":\"$versie\"}") || {
    echo "$antwoord" >&2
    stop 1 "de catalogus op het platform gaf geen antwoord voor $naam $versie (melding van curl hierboven)"
  }
  hash=$(hash_uit_catalogus "$antwoord") || { echo "$antwoord" >&2; stop 1 "geen hash in de catalogus voor $naam $versie"; }

  # De registry van de sandbox verdwijnt met `af`, en publish heeft het image net opnieuw gebouwd;
  # alleen uit een gelijke build-cache komt dezelfde digest. Anders start de plug-in nooit.
  image=$(image_uit_definitie "$(jq -r '.manifest' <<<"$antwoord" | base64 -d)") ||
    stop 1 "geen image met digest in de definitie van $naam $versie"
  local repo=${image#localhost:5112/}
  curl -sf -o /dev/null --max-time 10 \
    -H 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    "http://127.0.0.1:5112/v2/${repo%@*}/manifests/${repo#*@}" ||
    stop 1 "het image van $naam $versie ($image) staat niet meer in de registry; rol het platform opnieuw uit: $0 af --met-platform en $0 op"

  echo "OK: $naam $versie stond al in de catalogus" >&2
  echo "$versie $hash"
}

# Publiceert en installeert een plug-in, tenzij de installatie er al is.
plugin_op() {
  local pad=$1 naam=$2
  local installatie=system--$naam

  if kc get plugininstallation "$installatie" >/dev/null 2>&1; then
    echo "OK: $installatie bestaat al"
  else
    local uitvoer versie hash

    if uitvoer=$(plugin_publiceren "$pad"); then
      read -r versie hash <<<"$uitvoer"
    elif [ "$(functl_fout_soort "$uitvoer")" = bestaat-al ]; then
      # In een command substitution, niet in < <(...): daar zou een stop het script niet afbreken.
      uitvoer=$(plugin_hervatten "$naam" "$uitvoer") || exit $?
      read -r versie hash <<<"$uitvoer"
    else
      echo "$uitvoer" >&2
      stop 1 "publiceren van $pad faalde"
    fi

    manifest_plugininstallatie "$naam" "$versie" "$hash" | kc apply -f -
  fi

  wacht_tot 600 "$installatie draait" installatie_klaar "$installatie" || {
    kc get plugininstallation "$installatie" -o jsonpath='{.status.message}{"\n"}' >&2 || true
    stop 1 "$installatie draait niet; zie: $KC_SANDBOX get plugininstallation $installatie -o yaml"
  }
}

# cert-manager geeft alleen een certificaat voor een geannoteerde Gateway met Gateway-ondersteuning
# aan; zonder blijft de https-listener zonder certificaat.
cert_manager_met_gateway() {
  [ "$(kc get plugininstallation system--cert-manager -o jsonpath='{.status.message}' 2>/dev/null)" = "cert-manager is running" ]
}

gatewayclass_geaccepteerd() {
  [ "$(kc get gatewayclass eg -o jsonpath='{.status.conditions[?(@.type=="Accepted")].status}' 2>/dev/null)" = True ]
}

plugins_op() {
  stap "plug-ins"

  local plugin

  for plugin in "${PLUGINS[@]}"; do
    plugin_op "${plugin%%:*}" "${plugin##*:}"
  done

  wacht_tot 300 "GatewayClass eg geaccepteerd" gatewayclass_geaccepteerd || stop 1 "GatewayClass eg is niet Accepted"
  wacht_tot 300 "CRD van CloudNativePG" kc get crd clusters.postgresql.cnpg.io ||
    stop 1 "de CRD clusters.postgresql.cnpg.io ontbreekt"
  wacht_tot 600 "cert-manager met Gateway-ondersteuning" cert_manager_met_gateway ||
    stop 1 "cert-manager draait zonder Gateway-ondersteuning; zie: $KC_SANDBOX get plugininstallation system--cert-manager -o yaml"
}

# --- Voorzieningen in de namespace ---------------------------------------------------------------

conditie_klaar() {
  local soort=$1 naam=$2 conditie=$3

  [ "$(kc -n "$NS" get "$soort" "$naam" -o jsonpath="{.status.conditions[?(@.type==\"$conditie\")].status}" 2>/dev/null)" = True ]
}

database_gezond() {
  [ "$(kc -n "$NS" get cluster.postgresql.cnpg.io "$1" -o jsonpath='{.status.phase}' 2>/dev/null)" = "Cluster in healthy state" ]
}

# Maakt een Secret alleen als hij er nog niet is: een tweede run mag geen nieuw wachtwoord zetten
# naast een Redis die het oude nog gebruikt. Met create, niet apply: apply zet de inhoud ook in
# een annotatie.
secret_eenmalig() {
  local naam=$1
  shift

  kc -n "$NS" get secret "$naam" >/dev/null 2>&1 && return 0
  kc -n "$NS" create secret generic "$naam" "$@" >/dev/null
}

voorzieningen_op() {
  stap "namespace $NS en Gateway"

  manifest_tenant_namespace "$NS" | kc apply -f -
  manifest_projectlid "$NS" | kc apply -f -
  manifest_lokale_ca "$NS" | kc apply -f -
  wacht_tot 300 "lokale CA" conditie_klaar certificate lokale-ca Ready || stop 1 "certificaat lokale-ca is niet Ready"
  manifest_gateway "$NS" | kc apply -f -
  wacht_tot 300 "certificaat web-tls van de Gateway" kc -n "$NS" get secret web-tls ||
    stop 1 "cert-manager maakte geen certificaat web-tls voor Gateway web"
  wacht_tot 300 "Gateway web Programmed" conditie_klaar gateway web Programmed ||
    stop 1 "Gateway web is niet Programmed; zie: $KC_SANDBOX -n $NS describe gateway web"

  stap "databases (CloudNativePG)"

  local db

  for db in db ldv-uitvraag; do
    manifest_cnpg_cluster "$db" "$NS" | kc apply -f -
  done

  for db in db ldv-uitvraag; do
    wacht_tot 600 "database $db gezond" database_gezond "$db" ||
      stop 1 "database $db is niet gezond; zie: $KC_SANDBOX -n $NS describe cluster.postgresql.cnpg.io $db"
    kc -n "$NS" get secret "$db-app" >/dev/null || stop 1 "Secret $db-app ontbreekt"
  done

  stap "Redis Stack en geheimen"

  # Geheimen als bestand, niet op de commandoregel: die is via ps zichtbaar voor andere gebruikers.
  geheim_bestand "$WERK/redis-wachtwoord"
  secret_eenmalig proef-voorzieningen --from-file=REDIS_PASSWORD="$WERK/redis-wachtwoord"
  kc -n "$NS" get secret proef-voorzieningen -o jsonpath='{.data.REDIS_PASSWORD}' | base64 -d >"$WERK/redis-wachtwoord"

  manifest_proef_redis "$NS" "$REDIS_IMAGE" | kc apply -f -
  kc -n "$NS" rollout status deployment/proef-redis --timeout=5m

  # Keystore en wachtwoord horen bij elkaar; ontbreekt er één, dan beide opnieuw.
  if ! kc -n "$NS" get secret stelselregister-keystore stelselregister-geheimen >/dev/null 2>&1; then
    mkdir -p "$WERK/pki"
    cp "$REPO/demo/environment/stelselregister/pki/maak-keten.sh" "$WERK/pki/"
    (cd "$WERK/pki" && STELSELDOCUMENT_OMGEVING=lokaal ./maak-keten.sh >/dev/null)
    kc -n "$NS" delete secret stelselregister-keystore stelselregister-geheimen --ignore-not-found >/dev/null
    secret_eenmalig stelselregister-keystore --from-file=keystore.p12="$WERK/pki/out/keystore.p12"
    secret_eenmalig stelselregister-geheimen --from-file=STELSELDOCUMENT_KEYSTORE_WACHTWOORD="$WERK/pki/out/wachtwoord"
  fi

  secret_eenmalig berichtenuitvraag-geheimen --from-file=REDIS_PASSWORD="$WERK/redis-wachtwoord"
  kc -n "$NS" get secret web-tls -o jsonpath='{.data.ca\.crt}' | base64 -d >"$WERK/lokale-ca.crt"
  echo "OK: Redis, keystore en geheimen"
}

# --- Installeren en controleren ------------------------------------------------------------------

# De chart-argumenten van een dienst, in de array DIENST_ARGS: release, chart, namespace en values.
# tlsTermination=mesh: lokaal is het pod-netwerk niet versleuteld. Dat kan hier, met proefgegevens
# op loopback; op Fundament hoort die keuze bij het pod-netwerk daar.
dienst_args() {
  local dienst=$1

  DIENST_ARGS=("$dienst" "$CHARTS/fbs-dienst" --namespace "$NS" -f "$CHARTS/fbs-dienst/diensten/$dienst.yaml")

  if [ "$dienst" = berichtenmagazijn ]; then
    DIENST_ARGS+=(-f "$CHARTS/voorbeelden/berichtenmagazijn-cnpg.yaml")
  else
    DIENST_ARGS+=(--set "dienst.extraEnvVarsSecret=$dienst-geheimen")
  fi

  values_dienst "$dienst" "$NS" >"$WERK/$dienst-values.yaml"
  DIENST_ARGS+=(-f "$WERK/$dienst-values.yaml" --set "dienst.image.tag=$TAG" --set dienst.tlsTermination=mesh)

  enkele_replica "$CHARTS/fbs-dienst/diensten/$dienst.yaml" || DIENST_ARGS+=(--set "dienst.replicaCount=$REPLICAS")
}

# Installeert één dienst met de chart; extra argumenten gaan naar helm (route, rol). Het expliciete
# return: een aanroeper met || zet errexit in deze functie uit, en dan zou een mislukte installatie
# toch "OK" melden.
installeer_dienst() {
  local dienst=$1
  shift

  dienst_args "$dienst"
  hm upgrade --install "${DIENST_ARGS[@]}" "$@" >/dev/null || return 1
  echo "OK: $dienst geïnstalleerd"
}

# Als organisatiebeheerder (cluster-admin), het pad van het fieldlab-voorbeeld: met route.
installeer_beheerder() {
  stap "installeren als organisatiebeheerder"

  local dienst route

  for dienst in "${DIENSTEN[@]}"; do
    losse_route_weg "$dienst"
    route=()
    while IFS= read -r regel; do route+=("$regel"); done < <(helm_route_args "$dienst")
    installeer_dienst "$dienst" "${route[@]}" || stop 1 "installeren van $dienst als beheerder faalde"
  done
}

# Een route die de beheerder voor een projectlid apart maakte, is niet van Helm; een installatie
# mét route zou er anders op stuklopen ("exists and cannot be imported").
losse_route_weg() {
  local release

  release=$(kc -n "$NS" get httproute "$1" -o jsonpath='{.metadata.annotations.meta\.helm\.sh/release-name}' 2>/dev/null) || return 0
  [ -n "$release" ] || kc -n "$NS" delete httproute "$1" >/dev/null
}

# Als projectlid: alleen de ClusterRole admin in de namespace. HTTPRoute hoort daar niet bij (de
# Envoy-plug-in voegt hem niet aan admin toe), dus de uitweg uit de README van de chart: installeren
# zonder route, en de route apart laten maken door een organisatiebeheerder.
installeer_projectlid() {
  stap "installeren als projectlid"

  local lid=system:serviceaccount:$NS:projectlid mag

  mag=$(kc auth can-i create httproutes.gateway.networking.k8s.io -n "$NS" --as "$lid" 2>/dev/null || true)
  echo "INFO: projectlid mag HTTPRoutes maken: ${mag:-onbekend}"

  local dienst route

  for dienst in "${DIENSTEN[@]}"; do
    hm uninstall "$dienst" --namespace "$NS" --wait >/dev/null 2>&1 || true
  done

  for dienst in "${DIENSTEN[@]}"; do
    # cluster.gateway.name ook zonder route: de NetworkPolicy laat de proxy's van die Gateway toe;
    # zonder wordt de selector leeg en blokkeert hij de route die de beheerder apart maakt.
    installeer_dienst "$dienst" --kube-as-user "$lid" --set ingress.enabled=false --set cluster.gateway.name=web ||
      stop 1 "installeren van $dienst als projectlid faalde; de chart hoort binnen de rol admin te blijven"
  done

  for dienst in "${DIENSTEN[@]}"; do
    route=()
    while IFS= read -r regel; do route+=("$regel"); done < <(helm_route_args "$dienst")
    dienst_args "$dienst"
    hm template "${DIENST_ARGS[@]}" "${route[@]}" --show-only templates/httproute.yaml | kc apply -n "$NS" -f - >/dev/null
    echo "OK: route van $dienst apart gemaakt door de beheerder"
  done
}

pod_klaar() {
  case "$(kc -n "$NS" get pod "$1" -o jsonpath='{.status.phase}' 2>/dev/null)" in
    Succeeded | Failed) return 0 ;;
    *) return 1 ;;
  esac
}

# Draait een probe-pod en print wat hij logde (zie probe_geweigerd). Een pod die niet draaide of
# niets logde, geeft "geen-uitvoer": dat mag niet lezen als een weigering.
probe_code() {
  local dienst=$1 soort=$2 naam=probe-$1

  [ "$soort" = met-label ] || naam=probe-zonder-label-$dienst

  # Zonder deze twee las het script de fase en de logs van een oude pod met dezelfde naam.
  kc -n "$NS" delete pod "$naam" --ignore-not-found --wait >/dev/null || { echo geen-uitvoer; return; }
  manifest_probe "$NS" "$dienst" "$CURL_IMAGE" "$soort" | kc apply -f - >/dev/null || { echo geen-uitvoer; return; }

  if wacht_tot 120 "probe $naam klaar" pod_klaar "$naam" >/dev/null; then
    kc -n "$NS" logs "$naam" 2>/dev/null | grep . || echo geen-uitvoer
  else
    echo geen-uitvoer
  fi
}

# De HTTP-status van een pad op de hostnaam van een dienst, via de Gateway en over https. Het
# certificaat wordt getoetst tegen de lokale CA: een TLS-fout geeft 000, geen stille -k.
via_gateway() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 3 --cacert "$WERK/lokale-ca.crt" \
    --resolve "$1.fbs.localhost:$HTTPS_POORT:127.0.0.1" "https://$1.fbs.localhost:$HTTPS_POORT$2" || true
}

fouten=0
GATEWAY_WACHTTIJD=180

fout() {
  fouten=$((fouten + 1))
  echo "FOUT: $*"
}

# Per pod van een dienst: naam, restarts, Ready en het moment van de laatste wissel van Ready.
# Blijft dit gelijk over een probe, dan was de dienst al die tijd gereed.
gereedheid_stempel() {
  kc -n "$NS" get pods -l "app.kubernetes.io/instance=$1,app.kubernetes.io/component=api" -o jsonpath='{range .items[*]}{.metadata.name} {.status.containerStatuses[0].restartCount} {.status.conditions[?(@.type=="Ready")].status} {.status.conditions[?(@.type=="Ready")].lastTransitionTime}{"\n"}{end}' |
    sort
}

controleer_dienst() {
  local dienst=$1 code staat="" gewenst gereed

  # Een rollout naar nul replica's is ook af; daarom moeten alle gewenste pods gereed zijn, en
  # minstens één.
  if kc -n "$NS" rollout status "deployment/$dienst" --timeout=5m >/dev/null; then
    staat=$(kc -n "$NS" get deployment "$dienst" -o jsonpath='{.spec.replicas} {.status.readyReplicas}') || true
  fi

  read -r gewenst gereed <<<"$staat" || true

  if [ "${gewenst:-0}" -ge 1 ] 2>/dev/null && [ "${gereed:-0}" = "$gewenst" ]; then
    echo "OK: $dienst rollout klaar, $gereed van $gewenst pods gereed"
  else
    fout "$dienst rollout niet klaar, of niet alle pods gereed (${gereed:-0} van ${gewenst:-0})"
    kc -n "$NS" logs "deployment/$dienst" --tail=40 || true
    kc -n "$NS" get events --field-selector "involvedObject.kind=Pod" --sort-by=.lastTimestamp | grep "$dienst" | tail -5 || true
  fi

  code=$(probe_code "$dienst" met-label)

  if [ "${code%% *}" = 200 ]; then
    echo "OK: $dienst /q/health/ready binnen het cluster (200)"
  else
    fout "$dienst /q/health/ready binnen het cluster gaf $code, verwacht 200"
  fi

  # De NetworkPolicy is wat de dienst afschermt bij tlsTermination=mesh; zonder clientlabel hoort
  # een pod er niet in te komen. Een Service zonder gereede pods weigert ook, met dezelfde
  # foutcode: de weigering telt alleen als alle pods de hele probe gereed bleven.
  local voor na
  voor=$(gereedheid_stempel "$dienst") || voor=""
  code=$(probe_code "$dienst" zonder-label)
  na=$(gereedheid_stempel "$dienst") || na=""

  if ! alle_pods_gereed "$voor" || [ "$voor" != "$na" ]; then
    fout "$dienst was tijdens de probe zonder clientlabel niet steeds gereed; de weigering zegt dan niets over de NetworkPolicy"
  elif probe_geweigerd "$code"; then
    echo "OK: $dienst weigert een pod zonder clientlabel (curl ${code#* })"
  else
    fout "$dienst gaf $code aan een pod zonder clientlabel, verwacht geen verbinding"
  fi

  # Envoy pikt een nieuwe route pas na de rollout op.
  local einde=$((SECONDS + GATEWAY_WACHTTIJD))

  until code=$(via_gateway "$dienst" /openapi.json) && [ "$code" = 200 ] || [ "$SECONDS" -ge "$einde" ]; do
    sleep 2
  done

  if [ "$code" = 200 ]; then
    echo "OK: $dienst bereikbaar via https://$dienst.fbs.localhost:$HTTPS_POORT/openapi.json"
  else
    fout "$dienst via de Gateway gaf $code op /openapi.json, verwacht 200"
  fi

  # /q (health, Swagger UI) hoort niet via de Gateway bereikbaar te zijn.
  code=$(via_gateway "$dienst" /q/health/ready)

  if [ "$code" = 404 ]; then
    echo "OK: $dienst /q niet via de Gateway (404)"
  else
    fout "$dienst /q/health/ready via de Gateway gaf $code, verwacht 404"
  fi
}

controleer() {
  stap "controleren"

  fouten=0

  [ -s "$WERK/lokale-ca.crt" ] ||
    kc -n "$NS" get secret web-tls -o jsonpath='{.data.ca\.crt}' | base64 -d >"$WERK/lokale-ca.crt"

  # Anders toont een kapotte DNS zich pas als een rollout die na vijf minuten niet klaar is, zonder
  # oorzaak; of, na een netwerkwissel zonder herstart, helemaal niet zolang de pods blijven draaien.
  sandbox_dns_ok || fout "$DNS_MELDING"

  local dienst

  for dienst in "${DIENSTEN[@]}"; do
    controleer_dienst "$dienst"
  done

  if [ "$fouten" -ne 0 ]; then
    echo "$fouten controle(s) niet in orde. De opstelling blijft staan voor onderzoek met $KC_SANDBOX -n $NS."
    na_afloop
    exit 1
  fi

  echo "Alle diensten gezond en bereikbaar. Weg met: $0 af"
}

# --- Opruimen ------------------------------------------------------------------------------------

# Ruimt op wat er staat, ook als de worktree al weg is: de recipes om op te ruimen staan in de
# worktree, dus die komt dan eerst terug. Het platform alleen met --met-platform: het kan voor ander
# Fundament-werk in gebruik zijn, en opnieuw opzetten bouwt alle images opnieuw. Een sandbox die niet
# van deze opstelling is, blijft staan.
af() {
  local met_platform=$1 gedaan=nee

  if sandbox_bestaat; then
    sandbox_van_ons || stop 2 "de sandbox k3d-fundament-plugin is niet van deze opstelling; ruim hem zelf op"
    fundament_worktree >/dev/null
    fj plugins cluster-delete
    echo "OK: sandbox weg"
    gedaan=ja
  fi

  if [ "$met_platform" = ja ] && platform_bestaat; then
    fundament_worktree >/dev/null
    fj cluster-delete
    echo "OK: platform weg"
    gedaan=ja
  fi

  if [ -d "$WORKTREE" ]; then
    git -C "$FUNDAMENT_REPO" worktree remove --force "$WORKTREE"
    echo "OK: worktree $WORKTREE weg"
    gedaan=ja
  fi

  # De functl-sessie van deze opstelling: een API-key die nog tot een uur geldig is.
  rm -rf "$FUNCTL_CONFIG_DIR"

  [ "$gedaan" = ja ] || echo "OK: niets op te ruimen"

  if [ "$met_platform" != ja ] && platform_bestaat; then
    echo "Het platform k3d-fundament blijft staan; ook weg met: $0 af --met-platform"
  fi
}

# Wat er na een proef met de opstelling gebeurt, is een keuze van wie hem draait: laten staan scheelt
# een volgende keer de opbouw, maar de twee clusters houden ook zonder verkeer de processor wakker
# (op een laptop op batterij bijna een derde van het verbruik bij stilstand). Na een herstart van de
# machine start Docker draaiende clusters zelf (restart-policy unless-stopped), en kon de node ghcr.io
# niet opzoeken (zie sandbox_dns_ok); een herstart via k3d herstelt dat. Pauzeren via k3d, zoals
# Fundament het doet, niet met docker stop/start.
na_afloop() {
  cat <<EOF

De opstelling blijft draaien en kost ook zonder verkeer stroom. Na een herstart van de machine
start Docker de containers zelf weer, en kunnen pods daarna hun image soms niet ophalen. Herstart
beide clusters dan via k3d en draai daarna op:
  $K3D_HERSTART
  TAG=<tag> $0 op

Kies:
  laten draaien   niets doen
  pauzeren        (cd $WORKTREE && mise exec -- k3d cluster stop fundament-plugin fundament)
                  hervatten, vóór een volgende op of controleer:
                  (cd $WORKTREE && mise exec -- k3d cluster start fundament fundament-plugin)
  opruimen        $0 af --met-platform
                  (alleen af laat het platform draaien; opnieuw opzetten bouwt alle images van Fundament)
EOF
}

# --- Subcommando's -------------------------------------------------------------------------------

op() {
  : "${TAG:?zet TAG op een gepubliceerde image-tag, bijvoorbeeld main-<sha7> van de laatste main-commit}"
  [[ $REPLICAS =~ ^[1-9][0-9]?$ ]] || stop 2 "REPLICAS moet een geheel getal van 1 tot 99 zijn"
  geen_test_vm
  vereis_tools docker mise git jq curl openssl mkcert "$(command -v ss >/dev/null && echo ss || echo lsof)"
  # type -P: kubectl en helm zijn hierboven vangfuncties, command -v zou die vinden.
  type -P kubectl >/dev/null && type -P helm >/dev/null || stop 2 "kubectl en helm moeten op het PATH staan"

  werkmap
  voorcontrole
  fundament_worktree
  platform_op
  functl_inloggen
  sandbox_op
  plugins_op
  voorzieningen_op

  case "$ROL" in
    beheerder | beide)
      installeer_beheerder
      controleer
      ;;
  esac

  case "$ROL" in
    projectlid | beide)
      installeer_projectlid
      controleer
      ;;
  esac

  na_afloop
}

main() {
  ROL=beide

  case "${1:-}" in
    op)
      case "${2:-}" in
        "") ;;
        --rol)
          case "${3:-}" in
            beheerder | projectlid | beide) ROL=$3 ;;
            *) stop 2 "--rol is beheerder, projectlid of beide" ;;
          esac

          [ "$#" -eq 3 ] || stop 2 "onbekend argument na --rol $3: ${4:-}"
          ;;
        *) stop 2 "onbekend argument: $2" ;;
      esac

      op
      ;;
    af)
      vereis_tools docker git mise jq
      geen_test_vm

      case "${2:-}" in
        "") af nee ;;
        --met-platform) af ja ;;
        *) stop 2 "af kent alleen --met-platform" ;;
      esac
      ;;
    controleer)
      [ "$#" -eq 1 ] || stop 2 "controleer kent geen argumenten"
      vereis_tools curl docker
      [ -f "$KUBECONFIG_LOKAAL" ] ||
        stop 2 "geen opstelling op Fundament ${FUNDAMENT_REF:0:8} ($KUBECONFIG_LOKAAL ontbreekt); zet dezelfde FUNDAMENT_REF als bij op, of draai eerst op"
      werkmap
      controleer
      ;;
    *)
      echo "gebruik: $0 op [--rol beheerder|projectlid|beide] | controleer | af [--met-platform]" >&2
      exit 2
      ;;
  esac
}

# Gesourcet (door de tests) draait er niets.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
