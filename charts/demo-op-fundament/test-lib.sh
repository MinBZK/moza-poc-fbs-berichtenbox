#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Unittests voor lib.sh van charts/demo-op-fundament.sh: de afleiding van het basisdomein, de
# manifests en de parsers, zonder cluster. Wat alleen in een cluster te zien is, controleert het
# script zelf (`controleer`).

set -euo pipefail

HIER=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly HIER
# shellcheck source=charts/demo-op-fundament/lib.sh
source "$HIER/lib.sh"

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

faal() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gekregen=$3

  if [ "$verwacht" = "$gekregen" ]; then
    ok "$wat"
  else
    faal "$wat: verwacht '$verwacht', kreeg '$gekregen'"
  fi
}

faalt() {
  local wat=$1
  shift

  if "$@" >/dev/null 2>&1; then
    faal "$wat: slaagde, maar had moeten falen"
  else
    ok "$wat"
  fi
}

# --- basisdomein_uit_adres ---

gelijk "IPv4 naar sslip.io" "fbs.192-0-2-10.sslip.io" "$(basisdomein_uit_adres 192.0.2.10)"
gelijk "grenswaarden 0 en 255" "fbs.0-255-0-255.sslip.io" "$(basisdomein_uit_adres 0.255.0.255)"

for adres in "" gateway.example.nl 2001:db8::1 192.0.2 192.0.2.10.5 256.0.2.10 192.0.2.1000 " 192.0.2.10" "192.0.2.10 "; do
  faalt "geen IPv4: '$adres'" basisdomein_uit_adres "$adres"
done

# --- manifest_certificaat ---

cert=$(manifest_certificaat tnt-fbs--demo fbs.x.nl letsencrypt web-tls)
gelijk "certificaat: één naam per route" "${#DEMO_HOSTS[@]}" "$(grep -c '^    - .*\.fbs\.x\.nl$' <<<"$cert")"

for host in "${DEMO_HOSTS[@]}"; do
  grep -qx "    - $host.fbs.x.nl" <<<"$cert" && ok "certificaat noemt $host" || faal "certificaat mist $host"
done

grep -q '^  secretName: web-tls$' <<<"$cert" && ok "certificaat naar het Secret van de listener" || faal "secretName"
grep -qx '  name: web-tls' <<<"$cert" && ok "certificaat heet als het Secret" || faal "metadata.name"
grep -qx '  namespace: tnt-fbs--demo' <<<"$cert" && ok "certificaat in de namespace van de demo" || faal "namespace"
grep -q '^  issuerRef: {name: letsencrypt, kind: Issuer}$' <<<"$cert" && ok "Issuer in de namespace, geen ClusterIssuer" ||
  faal "issuerRef"
grep -q '\*' <<<"$cert" && faal "certificaat vraagt een wildcard" || ok "geen wildcard: Let's Encrypt geeft die niet met HTTP-01"

# De routes van de chart en de hostnamen van de stelselreleases: dezelfde verzameling als DEMO_HOSTS.
chart_hosts=$(sed -n 's/^  (dict "naam" "\([a-z-]*\)".*/\1/p' "$HIER/../fbs-demo/templates/httproutes.yaml")
# De stelselreleases met een route: alle behalve de uitvraag (stelsel/berichtenuitvraag.yaml).
stelsel_hosts=$(printf '%s\n' "${STELSEL_RELEASES[@]}" | grep -vx berichtenuitvraag)
gelijk "DEMO_HOSTS = routes van fbs-demo + hostnamen van het stelsel" \
  "$(printf '%s\n' $chart_hosts $stelsel_hosts | sort | paste -sd' ' -)" \
  "$(printf '%s\n' "${DEMO_HOSTS[@]}" | sort | paste -sd' ' -)"

# --- manifest_cluster ---

db=$(manifest_cluster db ns 200)
grep -q '^      max_connections: "200"$' <<<"$db" && ok "db met 200 verbindingen" || faal "max_connections ontbreekt"
grep -q 'major: 18' <<<"$db" && ok "PostgreSQL 18" || faal "major"
ldv=$(manifest_cluster ldv-uitvraag ns)
grep -q 'max_connections' <<<"$ldv" && faal "ldv-uitvraag zonder eigen max_connections" || ok "ldv-uitvraag met de standaard"

# --- keten_oordeel ---

A=$MAGAZIJN_A_OIN
B=$MAGAZIJN_B_OIN
voltooid() { printf 'event: magazijn-bevraging-voltooid\ndata: {"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"%s","aantalBerichten":%s}\n\n' "$1" "$2" "$3"; }
gereed='event: ophalen-gereed
data: {"event":"ophalen-gereed"}'

stroom="$(voltooid "$A" OK 3; voltooid 00000009000000000001 OK 27; voltooid "$B" OK 2)
$gereed"
keten_oordeel "$stroom" >/dev/null && ok "A en B leverden, met een gesimuleerd magazijn ertussen" || faal "A en B OK gaf geen oordeel in orde"

# Zonder spatie na data: mag volgens de SSE-spec ook.
stroom=$(printf 'data:{"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"OK","aantalBerichten":1}\ndata:{"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"OK","aantalBerichten":1}\n' "$A" "$B")
keten_oordeel "$stroom" >/dev/null && ok "data: zonder spatie" || faal "data: zonder spatie"

# Een regel die geen JSON is, mag de rest niet verbergen.
stroom="data: geen json
$(voltooid "$A" OK 1; voltooid "$B" OK 1)"
keten_oordeel "$stroom" >/dev/null && ok "een regel zonder JSON ertussen" || faal "een regel zonder JSON verbergt de rest"

gelijk "B faalde" "magazijn $B: FOUT, 0 berichten" "$(keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" FOUT 0)" || true)"
gelijk "B leverde niets" "magazijn $B: OK, 0 berichten" "$(keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" OK 0)" || true)"
gelijk "A meldde niets" "magazijn $A meldde geen uitkomst" "$(keten_oordeel "$(voltooid "$B" OK 3)" || true)"
gelijk "alleen gesimuleerde magazijnen" "magazijn $A meldde geen uitkomst" \
  "$(keten_oordeel "$(voltooid 00000009000000000001 OK 27; voltooid 00000009000000000002 OK 27)" || true)"
gelijk "lege stroom" "magazijn $A meldde geen uitkomst" "$(keten_oordeel "" || true)"
gelijk "ophalen-fout zonder magazijnen" "magazijn $A meldde geen uitkomst" \
  "$(keten_oordeel 'data: {"event":"ophalen-fout","detail":"x"}' || true)"
# Alleen het voltooid-event telt: een ander event met dezelfde OIN na de fout maakt hem niet goed.
stroom="$(voltooid "$A" OK 3; voltooid "$B" FOUT 0)
data: {\"event\":\"magazijn-bericht\",\"magazijnId\":\"$B\",\"status\":\"OK\",\"aantalBerichten\":5}"
gelijk "een ander event na de fout telt niet" "magazijn $B: FOUT, 0 berichten" "$(keten_oordeel "$stroom" || true)"
faalt "B faalde: geen oordeel in orde" keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" TIMEOUT 0)"

# --- sessie_uit_antwoord ---

gelijk "sessie uit het antwoord" "s-123" "$(sessie_uit_antwoord '{"sessieId": "s-123", "geldigTot": "x"}')"

for antwoord in '' '{}' '{"sessieId": ""}' '{"sessieId": null}' '{"sessieId": 5}' '{"type": "about:blank", "status": 429}'; do
  faalt "geen sessie in '$antwoord'" sessie_uit_antwoord "$antwoord"
done

# --- vaste lijsten ---

gelijk "acht images" 8 "${#DEMO_IMAGES[@]}"

# De Deployments van fbs-demo: één per onderdeel in charts/fbs-demo/templates/_componenten.tpl.
onderdelen=$(sed -n 's/^\([a-z-]*\):$/fbs-demo-\1/p' "$HIER/../fbs-demo/templates/_componenten.tpl" | sort | paste -sd' ' -)
gelijk "DEMO_DEPLOYMENTS = de onderdelen van fbs-demo" "$onderdelen" "$(printf '%s\n' "${DEMO_DEPLOYMENTS[@]}" | sort | paste -sd' ' -)"

# --- values_demo_jssecacerts ---

for release in berichtenuitvraag berichtenmagazijn-a berichtenmagazijn-b; do
  v=$(values_demo_jssecacerts "$release" /usr/lib/jvm/jre)
  grep -qx '      mountPath: /usr/lib/jvm/jre/lib/security/jssecacerts' <<<"$v" && ok "$release: jssecacerts op JAVA_HOME" || faal "$release: mountPath"
  grep -qx '      subPath: jssecacerts' <<<"$v" && ok "$release: alleen het bestand" || faal "$release: subPath"
  grep -qx '        secretName: gateway-ca' <<<"$v" && ok "$release: uit gateway-ca" || faal "$release: secret"
done

v=$(values_demo_jssecacerts berichtenuitvraag /j)
grep -qx '        name: fbs-demo-magazijnenregister' <<<"$v" && ok "uitvraag houdt het register" || faal "uitvraag: register"
grep -qx '      mountPath: /home/jboss/config' <<<"$v" && ok "uitvraag: register op de werkmap" || faal "uitvraag: register-mount"
grep -qx '        secretName: ldv-uitvraag-ca' <<<"$v" && ok "uitvraag houdt de CA van het logboek" || faal "uitvraag: ldv-ca"
v=$(values_demo_jssecacerts berichtenmagazijn-a /j)
grep -qx '        secretName: db-ca' <<<"$v" && ok "magazijn houdt de CA van de database" || faal "magazijn: db-ca"
grep -q 'magazijnenregister' <<<"$v" && faal "magazijn krijgt het register" || ok "magazijn zonder register"
faalt "stelselregister krijgt geen jssecacerts" values_demo_jssecacerts stelselregister /j
gelijk "vier stelselreleases" 4 "${#STELSEL_RELEASES[@]}"

for release in "${STELSEL_RELEASES[@]}"; do
  [ -f "$HIER/../fbs-demo/stelsel/$release.yaml" ] && ok "values voor $release" || faal "charts/fbs-demo/stelsel/$release.yaml ontbreekt"
done

echo "ASSERTIES=$asserties"
[ "$mislukt" -eq 0 ]
