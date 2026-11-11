#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Zet de plugin-sandbox van Fundament op als fieldlab-cluster (Kubernetes 1.36, de plug-ins via de
# echte plugin-controller), installeert de drie diensten met de chart en controleert dat ze gezond
# starten en via de Gateway bereikbaar zijn. Het ontwerp staat in
# docs/plans/2026-11-11-lokaal-fundament-testcluster.md.
#
#   TAG=main-<sha7> charts/fundament-testcluster.sh op        opzetten, installeren, controleren
#   charts/fundament-testcluster.sh controleer                 alleen controleren
#   charts/fundament-testcluster.sh af                         sandbox weg; het platform blijft
#
# Leunt op een clone van fundament-oss/fundament (FUNDAMENT_REPO) en draait die op een vaste commit
# (FUNDAMENT_REF) in een eigen worktree, met de tools uit hun mise.toml. Voor het publiceren van de
# plug-ins maakt het zelf een API-key met het lokale seed-account; FUNCTL_API_KEY gaat voor.
#
# Elke kubectl- en helm-aanroep noemt de context, en het script wisselt de huidige context niet:
# de proefinstallatie heeft een onveilige klep (Redis zonder TLS) en mag niet in een echt cluster
# belanden als iemand tijdens het draaien elders van context wisselt.
#
# Exitcodes: 0 in orde, 1 een controle faalde, 2 een voorwaarde ontbreekt.
set -euo pipefail

CHARTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly CHARTS

# shellcheck source=charts/fundament-testcluster/lib.sh
source "$CHARTS/fundament-testcluster/lib.sh"

readonly SANDBOX_CONTEXT=k3d-fundament-plugin
readonly PLATFORM_CONTEXT=k3d-fundament
HTTPS_POORT=${HTTPS_POORT:-9443}
FUNDAMENT_REPO=${FUNDAMENT_REPO:-$HOME/dev/fundament}
FUNDAMENT_REF=${FUNDAMENT_REF:-b9c12531}
FUNDAMENT_SUBNET=${FUNDAMENT_SUBNET:-172.29.0.0/16}
readonly HTTPS_POORT FUNDAMENT_REPO FUNDAMENT_REF FUNDAMENT_SUBNET
WERKBOOM=${XDG_CACHE_HOME:-$HOME/.cache}/fbs-fundament-testcluster/fundament-$FUNDAMENT_REF
readonly WERKBOOM

readonly K3S_IMAGE=docker.io/rancher/k3s:v1.36.6-k3s1@sha256:88d3fac10eb4a429d3c5275f4868fe4b13620f6bbca5bdb5dca0627ac76ae2a0

# De organisatie system publiceert de first-party plug-ins (seeddata van Fundament).
readonly SYSTEM_ORGANISATIE_ID=019b4000-0000-7000-8000-000000000000
# <pad onder plugins/>:<metadata.name uit definition.yaml>
readonly PLUGINS=(cert-manager:cert-manager gateway-api/envoy-gateway:gateway-api-envoy cloudnativepg:cloudnativepg)
# Een geseede DCIM-gebruiker van Fundament die versies mag goedkeuren (zoals in test-env).
readonly REVIEWER=alice@acme-corp.com

# Een eigen functl-configuratie: de sessie van deze opstelling mag die van de gebruiker niet
# overschrijven.
export FUNCTL_CONFIG_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/fbs-fundament-testcluster/functl

kc() { kubectl --context "$SANDBOX_CONTEXT" "$@"; }
kcp() { kubectl --context "$PLATFORM_CONTEXT" "$@"; }
hm() { helm --kube-context "$SANDBOX_CONTEXT" "$@"; }
# Alleen voor het lezen en terugzetten van de huidige context, nooit voor iets in een cluster.
kconfig() { kubectl config "$@"; }

# Een recipe van Fundament, in de worktree en met de tools uit hun mise.toml.
fj() { (cd "$WERKBOOM" && mise exec -- just "$@"); }

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

# --- Fundament -----------------------------------------------------------------------------------

# Fundament op FUNDAMENT_REF in een eigen worktree, zodat de clone zelf niet verandert en de
# opstelling niet meebeweegt met wat daar toevallig uitgecheckt staat.
fundament_werkboom() {
  [ -d "$FUNDAMENT_REPO/.git" ] || stop 2 "geen clone van fundament-oss/fundament in $FUNDAMENT_REPO (zet FUNDAMENT_REPO)"

  local commit
  commit=$(git -C "$FUNDAMENT_REPO" rev-parse --verify --quiet "$FUNDAMENT_REF^{commit}") ||
    stop 2 "$FUNDAMENT_REF bestaat niet in $FUNDAMENT_REPO (git -C $FUNDAMENT_REPO fetch)"

  if [ -d "$WERKBOOM" ]; then
    [ "$(git -C "$WERKBOOM" rev-parse HEAD)" = "$commit" ] ||
      stop 2 "$WERKBOOM staat niet op $FUNDAMENT_REF; ruim hem op met: $0 af"
  else
    stap "Fundament $FUNDAMENT_REF in $WERKBOOM"
    mkdir -p "$(dirname "$WERKBOOM")"
    git -C "$FUNDAMENT_REPO" worktree add --detach "$WERKBOOM" "$commit"
  fi

  (cd "$WERKBOOM" && mise trust --quiet && mise install --quiet)
}

platform_draait() {
  [ "$(docker inspect -f '{{.State.Running}}' k3d-fundament-server-0 2>/dev/null)" = true ]
}

# Een Deployment die bestaat maar niet start (een image dat niet op te halen is) telt niet als
# een draaiend platform.
platform_gereed() {
  [ "$(kcp -n fundament get deployment organization-api -o jsonpath='{.status.availableReplicas}' 2>/dev/null)" -ge 1 ] 2>/dev/null
}

context_terugzetten() {
  local voor=$1

  [ "$(kconfig current-context 2>/dev/null || true)" != "$voor" ] || return 0

  if [ -n "$voor" ]; then
    kconfig use-context "$voor" >/dev/null
  else
    kconfig unset current-context >/dev/null
  fi

  echo "OK: huidige context teruggezet op ${voor:-<geen>}"
}

console_antwoordt() {
  curl -sf -o /dev/null --max-time 5 --cacert "$(mkcert -CAROOT)/rootCA.pem" https://console.fundament.localhost:8443/
}

# Het platform, alleen als het nog niet draait: elke uitrol wist zijn databases, en daarmee de
# API-keys en de gepubliceerde plug-ins.
platform_op() {
  stap "Fundament-platform ($PLATFORM_CONTEXT)"

  if platform_draait && platform_gereed; then
    echo "OK: platform draait al"
  else
    # Fundament maakt het netwerk anders zelf op 172.19.0.0/16, alleen om 172.18 (Gardener) te
    # ontwijken; op een machine met meer compose-projecten is dat subnet vaak al bezet. Een
    # bestaand netwerk k3d-fundament gebruikt hun recipe gewoon.
    if ! docker network inspect k3d-fundament >/dev/null 2>&1; then
      docker network create --subnet "$FUNDAMENT_SUBNET" k3d-fundament >/dev/null ||
        stop 2 "subnet $FUNDAMENT_SUBNET is bezet (zet FUNDAMENT_SUBNET op een vrij /16)"
    fi

    # k3d wisselt bij het aanmaken van het platform de huidige context; zet die terug, zodat een
    # kale kubectl na deze opstelling nog wijst waar hij voor het draaien wees.
    local context_voor
    context_voor=$(kconfig current-context 2>/dev/null || true)
    fj cluster-start
    context_terugzetten "$context_voor"
    # Zoals `just dev`, maar eenmalig. Niet `just deploy local`: dat pusht naar
    # k3d-registry.localhost:5111 (SKAFFOLD_DEFAULT_REPO uit hun mise.toml), een naam die de
    # node niet kent; de mirror in de k3d-config dekt alleen localhost:5111.
    echo "Platform uitrollen; de eerste keer bouwt dit alle images van Fundament en duurt het lang."
    (cd "$WERKBOOM" && mise exec -- skaffold run --kube-context "$PLATFORM_CONTEXT" --profile env-local \
      --default-repo=localhost:5111)
  fi

  wacht_tot 900 "console bereikbaar op https://console.fundament.localhost:8443" console_antwoordt ||
    stop 1 "de console antwoordt niet; zie: kubectl --context $PLATFORM_CONTEXT -n fundament get pods"
}

# Een API-key voor functl, zoals de e2e-suite van Fundament die maakt: inloggen met het
# wachtwoord van het seed-account platform-admin (alleen lokaal; elke uitrol van het platform zet
# het terug) en CreateAPIKey aanroepen. Het toegangstoken gaat via een bestand naar curl, niet op
# de commandoregel: die is via ps zichtbaar voor andere gebruikers.
api_key_maken() {
  local ca token antwoord
  ca="$(mkcert -CAROOT)/rootCA.pem"

  token=$(curl -sf --cacert "$ca" -X POST https://authn.fundament.localhost:8443/login/password \
    -H 'Content-Type: application/json' -d '{"email":"platform-admin@fundament.io","password":"password"}' |
    jq -er .access_token) || return 1

  printf 'Authorization: Bearer %s\n' "$token" >"$WERK/auth-header"
  antwoord=$(curl -s --cacert "$ca" -X POST \
    https://organization.fundament.localhost:8443/organization.v1.APIKeyService/CreateAPIKey \
    -H 'Content-Type: application/json' -H @"$WERK/auth-header" \
    -H "Fun-Organization: $SYSTEM_ORGANISATIE_ID" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
    -d '{"name":"fbs-fundament-testcluster","expiresIn":"1h"}')
  rm -f "$WERK/auth-header"

  api_key_uit_antwoord "$antwoord"
}

# functl inloggen voor de organisatie system. Een eerdere sessie blijft geldig tot de key
# verloopt of het platform opnieuw is uitgerold; dan komt er een nieuwe key. FUNCTL_API_KEY gaat
# voor, voor wie een eigen key wil gebruiken. De key gaat via stdin naar functl.
functl_inloggen() {
  stap "functl"

  local uitvoer key=${FUNCTL_API_KEY:-}

  # `functl auth status` slaagt ook zonder login; `org set` vraagt een geldige sessie.
  if [ -z "$key" ] && uitvoer=$(fj functl org set "$SYSTEM_ORGANISATIE_ID" 2>&1); then
    echo "OK: functl was al ingelogd"
    return
  fi

  if [ -z "$key" ]; then
    [ "$(functl_fout_soort "$uitvoer")" = niet-ingelogd ] || { echo "$uitvoer" >&2; stop 1 "functl org set faalde"; }
    key=$(api_key_maken) || stop 1 "kon geen API-key maken met het seed-account platform-admin@fundament.io"
  fi

  uitvoer=$(printf '%s\n' "$key" | fj functl auth login 2>&1) || {
    echo "$uitvoer" >&2
    stop 1 "functl auth login faalde (na een nieuwe uitrol van het platform is een oude FUNCTL_API_KEY ongeldig)"
  }
  uitvoer=$(fj functl org set "$SYSTEM_ORGANISATIE_ID" 2>&1) || { echo "$uitvoer" >&2; stop 1 "functl org set faalde"; }
  echo "OK: functl ingelogd voor de organisatie system"
}

# k3d staat alleen via de mise.toml van Fundament op het PATH.
sandbox_bestaat() { (cd "$WERKBOOM" && mise exec -- k3d cluster get fundament-plugin >/dev/null 2>&1); }

# De sandbox van Fundament, maar op Kubernetes 1.36 en met de https-poort op loopback. Niet via
# `just plugins cluster-create`: die wisselt de huidige context.
sandbox_op() {
  stap "sandbox ($SANDBOX_CONTEXT, Kubernetes 1.36)"

  if sandbox_bestaat; then
    sandbox_past "$(kc version -o json | jq -r .serverVersion.gitVersion)" \
      "$(docker port k3d-fundament-plugin-serverlb)" "$HTTPS_POORT" ||
      stop 2 "deze sandbox is niet van deze opstelling; ruim hem op met: (cd $WERKBOOM && mise exec -- just plugins cluster-delete)"
    echo "OK: sandbox bestaat al"
  else
    poort_vrij "$HTTPS_POORT" || stop 2 "poort 127.0.0.1:$HTTPS_POORT is bezet (zet HTTPS_POORT)"
    (cd "$WERKBOOM/plugins" && mise exec -- k3d cluster create --config=sandbox/k3d-config.yaml \
      --image "$K3S_IMAGE" -p "127.0.0.1:$HTTPS_POORT:443@loadbalancer" \
      --kubeconfig-switch-context=false --wait)
  fi

  fj plugins deploy
  fj plugin-sandbox-kubeconfig
  fj plugins sandbox-catalog
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

# Een versie die al bestaat, bij een herhaalde run na een onderbreking. De hash staat alleen in de
# uitvoer van de eerste publish; de installatie wordt dan niet gepind. Print "<versie>".
plugin_hervatten() {
  local naam=$1 uitvoer=$2 versie status id

  versie=$(bestaande_versie "$uitvoer") || { echo "$uitvoer" >&2; stop 1 "publiceren van $naam faalde"; }
  read -r status id < <(versie_uit_catalogus "$(fj funops plugin list --status all -o json 2>/dev/null)" "$naam" "$versie") ||
    stop 1 "versie $versie van $naam staat niet in de catalogus"

  case "$status" in
    approved) ;;
    pending) fj funops plugin approve "$id" "$versie" --reviewer "$REVIEWER" >&2 || stop 1 "goedkeuren van $naam $versie faalde" ;;
    *)
      stop 1 "versie $versie van $naam staat op $status en is niet meer in te dienen; rol het platform opnieuw uit: (cd $WERKBOOM && mise exec -- just cluster-delete) en $0 op"
      ;;
  esac

  echo "LET OP: $naam $versie stond al in de catalogus; de installatie is niet gepind op een hash." >&2
  echo "$versie"
}

# Publiceert en installeert een plug-in, tenzij de installatie er al is.
plugin_op() {
  local pad=$1 naam=$2
  local installatie=system--$naam

  if kc get plugininstallation "$installatie" >/dev/null 2>&1; then
    echo "OK: $installatie bestaat al"
  else
    local uitvoer versie hash=""

    if uitvoer=$(plugin_publiceren "$pad"); then
      read -r versie hash <<<"$uitvoer"
    elif [ "$(functl_fout_soort "$uitvoer")" = bestaat-al ]; then
      versie=$(plugin_hervatten "$naam" "$uitvoer")
    else
      echo "$uitvoer" >&2
      stop 1 "publiceren van $pad faalde"
    fi

    manifest_plugininstallatie "$naam" "$versie" "$hash" | kc apply -f -
  fi

  wacht_tot 600 "$installatie draait" installatie_klaar "$installatie" || {
    kc get plugininstallation "$installatie" -o jsonpath='{.status.message}{"\n"}' >&2 || true
    stop 1 "$installatie draait niet; zie: kubectl --context $SANDBOX_CONTEXT get plugininstallation $installatie -o yaml"
  }
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
}

# --- Subcommando's -------------------------------------------------------------------------------

op() {
  : "${TAG:?zet TAG op een gepubliceerde image-tag, bijvoorbeeld main-<sha7> van de laatste main-commit}"
  vereis_tools docker kubectl helm mise git jq curl ss openssl mkcert

  umask 077
  WERK=$(mktemp -d)
  trap 'rm -rf "$WERK"' EXIT

  fundament_werkboom
  platform_op
  functl_inloggen
  sandbox_op
  plugins_op
}

case "${1:-}" in
  op) op ;;
  *)
    echo "gebruik: $0 op|controleer|af" >&2
    exit 2
    ;;
esac
