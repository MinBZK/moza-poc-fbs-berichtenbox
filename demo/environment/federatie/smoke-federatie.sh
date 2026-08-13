#!/usr/bin/env bash
# Smoke: bewijst dat de peers écht één federatie vormen en niet twee losse harnessen die toevallig
# tegelijk draaien. Draai na `./federatie.sh up`.
#
#   1. gedeelde group-CA — niet als bestandsvergelijking maar als TLS-handshake dwars door de
#      router: peer A's anker moet peer B's certificaat accepteren;
#   2. internal-CA-isolatie — een internal-leaf van A mag NIET valideren tegen de internal-root
#      van B. Dat is de grens die de federatie voor het eerst op de proef stelt;
#   3. poortschema — elke poort die de overlays declareren luistert, geen standalone-poort is
#      blijven hangen, en niets luistert buiten loopback;
#   4. één directory — alle peers plus de directory, op :443, en géén rijen te veel;
#   5. publiceren + ontdekken — een gast publiceert een dienst en vindt 'm in de gedeelde catalogus.
#
# Linux + podman: gebruikt `ss` (iproute2) en `podman`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=peers.env
. "${HERE}/peers.env"

fsc_errlog_init

command -v ss >/dev/null 2>&1 || { echo "FAIL: 'ss' (iproute2) is vereist." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "FAIL: 'openssl' is vereist." >&2; exit 1; }

FOUTEN=0
fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

peer_var() { printf '%s' "$1" | tr '-' '_'; }
alle_peers() { printf '%s %s' "$GASTHEER" "$GASTEN"; }

# peers.env levert `OIN_<peer>`/`BLOK_<peer>`; indirecte expansie omdat bash 3.2 (macOS-default,
# waar de rest van de harness op mikt) geen associative arrays kent.
peer_waarde() { eval "printf '%s' \"\${$1_$(peer_var "$2"):-}\""; }

compose_project() {
  sed -n 's/^name:[[:space:]]*//p' "${ENVDIR}/$1/deploy/local/docker-compose.yaml" | head -n1
}

# --- 1. Gedeelde group-CA, bewezen op de verbinding ----------------------------------------------
# Een bestandsvergelijking van root.pem zou zeggen dat de bestanden gelijk zijn, niet dat de
# draaiende processen elkaar vertrouwen (certs worden bij containerstart geladen; de CA ná `up`
# delen geeft identieke bestanden én wantrouwende peers). De handshake toetst het echte gedrag.
echo "== 1. gedeelde group-CA (TLS-handshake door de router) =="
for peer in $(alle_peers); do
  for tegen in $(alle_peers); do
    [ "$peer" != "$tegen" ] || continue
    ANKER="${ENVDIR}/${peer}/pki/ca/root.pem"

    if [ ! -r "$ANKER" ]; then
      fout "${peer} heeft geen leesbare group-root (${ANKER})"
      continue
    fi

    UIT="$(echo | openssl s_client -connect 127.0.0.1:443 \
             -servername "${tegen}.fsc-test.local" -CAfile "$ANKER" 2>&1 || true)"

    if printf '%s' "$UIT" | grep -q 'Verify return code: 0'; then
      ok "${peer}'s anker accepteert het certificaat van ${tegen}"
    else
      fout "${peer}'s anker verwerpt ${tegen} — group-CA niet gedeeld, of de router routeert niet: $(printf '%s' "$UIT" | grep -m1 'Verify return code' || echo '<geen verify-regel>')"
    fi
  done
done

# --- 2. Internal-CA-isolatie ---------------------------------------------------------------------
# De internal-CA is per peer en tekent alleen verkeer bínnen die peer. Dat moet aantoonbaar zo
# blijven: slaagt deze validatie wél, dan delen twee peers een internal-anker en is de scheiding weg.
echo "== 2. internal-CA blijft per peer =="
for peer in $(alle_peers); do
  for tegen in $(alle_peers); do
    [ "$peer" != "$tegen" ] || continue
    ANKER="${ENVDIR}/${peer}/pki/internal/${peer}/ca/root.pem"
    LEAF="${ENVDIR}/${tegen}/pki/internal/${tegen}/manager/cert.pem"
    [ -r "$ANKER" ] && [ -r "$LEAF" ] || { fout "internal-materiaal ontbreekt (${ANKER} / ${LEAF})"; continue; }

    if openssl verify -CAfile "$ANKER" "$LEAF" >/dev/null 2>&1; then
      fout "${peer}'s internal-root accepteert de internal-leaf van ${tegen} — de isolatie is weg"
    else
      ok "${peer}'s internal-root verwerpt ${tegen} (isolatie intact)"
    fi
  done
done

# --- 3. Poortschema ------------------------------------------------------------------------------
# De verwachte peer-poorten worden UIT de overlays gelezen, niet met de hand bijgehouden: een lijst
# die naast de overlays leeft, drift eruit en dekt dan minder dan hij belooft.
echo "== 3. poortschema =="
peer_poorten() { grep -oE '127\.0\.0\.1:[0-9]+' "${HERE}/compose/$1.yaml" | cut -d: -f2 | sort -un; }

# INFRA_POORTEN komt uit peers.env — dezelfde lijst die de CI-guard uitzondert van de
# blok-controle, zodat guard en smoke niet uiteen kunnen lopen.

# Standalone-poorten: als een service in de federatie-overlay ontbreekt, houdt hij zijn
# hostnet-poort — en die is voor elke peer identiek, dus twee peers botsen. Hun aanwezigheid is
# het directe symptoom; een positieve lijst alleen ziet dat niet.
STANDALONE_POORTEN="8443 8444 9443 9444 28080 28443 29444 38081 39443 48081 49443 58081 58443"

LISTENERS="$(ss -ltnH 2>"$ERRLOG" | awk '{print $4}' | sort -u || true)"
fsc_warn_errlog "ss faalde"
[ -n "$LISTENERS" ] || fout "geen enkele listener gevonden — draait de federatie, en deelt deze shell de netns?"

LOOPBACK="$(printf '%s\n' "$LISTENERS" | grep -E '^127\.0\.0\.1:' | sed 's/.*://' | sort -u || true)"

luistert() { printf '%s\n' "$LOOPBACK" | grep -qx "$1"; }

GEZIEN=""
for peer in $(alle_peers); do
  BLOK="$(peer_waarde BLOK "$peer")"
  [ -n "$BLOK" ] || { fout "geen BLOK_$(peer_var "$peer") in peers.env"; continue; }

  AANTAL=0
  for poort in $(peer_poorten "$peer"); do
    AANTAL=$((AANTAL + 1))

    # Hoort de poort in het blok van deze peer? Dat vangt een copy-paste-fout bij een nieuwe peer
    # die de overlays wél consistent maakt maar het blok van een ander overneemt.
    if [ "$poort" -lt "$BLOK" ] || [ "$poort" -ge $((BLOK + 100)) ]; then
      fout "${peer}: poort ${poort} valt buiten zijn blok ${BLOK}-$((BLOK + 99))"
    fi

    if printf '%s\n' "$GEZIEN" | grep -qx "$poort"; then
      fout "poort ${poort} wordt door twee peers geclaimd"
    fi
    GEZIEN="${GEZIEN}
${poort}"

    luistert "$poort" || fout "${peer}: poort ${poort} staat in de overlay maar luistert niet"
  done

  [ "$AANTAL" -gt 0 ] && ok "${peer}: ${AANTAL} poorten uit blok ${BLOK} luisteren"
done

for poort in $INFRA_POORTEN; do
  luistert "$poort" || fout "federatie-infra: poort ${poort} luistert niet"
done
ok "federatie-infra: $(printf '%s' "$INFRA_POORTEN" | wc -w | tr -d ' ') vaste poorten luisteren"

for poort in $STANDALONE_POORTEN; do
  if luistert "$poort"; then
    fout "standalone-poort ${poort} luistert — een service mist zijn federatie-overlay (en botst bij een tweede peer)"
  fi
done
ok "geen standalone-poort blijven hangen"

# Negatieve assert: in een gedeelde netns is een niet-loopback bind de hele machine. Een filter op
# `^127.0.0.1:` zou zo'n bind juist onzichtbaar maken, dus toets op de afwezigheid ervan.
#
# Loopback is breder dan het ene adres dat wij configureren, en dat is legitiem: podman's interne
# resolver luistert op 127.0.0.11, en een dual-stack listener verschijnt als `[::ffff:127.0.0.1]`
# of `[::1]`. Alles daarbuiten — `0.0.0.0`, `*`, `[::]`, een LAN-adres — is wél de hele machine.
BUITEN="$(printf '%s\n' "$LISTENERS" \
  | grep -vE '^(127\.[0-9]+\.[0-9]+\.[0-9]+|\[::1\]|\[::ffff:127\.[0-9]+\.[0-9]+\.[0-9]+\]):' || true)"
if [ -n "$BUITEN" ]; then
  fout "listener(s) buiten loopback: $(printf '%s' "$BUITEN" | tr '\n' ' ')"
else
  ok "niets luistert buiten loopback"
fi

# --- 4. Eén directory ----------------------------------------------------------------------------
echo "== 4. één directory =="
PROJECT="$(compose_project "$GASTHEER")"
RC=0
RIJEN="$(podman exec "${PROJECT}-postgres-1" psql -U postgres -d fsc_directory -tA \
  -c "SELECT id FROM peers.peers WHERE manager_address LIKE '%:443' ORDER BY id" 2>"$ERRLOG")" || RC=$?

if [ "$RC" -ne 0 ]; then
  fout "directory-DB niet bevraagbaar: $(fsc_last_error)"
else
  for peer in $(alle_peers) directory; do
    OIN="$(peer_waarde OIN "$peer")"
    [ -n "$OIN" ] || { fout "geen OIN_$(peer_var "$peer") in peers.env"; continue; }

    if printf '%s\n' "$RIJEN" | grep -qx "$OIN"; then
      ok "${peer} (${OIN}) aangemeld op :443"
    else
      fout "${peer} (${OIN}) ontbreekt in peers.peers, of niet op :443"
    fi
  done

  # Ook te véél rijen is fout: een peer uit een eerdere incarnatie (andere group-CA) blijft achter
  # in een niet-gewist volume en zou anders onzichtbaar meeliften.
  VERWACHT=1
  for _ in $(alle_peers); do VERWACHT=$((VERWACHT + 1)); done
  AANTAL="$(printf '%s\n' "$RIJEN" | grep -c . || true)"

  if [ "$AANTAL" -eq "$VERWACHT" ]; then
    ok "precies ${VERWACHT} peers in peers.peers"
  else
    fout "peers.peers heeft ${AANTAL} rijen op :443, verwacht ${VERWACHT} (stale rij uit een eerdere run? draai 'federatie.sh down')"
  fi
fi

# --- 5. Publiceren + ontdekken -------------------------------------------------------------------
# Bewust via de peer-eigen scripts met de federatie-poorten uit env: dat toetst meteen dat de
# de-hardcoding werkt en dat de standalone-scripts in deze opstelling bruikbaar blijven.
echo "== 5. dienst publiceren + ontdekken (magazijn-a) =="
MGZ_BLOK="$(peer_waarde BLOK magazijn-a)"
[ -n "$MGZ_BLOK" ] || { echo "FAIL: geen BLOK_magazijn_a in peers.env." >&2; exit 1; }

draai() {  # <omschrijving> <commando...> — bewaart de uitvoer en toont 'm alleen bij falen
  local wat="$1"; shift
  local kind; kind=$(mktemp)

  if "$@" >"$kind" 2>&1; then
    ok "$wat"
    rm -f "$kind"
  else
    fout "${wat} — uitvoer:"
    tail -n 20 "$kind" >&2
    rm -f "$kind"
  fi
}

FSC_CONTROLLER="https://controller.magazijn-a.fsc-test.local:$((MGZ_BLOK + 12))" \
FSC_MANAGER="https://manager.magazijn-a.fsc-test.local:$((MGZ_BLOK + 1))" \
FSC_STUB_URL="http://stub-upstream:$((MGZ_BLOK + 50))" \
  draai "berichtenmagazijn gepubliceerd" "${ENVDIR}/magazijn-a/deploy/local/publish-service.sh"

FSC_MANAGER="https://manager.magazijn-a.fsc-test.local:$((MGZ_BLOK + 1))" \
  draai "berichtenmagazijn vindbaar in de catalogus" "${ENVDIR}/magazijn-a/deploy/local/smoke-discover.sh"

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== FEDERATIE-SMOKE GROEN =="
else
  echo "== FEDERATIE-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
