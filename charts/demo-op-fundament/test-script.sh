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

# Draait $2 (bash) met het script gesourcet, in een eigen map met een log. Zet UIT (uitvoer), CODE
# (exitcode) en LOG (wat de stubs vastlegden). $1 = naam van het geval.
draai() {
  local naam=$1 code
  local map=$WERKMAP/$naam
  mkdir -p "$map"
  : >"$map/log"

  code=0
  # Met een grens: een stub die nooit "gereed" zegt, laat wacht_tot anders zonder sleep rondgaan.
  UIT=$(cd "$map" && LOG="$map/log" WERK="$map" timeout 60 bash -c "
    source '$SCRIPT'
    # Leest stdin bij -f -, zoals kubectl: anders breekt de pipe ervoor af (SIGPIPE, pipefail).
    kc() { echo \"kc \$*\" >>\"\$LOG\"; case \"\$*\" in *'-f -'*) cat >/dev/null ;; esac; stub_kc \"\$@\"; }
    hm() { echo \"hm \$*\" >>\"\$LOG\"; }
    docker() { echo \"docker \$*\" >>\"\$LOG\"; }
    sleep() { :; }
    stub_kc() { return 0; }
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

draai context-vast 'NS=ns; KUBE_CONTEXT=fieldlab; BASISDOMEIN=fbs.x.nl; voorwaarden; KUBE_CONTEXT=ander 2>/dev/null || echo "readonly"'
bevat "context vast: na de start niet te wijzigen" "readonly" "$UIT"

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
draai db-bestaat-100 'NS=ns; KUBE_CONTEXT=x; echo 0 >"$WERK/n"; stub_kc() { case "$*" in *max_connections*) echo 100 ;; *phase*) n=$(( $(cat "$WERK/n") + 1 )); echo $n >"$WERK/n"; [ $n -gt 1 ] && echo "Cluster in healthy state" || echo "Applying configuration" ;; esac; }; databases'
gelijk "db met 100: in orde" 0 "$CODE"
bevat "db met 100: max_connections naar 200" 'patch cluster.postgresql.cnpg.io db --type merge -p {"spec":{"postgresql":{"parameters":{"max_connections":"200"}}}}' "$LOG"

draai db-ontbreekt 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get cluster.postgresql.cnpg.io db"|"get cluster.postgresql.cnpg.io ldv-uitvraag") return 1 ;; *phase*) echo "Cluster in healthy state" ;; esac; }; databases'
gelijk "geen databases: twee nieuwe" 2 "$(grep -c 'create -f -' <<<"$LOG")"

echo "== geheimen =="

draai geheimen-bestaan 'NS=ns; KUBE_CONTEXT=x; geheimen'
gelijk "geheimen bestaan: in orde" 0 "$CODE"
bevat_niet "geheimen bestaan: niets nieuw" "create secret" "$LOG"

draai openssl-zonder-6 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get secret fbs-demo-bediening") return 1 ;; esac; }; openssl() { case "$*" in "passwd -6 -stdin") return 1 ;; *) command openssl "$@" ;; esac; }; geheimen'
gelijk "openssl zonder -6: exit 2" 2 "$CODE"
bevat_niet "openssl zonder -6: geen htpasswd in het cluster" "fbs-demo-bediening --from-file" "$LOG"

draai openssl-andere-hash 'NS=ns; KUBE_CONTEXT=x; stub_kc() { case "$*" in "get secret fbs-demo-bediening") return 1 ;; esac; }; openssl() { case "$*" in "passwd -6 -stdin") echo "abcdef" ;; *) command openssl "$@" ;; esac; }; geheimen'
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

echo "== keten =="

A=00000000000000100000
B=00000001823288444000
sse() { printf 'data: {"event":"magazijn-bevraging-voltooid","magazijnId":"%s","status":"%s","aantalBerichten":%s}\n' "$@"; }
keten_ok="$(sse "$A" OK 3; sse "$B" OK 2)"
keten_b_fout="$(sse "$A" OK 3; sse "$B" FOUT 0)"

stub_curl() {
  cat <<EOF
gw_curl() {
  echo "curl \$*" >>"\$LOG"
  case "\$*" in
    *"-X POST"*sessies*) $1 ;;
    *_ophalen*) printf '%s\n' '$2' ;;
    *DELETE*) return 0 ;;
  esac
}
EOF
}

draai keten-ok "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; $(stub_curl "printf '{\"sessieId\":\"s-1\"}\n201'" "$keten_ok"); controleer_keten; echo FOUTEN=\$fouten"
gelijk "A en B leverden: in orde" 0 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
bevat "sessie beëindigd" "-X DELETE" "$LOG"
gelijk "één sessie" 1 "$(grep -c 'X POST' <<<"$LOG")"
bevat_niet "sessie-id niet als argument" "s-1" "$LOG"

draai keten-b-fout "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; KETEN_SECONDEN=0; $(stub_curl "printf '{\"sessieId\":\"s-1\"}\n201'" "$keten_b_fout"); controleer_keten; echo FOUTEN=\$fouten"
gelijk "B faalde: een fout" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
bevat "B faalde: met de reden" "magazijn $B: FOUT, 0 berichten" "$UIT"
bevat "B faalde: sessie toch beëindigd" "-X DELETE" "$LOG"

draai keten-429 "NS=ns; KUBE_CONTEXT=x; BASISDOMEIN=fbs.x.nl; $(stub_curl "printf '{\"status\":429}\n429'" "$keten_ok"); controleer_keten; echo FOUTEN=\$fouten"
gelijk "geen sessie: een fout" 1 "$(sed -n 's/^FOUTEN=//p' <<<"$UIT")"
bevat "geen sessie: met de statuscode" "POST /api/v1/sessies gaf 429" "$UIT"
bevat_niet "geen sessie: geen ophaalronde" "_ophalen" "$LOG"

echo "ASSERTIES=$asserties"
exit "$mislukt"
