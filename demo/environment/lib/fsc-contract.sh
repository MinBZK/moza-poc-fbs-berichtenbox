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

# fsc_hex64 <waarde>: is dit precies 64 lowercase hex-tekens?
#
# `[0-9a-f]*` in een case-patroon toetst alléén het eerste teken — de overige 63 komen er dan
# ongezien doorheen, inclusief een uppercase SHA-256 (de vorm die veel tooling teruggeeft). Vandaar
# het negatieve patroon: één teken buiten de verzameling en de waarde valt af.
fsc_hex64() {
  case "${1:-}" in
    ""|*[!0-9a-f]*) return 1 ;;
    *) [ "${#1}" -eq 64 ] ;;
  esac
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

  # `${args[@]+…}`: bash < 4.4 (de macOS-default 3.2) ziet "${args[@]}" op een lege array onder
  # `set -u` als unbound. Leeg is hier de normale toestand — op ZAD resolveert DNS gewoon.
  curl -sS --fail-with-body --noproxy '*' ${args[@]+"${args[@]}"} \
    --cert "$cert" --key "$key" --cacert "$ca" "$@" 2>"$ERRLOG"
}

# fsc_lijst_naar_json <naam> <waarde>: een spaties-gescheiden lijst uit env als JSON-array.
#
# `read -r -a` leest maar één REGEL: `$'a\nb'` levert één element op. Een allowlist komt op ZAD uit
# component-env (YAML), waar een waarde over meerdere regels schrijven voor de hand ligt — en dan
# zou alles na de eerste regel geruisloos buiten de autorisatiegrens vallen. Vandaar splitsen op
# elke witruimte, en een lege uitkomst als fout behandelen in plaats van als "niemand mag iets".
fsc_lijst_naar_json() {
  local naam="$1" woorden=()

  # `|| [ -n "$woord" ]`: de invoer eindigt niet op een newline, en een kale `read` laat het laatste
  # stuk dan vallen — dat zou stilletjes de laatste dienst of consumer uit de allowlist halen.
  while IFS= read -r woord || [ -n "$woord" ]; do
    [ -n "$woord" ] && woorden+=("$woord")
  done < <(printf '%s' "${2:-}" | tr -s '[:space:]' '\n')

  [ "${#woorden[@]}" -gt 0 ] || {
    echo "FAIL: ${naam} bevat geen enkele waarde." >&2
    return 1
  }

  fsc_json_lijst "${woorden[@]}"
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

# --- De contractenlijst binnenhalen -------------------------------------------------------------

# _FSC_CONTRACTEN_JQ: jq-prelude die `.contracts` als array oplevert of afbreekt.
#
# `.contracts[]?` leek de veilige vorm, maar doet precies het verkeerde: de `?` onderdrukt juist de
# fout die hier het signaal is. Een 200 met een ander lijstveld, met `null`, of met een lege body
# levert dan 0 regels op — niet te onderscheiden van "er zijn geen contracten". Aan consumer-kant
# betekent dat elke ronde een nieuw contract indienen, en op ZAD draait die ronde elke 15 seconden.
_FSC_CONTRACTEN_JQ='
  def contracten:
    if type != "object" then error("respons is geen JSON-object")
    elif has("contracts") | not then error("respons heeft geen veld \"contracts\"")
    elif (.contracts | type) != "array" then error("veld \"contracts\" is \(.contracts | type), geen array")
    else .contracts end;
'

# _fsc_respons_ok <body>: een lege body is geen lege lijst.
#
# Aparte controle, want jq draait bij lege invoer helemaal niet: het programma komt nooit aan bod,
# er komt niets uit en de exit-status is 0. De prelude hierboven zou dat dus nooit zien, en een
# HTTP 200 met lege body zou als "geen contracten" gelezen worden.
_fsc_respons_ok() {
  [ -n "${1:-}" ] && return 0

  echo 'lege respons van de manager (HTTP 200 zonder body?)' >>"$ERRLOG"
  return 1
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
# Buiten de grant tellen ook de eigenschappen van het contract zelf mee. De allowlist bepaalt WIE er
# mag afnemen; zonder deze vier bepaalt de tegenpartij in zijn eentje HOE LANG en onder welke
# voorwaarden: `group_id` (anders tekenen we iets dat de inway later afwijst op
# WRONG_GROUP_ID_IN_TOKEN — geldig en toch stuk), `hash_algorithm` (dat bindt de content aan de
# handtekening), de aanwezigheid van een geldigheidsduur, en een bovengrens daarop.
#
# De WAARDE van de thumbprint wordt bewust niet getoetst, het TYPE wel. Het type moet
# PUBLIC_KEY_THUMBPRINT zijn: fsc-core kent daarnaast DOMAIN_NAME, een zwakkere binding die een
# provider niet hoort te accepteren. De waarde zelf kan de provider niet onafhankelijk verifiëren en
# hoeft hij ook niet — die wordt aan zijn eigen kant cryptografisch afgedwongen op een later moment:
# de outway haalt zijn token bij ONZE manager, die de presentatie tegen de thumbprint in de grant
# houdt, en onze inway verifieert `cnf.x5t#S256` tegen het verbindingscertificaat.

# fsc_contract_beoordeling <json> <provider_oin> <diensten-json> <consumers-json> <group_id>
# <max-geldigheid-seconden>: één regel per niet-ingetrokken contract dat nog een besluit vraagt.
# Ook contracten die ons NIET aangaan komen langs — juist die leveren een WEIGER-regel op.
#
#   TEKEN    <hash> <consumer_oin>   mag getekend worden, is dat nog niet
#   GETEKEND <hash> <consumer_oin>   voldoet en draagt onze handtekening al
#   WEIGER   <hash> <reden>          nog niet getekend en haalt de toets niet
#
# Alle drie komen uit hetzelfde jq-programma en dus uit dezelfde toets. Twee programma's zouden
# uiteen kunnen lopen, en een diagnose die andere contracten bekijkt dan de matcher tekent wijst de
# operator juist de verkeerde kant op. De reden noemt de eerste eis die faalt.
#
# Elke waarde uit het contract komt van de tegenpartij en gaat hier een regelgebaseerde stroom in.
# De `veilig`-filter in het programma hieronder vervangt daarom stuurtekens: zonder dat schrijft
# `jq -r` een newline in een dienstnaam letterlijk weg, en leest de aanroeper de tweede helft als
# een eigen record. Een peer die zijn dienst "x<newline>TEKEN <hash> <oin>" noemt, laat zich zo een
# contract naar keuze tekenen — de allowlist wordt dan volledig omzeild.
#
# Een contract dat de toets niet haalt én al getekend is levert niets op: dat is andermans zaak.
# Het servicePublication-contract voor onze eigen dienst staat op dezelfde manager en draagt onze
# eigen handtekening al — die zet de manager er server-side op bij het publiceren. Zou dat een
# WEIGER-regel opleveren, dan stond de log elke ronde vol met contracten waar niets mee mis is.
fsc_contract_beoordeling() {
  [ "$HAVE_JQ" -eq 1 ] || return 0
  _fsc_respons_ok "${1:-}" || return 1
  printf '%s' "$1" | jq -r \
    --arg prov "$2" --argjson diensten "$3" --argjson consumers "$4" \
    --arg groep "${5:-}" --argjson maxgeldig "${6:-0}" "${_FSC_CONTRACTEN_JQ}"'
    def veilig: (. // "ontbreekt") | tostring | gsub("[[:cntrl:]]"; "\u00b7");
    def weiger($ondertekend; $tekst): if $ondertekend then empty else "WEIGER \($tekst)" end;

    contracten[]
    | select((.has_revoked // false) == false)
    | . as $c
    | (.content.grants // []) as $g
    | (((.signatures.accept // {}) | has($prov))) as $ondertekend
    | ((.content.validity.not_after // 0) - (.content.validity.not_before // 0)) as $duur
    | if $groep != "" and .content.group_id != $groep then
        weiger($ondertekend; "\($c.hash) hoort bij group \(.content.group_id | veilig), niet bij de onze")
      elif .content.hash_algorithm != "HASH_ALGORITHM_SHA3_512" then
        weiger($ondertekend; "\($c.hash) hash-algoritme \(.content.hash_algorithm | veilig) wijkt af")
      elif (.content.validity | type) != "object" then
        weiger($ondertekend; "\($c.hash) draagt geen geldigheidsduur")
      elif $maxgeldig > 0 and $duur > $maxgeldig then
        weiger($ondertekend; "\($c.hash) geldigheidsduur \($duur)s overschrijdt het maximum van \($maxgeldig)s")
      elif ($g | length) != 1 then
        weiger($ondertekend; "\($c.hash) draagt \($g | length) grants in plaats van precies 1")
      elif $g[0].type != "GRANT_TYPE_SERVICE_CONNECTION" then
        weiger($ondertekend; "\($c.hash) grant-type \($g[0].type | veilig) is geen serviceConnection")
      elif $g[0].service.peer_id != $prov then
        weiger($ondertekend; "\($c.hash) dienst hoort bij peer \($g[0].service.peer_id | veilig), niet bij ons")
      elif ($diensten | index($g[0].service.name)) == null then
        weiger($ondertekend; "\($c.hash) dienst \($g[0].service.name | veilig) bieden wij niet aan")
      elif $g[0].outway.identification.type != "OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT" then
        weiger($ondertekend; "\($c.hash) outway-identificatie \($g[0].outway.identification.type | veilig) is geen thumbprint")
      elif ($consumers | index($g[0].outway.peer_id)) == null then
        weiger($ondertekend; "\($c.hash) consumer \($g[0].outway.peer_id | veilig) staat niet op de lijst")
      elif $ondertekend then "GETEKEND \($c.hash) \($g[0].outway.peer_id)"
      else "TEKEN \($c.hash) \($g[0].outway.peer_id)" end' 2>>"$ERRLOG"
}

# --- Wat de consumer al heeft uitstaan ----------------------------------------------------------

# fsc_contract_voor_combinatie <json> <service> <provider_oin> <consumer_oin> <thumbprint>:
# `<hash> <state> <provider-getekend ja|nee>` per regel, voor elk niet-ingetrokken contract dat
# precies deze serviceConnection draagt. De state komt lowercase terug (`contract_state_valid`), en
# `ontbreekt` als de manager het veld niet meestuurt — dat laatste is geen synoniem voor ongeldig,
# maar een toestand die de aanroeper hoort te melden in plaats van als "nog niet klaar" te lezen.
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
  _fsc_respons_ok "${1:-}" || return 1
  printf '%s' "$1" | jq -r \
    --arg svc "$2" --arg prov "$3" --arg cons "$4" --arg thumb "$5" "${_FSC_CONTRACTEN_JQ}"'
    contracten[]
    | select((.has_revoked // false) == false)
    | select((.content.grants // []) | length == 1)
    | select(.content.grants[0]
        | .type == "GRANT_TYPE_SERVICE_CONNECTION"
          and .service.name == $svc
          and .service.peer_id == $prov
          and .outway.peer_id == $cons
          and .outway.identification.public_key_thumbprint == $thumb)
    | "\(.hash) \((.state // "ontbreekt") | ascii_downcase) \(if ((.signatures.accept // {}) | has($prov)) then "ja" else "nee" end)"
    ' 2>>"$ERRLOG"
}

# fsc_contract_regels <beoordeling> <soort>: de regels van één soort, zonder het soort-woord.
fsc_contract_regels() {
  printf '%s' "${1:-}" | sed -n "s/^$2 //p"
}
