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

stream="$(voltooid "$A" OK 3; voltooid 00000009000000000001 OK 27; voltooid "$B" OK 2)
$gereed"
keten_oordeel "$stream" >/dev/null && ok "A en B leverden, met een gesimuleerd magazijn ertussen" || faal "A en B OK gaf geen oordeel in orde"

# Zonder spatie na data: mag volgens de SSE-spec ook.
stream=$(printf 'data:{"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"OK","aantalBerichten":1}\ndata:{"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"OK","aantalBerichten":1}\n' "$A" "$B")
keten_oordeel "$stream" >/dev/null && ok "data: zonder spatie" || faal "data: zonder spatie"

# Een regel die geen JSON is, mag de rest niet verbergen.
stream="data: geen json
$(voltooid "$A" OK 1; voltooid "$B" OK 1)"
keten_oordeel "$stream" >/dev/null && ok "een regel zonder JSON ertussen" || faal "een regel zonder JSON verbergt de rest"

gelijk "B faalde" "magazijn $B: FOUT, 0 berichten" "$(keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" FOUT 0)" || true)"
gelijk "B leverde niets" "magazijn $B: OK, 0 berichten" "$(keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" OK 0)" || true)"
gelijk "A meldde niets" "magazijn $A meldde geen uitkomst" "$(keten_oordeel "$(voltooid "$B" OK 3)" || true)"
gelijk "alleen gesimuleerde magazijnen" "magazijn $A meldde geen uitkomst" \
  "$(keten_oordeel "$(voltooid 00000009000000000001 OK 27; voltooid 00000009000000000002 OK 27)" || true)"
gelijk "lege stream" "magazijn $A meldde geen uitkomst" "$(keten_oordeel "" || true)"
gelijk "ophalen-fout zonder magazijnen" "magazijn $A meldde geen uitkomst" \
  "$(keten_oordeel 'data: {"event":"ophalen-fout","detail":"x"}' || true)"
# Alleen het voltooid-event telt: een ander event met dezelfde OIN na de fout maakt hem niet goed.
stream="$(voltooid "$A" OK 3; voltooid "$B" FOUT 0)
data: {\"event\":\"magazijn-bericht\",\"magazijnId\":\"$B\",\"status\":\"OK\",\"aantalBerichten\":5}"
gelijk "een ander event na de fout telt niet" "magazijn $B: FOUT, 0 berichten" "$(keten_oordeel "$stream" || true)"
# Twee uitkomsten voor B: de laatste telt.
gelijk "de laatste uitkomst van B telt" "magazijn $B: FOUT, 0 berichten" "$(keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" OK 2; voltooid "$B" FOUT 0)" || true)"
faalt "B faalde: geen oordeel in orde" keten_oordeel "$(voltooid "$A" OK 3; voltooid "$B" TIMEOUT 0)"

# --- sessie_cookie_uit_kop ---

kop=$(mktemp)
printf 'HTTP/1.1 201 Created\r\nSet-Cookie: keten-sessie=%%7B%%22s%%22%%3A1%%7D; Path=/api; HttpOnly\r\n\r\n' >"$kop"
gelijk "sessie-cookie uit de headers" "keten-sessie=%7B%22s%22%3A1%7D" "$(sessie_cookie_uit_kop "$kop")"
printf 'HTTP/2 201\r\nset-cookie: personaId=x; Path=/\r\nset-cookie: keten-sessie=abc; Path=/api\r\n' >"$kop"
gelijk "naast een ander cookie, in kleine letters" "keten-sessie=abc" "$(sessie_cookie_uit_kop "$kop")"

printf 'HTTP/1.1 201\r\nSet-Cookie:keten-sessie=x; Path=/api\r\n' >"$kop"
gelijk "zonder spatie na de dubbele punt" "keten-sessie=x" "$(sessie_cookie_uit_kop "$kop")"
printf 'HTTP/1.1 201\r\nSet-Cookie: keten-sessie=; Max-Age=0\r\nSet-Cookie: keten-sessie=nieuw\r\n' >"$kop"
gelijk "een gewist en daarna een nieuw cookie: het laatste telt" "keten-sessie=nieuw" "$(sessie_cookie_uit_kop "$kop")"

for inhoud in '' 'HTTP/1.1 201\r\n' 'HTTP/1.1 201\r\nSet-Cookie: personaId=x\r\n' 'HTTP/1.1 201\r\nSet-Cookie: keten-sessie=; Max-Age=0\r\n' 'HTTP/1.1 201\r\nSet-Cookie: keten-sessie="";Path=/api\r\n' 'HTTP/1.1 201\r\nSet-Cookie: keten-sessie=a\r\nSet-Cookie: keten-sessie=; Max-Age=0\r\n'; do
  printf '%b' "$inhoud" >"$kop"
  faalt "geen sessie-cookie in '$inhoud'" sessie_cookie_uit_kop "$kop"
done

faalt "geen headerbestand" sessie_cookie_uit_kop "$kop.bestaat-niet"
rm -f "$kop"

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

# --- aanmelding_lezen ---

OIN_C=00000009000000000777
URL_C='magazijnen."00000009000000000777".url=https://magazijn-c.voorbeeld.nl'
NAAM_C='magazijnen."00000009000000000777".naam=Testmagazijn C'

a=$(printf '# uit de ConfigMap\n\n%s\r\n  %s  \n' "$URL_C" "$NAAM_C" | aanmelding_lezen -)
gelijk "lezen: de OIN" "$OIN_C" "$(jq -r .oin <<<"$a")"
gelijk "lezen: de url zonder sleutel" "https://magazijn-c.voorbeeld.nl" "$(jq -r .url <<<"$a")"
gelijk "lezen: de twee regels, zonder CR, spaties en commentaar" "$URL_C"$'\n'"$NAAM_C" "$(jq -r .regels <<<"$a")"
a=$(printf '%s\n%s' "$NAAM_C" "$URL_C" | aanmelding_lezen -)
gelijk "lezen: volgorde vrij, laatste regel zonder regelovergang" "$URL_C"$'\n'"$NAAM_C" "$(jq -r .regels <<<"$a")"
ESCAPES='magazijnen."00000009000000000777".naam=Met \: en \\ en é'
a=$(printf '%s\n%s\n' "$URL_C" "$ESCAPES" | aanmelding_lezen -)
gelijk "lezen: escapes uit het properties-formaat blijven" "$ESCAPES" "$(jq -r '.regels | split("\n")[1]' <<<"$a")"
WERKMAP_LIB=$(mktemp -d)
trap 'rm -rf "$WERKMAP_LIB"' EXIT
printf '%s\n%s\n' "$URL_C" "$NAAM_C" >"$WERKMAP_LIB/c.properties"
gelijk "lezen: uit een bestand" "$OIN_C" "$(aanmelding_lezen "$WERKMAP_LIB/c.properties" | jq -r .oin)"

weigert_regels() {
  local wat=$1 regels=$2

  if printf '%s' "$regels" | aanmelding_lezen - >/dev/null 2>&1; then
    faal "lezen weigert $wat"
  else
    ok "lezen weigert $wat"
  fi
}

weigert_regels "een lege invoer" ""
weigert_regels "alleen url" "$URL_C"
weigert_regels "alleen naam" "$NAAM_C"
weigert_regels "url twee keer" "$URL_C"$'\n'"$URL_C"$'\n'"$NAAM_C"
weigert_regels "twee OIN's" "$URL_C"$'\n''magazijnen."00000009000000000778".naam=D'
weigert_regels "een andere instelling" "$URL_C"$'\n'"$NAAM_C"$'\n''quarkus.http.port=1'
weigert_regels "http" 'magazijnen."00000009000000000777".url=http://magazijn-c.voorbeeld.nl'$'\n'"$NAAM_C"
weigert_regels "een url met spatie" 'magazijnen."00000009000000000777".url=https://a b'$'\n'"$NAAM_C"
weigert_regels "een OIN van 19 cijfers" 'magazijnen."0000000900000000077".url=https://a'$'\n''magazijnen."0000000900000000077".naam=C'
weigert_regels "een OIN zonder aanhalingstekens" 'magazijnen.00000009000000000777.url=https://a'$'\n'"$NAAM_C"
weigert_regels "een lege naam" "$URL_C"$'\n''magazijnen."00000009000000000777".naam='
weigert_regels "een naam die op een backslash eindigt" "$URL_C"$'\n''magazijnen."00000009000000000777".naam=C\'
weigert_regels "een CR midden in de naam (regeleinde voor Java)" "$URL_C"$'\n''magazijnen."00000009000000000777".naam=C'$'\r''quarkus.http.port=1'
weigert_regels "een form feed in de naam" "$URL_C"$'\n''magazijnen."00000009000000000777".naam=C'$'\f''x'
weigert_regels "een backslash voor een CR" "$URL_C"$'\n''magazijnen."00000009000000000777".naam=C\'$'\r''x'
weigert_regels "een tab in de url" 'magazijnen."00000009000000000777".url=https://a'$'\t''b'$'\n'"$NAAM_C"
weigert_regels "de zin uit het statusbericht" \
  "voeg toe aan het magazijnregister: $URL_C en $NAAM_C"

# --- aanmeldingen_met en aanmeldingen_zonder ---

leeg='{"register":"","optIns":[]}'
basis='{"partijId":902,"voorkeuren":[{"voorkeurType":"OntvangViaBerichtenbox","waarde":"true","scopes":[]}]}'
a=$(printf '%s\n%s\n' "$URL_C" "$NAAM_C" | aanmelding_lezen -)

n=$(aanmeldingen_met "$leeg" "$a" '{}')
gelijk "met: register met de twee regels" "$URL_C"$'\n'"$NAAM_C" "$(jq -r .register <<<"$n")"
gelijk "met: zonder ontvanger geen opt-in" 0 "$(jq '.optIns | length' <<<"$n")"
aangemeld "$n" "$OIN_C" && ok "aangemeld: na met" || faal "aangemeld: na met"
aangemeld "$leeg" "$OIN_C" && faal "aangemeld: leeg" || ok "aangemeld: niet in leeg"

n=$(aanmeldingen_met "$leeg" "$a" "{\"KVK:90000014\": $basis}" KVK:90000014)
gelijk "met: een opt-in voor de ontvanger" '[{"ontvanger":"KVK:90000014","oins":["00000009000000000777"]}]' \
  "$(jq -c '[.optIns[] | {ontvanger, oins}]' <<<"$n")"
gelijk "met: de basis uit het antwoord" 902 "$(jq '.optIns[0].basis.partijId' <<<"$n")"

# Opnieuw met een andere url: de regels vervangen, de opt-in blijft één keer, de basis blijft.
a2=$(printf '%s\n%s\n' 'magazijnen."00000009000000000777".url=https://nieuw.voorbeeld.nl' "$NAAM_C" | aanmelding_lezen -)
n2=$(aanmeldingen_met "$n" "$a2" '{}' KVK:90000014)
gelijk "opnieuw: de url vervangen" 1 "$(jq -r '.register | split("\n") | map(select(contains(".url="))) | length' <<<"$n2")"
bevat_regel() { grep -qxF -- "$2" <<<"$3" && ok "$1" || faal "$1: '$2' ontbreekt"; }
bevat_regel "opnieuw: de nieuwe url" 'magazijnen."00000009000000000777".url=https://nieuw.voorbeeld.nl' "$(jq -r .register <<<"$n2")"
gelijk "opnieuw: de OIN één keer in de opt-in" '["00000009000000000777"]' "$(jq -c '.optIns[0].oins' <<<"$n2")"
gelijk "opnieuw: de basis blijft" 902 "$(jq '.optIns[0].basis.partijId' <<<"$n2")"

# Een tweede magazijn: het eerste blijft staan, en de ontvanger krijgt beide.
a3=$(printf '%s\n%s\n' 'magazijnen."00000009000000000778".url=https://d.voorbeeld.nl' 'magazijnen."00000009000000000778".naam=D' | aanmelding_lezen -)
n3=$(aanmeldingen_met "$n2" "$a3" "{\"BSN:999993653\": $basis}" KVK:90000014 BSN:999993653)
gelijk "tweede: vier regels" 4 "$(jq -r '.register | split("\n") | length' <<<"$n3")"
gelijk "tweede: de bestaande ontvanger krijgt beide" '["00000009000000000777","00000009000000000778"]' \
  "$(jq -c '.optIns[] | select(.ontvanger == "KVK:90000014") | .oins' <<<"$n3")"
gelijk "tweede: een nieuwe ontvanger alleen het nieuwe" '["00000009000000000778"]' \
  "$(jq -c '.optIns[] | select(.ontvanger == "BSN:999993653") | .oins' <<<"$n3")"

z=$(aanmeldingen_zonder "$n3" 00000009000000000778)
gelijk "zonder: de regels van dat magazijn weg" "$(jq -r .register <<<"$n2")" "$(jq -r .register <<<"$z")"
gelijk "zonder: een ontvanger zonder OIN's meer valt weg" '["KVK:90000014"]' "$(jq -c '[.optIns[].ontvanger]' <<<"$z")"
gelijk "zonder: de andere opt-in blijft" '["00000009000000000777"]' "$(jq -c '.optIns[0].oins' <<<"$z")"
gelijk "zonder: alles weg" "$leeg" "$(aanmeldingen_zonder "$z" "$OIN_C")"
gelijk "zonder: een register van de demo-generator blijft onaangeroerd" "$(jq -r .register <<<"$n")" \
  "$(jq -r .register <<<"$(aanmeldingen_zonder "$n" 00000009000000000001)")"

# --- antwoorden van de profielservice en het stelseldocument ---

antwoord_heeft_voorkeur "$basis" && ok "voorkeur: in de basis" || faal "voorkeur: in de basis"
antwoord_heeft_voorkeur '{"voorkeuren":[]}' && faal "voorkeur: zonder" || ok "voorkeur: zonder"
antwoord_heeft_voorkeur 'geen json' && faal "voorkeur: geen JSON" || ok "voorkeur: geen JSON"
met_optin='{"voorkeuren":[{"voorkeurType":"OntvangViaBerichtenbox","waarde":"true","scopes":[{"partij":{"identificatieType":"OIN","identificatieNummer":"00000009000000000777"}}]}]}'
antwoord_heeft_optin "$met_optin" "$OIN_C" && ok "opt-in: aanwezig" || faal "opt-in: aanwezig"
antwoord_heeft_optin "$basis" "$OIN_C" && faal "opt-in: zonder scope" || ok "opt-in: zonder scope"
antwoord_heeft_optin "${met_optin/\"true\"/\"false\"}" "$OIN_C" && faal "opt-in: voorkeur uit" || ok "opt-in: niet als de voorkeur uit staat"
antwoord_heeft_optin "" "$OIN_C" && faal "opt-in: leeg antwoord" || ok "opt-in: niet bij een leeg antwoord"

b64url() { base64 -w0 | tr '/+' '_-' | tr -d '='; }
payload='{"organizations":[{"oin":"00000009000000000777","name":"C","magazijn_url":"https://magazijn-c.voorbeeld.nl"}]}'
jws="$(printf '{"alg":"ES256"}' | b64url).$(printf '%s' "$payload" | b64url).handtekening"
gelijk "stelseldocument: de payload" "$(jq -c . <<<"$payload")" "$(stelseldocument_payload "$jws")"
gelijk "stelseldocument: de url van een OIN" "https://magazijn-c.voorbeeld.nl" "$(stelseldocument_url "$(stelseldocument_payload "$jws")" "$OIN_C")"
gelijk "stelseldocument: niets voor een andere OIN" "" "$(stelseldocument_url "$(stelseldocument_payload "$jws")" 00000009000000000778)"
faalt "stelseldocument: geen JWS" stelseldocument_payload "geen-jws"

# --- url_delen en is_openapi ---

gelijk "url: host, standaardpoort, geen pad" "magazijn-c.voorbeeld.nl 443 " "$(url_delen https://magazijn-c.voorbeeld.nl)"
gelijk "url: slash aan het eind telt niet" "magazijn-c.voorbeeld.nl 443 " "$(url_delen https://magazijn-c.voorbeeld.nl/)"
gelijk "url: poort en pad" "simulator.fbs.x.nl 8443 /magazijn/00000009000000000001" \
  "$(url_delen https://simulator.fbs.x.nl:8443/magazijn/00000009000000000001/)"
for u in http://a.nl https://a.nl?x=1 "https://a b.nl" https:// ftp://a.nl; do
  faalt "url geweigerd: '$u'" url_delen "$u"
done

is_openapi '{"openapi":"3.1.0","info":{}}' && ok "openapi: 3.1" || faal "openapi: 3.1"
is_openapi '{"openapi":"3.0.3"}' && ok "openapi: 3.0" || faal "openapi: 3.0"
is_openapi '{"swagger":"2.0"}' && faal "openapi: swagger 2" || ok "openapi: geen swagger 2"
is_openapi '<html>404</html>' && faal "openapi: html" || ok "openapi: geen html"
is_openapi '' && faal "openapi: leeg" || ok "openapi: niet leeg"

for o in KVK:90000014 BSN:999993653; do
  [[ $o =~ $ONTVANGER_REGEL ]] && ok "ontvanger $o" || faal "ontvanger $o"
done
for o in KVK:1 kvk:90000014 KVK90000014 OIN:00000009000000000777 "KVK:90000014 "; do
  [[ $o =~ $ONTVANGER_REGEL ]] && faal "ontvanger '$o' geweigerd" || ok "ontvanger '$o' geweigerd"
done

echo "ASSERTIES=$asserties"
[ "$mislukt" -eq 0 ]
