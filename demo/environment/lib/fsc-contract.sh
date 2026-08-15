#!/usr/bin/env bash
# Bouwstenen voor de contract-bootstrap, gedeeld door de consumer- en de provider-helft.
#
# De twee helften praten elk met precies één manager: die van hun eigen peer. Dat is geen
# stijlkeuze maar een eis van de omgeving — op ZAD isoleert de tenant-baseline-NetworkPolicy per
# deployment, en de interne manager-API heeft geen route. Eén proces dat beide managers aanspreekt
# bestaat daar dus niet. Het contract kruist in plaats daarvan via de FSC-mesh, de weg die de
# managers onderling toch al gebruiken.
#
# Vereist $ERRLOG (fsc_errlog_init) en $HAVE_JQ (fsc_have_jq) uit fsc-harness.sh.

# fsc_env_vereist <naam> [uitleg]: de waarde van die variabele op stdout, of afbreken met 2.
#
# Niet `${VAR:?melding}`: dat eindigt op 1, en 1 betekent hier "het ging mis, probeer opnieuw".
# Een ontbrekende variabele wordt door opnieuw proberen nooit beter, en de lus op ZAD zou er
# eindeloos op blijven herstarten met steeds dezelfde melding. Uitgang 2 is de afspraak voor
# "configuratie deugt niet"; de lus stopt daarop, wat een zichtbare crashloop oplevert in plaats van
# een component dat draait en niets doet.
#
# Leunt op `set -e` bij de aanroeper: de exit-status van de commando-substitutie is de status van de
# toekenning, dus `X="$(fsc_env_vereist FOO)"` breekt het script af met 2.
fsc_env_vereist() {
  local naam="$1" waarde="${!1-}"

  [ -n "$waarde" ] || {
    echo "FAIL: zet ${naam}${2:+ — $2}" >&2
    exit 2
  }

  printf '%s' "$waarde"
}

# fsc_contract_manager_ok <url...>: elk adres moet met https:// beginnen.
#
# De adressen worden geconcateneerd tot curl's URL-argument; een waarde die met `-` begint zou curl
# als optie lezen (`-K/pad` maakt er een config-file-lees van).
fsc_contract_manager_ok() {
  local url
  for url in "$@"; do
    case "$url" in
      https://*) ;;
      *) echo "FAIL: manager-adres moet met https:// beginnen: '${url}'" >&2; return 1 ;;
    esac
  done
}

# fsc_contract_api <url> <cert> <key> <ca> <adres> <curl-args...>: één call naar een manager.
#
# <adres> is het IP waar de hostnaam heen moet wijzen, of leeg. Leeg = gewone DNS-resolutie, wat op
# ZAD het geval is: daar is de manager een ClusterIP-service met een echte naam. Lokaal staan de
# federatie-namen in geen enkele resolver (`extra_hosts` geldt alleen binnen containers), vandaar
# --resolve met het loopback-adres van die component.
fsc_contract_api() {
  local url="$1" cert="$2" key="$3" ca="$4" adres="$5"; shift 5
  local args=() hostnaam poort

  if [ -n "$adres" ]; then
    hostnaam="${url#https://}"; hostnaam="${hostnaam%%/*}"
    poort="${hostnaam##*:}"; hostnaam="${hostnaam%%:*}"
    args=(--resolve "${hostnaam}:${poort}:${adres}")
  fi

  curl -sS --fail-with-body --noproxy '*' "${args[@]}" \
    --cert "$cert" --key "$key" --cacert "$ca" "$@" 2>"$ERRLOG"
}

# fsc_json_lijst <woord...>: de woorden als JSON-array op stdout, voor jq's --argjson.
fsc_json_lijst() {
  local eerste=1 woord
  printf '['
  for woord in "$@"; do
    [ "$eerste" -eq 1 ] || printf ','
    eerste=0
    printf '%s' "$woord" | jq -R .
  done
  printf ']'
}

# --- Wat de provider mag tekenen ----------------------------------------------------------------
# Vóór de splitsing was de accept een gerichte handeling: het script kende het hash dat het zelf
# net had laten indienen. De provider-helft kent dat hash niet — hij vindt een contract in zijn
# lijst en moet zélf besluiten of hij tekent. Dat is een autorisatiebesluit, en de inhoud van het
# contract komt van de tegenpartij.
#
# Een contract draagt een LIJST grants. Toetsen of er een grant in zit die ons bevalt is daarom
# niet genoeg: wie een tweede grant meestuurt, krijgt die anders mee-ondertekend. Het contract moet
# als geheel kloppen, dus de eis is "precies één grant, en die klopt" — niet "er is er één die
# klopt".
#
# De thumbprint in de grant wordt bewust NIET getoetst. De provider heeft het outway-cert van de
# consumer niet en kan hem dus niet onafhankelijk verifiëren. Hij hoeft dat ook niet: de thumbprint
# zegt wélke outway van díé consumer de dienst mag afnemen, en dat is aan de consumer. Wat de
# provider bewaakt is dat het om zijn eigen dienst gaat en om een consumer die hij kent.

# fsc_contract_beoordeling <json> <provider_oin> <diensten-json> <consumers-json>: één regel per
# niet-ingetrokken contract dat de provider aangaat:
#
#   TEKEN    <hash> <consumer_oin>   mag getekend worden, is dat nog niet
#   GETEKEND <hash> <consumer_oin>   voldoet en draagt onze handtekening al
#   WEIGER   <hash> <reden>          nog niet getekend en haalt de toets niet
#
# Alle drie komen uit hetzelfde jq-programma en dus uit dezelfde toets. Twee programma's zouden
# uiteen kunnen lopen, en een diagnose die andere contracten bekijkt dan de matcher tekent wijst de
# operator juist de verkeerde kant op. De reden noemt de eerste eis die faalt.
#
# Een contract dat de toets niet haalt én al getekend is levert niets op: dat is andermans zaak.
# Het servicePublication-contract voor onze eigen dienst staat op dezelfde manager en wordt door
# `AUTO_SIGN_GRANTS` vanzelf getekend; zou dat een WEIGER-regel opleveren, dan stond de log elke
# ronde vol met contracten waar niets mee mis is.
fsc_contract_beoordeling() {
  [ "$HAVE_JQ" -eq 1 ] || return 0
  printf '%s' "$1" | jq -r \
    --arg prov "$2" --argjson diensten "$3" --argjson consumers "$4" '
    def weiger($ondertekend; $tekst): if $ondertekend then empty else "WEIGER \($tekst)" end;

    .contracts[]?
    | select((.has_revoked // false) == false)
    | . as $c
    | (.content.grants // []) as $g
    | (((.signatures.accept // {}) | has($prov))) as $ondertekend
    | if ($g | length) != 1 then
        weiger($ondertekend; "\($c.hash) draagt \($g | length) grants in plaats van precies 1")
      elif $g[0].type != "GRANT_TYPE_SERVICE_CONNECTION" then
        weiger($ondertekend; "\($c.hash) grant-type \($g[0].type // "ontbreekt") is geen serviceConnection")
      elif $g[0].service.peer_id != $prov then
        weiger($ondertekend; "\($c.hash) dienst hoort bij peer \($g[0].service.peer_id // "ontbreekt"), niet bij ons")
      elif ($diensten | index($g[0].service.name)) == null then
        weiger($ondertekend; "\($c.hash) dienst \($g[0].service.name // "ontbreekt") bieden wij niet aan")
      elif ($consumers | index($g[0].outway.peer_id)) == null then
        weiger($ondertekend; "\($c.hash) consumer \($g[0].outway.peer_id // "ontbreekt") staat niet op de lijst")
      elif $ondertekend then "GETEKEND \($c.hash) \($g[0].outway.peer_id)"
      else "TEKEN \($c.hash) \($g[0].outway.peer_id)" end' 2>/dev/null
}

# --- Wat de consumer al heeft uitstaan ----------------------------------------------------------

# fsc_contract_voor_combinatie <json> <service> <provider_oin> <consumer_oin> <thumbprint>:
# `<hash> <state> <provider-getekend ja|nee>` per regel, voor elk niet-ingetrokken contract dat
# precies deze serviceConnection draagt.
#
# De consumer-helft heeft dit nodig om te weten of hij nog moet indienen. Alleen kijken naar
# volledig geldige contracten (fsc_grant_actief) zou niet volstaan: in de gesplitste opzet zit er
# tijd tussen indienen en tekenen, en in dat gat zou elke ronde er nóg een contract bij posten.
#
# De "precies één grant"-eis staat er ook hier, zodat consumer en provider dezelfde contracten als
# "van deze combinatie" zien. Zouden ze daarin verschillen, dan wacht de consumer op een contract
# dat de provider nooit gaat tekenen.
fsc_contract_voor_combinatie() {
  [ "$HAVE_JQ" -eq 1 ] || return 0
  printf '%s' "$1" | jq -r \
    --arg svc "$2" --arg prov "$3" --arg cons "$4" --arg thumb "$5" '
    .contracts[]?
    | select((.has_revoked // false) == false)
    | select((.content.grants // []) | length == 1)
    | select(.content.grants[0]
        | .type == "GRANT_TYPE_SERVICE_CONNECTION"
          and .service.name == $svc
          and .service.peer_id == $prov
          and .outway.peer_id == $cons
          and .outway.identification.public_key_thumbprint == $thumb)
    | "\(.hash) \((.state // "onbekend") | ascii_downcase) \(if ((.signatures.accept // {}) | has($prov)) then "ja" else "nee" end)"
    ' 2>/dev/null
}

# fsc_contract_regels <beoordeling> <soort>: de regels van één soort, zonder het soort-woord.
fsc_contract_regels() {
  printf '%s' "${1:-}" | sed -n "s/^$2 //p"
}
