#!/usr/bin/env bash
# Smoke: bewijst dat de FBS-keten écht door FSC loopt — berichtenuitvraag haalt een bericht op bij
# berichtenmagazijn-a via outway -> router -> inway, in plaats van rechtstreeks.
#
#   1. data-pad — een bericht dat alleen in de database van magazijn-a bestaat, komt via de
#      uitvraag terug. Een 200 op zichzelf zegt niets: de inhoud moet uniek zijn voor deze run,
#      anders kan een oud bericht uit de cache of een stub de assert groen houden;
#   2. verantwoording — dezelfde transactie staat in beide txlogs, uitgaand bij de uitvraag-peer en
#      inkomend bij het magazijn. Dit onderscheidt "door de keten" van "toevallig hetzelfde
#      antwoord": ging de call rechtstreeks, dan groeit geen van beide logboeken;
#   3. het niet-FSC-pad blijft werken — magazijn-b loopt rechtstreeks en mag hier niet door sneuvelen.
#
# Voorwaarden:
#   - de federatie draait en het contract staat  -> ./federatie.sh up && contracts/fbs-contracten.sh
#   - magazijn-a's inway wijst naar het ECHTE magazijn, niet naar de stub:
#       FSC_UPSTREAM_URL=http://127.0.0.1:8090 ../magazijn-a/deploy/local/publish-service.sh
#   - de demo-stack draait met de uitvraag door de outway:
#       MODUS=hostnet MAGAZIJN_A_URL=http://127.20.1.5:8443 demo/podman-up.sh
#
# Linux + podman: gebruikt `podman` en `curl`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=peers.env
. "${HERE}/peers.env"

fsc_errlog_init

UITVRAAG_URL="${UITVRAAG_URL:-http://127.0.0.1:8086/api/v1}"
MAGAZIJN_A_DIRECT="${MAGAZIJN_A_DIRECT:-http://127.0.0.1:8090/api/v1}"
MAGAZIJN_B_DIRECT="${MAGAZIJN_B_DIRECT:-http://127.0.0.1:8091/api/v1}"
OPHAAL_TIMEOUT="${OPHAAL_TIMEOUT:-60}"
# Ruimte voor de inway om een gewijzigde upstream op te pikken; elke poging is een volledige
# ophaling, dus dit is geen vaste wachttijd maar een bovengrens.
KETEN_POGINGEN="${KETEN_POGINGEN:-5}"
KETEN_WACHT="${KETEN_WACHT:-3}"

# Testgegevens. Elke run een VERSE ontvanger, en niet een vaste test-BSN: de uitvraag houdt een
# sessiecache per ontvanger, dus bij een tweede run zou `_ophalen` uit die cache serveren zonder het
# magazijn ooit te bellen. Assert 1 blijft dan groen terwijl de keten stuk kan zijn, en assert 2
# ziet geen nieuwe transactie. De BSN moet door de elfproef komen, anders weigert het magazijn hem
# en leest dat als een ketenfout.
nieuwe_bsn() {
  local cijfers som i c laatste

  while :; do
    cijfers=""; som=0

    for i in 9 8 7 6 5 4 3 2; do
      # Eerste cijfer nooit 0: een BSN met een voorloopnul is negen tekens lang maar wordt door
      # sommige parsers als achtcijferig gelezen.
      if [ "$i" -eq 9 ]; then c=$((RANDOM % 9 + 1)); else c=$((RANDOM % 10)); fi
      cijfers="${cijfers}${c}"
      som=$((som + c * i))
    done

    # Het negende cijfer telt met gewicht -1; bestaat er geen passend cijfer 0-9, dan opnieuw.
    for laatste in 0 1 2 3 4 5 6 7 8 9; do
      if [ $(( (som - laatste) % 11 )) -eq 0 ]; then
        printf '%s%s' "$cijfers" "$laatste"
        return 0
      fi
    done
  done
}

BSN="${KETEN_BSN:-$(nieuwe_bsn)}"
AFZENDER_OIN="$(fsc_peer_waarde OIN magazijn-a)"

FOUTEN=0
fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

command -v curl >/dev/null 2>&1 || { echo "FAIL: 'curl' is vereist." >&2; exit 1; }

# Uniek per run: zonder dat kan een bericht uit een eerdere run, of uit de sessiecache, deze smoke
# groen houden terwijl de keten stuk is.
MERK="ketensmoke-$$-$(od -An -N4 -tu4 < /dev/urandom | tr -d ' ')"

aanleveren() {  # <magazijn-basis-url> <onderwerp>
  curl -sS -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 20 \
    -X POST "$1/berichten" -H 'Content-Type: application/json' \
    -d "{\"afzender\":\"${AFZENDER_OIN}\",\"ontvanger\":{\"type\":\"BSN\",\"waarde\":\"${BSN}\"},\"onderwerp\":\"$2\",\"inhoud\":\"$2\"}" \
    2>"$ERRLOG"
}

# ophalen: vult de sessiecache van de uitvraag uit de magazijnen. Dit is de call die door de outway
# gaat; `_ophalen` is een SSE-stream, dus we lezen 'm met een harde timeout leeg.
ophalen() {
  curl -sS -o /dev/null --noproxy '*' --max-time "$OPHAAL_TIMEOUT" \
    -H "X-Ontvanger: BSN:${BSN}" -H 'Accept: text/event-stream' \
    "${UITVRAAG_URL}/berichten/_ophalen" 2>"$ERRLOG"
}

berichten() {
  curl -sS --noproxy '*' --max-time 20 -H "X-Ontvanger: BSN:${BSN}" \
    "${UITVRAAG_URL}/berichten" 2>"$ERRLOG"
}

# De txlog-meting hieronder telt alleen transacties die DEZE run veroorzaakt. Een eerdere
# smoke-contract-run laat namelijk ook een gedeelde transactie achter; zonder nulmeting zou deze
# smoke groen blijven terwijl de uitvraag rechtstreeks naar het magazijn ging.
if PROJECT="$(fsc_compose_project "${ENVDIR}/${GASTHEER}/deploy/local/docker-compose.yaml")"; then
  txids() {  # <db> <richting>
    podman exec "${PROJECT}-postgres-1" psql -U postgres -d "$1" -tA \
      -c "SELECT transaction_id FROM transactionlog.records
          WHERE direction = '$2' AND service_name = '${MAGAZIJN_DIENST}' AND grant_hash IS NOT NULL" \
      2>"$ERRLOG" | sort -u
  }

  # Eerst opvangen in variabelen, dan pas vergelijken. In een process substitution is de exitstatus
  # onzichtbaar: een gestopte postgres of een psql-fout levert dan lege uitvoer met exitcode 0, en
  # de assert hieronder zou "de uitvraag ging buiten de outway om" melden terwijl het logboek
  # simpelweg onleesbaar was.
  gedeelde_txids() {
    local uit in
    uit="$(txids "fsc_txlog_$(fsc_peer_var "$UITVRAAG")" out)" || {
      echo "WAARSCHUWING: txlog van ${UITVRAAG} niet leesbaar: $(fsc_last_error)" >&2
      return 1
    }
    in="$(txids "fsc_txlog_$(fsc_peer_var magazijn-a)" in)" || {
      echo "WAARSCHUWING: txlog van magazijn-a niet leesbaar: $(fsc_last_error)" >&2
      return 1
    }

    comm -12 <(printf '%s\n' "$uit") <(printf '%s\n' "$in")
  }

  TXLOG_LEESBAAR=1
else
  TXLOG_LEESBAAR=0
fi

# De inway moet naar het ECHTE magazijn wijzen. Dat hier afdwingen en niet als voorwaarde aan de
# gebruiker laten: `smoke-federatie.sh` publiceert de dienst met de echo-stub als upstream, dus wie
# die smoke ná het instellen draait, zet 'm ongemerkt terug. De publicatie is idempotent.
echo "== 0. inway wijst naar het echte magazijn =="
if fsc_zet_upstream "$ENVDIR" magazijn-a "${MAGAZIJN_A_UPSTREAM:-http://127.0.0.1:8090}" \
     >/dev/null 2>"$ERRLOG"; then
  # De dienstwijziging propageert asynchroon naar de inway; de eerste calls erna komen nog bij de
  # vórige upstream uit. Hier uitwachten en niet in assert 1: die mag maar één keer ophalen, want
  # daarna serveert de sessiecache en meet een tweede poging niets meer.
  #
  # De probe gaat door de outway naar `/q/health` — een pad dat het echte magazijn met JSON
  # beantwoordt en de echo-stub met zijn vaste tekst. Zo meten we de keten zelf en niet wat de
  # controller denkt te weten.
  # Via de lib-functie en niet met een kale `sed`: het bestand draagt compose-escaping, dus de
  # dollars staan er verdubbeld in.
  GRANT="$(fsc_compose_env_lees "$(cd "${ENVDIR}/.." && pwd)/generated/fsc-grants.env" MAGAZIJN_A_GRANT_HASH || true)"
  OUTWAY="http://$(fsc_component_adres "$(fsc_peer_waarde NET "$UITVRAAG")" outway):8443"
  POGING=1
  DOOR=0

  if ! fsc_grant_bruikbaar "$GRANT"; then
    fout "geen bruikbaar grant-hash in demo/generated/fsc-grants.env — draai contracts/fbs-contracten.sh"
  else
    while [ "$POGING" -le "$KETEN_POGINGEN" ]; do
      if curl -sS --noproxy '*' --max-time 10 -H "Fsc-Grant-Hash: ${GRANT}" \
           "${OUTWAY}/q/health" 2>"$ERRLOG" | grep -q '"status"'; then
        DOOR=1
        break
      fi

      [ "$POGING" -lt "$KETEN_POGINGEN" ] && sleep "$KETEN_WACHT"
      POGING=$((POGING + 1))
    done

    if [ "$DOOR" -eq 1 ]; then
      ok "upstream van de inway staat op ${MAGAZIJN_A_UPSTREAM:-http://127.0.0.1:8090} (na ${POGING} poging(en))"
    else
      fout "de inway levert na ${KETEN_POGINGEN} pogingen nog niet het echte magazijn — upstream niet doorgedrongen"
    fi
  fi
else
  fout "kon de upstream van magazijn-a niet zetten: $(fsc_last_error)"
fi

# Nulmeting NA stap 0, niet ervoor: die probe gaat zelf door de outway naar de inway en schrijft
# dus een rij in beide txlogs. Stond de nulmeting ervóór, dan telde die rij als "nieuw" en kon
# assert 2 nooit rood worden — ook niet als de uitvraag rechtstreeks met het magazijn praat, wat
# precies het geval is dat deze smoke moet uitsluiten.
# En pas nemen als de txlogs tot stilstand zijn gekomen. De inway/outway schrijven hun record
# out-of-band via de txlog-API, dus de rij van de probe hierboven kan ná de nulmeting landen en dan
# als "nieuw" meetellen — dezelfde vals-groen, alleen via een race in plaats van via de ordening.
VOOR_OK=0
VOOR=""

if [ "$TXLOG_LEESBAAR" -eq 1 ]; then
  STABIEL=0
  POGING=1

  while [ "$POGING" -le "$KETEN_POGINGEN" ]; do
    HUIDIG="$(gedeelde_txids)" || break

    if [ "$POGING" -gt 1 ] && [ "$HUIDIG" = "$VORIG" ]; then
      STABIEL=1
      break
    fi

    VORIG="$HUIDIG"
    sleep "$KETEN_WACHT"
    POGING=$((POGING + 1))
  done

  if [ "$STABIEL" -eq 1 ]; then
    VOOR="$HUIDIG"
    VOOR_OK=1
  else
    echo "WAARSCHUWING: de txlogs kwamen niet tot stilstand binnen ${KETEN_POGINGEN} metingen" >&2
  fi
fi

# --- 1. Data-pad ---------------------------------------------------------------------------------
echo "== 1. bericht uit magazijn-a via de FSC-keten =="
CODE="$(aanleveren "$MAGAZIJN_A_DIRECT" "$MERK")" || CODE=""

if [ "$CODE" != "201" ] && [ "$CODE" != "200" ]; then
  fout "aanleveren bij magazijn-a gaf HTTP ${CODE:-<geen>}: $(fsc_last_error) — draait de demo-stack?"
else
  ok "bericht '${MERK}' aangeleverd bij magazijn-a (HTTP ${CODE})"

  # Eén ophaling, bewust. Herproberen kan niet: de eerste ophaling vult de sessiecache voor deze
  # ontvanger, en elke volgende komt daaruit zonder het magazijn nog te bellen. Een tweede poging
  # zou dus niets nieuws meten en assert 2 juist rood maken.
  if ! ophalen; then
    fout "ophalen bij de uitvraag mislukte: $(fsc_last_error)"
  else
    LIJST="$(berichten)" || LIJST=""

    if printf '%s' "$LIJST" | grep -qF "$MERK"; then
      ok "de uitvraag levert het bericht van magazijn-a terug"
    else
      fout "het bericht '${MERK}' zit niet in de uitvraag-lijst — kwam de keten niet rond? $(fsc_last_error)"
    fi
  fi
fi

# --- 2. Verantwoording ---------------------------------------------------------------------------
# Zelfde meting als smoke-contract.sh, maar nu over verkeer dat de ÁPPLICATIE veroorzaakte in
# plaats van een handmatige curl. Ging de uitvraag rechtstreeks naar het magazijn, dan staat er
# niets in de txlogs en valt dat hier op.
echo "== 2. verantwoording in beide txlogs =="
if [ -z "${PROJECT:-}" ]; then
  fout "projectnaam van de gastheer niet af te leiden — deze assert heeft niets gemeten"
elif [ "$VOOR_OK" -ne 1 ]; then
  fout "de nulmeting op de txlogs mislukte — deze assert heeft niets gemeten"
elif ! NA="$(gedeelde_txids)"; then
  fout "de txlogs zijn na afloop niet leesbaar — deze assert heeft niets gemeten"
else
  NIEUW="$(comm -13 <(printf '%s\n' "$VOOR") <(printf '%s\n' "$NA") | grep -c . || true)"

  if [ "${NIEUW:-0}" -gt 0 ]; then
    ok "${NIEUW} nieuwe transactie(s) in beide txlogs, uitgaand bij ${UITVRAAG} en inkomend bij magazijn-a"
  else
    fout "geen NIEUWE gedeelde transactie-id — de uitvraag ging buiten de outway om (of het ophalen leverde niets op)"
  fi
fi

# --- 3. Het niet-FSC-pad ------------------------------------------------------------------------
# magazijn-b loopt rechtstreeks. Zonder deze assert zou een omzetting die alle magazijnen door FSC
# dwingt (of het rechtstreekse pad sloopt) hier ongemerkt doorheen komen.
echo "== 3. magazijn-b blijft rechtstreeks werken =="
CODE_B="$(aanleveren "$MAGAZIJN_B_DIRECT" "${MERK}-b")" || CODE_B=""

if [ "$CODE_B" = "201" ] || [ "$CODE_B" = "200" ]; then
  ok "magazijn-b neemt een bericht aan buiten FSC om (HTTP ${CODE_B})"
else
  fout "magazijn-b gaf HTTP ${CODE_B:-<geen>}: $(fsc_last_error)"
fi

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== KETEN-SMOKE GROEN =="
else
  echo "== KETEN-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
