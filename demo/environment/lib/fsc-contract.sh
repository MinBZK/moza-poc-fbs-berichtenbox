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

# fsc_getal_vereist <naam> <waarde>: de waarde op stdout als het een geheel getal is, anders exit 2.
#
# Intervallen en drempels komen op ZAD uit component-env. Een tikfout maakt daar geen fout van maar
# iets ergers: `sleep "15s"` mislukt meteen, `pauzeer` keert direct terug en de lus wordt een hot
# loop die de manager zo snel mogelijk bevraagt terwijl hij elke ronde OK meldt. En een niet-numerieke
# drempel laat `[ ]` falen, waarna de vangrail die de lus zou stoppen niets meer doet. Beide falen
# dus open; vandaar één controle bij het opstarten.
# Het patroon eist een positief getal zónder voorloopnul, niet "alleen cijfers". `0` zou de guard
# passeren en precies de hot loop opleveren waarvoor hij bestaat, en `08` wordt door bash als
# octaal gelezen: `$((… * 08))` breekt af met "value too great for base", midden in de lus.
fsc_getal_vereist() {
  # Twee patronen en niet één: een case-patroon is een glob, geen reguliere expressie, dus
  # `[1-9][0-9]*` zou ook `15s` accepteren — de `*` matcht daar willekeurige tekens.
  case "${2:-}" in
    ""|*[!0-9]*|0|0*)
      echo "FAIL: ${1} moet een positief geheel getal zijn zonder voorloopnul, niet '${2:-}'." >&2
      exit 2
      ;;
  esac

  printf '%s' "$2"
}

# fsc_getal_hoogstens <naam> <waarde> <max>: als fsc_getal_vereist, met een bovengrens.
fsc_getal_hoogstens() {
  local waarde
  waarde="$(fsc_getal_vereist "$1" "$2")"

  [ "$waarde" -le "$3" ] || {
    echo "FAIL: ${1} mag hoogstens ${3} zijn, niet '${waarde}'." >&2
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

# fsc_hash_ok <waarde>: heeft dit contract-hash een vorm die veilig in een URL-pad past?
#
# FSC-hashes hebben de vorm `$1$4$<base64url>`. Een waarde met een schuine streep of witruimte zou
# het pad verleggen — curl normaliseert `..`-segmenten — dus die valt af.
fsc_hash_ok() {
  case "${1:-}" in
    # Puur punten apart: `.` en `..` bestaan volledig uit toegestane tekens, en curl normaliseert ze
    # weg — `/v1/contracts/../revoke` komt aan als `/v1/revoke`.
    ""|.|..|*[!A-Za-z0-9._~$+-]*) return 1 ;;
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
  local naam="$1" woord woorden=()

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
    elif ((.pagination.next_cursor? // .next_cursor? // "") | tostring) != "" then
      # De lijst is cursor-gepagineerd. Alleen pagina 1 lezen zou betekenen dat de consumer zijn
      # eigen contract niet meer ziet zodra de manager er genoeg heeft — en dan dient hij elke ronde
      # een nieuw contract in. Afbreken tot iemand de cursor volgt; stil doorgaan is hier de
      # gevaarlijke keuze.
      #
      # Twee plekken, want het veld staat genest onder `pagination` en niet op topniveau; alleen de
      # topniveau-vorm toetsen zou een guard opleveren die nooit vuurt, en dat is slechter dan geen
      # guard. De aanroepers vragen daarnaast expliciet een ruime `limit` op, zodat dit een vangrail
      # blijft in plaats van een dagelijkse blokkade.
      error("de contractenlijst is gepagineerd (next_cursor gezet) en dat volgen we nog niet")
    else .contracts end;
'

# _fsc_respons_ok <body>: een lege body is geen lege lijst.
#
# Aparte controle, want jq draait bij lege invoer helemaal niet: het programma komt nooit aan bod,
# er komt niets uit en de exit-status is 0. De prelude hierboven zou dat dus nooit zien, en een
# HTTP 200 met lege body zou als "geen contracten" gelezen worden.
_fsc_respons_ok() {
  # Niet `[ -n ]`: jq draait óók niet op invoer die alleen witruimte is — het programma komt dan
  # nooit aan bod, er komt niets uit en de status is 0. Vandaar toetsen op minstens één teken dat
  # géén witruimte is.
  case "${1:-}" in
    *[![:space:]]*) return 0 ;;
  esac

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
# De `veilig`-filter vervangt daarom álle witruimte en niet alleen newlines: elke lezer van deze
# regels telt kolommen, dus een spatie in een hash verschuift de rest en laat een controle op de
# verkeerde kolom kijken. Zonder dat schrijft
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
    --arg groep "${5:-}" --argjson maxgeldig "${6:-0}" --argjson nu "${7:-0}" "${_FSC_CONTRACTEN_JQ}"'
    def veilig: (. // "ontbreekt") | tostring | gsub("[[:cntrl:]]"; "\u00b7");
    # Een contract dat de toets niet haalt en al onze handtekening draagt, is meestal andermans zaak
    # (het publicatiecontract voor onze eigen dienst staat op dezelfde manager). Máár als het een
    # serviceConnection voor onze eigen dienst is, was het ooit van ons en is het nu afgekeurd — door
    # een gecorrigeerde allowlist, een verlopen looptijd of een verlaagde bovengrens. Dat stil laten
    # zou de provider "nog niets binnen" laten melden terwijl het contract er wel degelijk ligt.
    def weiger($ondertekend; $tekst):
      if $ondertekend then
        if (((.content.grants // [])[0].type? // "") == "GRANT_TYPE_SERVICE_CONNECTION"
            and ((.content.grants // [])[0].service?.peer_id? // "") == $prov)
        then "AANDACHT \($tekst)" else empty end
      else "WEIGER \($tekst)" end;

    contracten[]
    # Eerst het type: een rij die geen object is, laat élke veldtoets hieronder afbreken en daarmee
    # het hele programma — dan wordt in die ronde geen enkel legitiem contract meer getekend. De
    # `try` verderop begint pas ná deze regels en zou dat niet vangen.
    | if type != "object" then "WEIGER <geen object> een rij in de contractenlijst is \(type), geen object"
      else
        # Ook `has_rejected`: een contract dat wij eerder hebben afgewezen draagt onze
        # accept-handtekening niet, dus zonder deze regel komt het elke ronde terug als kandidaat en
        # tekenen we alsnog wat we bewust hebben geweigerd.
        select((.has_revoked // false) == false and (.has_rejected // false) == false)
    | . as $c
    | ($c.hash | veilig) as $h

    # Per contract afvangen. Zonder deze try sloopt één misvormde rij — `content` een string,
    # `validity` een datumtekst, een grant die geen object is — het hele programma, en dan wordt in
    # die ronde geen enkel legitiem contract meer getekend. Een rij die we niet kunnen lezen, tekenen
    # we niet: dat is dezelfde veilige uitkomst als elke andere weigering.
    | try (
        (.content.grants // []) as $g
        | (((.signatures.accept // {}) | has($prov))) as $ondertekend
        | if $groep != "" and .content.group_id != $groep then
            weiger($ondertekend; "\($h) hoort bij group \(.content.group_id | veilig), niet bij de onze")
          elif .content.hash_algorithm != "HASH_ALGORITHM_SHA3_512" then
            weiger($ondertekend; "\($h) hash-algoritme \(.content.hash_algorithm | veilig) wijkt af")
          elif (.content.validity | type) != "object" then
            weiger($ondertekend; "\($h) draagt geen geldigheidsduur")
          elif (.content.validity.not_before | type) != "number"
               or (.content.validity.not_after | type) != "number" then
            # Alleen op "is een object" toetsen zou te weinig zijn: een `validity` zonder `not_after`
            # levert een contract zonder einddatum op, en dat is juist wat de bovengrens moet vangen.
            weiger($ondertekend; "\($h) geldigheidsduur is onvolledig (not_before/not_after ontbreekt of is geen getal)")
          elif .content.validity.not_after <= .content.validity.not_before then
            weiger($ondertekend; "\($h) einddatum ligt niet ná de begindatum")
          elif $nu > 0 and .content.validity.not_after <= $nu then
            # fsc-core eist dat not_after in de toekomst ligt. Een verlopen contract tekenen is
            # bovendien zinloos werk dat elke ronde terugkomt.
            weiger($ondertekend; "\($h) is al verlopen")
          elif $nu > 0 and $maxgeldig > 0 and (.content.validity.not_after - $nu) > $maxgeldig then
            # Gemeten vanaf NU en niet als vensterlengte: de vraag is hoe lang deze tegenpartij een
            # claim op ons kan houden. Een venster van tien jaar dat over vijf jaar begint, is een
            # claim tot over vijftien jaar terwijl de lengte binnen de grens valt.
            weiger($ondertekend; "\($h) loopt nog \(.content.validity.not_after - $nu)s, meer dan het maximum van \($maxgeldig)s")
          elif ($g | length) != 1 then
            weiger($ondertekend; "\($h) draagt \($g | length) grants in plaats van precies 1")
          elif $g[0].type != "GRANT_TYPE_SERVICE_CONNECTION" then
            weiger($ondertekend; "\($h) grant-type \($g[0].type | veilig) is geen serviceConnection")
          elif $g[0].service.peer_id != $prov then
            weiger($ondertekend; "\($h) dienst hoort bij peer \($g[0].service.peer_id | veilig), niet bij ons")
          elif ($diensten | index($g[0].service.name)) == null then
            weiger($ondertekend; "\($h) dienst \($g[0].service.name | veilig) bieden wij niet aan")
          elif $g[0].service.type != "SERVICE_TYPE_SERVICE" then
            # Dezelfde klasse discriminator als het identificatietype hieronder: een
            # DELEGATED_SERVICE zet een delegator-claim in het token en verandert welke
            # handtekeningen vereist zijn. Wij bieden geen gedelegeerde diensten aan.
            weiger($ondertekend; "\($h) dienst-type \($g[0].service.type | veilig) is geen gewone dienst")
          elif (($g[0].properties // {}) | length) != 0 then
            # `properties` schrijft claims die onze eigen manager in het access token zet en die onze
            # inway en de dienst erachter te zien krijgen — ongesanitiseerd, door de tegenpartij
            # opgesteld. Dat is dezelfde soort blinde ondertekening als een tweede grant.
            weiger($ondertekend; "\($h) draagt grant-properties die wij niet ondertekenen")
          elif $g[0].outway.identification.type != "OUTWAY_IDENTIFICATION_TYPE_PUBLIC_KEY_THUMBPRINT" then
            weiger($ondertekend; "\($h) outway-identificatie \($g[0].outway.identification.type | veilig) is geen thumbprint")
          elif ($consumers | index($g[0].outway.peer_id)) == null then
            weiger($ondertekend; "\($h) consumer \($g[0].outway.peer_id | veilig) staat niet op de lijst")
          elif ((.signatures.accept // {}) | has($g[0].outway.peer_id)) | not then
            # fsc-core wil onder een serviceConnection de handtekening van beide kanten. Tekenen
            # vóór de tegenpartij zich gecommitteerd heeft, is een verplichting aangaan die de ander
            # nog niet is aangegaan.
            weiger($ondertekend; "\($h) draagt de handtekening van de consumer nog niet")
          elif $ondertekend then "GETEKEND \($c.hash) \($g[0].outway.peer_id)"
          else "TEKEN \($c.hash) \($g[0].outway.peer_id)" end
      ) catch "WEIGER \($h) is niet te beoordelen: \(. | tostring | gsub("[[:cntrl:]]"; "·"))"
      end' 2>>"$ERRLOG"
}

# --- Wat de consumer al heeft uitstaan ----------------------------------------------------------

# fsc_contract_voor_combinatie <json> <service> <provider_oin> <consumer_oin> <thumbprint>:
# `<hash> <state> <provider-getekend ja|nee> <afgewezen|->` per regel, voor elk niet-ingetrokken
# contract dat
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
    def veilig: (. // "ontbreekt") | tostring | gsub("[[:cntrl:]]"; "\u00b7");

    contracten[]
    | select(type == "object")
    # Hier bewust WÉL de afgewezen contracten erbij, anders dan in de beoordeling hiernaast. Daar is
    # "afgewezen" een besluit van onszelf dat we niet moeten overdoen; hier is het het antwoord van
    # de overkant op ónze aanvraag. Wegfilteren zou de consumer zijn eigen afgewezen contract niet
    # meer laten zien, waarna hij elke ronde een nieuwe indient en de afwijzing nergens blijkt.
    | select((.has_revoked // false) == false)
    # Zelfde reden als in de beoordeling hiernaast: één misvormde rij mag de rest niet meenemen.
    # Hier zonder melding — deze matcher zoekt ons eigen contract, en een rij van iemand anders die
    # we niet kunnen lezen is simpelweg niet de onze. De provider-kant maakt er wél een WEIGER van,
    # want daar is het een besluit.
    | try (
        select((.content.grants // []) | length == 1)
    | select(.content.grants[0]
        | .type == "GRANT_TYPE_SERVICE_CONNECTION"
          and .service.name == $svc
          and .service.peer_id == $prov
          and .outway.peer_id == $cons
          and .outway.identification.public_key_thumbprint == $thumb)
    # `veilig` ook op het hash: dit is net zo goed een regelgebaseerde stroom als de beoordeling
    # hiernaast, dus een hash met een newline erin zou hier een tweede record schrijven dat de
    # consumer-helft als eigen contract leest — en vervolgens intrekt, op een pad naar keuze.
        | "\(.hash | veilig) \((.state // "ontbreekt") | tostring | ascii_downcase) \(if ((.signatures.accept // {}) | has($prov)) then "ja" else "nee" end) \(if (.has_rejected // false) then "afgewezen" else "-" end)"
      ) catch empty
    ' 2>>"$ERRLOG"
}

# fsc_contract_regels <beoordeling> <soort>: de regels van één soort, zonder het soort-woord.
fsc_contract_regels() {
  printf '%s' "${1:-}" | sed -n "s/^$2 //p"
}
