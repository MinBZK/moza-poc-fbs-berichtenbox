#!/usr/bin/env bash
# Fixture-tests voor logius/deploy/local/consume-service.sh: herkent het een bestaand contract ook
# als dat niet op de eerste pagina van de contractenlijst staat, en dient het niets in op basis van
# een lijst die het niet kon lezen?
#
# Het script praat met de manager via `docker compose exec toolbox curl`. Een `docker` vooraan op
# PATH speelt die manager na: hij levert de lijst in pagina's per cursor, en houdt bij welke
# muterende aanroepen er binnenkwamen. Dat is een manager die wél doorbladert; het gedrag van
# OpenFSC v2.5.2, waar de pagina na een volle eerste pagina leeg is, speelt het geval
# "afgekapte-lijst" na. Het script draait in een kopie van de mappenstructuur, zodat state-file en
# outway-certificaat per geval vers zijn.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${HERE}/../logius/deploy/local/consume-service.sh"

command -v jq >/dev/null 2>&1 || { echo "FAIL: jq is vereist." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "FAIL: openssl is vereist." >&2; exit 1; }

OIN=00000000000000001000
DIENST=profiel-service
NIEUW=nieuw-contract

fails=0

fout() {
  echo "FAIL: $1" >&2
  fails=$((fails + 1))
}

# contract <hash> <thumbprint>: een geaccepteerd, geldig serviceConnection-contract.
contract() {
  jq -nc --arg h "$1" --arg thumb "$2" --arg oin "$OIN" --arg svc "$DIENST" '
    { hash: $h, state: "CONTRACT_STATE_VALID",
      signatures: { accept: { ($oin): true } },
      content: { grants: [ { hash: ("grant-" + $h), type: "GRANT_TYPE_SERVICE_CONNECTION",
        service: { name: $svc, peer_id: $oin },
        outway: { peer_id: $oin, identification: { public_key_thumbprint: $thumb } } } ] } }'
}

# vreemd <hash>: een contract van een andere dienst; vult pagina's zonder iets te matchen.
vreemd() {
  jq -nc --arg h "$1" '{ hash: $h, state: "CONTRACT_STATE_VALID", signatures: { accept: {} },
    content: { grants: [ { type: "GRANT_TYPE_SERVICE_PUBLICATION", service: { name: "iets-anders" } } ] } }'
}

# opzet: een verse kopie van de mappenstructuur met outway-certificaat en docker-stub. Zet $TMP en
# $THUMB.
opzet() {
  TMP="$(mktemp -d)"
  mkdir -p "${TMP}/logius/deploy/local" "${TMP}/logius/pki/out/logius/outway" "${TMP}/stub"
  ln -s "$HERE" "${TMP}/lib"
  cp "$SCRIPT" "${TMP}/logius/deploy/local/consume-service.sh"

  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 \
    -subj /CN=outway -keyout "${TMP}/stub/key.pem" -out "${TMP}/logius/pki/out/logius/outway/cert.pem" \
    >/dev/null 2>&1
  THUMB="$(openssl x509 -in "${TMP}/logius/pki/out/logius/outway/cert.pem" -pubkey -noout \
    | openssl pkey -pubin -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1)"

  # Pagina N staat in pagina-N.json; een pagina die ná indienen anders luidt in pagina-N.na.json.
  # Elke aanroep komt in aanroepen.log, de muterende met hun methode ervoor.
  cat > "${TMP}/stub/docker" <<'STUB'
#!/usr/bin/env bash
dir="$(dirname "$0")"
methode=GET url=""
vorige=""

for arg in "$@"; do
  [ "$vorige" = "-X" ] && methode="$arg"
  case "$arg" in https://*) url="$arg" ;; esac
  vorige="$arg"
done

echo "${methode} ${url}" >> "${dir}/aanroepen.log"

case "${methode} ${url}" in
  "POST "*"/v1/contracts")
    touch "${dir}/ingediend"
    echo '{"content_hash":"nieuw-contract"}' ;;
  "PUT "*)
    touch "${dir}/geaccepteerd"
    echo '{}' ;;
  "GET "*"/v1/contracts?"*)
    pagina=1
    case "$url" in *"cursor="*) pagina="${url##*cursor=c}" ;; esac

    # faal-<n>: pagina n geeft steeds een HTTP-fout. faal-eenmalig-na: de eerste lezing ná indienen
    # faalt, de volgende niet — een manager die even hapert terwijl het script pollt.
    # faal-na-accept: elke lezing ná de accept faalt — de verificatie van stap 4 strandt.
    if [ -f "${dir}/geaccepteerd" ] && [ -f "${dir}/faal-na-accept" ]; then
      echo "curl: (22) The requested URL returned error: 503" >&2
      exit 22
    fi

    if [ -f "${dir}/faal-${pagina}" ]; then
      echo "curl: (22) The requested URL returned error: 503" >&2
      exit 22
    fi

    if [ -f "${dir}/ingediend" ] && [ -f "${dir}/faal-eenmalig-na" ]; then
      rm "${dir}/faal-eenmalig-na"
      echo "curl: (56) Recv failure: Connection reset by peer" >&2
      exit 56
    fi

    if [ -f "${dir}/ingediend" ] && [ -f "${dir}/pagina-${pagina}.na.json" ]; then
      cat "${dir}/pagina-${pagina}.na.json"
    else
      cat "${dir}/pagina-${pagina}.json"
    fi ;;
  *) echo "onverwachte aanroep: ${methode} ${url}" >&2; exit 22 ;;
esac
STUB
  chmod +x "${TMP}/stub/docker"
}

# pagina <n> <laatste: ja|nee> <contract-json...>: zet pagina n klaar; behalve de laatste draagt
# elke pagina een cursor naar de volgende. De manager zet ook op de laatste gevulde pagina een
# cursor, dus "laatste" is de lege slotpagina.
pagina() {
  local n="$1" laatste="$2" cursor=""
  shift 2
  [ "$laatste" = ja ] || cursor="c$((n + 1))"
  printf '%s\n' "$@" | jq -sc --arg c "$cursor" '{ contracts: map(select(. != null)), pagination: { next_cursor: $c } }' \
    > "${TMP}/stub/pagina-${n}.json"
}

# met_state <hash>: een eerdere run legde dit contract vast.
met_state() {
  mkdir -p "${TMP}/logius/contracts/.bootstrap-state"
  printf '%s\n' "$1" > "${TMP}/logius/contracts/.bootstrap-state/${OIN}-${OIN}-${DIENST}.hash"
}

# draai: het script tegen de stub; zet $RC en $UIT.
draai() {
  RC=0
  UIT="$(PATH="${TMP}/stub:$PATH" "${TMP}/logius/deploy/local/consume-service.sh" 2>&1)" || RC=$?
}

aantal() { grep -c "^$1 " "${TMP}/stub/aanroepen.log" 2>/dev/null || true; }

# --- Een tweede run met een bestaand contract dient niets in ------------------------------------
# Leeg, één pagina en meer pagina's. Wie alleen de eerste pagina leest, ziet een eigen contract
# verderop niet en dient elke run een nieuw in. Een lege lijst is wél een reden om opnieuw op te
# zetten — die moet zich blijven onderscheiden van een onleesbare lijst.
for geval in leeg een-pagina meer-paginas; do
  opzet
  met_state bestaand

  case "$geval" in
    leeg)
      pagina 1 ja
      jq -c --argjson c "$(contract "$NIEUW" "$THUMB")" '.contracts += [$c]' "${TMP}/stub/pagina-1.json" \
        > "${TMP}/stub/pagina-1.na.json"
      verwacht_posts=1 verwacht_grant="grant-${NIEUW}" ;;
    een-pagina)
      pagina 1 nee "$(vreemd v1)" "$(contract bestaand "$THUMB")"
      pagina 2 ja
      verwacht_posts=0 verwacht_grant=grant-bestaand ;;
    meer-paginas)
      pagina 1 nee "$(vreemd v1)" "$(vreemd v2)"
      pagina 2 nee "$(contract bestaand "$THUMB")"
      pagina 3 ja
      verwacht_posts=0 verwacht_grant=grant-bestaand ;;
  esac

  draai

  if [ "$RC" -ne 0 ] || [ "$(aantal POST)" != "$verwacht_posts" ] \
     || ! grep -q "GRANT-HASH: ${verwacht_grant}" <<<"$UIT"; then
    fout "${geval}: verwacht ${verwacht_posts} POST('s) en ${verwacht_grant} (exit ${RC}, POST's: $(aantal POST)): $(tail -n3 <<<"$UIT")"
  else
    echo "OK: ${geval}: ${verwacht_posts} contract(en) ingediend, grant ${verwacht_grant}"
  fi

  rm -rf "$TMP"
done

# --- Een nieuw contract dat pas op pagina 2 verschijnt, wordt geaccepteerd ---------------------
# Zelfde gat aan de andere kant: de poll na indienen zocht alleen op pagina 1 en meldde dan "niet
# zichtbaar", terwijl het contract er wél stond.
opzet
pagina 1 nee "$(vreemd v1)"
pagina 2 nee
jq -c --argjson c "$(contract "$NIEUW" "$THUMB")" '.contracts += [$c]' "${TMP}/stub/pagina-2.json" \
  > "${TMP}/stub/pagina-2.na.json"
pagina 3 ja
draai

if [ "$RC" -ne 0 ] || [ "$(aantal POST)" != 1 ] || [ "$(aantal PUT)" != 1 ] \
   || ! grep -q "CONSUME OK" <<<"$UIT"; then
  fout "nieuw contract op pagina 2 niet afgerond (exit ${RC}, POST's: $(aantal POST), PUT's: $(aantal PUT)): $(tail -n3 <<<"$UIT")"
else
  echo "OK: een nieuw contract dat op pagina 2 verschijnt, wordt één keer ingediend en geaccepteerd"
fi
rm -rf "$TMP"

# --- Een haperende manager tijdens de poll breekt de run niet af --------------------------------
# Het contract is dan al ingediend. Afbreken zou het half ondertekend en zonder state-file achterlaten,
# en de volgende run zou er een tweede naast indienen.
opzet
pagina 1 ja
jq -c --argjson c "$(contract "$NIEUW" "$THUMB")" '.contracts += [$c]' "${TMP}/stub/pagina-1.json" \
  > "${TMP}/stub/pagina-1.na.json"
touch "${TMP}/stub/faal-eenmalig-na"
draai
state="${TMP}/logius/contracts/.bootstrap-state/${OIN}-${OIN}-${DIENST}.hash"

if [ "$RC" -ne 0 ] || [ "$(aantal POST)" != 1 ] || ! grep -q "CONSUME OK" <<<"$UIT" \
   || [ "$(cat "$state" 2>/dev/null)" != "$NIEUW" ]; then
  fout "haperende poll niet overleefd (exit ${RC}, POST's: $(aantal POST)): $(tail -n3 <<<"$UIT")"
else
  echo "OK: een eenmalige leesfout tijdens de poll wordt overgeslagen; het contract wordt afgerond"
fi
rm -rf "$TMP"

# --- Een verificatie die na de accept strandt, levert de volgende run geen tweede contract op ----
# De state-file staat er dan al; de volgende run beoordeelt het geaccepteerde contract in plaats
# van er een tweede naast in te dienen.
opzet
pagina 1 ja
jq -c --argjson c "$(contract "$NIEUW" "$THUMB")" '.contracts += [$c]' "${TMP}/stub/pagina-1.json" \
  > "${TMP}/stub/pagina-1.na.json"
touch "${TMP}/stub/faal-na-accept"
draai
rc_eerste=$RC
rm "${TMP}/stub/faal-na-accept"
draai

if [ "$rc_eerste" -eq 0 ] || [ "$RC" -ne 0 ] || [ "$(aantal POST)" != 1 ] \
   || ! grep -q "GRANT-HASH: grant-${NIEUW}" <<<"$UIT"; then
  fout "gestrande verificatie (exit ${rc_eerste} dan ${RC}, POST's: $(aantal POST)): $(tail -n3 <<<"$UIT")"
else
  echo "OK: na een gestrande verificatie neemt de volgende run het contract over zonder nieuw indienen"
fi
rm -rf "$TMP"

# --- Een vastgelegd contract dat niet geldig is, wordt opnieuw opgezet -------------------------
# Ook zonder accept-handtekening in de lijst: de state-poort geldt voor beide takken van stap 1.
opzet
jq -nc --argjson c "$(contract bestaand "$THUMB")" \
  '{ contracts: [ $c | del(.signatures) | .state = "CONTRACT_STATE_PROPOSED" ], pagination: { next_cursor: "" } }' \
  > "${TMP}/stub/pagina-1.json"
jq -c --argjson c "$(contract "$NIEUW" "$THUMB")" '.contracts += [$c]' "${TMP}/stub/pagina-1.json" \
  > "${TMP}/stub/pagina-1.na.json"
met_state bestaand
draai

if [ "$RC" -ne 0 ] || [ "$(aantal POST)" != 1 ] || ! grep -q "GRANT-HASH: grant-${NIEUW}" <<<"$UIT"; then
  fout "ongeldig vastgelegd contract niet vervangen (exit ${RC}, POST's: $(aantal POST)): $(tail -n3 <<<"$UIT")"
else
  echo "OK: een vastgelegd contract dat niet geldig is, wordt vervangen door een nieuw"
fi
rm -rf "$TMP"

# --- Een onleesbare lijst leidt niet tot indienen ----------------------------------------------
# Als lege lijst gelezen zou een mislukte lezing het script een tweede contract laten indienen naast
# het bestaande.
#
# De melding telt mee: zonder die check slaagt een geval ook als het script om een heel andere
# reden stopt, bijvoorbeeld op een aanroep die de stub niet kent.
for geval in "geen-lijst" "http-fout" "fout-op-pagina-2" "eindeloze-cursor" "afgekapte-lijst"; do
  opzet
  met_state bestaand

  case "$geval" in
    geen-lijst)
      echo '{"error":"onverwacht"}' > "${TMP}/stub/pagina-1.json"
      melding='geen veld "contracts"' ;;
    http-fout)
      pagina 1 ja
      touch "${TMP}/stub/faal-1"
      melding='returned error: 503' ;;
    fout-op-pagina-2)
      # Een half gelezen lijst mist misschien juist het eigen contract; die is dus net zo onbruikbaar.
      pagina 1 nee "$(vreemd v1)"
      touch "${TMP}/stub/faal-2"
      melding='returned error: 503' ;;
    eindeloze-cursor)
      # Elke pagina verwijst naar zichzelf: de lus moet afbreken op de herhaling.
      jq -nc --argjson c "$(vreemd v1)" '{ contracts: [$c], pagination: { next_cursor: "c1" } }' \
        > "${TMP}/stub/pagina-1.json"
      melding='al gelezen contracten — afgebroken' ;;
    afgekapte-lijst)
      # Zo gedraagt OpenFSC v2.5.2 zich bij meer contracten dan de `limit` van het script: een volle
      # eerste pagina mét cursor, en daarachter niets. Het eigen contract kan op het afgekapte deel
      # staan.
      jq -nc --argjson c "$(vreemd v)" \
        '{ contracts: [range(1000) as $i | $c | .hash = "v\($i)"], pagination: { next_cursor: "c2" } }' \
        > "${TMP}/stub/pagina-1.json"
      pagina 2 ja
      melding='mogelijk afgekapt' ;;
  esac

  draai

  if [ "$RC" -eq 0 ] || [ "$(aantal POST)" != 0 ] \
     || ! grep -q "kon de contractenlijst niet ophalen: .*${melding}" <<<"$UIT"; then
    fout "${geval}: verwacht een harde fout met '${melding}' zonder indienen (exit ${RC}, POST's: $(aantal POST)): $(tail -n3 <<<"$UIT")"
  else
    echo "OK: ${geval}: het script faalt zonder een contract in te dienen (exit ${RC})"
  fi

  rm -rf "$TMP"
done

if [ "$fails" -gt 0 ]; then
  echo "${fails} test(s) gefaald." >&2
  exit 1
fi

echo "Alle consume-service-tests geslaagd."
