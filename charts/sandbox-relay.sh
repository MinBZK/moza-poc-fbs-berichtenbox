#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Maakt de Gateway van FBS uit fundament-testcluster.sh bereikbaar vanuit de claude-sandbox
# (hackathon-claude-code/claude-sandbox): de demo en de diensten via https, zoals op het fieldlab.
#
#   charts/sandbox-relay.sh op        relay starten, of opnieuw na nieuwe HTTPRoutes
#   charts/sandbox-relay.sh status    draait hij, en welke namen wijzen ernaar
#   charts/sandbox-relay.sh af        relay weg
#
# De opstelling luistert alleen op 127.0.0.1 van de host, en dat bereikt de sandbox-container niet.
# De relay staat op het netwerk van de sandbox en op k3d-fundament-plugin, en zet alleen de Gateway web
# van FBS door, op dezelfde poort als op de host (9443 -> k3d-fundament-plugin-serverlb:443).
# Alleen TCP-doorgifte: TLS en SNI blijven van eind tot eind. De hostnamen van die Gateway krijgt
# de relay als alias op het netwerk van de sandbox; Docker-DNS laat ze daar naar de relay wijzen.
#
# Bewust NIET het platform (8443) en niet de API-server. Lokaal draait de kube-api-proxy van
# Fundament in mock-modus en stuurt verzoeken van gebruikers met de admin-gegevens van de
# sandbox-cluster door (kube-api-proxy/pkg/kube/sandbox_proxy.go): elke Fundament-gebruiker, ook
# een projectlid, is daar cluster-admin. Met het seed-account (openbaar wachtwoord) kan dat iedereen
# die authn bereikt. De nodes van k3d zijn privileged containers op de Docker van de host, dus
# cluster-admin is hier root op de machine. Om dezelfde reden niet de sandbox zelf op k3d-fundament:
# daar staan ook de registries zonder authenticatie.
#
# Exitcodes: 0 in orde, 1 een stap faalde, 2 een voorwaarde ontbreekt.
set -euo pipefail

readonly NAAM=claude-sandbox-fundament-relay
readonly SANDBOX_NET=${SANDBOX_NET:-claude-sandbox_default}
readonly K3D_NET=k3d-fundament-plugin
readonly SOCAT_IMAGE=docker.io/alpine/socat:1.8.0.3@sha256:beb4a68d9e4fe6b0f21ea774a0fde6c31f580dde6368939ed70100c5385b015e
# Gelijk aan fundament-testcluster.sh.
FUNDAMENT_REF=${FUNDAMENT_REF:-b363604dbed1d3d389504e5bb9e1b48e800618b6}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/fbs-fundament-testcluster
KUBECONFIG_LOKAAL=$CACHE/fundament-${FUNDAMENT_REF:0:8}/.dev/kubeconfig
readonly FUNDAMENT_REF CACHE KUBECONFIG_LOKAAL

stop() {
  local code=$1
  shift
  echo "FOUT: $*" >&2
  exit "$code"
}

hostnamen() {
  command kubectl --kubeconfig "$KUBECONFIG_LOKAAL" --context "$1" get httproute -A -o json |
    jq -r '[.items[].spec.hostnames[]?] | unique | .[]'
}

op() {
  [ -f "$KUBECONFIG_LOKAAL" ] || stop 2 "geen opstelling op Fundament ${FUNDAMENT_REF:0:8}; draai eerst charts/fundament-testcluster.sh op"
  docker network inspect "$SANDBOX_NET" >/dev/null 2>&1 || stop 2 "netwerk $SANDBOX_NET bestaat niet; draait de sandbox? (SANDBOX_NET)"
  docker network inspect "$K3D_NET" >/dev/null 2>&1 || stop 2 "netwerk $K3D_NET bestaat niet; draait de sandbox van Fundament?"

  local namen
  # Alleen de sandbox-cluster: geen naam van het platform mag in de sandbox naar de relay wijzen.
  namen=$(hostnamen k3d-fundament-plugin | sort -u) || stop 1 "de hostnamen van de Gateway zijn niet te lezen"
  [ -n "$namen" ] || stop 1 "de Gateway heeft geen hostnamen"
  ! grep -q 'fundament\.localhost$' <<<"$namen" || stop 1 "een hostnaam van het platform in de sandbox-cluster; die zet de relay niet door"

  docker rm -f "$NAAM" >/dev/null 2>&1 || true
  # Als nobody, read-only en zonder capabilities: socat heeft alleen één poort boven 1024 nodig.
  docker create --name "$NAAM" --network "$K3D_NET" --restart unless-stopped \
    --user 65534:65534 --read-only --cap-drop ALL --security-opt no-new-privileges \
    --entrypoint sh "$SOCAT_IMAGE" -c '
      exec socat TCP-LISTEN:9443,fork,reuseaddr TCP:k3d-fundament-plugin-serverlb:443' >/dev/null || stop 1 "de relay is niet aan te maken"

  local aliassen=() naam
  while IFS= read -r naam; do aliassen+=(--alias "$naam"); done <<<"$namen"
  docker network connect "${aliassen[@]}" "$SANDBOX_NET" "$NAAM" || stop 1 "de relay kan niet op $SANDBOX_NET"
  docker start "$NAAM" >/dev/null || stop 1 "de relay start niet"
  echo "OK: $NAAM draait; $(wc -l <<<"$namen") hostnamen wijzen in $SANDBOX_NET naar de relay"
}

af() {
  if docker rm -f "$NAAM" >/dev/null 2>&1; then
    echo "OK: $NAAM verwijderd"
  else
    echo "OK: $NAAM bestond niet"
  fi
}

status() {
  docker inspect "$NAAM" -f 'status={{.State.Status}} herstarts={{.RestartCount}}' 2>/dev/null ||
    stop 1 "$NAAM bestaat niet"
  docker inspect "$NAAM" -f '{{range $n, $v := .NetworkSettings.Networks}}{{$n}}={{$v.IPAddress}} {{$v.Aliases}}{{"\n"}}{{end}}'
}

case "${1:-}" in
  op) op ;;
  af) af ;;
  status) status ;;
  *)
    echo "gebruik: $0 op | status | af" >&2
    exit 2
    ;;
esac
