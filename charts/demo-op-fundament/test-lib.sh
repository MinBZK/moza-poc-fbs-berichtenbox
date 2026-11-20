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
grep -q '^  issuerRef: {name: letsencrypt, kind: Issuer}$' <<<"$cert" && ok "Issuer in de namespace, geen ClusterIssuer" ||
  faal "issuerRef"
grep -q '\*' <<<"$cert" && faal "certificaat vraagt een wildcard" || ok "geen wildcard: Let's Encrypt geeft die niet met HTTP-01"

# De routes van de chart en de hostnamen van de stelselreleases: dezelfde verzameling als DEMO_HOSTS.
chart_hosts=$(sed -n 's/^  (dict "naam" "\([a-z-]*\)".*/\1/p' "$HIER/../fbs-demo/templates/httproutes.yaml")
stelsel_hosts="berichtenmagazijn-a berichtenmagazijn-b stelselregister"
gelijk "DEMO_HOSTS = routes van fbs-demo + hostnamen van het stelsel" \
  "$(printf '%s\n' $chart_hosts $stelsel_hosts | sort | paste -sd' ' -)" \
  "$(printf '%s\n' "${DEMO_HOSTS[@]}" | sort | paste -sd' ' -)"

# --- manifest_cluster ---

db=$(manifest_cluster db ns 200)
grep -q '^      max_connections: "200"$' <<<"$db" && ok "db met 200 verbindingen" || faal "max_connections ontbreekt"
grep -q 'major: 18' <<<"$db" && ok "PostgreSQL 18" || faal "major"
ldv=$(manifest_cluster ldv-uitvraag ns)
grep -q 'max_connections' <<<"$ldv" && faal "ldv-uitvraag zonder eigen max_connections" || ok "ldv-uitvraag met de standaard"

# --- aantal_magazijnen ---

gelijk "lege lijst: nul" 0 "$(aantal_magazijnen '{"berichten": []}')"
gelijk "één magazijn, twee berichten" 1 \
  "$(aantal_magazijnen '{"berichten": [{"magazijnId": "00000000000000100000"}, {"magazijnId": "00000000000000100000"}]}')"
gelijk "twee magazijnen" 2 \
  "$(aantal_magazijnen '{"berichten": [{"magazijnId": "00000000000000100000"}, {"magazijnId": "00000001823288444000"}]}')"
gelijk "dieper genest, HAL" 3 \
  "$(aantal_magazijnen '{"_embedded": {"berichten": [{"magazijnId": "a"}, {"magazijnId": "b"}, {"magazijnId": "c"}]}}')"
gelijk "null of getal telt niet" 1 \
  "$(aantal_magazijnen '{"berichten": [{"magazijnId": null}, {"magazijnId": 5}, {"magazijnId": "a"}]}')"
faalt "geen JSON" aantal_magazijnen '<html>502</html>'
faalt "leeg antwoord" aantal_magazijnen ''

# --- sessie_uit_antwoord ---

gelijk "sessie uit het antwoord" "s-123" "$(sessie_uit_antwoord '{"sessieId": "s-123", "geldigTot": "x"}')"

for antwoord in '' '{}' '{"sessieId": ""}' '{"sessieId": null}' '{"sessieId": 5}' '{"type": "about:blank", "status": 429}'; do
  faalt "geen sessie in '$antwoord'" sessie_uit_antwoord "$antwoord"
done

# --- vaste lijsten ---

gelijk "acht images" 8 "${#DEMO_IMAGES[@]}"
gelijk "vier stelselreleases" 4 "${#STELSEL_RELEASES[@]}"

for release in "${STELSEL_RELEASES[@]}"; do
  [ -f "$HIER/../fbs-demo/stelsel/$release.yaml" ] && ok "values voor $release" || faal "charts/fbs-demo/stelsel/$release.yaml ontbreekt"
done

echo "ASSERTIES=$asserties"
[ "$mislukt" -eq 0 ]
