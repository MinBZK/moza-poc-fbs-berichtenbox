#!/usr/bin/env bash
# SPDX-License-Identifier: EUPL-1.2
#
# Tests voor de flow van charts/demo-op-fundament.sh, zonder cluster: het script wordt gesourcet in
# een subshell, met kc, hm, docker en gw_curl als stubs die hun aanroepen loggen. Wat een cluster
# vraagt (start een pod, komt een route door), toetst het script zelf met `controleer`.

set -euo pipefail

HIER=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly HIER
readonly SCRIPT="$HIER/../demo-op-fundament.sh"
CHARTS_PAD=$(cd "$HIER/.." && pwd)
readonly CHARTS_PAD

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

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) faal "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) faal "$wat — '$naald' staat er toch in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    faal "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Wat het script uit de omgeving leest; een geëxporteerde waarde op de machine mag geen geval
# veranderen.
OMGEVING=(TAG NS KUBE_CONTEXT BASISDOMEIN EXTRA_VALUES PROEFTUIN GATEWAY GATEWAY_SECRET ISSUER GATEWAY_ADRES
  HTTPS_POORT CA_BESTAND KETEN_ONTVANGER KETEN_SECONDEN)

# Draait $2 (bash) met het script gesourcet, in een eigen map met een log. Zet UIT (uitvoer), CODE
# (exitcode) en LOG (wat de stubs vastlegden). $1 = naam van het geval. Met ECHT=1 blijven kc en hm
# van het script staan, en loggen kubectl en helm.
draai() {
  local naam=$1 code
  local map=$WERKMAP/$naam
  local onset=()
  mkdir -p "$map"
  : >"$map/log"

  for v in "${OMGEVING[@]}"; do onset+=(-u "$v"); done

  code=0
  # sleep zet de klok vooruit in plaats van te wachten: zo lopen ook de time-outpaden van wacht_tot
  # en de ketencontrole in een oogwenk. Met een grens, voor een stub die nooit iets afrondt.
  UIT=$(cd "$map" && env "${onset[@]}" LOG="$map/log" WERK="$map" ECHT="${ECHT:-}" timeout 60 bash -c "
    source '$SCRIPT'
    if [ -z \"\$ECHT\" ]; then
      # Leest stdin bij -f -, zoals kubectl: anders breekt de pipe ervoor af (SIGPIPE, pipefail).
      kc() { echo \"kc \$*\" >>\"\$LOG\"; case \"\$*\" in *'-f -'*) cat >/dev/null ;; esac; stub_kc \"\$@\"; }
      hm() { echo \"hm \$*\" >>\"\$LOG\"; }
    fi
    kubectl() { echo \"kubectl \$*\" >>\"\$LOG\"; stub_kubectl \"\$@\"; }
    helm() { echo \"helm \$*\" >>\"\$LOG\"; }
    docker() { echo \"docker \$*\" >>\"\$LOG\"; }
    sleep() { SECONDS=\$((SECONDS + \${1:-1})); }
    stub_kc() { return 0; }
    stub_kubectl() { return 0; }
    $2" 2>&1) || code=$?
  CODE=$code
  LOG=$(cat "$map/log")
}

echo "== voorwaarden =="

draai zonder-tag 'main op'
gelijk "zonder TAG: exit 2" 2 "$CODE"
bevat "zonder TAG: zegt waarom" "zet TAG" "$UIT"
gelijk "zonder TAG: niets naar het cluster" "" "$LOG"

draai zonder-ns 'unset NS; KUBE_CONTEXT=x voorwaarden'
gelijk "zonder NS: exit 2" 2 "$CODE"
gelijk "zonder NS: niets naar het cluster" "" "$LOG"

draai extra-geen-map 'NS=ns KUBE_CONTEXT=x EXTRA_VALUES=/bestaat/niet voorwaarden'
gelijk "EXTRA_VALUES zonder map: exit 2" 2 "$CODE"
bevat "EXTRA_VALUES zonder map: zegt waarom" "EXTRA_VALUES (/bestaat/niet) is geen map" "$UIT"

# De echte kc en hm: elke aanroep met de vastgezette context, ook als de huidige context later wisselt.
ECHT=1 draai context-vast 'NS=ns; n=0; stub_kubectl() { case "$*" in "config current-context") n=$((n+1)); echo "ctx-$n" ;; esac; }; context_vastzetten; kc get pods; hm list; kc get pods'
gelijk "context: één keer opgevraagd" 1 "$(grep -c 'config current-context' <<<"$LOG")"
gelijk "context: kubectl met de vastgezette context" 2 "$(grep -c '^kubectl --context ctx-1 --namespace ns get pods$' <<<"$LOG")"
bevat "context: helm met de vastgezette context" "helm --kube-context ctx-1 --namespace ns list" "$LOG"

ECHT=1 draai context-gezet 'NS=ns; KUBE_CONTEXT=fieldlab; context_vastzetten; kc get pods'
bevat "KUBE_CONTEXT gezet: die, zonder te vragen" "kubectl --context fieldlab --namespace ns get pods" "$LOG"
bevat_niet "KUBE_CONTEXT gezet: niets gevraagd" "current-context" "$LOG"

draai context-readonly 'NS=ns; KUBE_CONTEXT=fieldlab; context_vastzetten; KUBE_CONTEXT=ander; echo "NIET HIER"'
bevat_niet "context: na het vastzetten niet te wijzigen" "NIET HIER" "$UIT"

ECHT=1 draai geen-context 'NS=ns; stub_kubectl() { return 1; }; context_vastzetten'
gelijk "geen huidige context: exit 2" 2 "$CODE"

mkdir -p "$WERKMAP/extra-vreemd"
echo "x: 1" >"$WERKMAP/extra-vreemd/berichtenmagazijn.yaml"
# Met BASISDOMEIN: anders stopt voorwaarden ook op het ontbrekende adres, met dezelfde exitcode.
draai extra-vreemd "NS=ns KUBE_CONTEXT=x BASISDOMEIN=fbs.x.nl EXTRA_VALUES='$WERKMAP/extra-vreemd' voorwaarden"
gelijk "EXTRA_VALUES met een onbekend bestand: exit 2" 2 "$CODE"
bevat "EXTRA_VALUES met een onbekend bestand: noemt het" "berichtenmagazijn.yaml hoort bij geen release" "$UIT"

draai ipv6 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in *jsonpath*) echo 2001:db8::1 ;; esac; }; voorwaarden'
gelijk "Gateway met IPv6 zonder BASISDOMEIN: exit 2" 2 "$CODE"

draai basisdomein-gezet 'NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; voorwaarden'
gelijk "met BASISDOMEIN: in orde" 0 "$CODE"
bevat_niet "met BASISDOMEIN: geen adres van de Gateway gevraagd" "jsonpath" "$LOG"

echo "== images =="

draai images-een-mist 'TAG=main-0123abc; docker() { echo "docker $*" >>"$LOG"; case "$*" in *fbs-demo-profiel*) echo "manifest unknown" >&2; return 1 ;; esac; }; images_op_tag'
gelijk "een image mist: exit 2" 2 "$CODE"
bevat "een image mist: noemt het" "niet op main-0123abc: fbs-demo-profiel" "$UIT"
bevat "een image mist: met de reden van het register" "manifest unknown" "$UIT"
gelijk "alle acht gevraagd" 8 "$(grep -c 'manifest inspect' <<<"$LOG")"

echo "== databases =="

draai db-bestaat-200 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in *max_connections*) echo 200 ;; *phase*) echo "Cluster in healthy state" ;; esac; }; databases'
gelijk "db met 200: in orde" 0 "$CODE"
bevat_niet "db met 200: geen patch" "patch" "$LOG"
bevat_niet "db met 200: niets nieuw" "create" "$LOG"

# De fase via een teller in een bestand: de stub draait in een command substitution, en een
# variabele daarin overleeft de aanroep niet.
draai db-bestaat-100 'NS=ns; KUBE_CONTEXT=x; echo 0 >"$WERK/n"; stub_kc() { case "$*" in *"parameters.max_connections"*) echo 100 ;; *"exec db-1"*) n=$(( $(cat "$WERK/n") + 1 )); echo $n >"$WERK/n"; [ $n -gt 2 ] && echo " 200" || echo " 100" ;; *phase*) echo "Cluster in healthy state" ;; esac; }; databases'
gelijk "db met 100: in orde" 0 "$CODE"
gelijk "db met 100: wacht tot PostgreSQL 200 gebruikt" 3 "$(grep -c 'exec db-1 -c postgres -- psql -tAc show max_connections' <<<"$LOG")"

draai db-blijft-100 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in *"parameters.max_connections"*) echo 100 ;; *"exec db-1"*) echo 100 ;; *phase*) echo "Cluster in healthy state" ;; esac; }; databases'
gelijk "db blijft op 100: exit 1" 1 "$CODE"
bevat "db blijft op 100: zegt waarom" "db gebruikt max_connections 200 niet" "$UIT"
bevat "db met 100: max_connections naar 200" 'patch cluster.postgresql.cnpg.io db --type merge -p {"spec":{"postgresql":{"parameters":{"max_connections":"200"}}}}' "$LOG"

draai db-ontbreekt 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get cluster.postgresql.cnpg.io db"|"get cluster.postgresql.cnpg.io ldv-uitvraag") return 1 ;; *phase*) echo "Cluster in healthy state" ;; esac; }; databases'
gelijk "geen databases: twee nieuwe" 2 "$(grep -c 'create -f -' <<<"$LOG")"

echo "== geheimen =="

draai geheimen-bestaan 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get secret "*" -o name --ignore-not-found") echo "secret/$3" ;; esac; }; geheimen'
gelijk "geheimen bestaan: in orde" 0 "$CODE"
bevat_niet "geheimen bestaan: niets nieuw" "create secret" "$LOG"

draai geheimen-nieuw 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get secret stelselregister-"*" -o name --ignore-not-found") echo "secret/$3" ;; esac; }; geheimen'
gelijk "nieuwe geheimen: in orde" 0 "$CODE"
bevat "nieuwe geheimen: Redis en beheer" "create secret generic fbs-demo-geheimen --from-file=REDIS_PASSWORD=" "$LOG"
gelijk "Redis-wachtwoord zonder regelovergang" 64 "$(wc -c <"$WERKMAP/geheimen-nieuw/redis-wachtwoord")"
[[ $(cat "$WERKMAP/geheimen-nieuw/htpasswd") =~ ^demo:\$6\$ ]] && ok "htpasswd: gebruiker demo met SHA-512" || faal "htpasswd: $(cat "$WERKMAP/geheimen-nieuw/htpasswd")"
bevat_niet "bestaande keystore blijft" "delete secret" "$LOG"

draai secret-fout 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get secret "*) return 1 ;; esac; }; geheimen'
gelijk "kubectl-fout bij een Secret: exit 1" 1 "$CODE"
bevat_niet "kubectl-fout bij een Secret: niets opnieuw gemaakt" "create secret" "$LOG"

draai openssl-zonder-6 'NS=ns; KUBE_CONTEXT=x; openssl() { case "$*" in "passwd -6 -stdin") return 1 ;; *) command openssl "$@" ;; esac; }; geheimen'
gelijk "openssl zonder -6: exit 2" 2 "$CODE"
bevat_niet "openssl zonder -6: geen htpasswd in het cluster" "fbs-demo-bediening --from-file" "$LOG"

draai openssl-andere-hash 'NS=ns; KUBE_CONTEXT=x; openssl() { case "$*" in "passwd -6 -stdin") echo "abcdef" ;; *) command openssl "$@" ;; esac; }; geheimen'
gelijk "openssl met een andere hash: exit 2" 2 "$CODE"
bevat "openssl met een andere hash: zegt waarom" "gaf geen SHA-512-hash" "$UIT"

echo "== installeren =="

mkdir -p "$WERKMAP/extra"
echo "dienst: {}" >"$WERKMAP/extra/berichtenuitvraag.yaml"
draai installeren "NS=ns; KUBE_CONTEXT=x; TAG=main-0123abc; BASISDOMEIN=fbs.x.nl; PROEFTUIN=p@sha256:0; EXTRA_VALUES='$WERKMAP/extra'; installeren"
gelijk "installeren: in orde" 0 "$CODE"
gelijk "vijf releases" 5 "$(grep -c '^hm upgrade --install' <<<"$LOG")"
uitvraag=$(grep '^hm upgrade --install berichtenuitvraag ' <<<"$LOG")
bevat "uitvraag: extra values als laatste" "-f charts/fbs-demo/stelsel/berichtenuitvraag.yaml -f $WERKMAP/extra/berichtenuitvraag.yaml" \
  "${uitvraag//$CHARTS_PAD\//charts/}"
bevat_niet "uitvraag: geen eigen route" "ingress.hostname" "$uitvraag"
magazijn=$(grep '^hm upgrade --install berichtenmagazijn-a ' <<<"$LOG")
bevat "magazijn A: cnpg vóór de demo-values" \
  "-f charts/voorbeelden/berichtenmagazijn-cnpg.yaml -f charts/fbs-demo/stelsel/berichtenmagazijn.yaml -f charts/fbs-demo/stelsel/berichtenmagazijn-a.yaml" \
  "${magazijn//$CHARTS_PAD\//charts/}"
bevat "magazijn A: eigen hostnaam" "ingress.hostname=berichtenmagazijn-a.fbs.x.nl" "$magazijn"
for r in berichtenuitvraag berichtenmagazijn-a berichtenmagazijn-b stelselregister; do
  regel=$(grep "^hm upgrade --install $r " <<<"$LOG")
  bevat "$r: TLS eindigt vóór de dienst" "--set dienst.tlsTermination=mesh" "$regel"
  bevat "$r: dezelfde tag" "--set dienst.image.tag=main-0123abc" "$regel"
  bevat "$r: het basisdomein" "--set demo.basisdomein=fbs.x.nl" "$regel"
done
bevat_niet "magazijn A: geen extra values van de uitvraag" "extra/" "$magazijn"
gelijk "precies één extra values-bestand" 1 "$(grep -o "$WERKMAP/extra/[a-z-]*.yaml" <<<"$LOG" | wc -l)"
bevat "register herstart na de installatie" "rollout restart deployment/berichtenuitvraag deployment/stelselregister" "$LOG"
bevat "register uit een kopie, niet uit demo/generated" "magazijnenregister=$WERKMAP/installeren/repo/demo/generated/" "$LOG"

draai installeren-faalt "NS=ns; KUBE_CONTEXT=x; TAG=main-0123abc; BASISDOMEIN=fbs.x.nl; PROEFTUIN=p@sha256:0; hm() { echo \"hm \$*\" >>\"\$LOG\"; case \"\$*\" in *'install berichtenmagazijn-b '*) return 1 ;; esac; }; installeren"
gelijk "magazijn B faalt: exit 1" 1 "$CODE"
bevat "magazijn B faalt: noemt hem" "installeren van berichtenmagazijn-b faalde" "$UIT"
bevat_niet "magazijn B faalt: geen herstart" "rollout restart" "$LOG"

echo "== rollouts =="

alle=$(source "$HIER/lib.sh" && printf 'deployment.apps/%s\n' "${DEMO_DEPLOYMENTS[@]}" "${STELSEL_RELEASES[@]}")
draai rollouts-alle "NS=ns; KUBE_CONTEXT=x; stub_kc() { case \"\$*\" in 'get deployments -o name') echo '$alle' ;; esac; }; controleer_rollouts; echo FOUTEN=\$fouten"
gelijk "alle Deployments gereed: geen fout" 0 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"

draai rollouts-kubectl-faalt 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get deployments -o name") return 1 ;; esac; }; controleer_rollouts; echo FOUTEN=$fouten'
gelijk "kubectl faalt: precies die fout, niet dertien keer \"ontbreekt\"" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
bevat "kubectl faalt: zegt waarom" "Deployments in ns niet op te vragen" "$UIT"

draai rollouts-leeg 'NS=ns; KUBE_CONTEXT=x; controleer_rollouts; echo FOUTEN=$fouten'
gelijk "geen Deployments: elke ontbrekende telt" 13 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"

draai rollouts-een-mist "NS=ns; KUBE_CONTEXT=x; stub_kc() { case \"\$*\" in 'get deployments -o name') grep -v stelselregister <<<'$alle' ;; esac; }; controleer_rollouts; echo FOUTEN=\$fouten"
bevat "stelselregister ontbreekt: noemt hem" "Deployment stelselregister ontbreekt" "$UIT"
gelijk "stelselregister ontbreekt: één fout" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"

echo "== controleren =="

# code(): (verwacht, gekregen) → in orde of fout.
for geval in "200 200 0" "200 000 1" "200 404 1" "!404 503 0" "!404 404 1" "!404 000 1" "400 400 0" "400 503 1"; do
  read -r verwacht gekregen fouten_verwacht <<<"$geval"
  draai "code-${verwacht//!/niet}-$gekregen" "BASISDOMEIN=fbs.x.nl; gw_curl() { printf '$gekregen'; }; code demo '$verwacht' proef https://x; echo FOUTEN=\$fouten"
  gelijk "code(): verwacht $verwacht, kreeg $gekregen" "$fouten_verwacht" "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
done

draai controleer-ok 'controleer_rollouts() { :; }; controleer_routes() { :; }; controleer_keten() { :; }; BASISDOMEIN=x; NS=ns; KUBE_CONTEXT=x; controleer'
gelijk "controleer zonder fouten: exit 0" 0 "$CODE"
draai controleer-fout 'controleer_rollouts() { fout x; }; controleer_routes() { :; }; controleer_keten() { :; }; BASISDOMEIN=x; NS=ns; KUBE_CONTEXT=x; controleer'
gelijk "controleer met een fout: exit 1" 1 "$CODE"
bevat "controleer met een fout: telt hem" "1 fout(en)" "$UIT"

# controleer_routes: welke adressen met welke verwachting. code() zelf staat hierboven.
draai routes 'NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; HTTPS_POORT=443; stub_kc() { printf pw | base64; }; code() { echo "CODE $2 $*" >>"$LOG"; }; controleer_routes'
bevat "routes: de simulator met een gesimuleerd magazijn, en een 400 van de simulator zelf" \
  "CODE 400 simulator 400 simulator: berichten lezen bereikt de simulator https://simulator.fbs.x.nl:443/magazijn/00000009000000000001/api/v1/berichten" "$LOG"
bevat "routes: aanleveren bij de simulator dicht" "/magazijn/00000009000000000001/api/v1/aanleveringen" "$(grep 'CODE 404' <<<"$LOG")"
bevat "routes: het stelseldocument" "CODE 200 stelselregister 200 stelselregister: het stelseldocument https://stelselregister.fbs.x.nl:443/api/v1/stelseldocument" "$LOG"
bevat "routes: de aanmeld-webhook dicht" "CODE 404 demo 404 aanmeld-webhook niet via de berichtenbox -X POST" "$LOG"
bevat "routes: paneel met het wachtwoord" "CODE 200 demo 200 paneel met het wachtwoord" "$LOG"
bevat_niet "routes: het wachtwoord niet als argument" "pw" "$(grep 'paneel met het wachtwoord' <<<"$LOG")"

# gw_curl: het certificaat getoetst, nooit -k.
draai gw-curl 'curl() { echo "curl $*"; }; BASISDOMEIN=fbs.x.nl; CA_BESTAND=/ca; GATEWAY_ADRES=127.0.0.1; HTTPS_POORT=9443; gw_curl demo https://x'
bevat "gw_curl: de CA van de Gateway" "--cacert /ca" "$UIT"
bevat "gw_curl: naar het adres, met de hostnaam" "--resolve demo.fbs.x.nl:9443:127.0.0.1" "$UIT"
bevat_niet "gw_curl: nooit -k" " -k " "$UIT"
bevat_niet "gw_curl: nooit --insecure" "--insecure" "$UIT"
draai gw-curl-dns 'curl() { echo "curl $*"; }; BASISDOMEIN=fbs.x.nl; HTTPS_POORT=443; gw_curl demo https://x'
bevat_niet "gw_curl zonder CA_BESTAND: de standaard-CA's" "--cacert" "$UIT"
bevat_niet "gw_curl zonder GATEWAY_ADRES: via DNS" "--resolve" "$UIT"

echo "== keten =="

A=00000000000000100000
B=00000001823288444000
sse() { printf 'data:{"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"%s","aantalBerichten":%s}\n' "$@"; }
sse "$A" OK 3 >"$WERKMAP/keten-ok.sse"
sse "$B" OK 2 >>"$WERKMAP/keten-ok.sse"
sse "$A" OK 3 >"$WERKMAP/keten-b-fout.sse"
sse "$B" FOUT 0 >>"$WERKMAP/keten-b-fout.sse"
: >"$WERKMAP/keten-leeg.sse"

# Een gw_curl die sessie, ophaalrondes en beëindigen nadoet. $1 = antwoord op POST /sessies (body,
# regelovergang, code), $2 = de rondes als "<code>:<sse-bestand>" na elkaar (de laatste herhaalt),
# $3 = code van DELETE.
stub_keten() {
  cat <<EOF
echo 0 >"\$WERK/ronde"
gw_curl() {
  echo "curl \$*" >>"\$LOG"
  local uit="" a vorige=""
  for a in "\$@"; do [ "\$vorige" = -o ] && uit=\$a; vorige=\$a; done
  case "\$*" in
    *"-X POST"*sessies*) printf '%b' '$1' ;;
    *_ophalen*)
      local rondes=($2) n
      n=\$(cat "\$WERK/ronde"); echo \$((n + 1)) >"\$WERK/ronde"
      [ "\$n" -lt "\${#rondes[@]}" ] || n=\$((\${#rondes[@]} - 1))
      cp "\${rondes[\$n]#*:}" "\$uit"; printf '%s' "\${rondes[\$n]%%:*}" ;;
    *DELETE*) printf '$3' ;;
  esac
}
EOF
}

sessie_ok='{"sessieId":"s-1"}\n201'

draai keten-ok "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; $(stub_keten "$sessie_ok" "200:$WERKMAP/keten-ok.sse" 204); controleer_keten; echo FOUTEN=\$fouten"
gelijk "A en B leverden: in orde" 0 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
gelijk "één ophaalronde" 1 "$(grep -c '_ophalen' <<<"$LOG")"
bevat "sessie beëindigd" "-X DELETE" "$LOG"
bevat "sessie-id via een header-bestand" "-H @" "$(grep '_ophalen' <<<"$LOG")"
gelijk "header-bestand met de sessie" "X-Sessie: s-1" "$(cat "$WERKMAP/keten-ok/sessie.hdr")"
bevat_niet "sessie-id niet als argument" "s-1" "$LOG"

# Eerst een fout, dan in orde: dezelfde sessie, een tweede ronde.
draai keten-tweede-ronde "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; $(stub_keten "$sessie_ok" "200:$WERKMAP/keten-b-fout.sse 200:$WERKMAP/keten-ok.sse" 204); controleer_keten; echo FOUTEN=\$fouten"
gelijk "tweede ronde in orde: geen fout" 0 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
gelijk "tweede ronde: één sessie" 1 "$(grep -c 'X POST' <<<"$LOG")"
gelijk "tweede ronde: twee ophaalrondes" 2 "$(grep -c '_ophalen' <<<"$LOG")"
gelijk "tweede ronde: één keer beëindigd" 1 "$(grep -c 'X DELETE' <<<"$LOG")"

draai keten-b-fout "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; KETEN_SECONDEN=60; $(stub_keten "$sessie_ok" "200:$WERKMAP/keten-b-fout.sse" 204); controleer_keten; echo FOUTEN=\$fouten"
gelijk "B faalt de hele tijd: één fout" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
bevat "B faalt: met de reden" "magazijn $B: FOUT, 0 berichten" "$UIT"
bevat "B faalt: sessie toch beëindigd" "-X DELETE" "$LOG"

draai keten-409 "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; KETEN_SECONDEN=0; $(stub_keten "$sessie_ok" "409:$WERKMAP/keten-leeg.sse" 204); controleer_keten; echo FOUTEN=\$fouten"
bevat "409 op _ophalen: de code in de reden" "_ophalen gaf 409" "$UIT"

draai keten-401 "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; KETEN_SECONDEN=300; $(stub_keten "$sessie_ok" "401:$WERKMAP/keten-leeg.sse" 204); controleer_keten; echo FOUTEN=\$fouten"
gelijk "401 op _ophalen: meteen gestopt" 1 "$(grep -c '_ophalen' <<<"$LOG")"
bevat "401 op _ophalen: de code in de reden" "_ophalen gaf 401" "$UIT"

draai keten-delete-401 "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; $(stub_keten "$sessie_ok" "200:$WERKMAP/keten-ok.sse" 401); controleer_keten; echo FOUTEN=\$fouten"
bevat "DELETE gaf 401: zegt het" "niet beëindigd: DELETE gaf 401" "$UIT"
gelijk "DELETE gaf 401: telt als fout" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"

draai keten-429 "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; $(stub_keten '{"detail":"te veel sessies"}\n429' "200:$WERKMAP/keten-ok.sse" 204); controleer_keten; echo FOUTEN=\$fouten"
gelijk "geen sessie: een fout" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
bevat "geen sessie: met de statuscode" "POST /api/v1/sessies gaf 429" "$UIT"
bevat "geen sessie: met de reden" "te veel sessies" "$UIT"
bevat_niet "geen sessie: geen ophaalronde" "_ophalen" "$LOG"

echo "ASSERTIES=$asserties"
exit "$mislukt"
