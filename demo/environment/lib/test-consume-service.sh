#!/usr/bin/env bash
# Fixture-tests voor logius/deploy/local/consume-service.sh: herkent het een bestaand contract ook
# als dat niet op de eerste pagina van de contractenlijst staat, en dient het niets in op basis van
# een lijst die het niet kon lezen?
#
# Het script praat met de manager via `docker compose exec toolbox curl`. Een `docker` vooraan op
# PATH speelt die manager na: hij levert de lijst in pagina's per cursor, zoals OpenFSC v2.5.2 dat
# doet, en houdt bij welke muterende aanroepen er binnenkwamen. Het script draait in een kopie van
# de mappenstructuur, zodat state-file en outway-certificaat per geval vers zijn.
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
  "PUT "*) echo '{}' ;;
  "GET "*"/v1/contracts?"*)
    pagina=1
    case "$url" in *"cursor="*) pagina="${url##*cursor=c}" ;; esac
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

# --- Een bestaand contract op pagina 2 wordt herkend -------------------------------------------
# De oude lijst-aanroep las alleen de eerste pagina (default honderd rijen). Stond het eigen
# contract daar niet meer op, dan diende elke run een nieuw in.
opzet
pagina 1 nee "$(vreemd v1)" "$(vreemd v2)"
pagina 2 nee "$(contract bestaand "$THUMB")"
pagina 3 ja
met_state bestaand
draai

if [ "$RC" -ne 0 ] || [ "$(aantal POST)" != 0 ] || ! grep -q "GRANT-HASH: grant-bestaand" <<<"$UIT"; then
  fout "bestaand contract op pagina 2 niet herkend (exit ${RC}, POST's: $(aantal POST)): $(tail -n3 <<<"$UIT")"
else
  echo "OK: een bestaand contract op pagina 2 wordt herkend, er wordt niets ingediend"
fi
rm -rf "$TMP"

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

# --- Een onleesbare lijst leidt niet tot indienen ----------------------------------------------
# Vroeger werd een mislukte lijst een WARN en daarna een lege lijst; het script diende dan een
# tweede contract in naast het bestaande.
for geval in "geen-lijst" "eindeloze-cursor"; do
  opzet
  met_state bestaand

  case "$geval" in
    geen-lijst) echo '{"error":"onverwacht"}' > "${TMP}/stub/pagina-1.json" ;;
    eindeloze-cursor)
      # Elke pagina verwijst naar zichzelf: de lus moet dan op zijn bovengrens afbreken.
      jq -nc --argjson c "$(vreemd v1)" '{ contracts: [$c], pagination: { next_cursor: "c1" } }' \
        > "${TMP}/stub/pagina-1.json" ;;
  esac

  draai

  if [ "$RC" -eq 0 ] || [ "$(aantal POST)" != 0 ]; then
    fout "${geval}: verwacht een harde fout zonder indienen (exit ${RC}, POST's: $(aantal POST))"
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
