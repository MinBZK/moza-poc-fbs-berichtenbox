#!/usr/bin/env bash
# Smoke: bewijst dat de peers écht één federatie vormen en niet twee losse harnessen die toevallig
# tegelijk draaien. Draai na `./federatie.sh up`.
#
#   1. gedeelde group-CA — als wederzijdse mTLS-handshake dwars door de router, mét
#      hostnaam-verificatie, zodat ook vaststaat dat de júiste peer antwoordde;
#   2. internal-CA-isolatie — eerst een positieve controle (eigen root valideert eigen leaf), dan
#      de eis dat de root van A de leaf van B verwerpt. Zonder die positieve controle zou een
#      kapotte PKI als "isolatie intact" lezen;
#   3. poortschema — elke poort die de overlays declareren luistert, geen standalone-poort is
#      blijven hangen, en niets luistert buiten loopback;
#   4. één directory — alle peers plus de directory, op :443, en géén rijen te veel;
#   5. cross-peer discovery — de consumer ziet de dienst van de provider in de gedeelde catalogus.
#      Dit is de functionele federatie-eigenschap; assert 4 toont alleen de DB-kant.
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
KIND=$(mktemp)
trap 'rm -f "$ERRLOG" "$KIND"' EXIT

command -v ss >/dev/null 2>&1 || { echo "FAIL: 'ss' (iproute2) is vereist." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "FAIL: 'openssl' is vereist." >&2; exit 1; }

FOUTEN=0
fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

alle_peers() { printf '%s %s' "$GASTHEER" "$GASTEN"; }

# ok_tenzij <telstand-voor> <melding>: geeft de OK alleen af als er sindsdien niets gemeld is.
# Zonder deze guard drukt een lus eerst zijn FAILs en dan alsnog een OK over dezelfde eigenschap.
ok_tenzij() { [ "$FOUTEN" -eq "$1" ] && ok "$2"; }

# --- 1. Gedeelde group-CA, bewezen op de verbinding ----------------------------------------------
# Een bestandsvergelijking van root.pem zegt niets over wat de draaiende processen geladen hebben.
# Deze handshake wél: hij gaat door de router naar de tegenpeer, met ONS client-cert. Accepteert
# die het, dan draagt zijn proces hetzelfde anker. `-verify_hostname` erbij, want beide peer-certs
# ketenen naar dezelfde root — zonder die vlag blijft de assert groen als de router misroutet.
echo "== 1. gedeelde group-CA (wederzijdse mTLS door de router) =="
for peer in $(alle_peers); do
  for tegen in $(alle_peers); do
    [ "$peer" != "$tegen" ] || continue

    ANKER="${ENVDIR}/${peer}/pki/ca/root.pem"
    CERT="${ENVDIR}/${peer}/pki/out/${peer}/manager/cert.pem"
    SLEUTEL="${ENVDIR}/${peer}/pki/out/${peer}/manager/key.pem"
    NAAM="${tegen}.fsc-test.local"

    if [ ! -r "$ANKER" ] || [ ! -r "$CERT" ] || [ ! -r "$SLEUTEL" ]; then
      fout "${peer}: group-materiaal ontbreekt (${ANKER} / ${CERT})"
      continue
    fi

    UIT="$(echo | openssl s_client -connect 127.0.0.1:443 -servername "$NAAM" \
             -verify_hostname "$NAAM" -CAfile "$ANKER" \
             -cert "$CERT" -key "$SLEUTEL" 2>&1 || true)"

    if printf '%s' "$UIT" | grep -q 'Verify return code: 0'; then
      ok "${peer} spreekt mTLS met ${tegen} door de router"
    else
      fout "${peer} krijgt ${tegen} niet geverifieerd — group-CA niet gedeeld, of de router routeert niet: $(printf '%s' "$UIT" | grep -m1 'Verify return code' || echo '<geen handshake>')"
    fi
  done
done

# --- 2. Internal-CA-isolatie ---------------------------------------------------------------------
# De internal-CA is per peer en tekent alleen verkeer bínnen die peer. `openssl verify` faalt óók
# bij een leeg, afgekapt of verlopen bestand, dus zonder positieve controle vooraf zou een volledig
# kapotte internal-PKI als "isolatie intact" rapporteren.
echo "== 2. internal-CA blijft per peer =="
for peer in $(alle_peers); do
  ANKER="${ENVDIR}/${peer}/pki/internal/${peer}/ca/root.pem"
  LEAF="${ENVDIR}/${peer}/pki/internal/${peer}/manager/cert.pem"

  if openssl verify -CAfile "$ANKER" "$LEAF" >/dev/null 2>"$ERRLOG"; then
    ok "${peer}'s internal-root valideert zijn eigen leaf (positieve controle)"
  else
    fout "${peer}'s internal-root valideert zijn eigen leaf NIET: $(fsc_last_error) — de PKI is stuk, de isolatie-uitkomst hieronder zegt dan niets"
    continue
  fi

  for tegen in $(alle_peers); do
    [ "$peer" != "$tegen" ] || continue
    VREEMD="${ENVDIR}/${tegen}/pki/internal/${tegen}/manager/cert.pem"

    # `openssl verify` schrijft de rede op een eigen regel ("error 20 at 0 depth lookup: …") en
    # daarná pas de samenvatting, dus over meerdere regels kijken. De eis dat de afwijzing een
    # VERTROUWENS-fout is, sluit uit dat een leeg of verlopen certificaat als "isolatie intact"
    # leest — dat zou de hele assert vals-groen maken op een kapotte PKI.
    if openssl verify -CAfile "$ANKER" "$VREEMD" >/dev/null 2>"$ERRLOG"; then
      fout "${peer}'s internal-root accepteert de internal-leaf van ${tegen} — de isolatie is weg"
    elif fsc_last_error 5 | grep -qE 'error (2|18|19|20|21) at|unable to get local issuer|self.signed|certificate signature failure'; then
      ok "${peer}'s internal-root verwerpt ${tegen} op vertrouwen (isolatie intact)"
    else
      fout "${peer}'s internal-root verwerpt ${tegen}, maar niet op vertrouwen: $(fsc_last_error 3 | tr '\n' ' ')"
    fi
  done
done

# --- 3. Poortschema ------------------------------------------------------------------------------
# Verwachte én verboden poorten worden UIT de overlays gelezen: een lijst die daarnaast leeft,
# drift eruit en dekt dan minder dan hij belooft.
echo "== 3. poortschema =="
poorten_uit() { grep -oE '127\.0\.0\.1:[0-9]+' "$1" | cut -d: -f2 | sort -un; }
peer_poorten()       { poorten_uit "${HERE}/compose/$1.yaml"; }
standalone_poorten() { poorten_uit "${ENVDIR}/$1/deploy/local/docker-compose.podman-hostnet.yaml"; }

LISTENERS="$(ss -ltnH 2>"$ERRLOG" | awk '{print $4}' | sort -u || true)"
fsc_warn_errlog "ss faalde"
[ -n "$LISTENERS" ] || fout "geen enkele listener gevonden — draait de federatie, en deelt deze shell de netns?"

# Eén predicaat voor "is dit loopback", overal gebruikt. Loopback is breder dan het ene adres dat
# wij configureren: podman's resolver zit op 127.0.0.11, en een dual-stack listener verschijnt als
# `[::ffff:127.0.0.1]` of `[::1]`. Een scope-id (`127.0.0.53%lo`) hoort er ook bij — systemd-resolved
# op een doorsnee Ubuntu levert die vorm.
LOOPBACK_RE='^(127\.[0-9]+\.[0-9]+\.[0-9]+|\[::1\]|\[::ffff:127\.[0-9]+\.[0-9]+\.[0-9]+\])(%[^:]*)?:'
LOOPBACK="$(printf '%s\n' "$LISTENERS" | grep -E "$LOOPBACK_RE" | sed 's/.*://' | sort -u || true)"
luistert() { printf '%s\n' "$LOOPBACK" | grep -qx "$1"; }

VOOR=$FOUTEN
GEZIEN=""
FED_POORTEN=""
for peer in $(alle_peers); do
  BLOK="$(fsc_peer_waarde BLOK "$peer")"
  [ -n "$BLOK" ] || { fout "geen BLOK_$(fsc_peer_var "$peer") in peers.env"; continue; }

  if ! POORTEN="$(peer_poorten "$peer")"; then
    fout "${peer}: poortlijst niet leesbaar uit compose/${peer}.yaml"
    continue
  fi
  # Nul poorten is niet "schoon" maar "de overlay ontbreekt of declareert niets" — anders wordt
  # een peer stilzwijgend niet gemeten.
  [ -n "$POORTEN" ] || { fout "${peer}: compose/${peer}.yaml declareert geen enkele 127.0.0.1-poort"; continue; }

  AANTAL=0
  for poort in $POORTEN; do
    AANTAL=$((AANTAL + 1))
    FED_POORTEN="${FED_POORTEN} ${poort}"

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

  [ "$AANTAL" -eq 0 ] || ok "${peer}: ${AANTAL} poorten uit blok ${BLOK} gecontroleerd"
done

VOOR_INFRA=$FOUTEN
for poort in $INFRA_POORTEN; do
  luistert "$poort" || fout "federatie-infra: poort ${poort} luistert niet"
done
ok_tenzij "$VOOR_INFRA" "federatie-infra: alle vaste poorten luisteren"

# Verboden poorten: wat de hostnet-overlay declareert maar de federatie-overlay overschrijft. Blijft
# zo'n poort luisteren, dan mist een service zijn federatie-overlay — en die poort is voor élke peer
# identiek, dus de tweede peer botst erop. Afgeleid in plaats van met de hand, want de handmatige
# variant miste 8080 (stub-upstream), precies het geval waar deze assert voor bestaat.
VOOR_STANDALONE=$FOUTEN
for peer in $(alle_peers); do
  for poort in $(standalone_poorten "$peer"); do
    case " $INFRA_POORTEN " in *" $poort "*) continue ;; esac
    case " $FED_POORTEN "   in *" $poort "*) continue ;; esac

    if luistert "$poort"; then
      fout "standalone-poort ${poort} (uit ${peer}'s hostnet-overlay) luistert — een service mist zijn federatie-overlay"
    fi
  done
done
ok_tenzij "$VOOR_STANDALONE" "geen standalone-poort blijven hangen"

# Negatieve assert: in een gedeelde netns is een niet-loopback bind de hele machine. Beperkt tot de
# poorten die de federatie claimt — `ss` ziet ook de dev-server en sshd van de gebruiker, en daar
# rood op gaan zou de assert op termijn versoepeld krijgen. TCP-only; de componenten zijn HTTP/TLS.
VOOR_BUITEN=$FOUTEN
BUITEN="$(printf '%s\n' "$LISTENERS" | grep -vE "$LOOPBACK_RE" || true)"
for adres in $BUITEN; do
  poort="${adres##*:}"
  case " $INFRA_POORTEN $FED_POORTEN " in
    *" $poort "*) fout "federatie-poort ${poort} luistert buiten loopback: ${adres}" ;;
  esac
done
ok_tenzij "$VOOR_BUITEN" "geen federatie-poort luistert buiten loopback (TCP)"
ok_tenzij "$VOOR" "poortschema consistent met de overlays"

# --- 4. Eén directory ----------------------------------------------------------------------------
echo "== 4. één directory =="
if ! PROJECT="$(fsc_compose_project "${ENVDIR}/${GASTHEER}/deploy/local/docker-compose.yaml")"; then
  fout "projectnaam van de gastheer niet af te leiden"
else
  RC=0
  RIJEN="$(podman exec "${PROJECT}-postgres-1" psql -U postgres -d fsc_directory -tA \
    -c "SELECT id FROM peers.peers WHERE manager_address LIKE '%:443' ORDER BY id" 2>"$ERRLOG")" || RC=$?

  if [ "$RC" -ne 0 ]; then
    fout "directory-DB niet bevraagbaar: $(fsc_last_error)"
  else
    for peer in $(alle_peers) directory; do
      OIN="$(fsc_peer_waarde OIN "$peer")"
      [ -n "$OIN" ] || { fout "geen OIN_$(fsc_peer_var "$peer") in peers.env"; continue; }

      if printf '%s\n' "$RIJEN" | grep -qx "$OIN"; then
        ok "${peer} (${OIN}) aangemeld op :443"
      else
        fout "${peer} (${OIN}) ontbreekt in peers.peers, of niet op :443"
      fi
    done

    # Ook te véél rijen is fout: een peer uit een eerdere incarnatie (andere group-CA) blijft
    # achter in een niet-gewist volume en zou anders onzichtbaar meeliften.
    VERWACHT=1
    for _ in $(alle_peers); do VERWACHT=$((VERWACHT + 1)); done
    AANTAL="$(printf '%s\n' "$RIJEN" | grep -c . || true)"

    if [ "$AANTAL" -eq "$VERWACHT" ]; then
      ok "precies ${VERWACHT} peers in peers.peers"
    else
      fout "peers.peers heeft ${AANTAL} rijen op :443, verwacht ${VERWACHT} (stale rij uit een eerdere run? draai 'federatie.sh down')"
    fi
  fi
fi

# --- 5. Cross-peer discovery ---------------------------------------------------------------------
# De kerneigenschap: peer A ziet de dienst van peer B. Dat bewijst in één stap dat B's publicatie
# over de gedeelde group-CA bij de gedeelde directory landde, dat A diezelfde directory over
# dezelfde CA bereikt, en dat het één directory is en niet twee.
#
# Bewust via de peer-eigen scripts met de federatie-poorten uit env: dat toetst meteen dat de
# de-hardcoding werkt en dat die scripts in deze opstelling bruikbaar blijven.
echo "== 5. publiceren + cross-peer ontdekken =="
draai() {  # <omschrijving> <commando...> — toont de uitvoer alleen bij falen
  local wat="$1"; shift
  if "$@" >"$KIND" 2>&1; then
    ok "$wat"
  else
    fout "${wat} — uitvoer:"
    tail -n 20 "$KIND" >&2
  fi
}

PROVIDER=magazijn-a
CONSUMER="$GASTHEER"
PROV_BLOK="$(fsc_peer_waarde BLOK "$PROVIDER")"
CONS_BLOK="$(fsc_peer_waarde BLOK "$CONSUMER")"
PROV_OIN="$(fsc_peer_waarde OIN "$PROVIDER")"

if [ -z "$PROV_BLOK" ] || [ -z "$CONS_BLOK" ] || [ -z "$PROV_OIN" ]; then
  fout "blok of OIN ontbreekt voor ${PROVIDER}/${CONSUMER} in peers.env"
else
  FSC_CONTROLLER="https://controller.${PROVIDER}.fsc-test.local:$((PROV_BLOK + 12))" \
  FSC_MANAGER="https://manager.${PROVIDER}.fsc-test.local:$((PROV_BLOK + 1))" \
  FSC_STUB_URL="http://stub-upstream:$((PROV_BLOK + 50))" \
    draai "${PROVIDER} publiceert berichtenmagazijn" \
      "${ENVDIR}/${PROVIDER}/deploy/local/publish-service.sh"

  FSC_MANAGER="https://manager.${CONSUMER}.fsc-test.local:$((CONS_BLOK + 1))" \
  FSC_PROVIDER_OIN="$PROV_OIN" \
  FSC_SERVICE_NAME=berichtenmagazijn \
    draai "${CONSUMER} vindt de dienst van ${PROVIDER} in de gedeelde catalogus" \
      "${ENVDIR}/${CONSUMER}/deploy/local/smoke-discover.sh"
fi

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== FEDERATIE-SMOKE GROEN =="
else
  echo "== FEDERATIE-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
