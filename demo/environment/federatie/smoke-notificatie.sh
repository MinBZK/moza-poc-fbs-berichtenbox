#!/usr/bin/env bash
# Smoke: bewijst dat het magazijn zijn CloudEvents door FSC pusht — berichtenmagazijn-a levert af
# via zijn eigen outway -> router -> inway van de notificatie-aanbieder -> WireMock-stub, in plaats
# van rechtstreeks.
#
#   1. data-pad — een bericht dat uniek is voor deze run komt als CloudEvent bij de stub aan, met
#      Content-Type application/cloudevents+json. Een aangekomen event op zichzelf zegt niets:
#      zonder uniek merk houdt een event uit een eerdere run dit groen;
#   2. verantwoording — dezelfde transactie staat in beide txlogs, uitgaand bij het magazijn en
#      inkomend bij de aanbieder. Ging de push rechtstreeks, dan groeit geen van beide;
#   3. fire-and-forget intact — precies één aflevering. Meer betekent dat het magazijn zijn 202
#      niet terugkreeg en in de retry-lus zat.
#
# Voorwaarden:
#   - de federatie draait en de contracten staan -> ./federatie.sh up && contracts/fbs-contracten.sh
#   - de demo-stack draait met het magazijn door de outway:
#       MODUS=hostnet NOTIFICATIE_URL=http://127.20.2.5:8443/events demo/podman-up.sh
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

MAGAZIJN_A_DIRECT="${MAGAZIJN_A_DIRECT:-http://127.0.0.1:8090/api/v1}"
NOTIFICATIE_STUB="${NOTIFICATIE_STUB:-http://127.0.0.1:8084}"
# De inway moet de stub als upstream krijgen. Host-adres, want de stub draait in de demo-stack en
# niet in de peer-stack; in hostnet-modus delen ze de netns.
NOTIFICATIE_UPSTREAM="${NOTIFICATIE_UPSTREAM:-http://127.0.0.1:8084}"
# De outbox-poller draait op 60s. Bewust niet verlaagd: dan bewijst deze smoke een configuratie die
# niemand draait. Vandaar een bovengrens in plaats van een vaste wachttijd.
PUBLICATIE_TIMEOUT="${PUBLICATIE_TIMEOUT:-150}"
PUBLICATIE_INTERVAL="${PUBLICATIE_INTERVAL:-5}"
PROBE_POGINGEN="${PROBE_POGINGEN:-5}"
PROBE_WACHT="${PROBE_WACHT:-3}"

PUSHER="$(printf '%s' "$PUSHERS" | awk '{print $1}')"
PUSHER_OIN="$(fsc_peer_waarde OIN "$PUSHER")"

FOUTEN=0
fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

command -v curl >/dev/null 2>&1 || { echo "FAIL: 'curl' is vereist." >&2; exit 1; }

# Elke run een VERSE ontvanger-BSN. Het merk zit in onderwerp en inhoud, maar een vaste BSN zou de
# journal van de stub met events van eerdere runs vullen en assert 3 (precies één aflevering)
# breken. De BSN moet door de elfproef komen, anders weigert het magazijn hem en leest dat als een
# ketenfout.
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

BSN="${NOTIFICATIE_BSN:-$(nieuwe_bsn)}"
MERK="notifsmoke-$$-$(od -An -N4 -tu4 < /dev/urandom | tr -d ' ')"

# tel_afleveringen [extra-matchers-json]: hoeveel requests met dit merk de stub ontving.
#
# WireMock's count-API en niet een grep over de journal: het merk staat in zowel onderwerp als
# inhoud van de CloudEvent, dus tekstvoorkomens tellen zou afleveringen dubbel tellen — en dat
# aantal zou meeveranderen met de payload.
tel_afleveringen() {
  curl -sS --noproxy '*' --max-time 10 -X POST "${NOTIFICATIE_STUB}/__admin/requests/count" \
    -H 'Content-Type: application/json' \
    -d "{\"method\":\"POST\",\"url\":\"/events\",\"bodyPatterns\":[{\"contains\":\"${MERK}\"}]${1:-}}" \
    2>"$ERRLOG" \
    | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p'
}

# --- 0. De inway van de aanbieder wijst naar de notificatie-stub --------------------------------
# Hier afdwingen en niet als voorwaarde aan de gebruiker laten: de publicatie is idempotent, en wie
# eerst een andere smoke draait zet de upstream ongemerkt terug.
echo "== 0. ${NOTIFICATIE} biedt ${NOTIFICATIE_DIENST} aan met de stub als upstream =="
if fsc_zet_upstream "$ENVDIR" "$NOTIFICATIE" "$NOTIFICATIE_UPSTREAM" "$NOTIFICATIE_DIENST" \
     >/dev/null 2>"$ERRLOG"; then
  ok "dienst ${NOTIFICATIE_DIENST} gepubliceerd op de inway van ${NOTIFICATIE}"
else
  fout "kon ${NOTIFICATIE_DIENST} niet publiceren: $(fsc_last_error)"
fi

# Probe door de outway: bewijst dat het contract leeft en dat de dienstwijziging is doorgedrongen,
# vóór we het van de applicatie afhankelijk maken. Die wijziging propageert asynchroon, dus de
# eerste pogingen kunnen nog bij de vorige upstream uitkomen.
GRANT="$(fsc_compose_env_lees "$(cd "${ENVDIR}/.." && pwd)/generated/fsc-grants.env" NOTIFICATIE_GRANT_HASH || true)"
OUTWAY="http://$(fsc_component_adres "$(fsc_peer_waarde NET "$PUSHER")" outway):8443"

if ! fsc_grant_bruikbaar "$GRANT"; then
  fout "geen bruikbaar NOTIFICATIE_GRANT_HASH in demo/generated/fsc-grants.env — draai contracts/fbs-contracten.sh"
else
  POGING=1
  DOOR=0

  while [ "$POGING" -le "$PROBE_POGINGEN" ]; do
    CODE="$(curl -sS -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 10 \
              -X POST "${OUTWAY}/events" \
              -H "Fsc-Grant-Hash: ${GRANT}" \
              -H 'Content-Type: application/cloudevents+json' \
              -d '{"specversion":"1.0","id":"probe","source":"urn:probe","type":"probe"}' \
              2>"$ERRLOG" || true)"

    if [ "$CODE" = "202" ]; then
      DOOR=1
      break
    fi

    [ "$POGING" -lt "$PROBE_POGINGEN" ] && sleep "$PROBE_WACHT"
    POGING=$((POGING + 1))
  done

  if [ "$DOOR" -eq 1 ]; then
    ok "de outway van ${PUSHER} bereikt de stub door de inway heen (202, na ${POGING} poging(en))"
  else
    fout "de probe door de outway gaf HTTP ${CODE:-<geen>}: $(fsc_last_error)"
  fi
fi

# --- Nulmeting op de txlogs ---------------------------------------------------------------------
# NA de probe, niet ervoor: die gaat zelf door outway en inway en schrijft dus in beide logboeken.
# Stond de nulmeting ervóór, dan telde die rij als "nieuw" en kon assert 2 nooit rood worden — ook
# niet als het magazijn buiten de outway om levert, wat deze smoke juist moet uitsluiten.
if PROJECT="$(fsc_compose_project "${ENVDIR}/${GASTHEER}/deploy/local/docker-compose.yaml")"; then
  txids() {  # <db> <richting>
    podman exec "${PROJECT}-postgres-1" psql -U postgres -d "$1" -tA \
      -c "SELECT transaction_id FROM transactionlog.records
          WHERE direction = '$2' AND service_name = '${NOTIFICATIE_DIENST}' AND grant_hash IS NOT NULL" \
      2>"$ERRLOG" | sort -u
  }

  # Eerst opvangen in variabelen, dan pas vergelijken. In een process substitution is de exitstatus
  # onzichtbaar: een gestopte postgres of een psql-fout levert dan lege uitvoer met exitcode 0, en
  # de assert hieronder zou "het magazijn ging buiten de outway om" melden terwijl het logboek
  # simpelweg onleesbaar was.
  gedeelde_txids() {
    local uit in

    uit="$(txids "fsc_txlog_$(fsc_peer_var "$PUSHER")" out)" || {
      echo "WAARSCHUWING: txlog van ${PUSHER} niet leesbaar: $(fsc_last_error)" >&2
      return 1
    }
    in="$(txids "fsc_txlog_$(fsc_peer_var "$NOTIFICATIE")" in)" || {
      echo "WAARSCHUWING: txlog van ${NOTIFICATIE} niet leesbaar: $(fsc_last_error)" >&2
      return 1
    }

    comm -12 <(printf '%s\n' "$uit") <(printf '%s\n' "$in")
  }

  TXLOG_LEESBAAR=1
else
  TXLOG_LEESBAAR=0
fi

VOOR_OK=0
VOOR=""

if [ "$TXLOG_LEESBAAR" -eq 1 ]; then
  STABIEL=0
  POGING=1

  # Uitwachten tot de logboeken stilstaan: in- en outway schrijven hun record out-of-band via de
  # txlog-API, dus de rij van de probe kan ná de nulmeting landen en dan als "nieuw" meetellen.
  while [ "$POGING" -le "$PROBE_POGINGEN" ]; do
    HUIDIG="$(gedeelde_txids)" || break

    if [ "$POGING" -gt 1 ] && [ "$HUIDIG" = "$VORIG" ]; then
      STABIEL=1
      break
    fi

    VORIG="$HUIDIG"
    sleep "$PROBE_WACHT"
    POGING=$((POGING + 1))
  done

  if [ "$STABIEL" -eq 1 ]; then
    VOOR="$HUIDIG"
    VOOR_OK=1
  else
    echo "WAARSCHUWING: de txlogs kwamen niet tot stilstand binnen ${PROBE_POGINGEN} metingen" >&2
  fi
fi

# --- 1. Data-pad --------------------------------------------------------------------------------
echo "== 1. de CloudEvent van magazijn ${PUSHER} komt bij de stub aan =="
AANTAL=0
CODE="$(curl -sS -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 20 \
          -X POST "${MAGAZIJN_A_DIRECT}/berichten" -H 'Content-Type: application/json' \
          -d "{\"afzender\":\"${PUSHER_OIN}\",\"ontvanger\":{\"type\":\"BSN\",\"waarde\":\"${BSN}\"},\"onderwerp\":\"${MERK}\",\"inhoud\":\"${MERK}\"}" \
          2>"$ERRLOG" || true)"

if [ "$CODE" != "201" ] && [ "$CODE" != "200" ]; then
  fout "aanleveren bij ${PUSHER} gaf HTTP ${CODE:-<geen>}: $(fsc_last_error) — draait de demo-stack?"
else
  echo "  bericht '${MERK}' aangeleverd (HTTP ${CODE}); wachten op de outbox-poller..."

  elapsed=0

  while [ "$elapsed" -lt "$PUBLICATIE_TIMEOUT" ]; do
    AANTAL="$(tel_afleveringen || true)"
    [ -n "$AANTAL" ] || AANTAL=0

    if [ "$AANTAL" -ge 1 ]; then
      break
    fi

    sleep "$PUBLICATIE_INTERVAL"
    elapsed=$((elapsed + PUBLICATIE_INTERVAL))
  done

  if [ "$AANTAL" -ge 1 ]; then
    ok "de stub ontving de CloudEvent van '${MERK}' (na ${elapsed}s)"

    # Zelfde telling, nu mét de header-eis. Blijft hij gelijk, dan gaf de inway het Content-Type
    # ongewijzigd door en overleeft structured content mode de keten.
    MET_HEADER="$(tel_afleveringen ',"headers":{"Content-Type":{"contains":"application/cloudevents+json"}}' || true)"
    [ -n "$MET_HEADER" ] || MET_HEADER=0

    if [ "$MET_HEADER" -eq "$AANTAL" ]; then
      ok "de inway gaf Content-Type application/cloudevents+json ongewijzigd door"
    else
      fout "${MET_HEADER} van ${AANTAL} afleveringen droeg het cloudevents-Content-Type"
    fi
  else
    fout "de stub ontving geen event voor '${MERK}' binnen ${PUBLICATIE_TIMEOUT}s"
  fi
fi

# --- 2. Verantwoording ---------------------------------------------------------------------------
# Zelfde meting als smoke-keten.sh, maar over verkeer dat het MAGAZIJN veroorzaakte en in de
# tegenovergestelde richting: hier is het magazijn de consumer.
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
    ok "${NIEUW} nieuwe transactie(s) in beide txlogs, uitgaand bij ${PUSHER} en inkomend bij ${NOTIFICATIE}"
  else
    fout "geen NIEUWE gedeelde transactie-id — het magazijn ging buiten de outway om"
  fi
fi

# --- 3. Fire-and-forget --------------------------------------------------------------------------
echo "== 3. één aflevering, geen retry-stapeling =="
if [ "$AANTAL" -eq 0 ]; then
  fout "geen aflevering gevonden voor '${MERK}' — assert 1 was al rood"
elif [ "$AANTAL" -eq 1 ]; then
  ok "het magazijn leverde één keer af en kreeg zijn 202 terug"
else
  fout "${AANTAL} afleveringen voor hetzelfde bericht — het antwoord van de stub komt niet terug door de keten"
fi

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== NOTIFICATIE-SMOKE GROEN =="
else
  echo "== NOTIFICATIE-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
