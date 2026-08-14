#!/usr/bin/env bash
# Smoke: bewijst dat de peers écht één federatie vormen en niet twee losse harnessen die toevallig
# tegelijk draaien. Draai na `./federatie.sh up`.
#
#   1. gedeelde group-CA — als échte HTTP-call over wederzijdse mTLS door de router: alleen een
#      client-cert dat de tegenpeer vertrouwt levert een HTTP-status op, en curl verifieert
#      daarbij de hostnaam, dus misroutering valt ook door de mand;
#   2. internal-CA-isolatie — eerst een positieve controle (eigen root valideert eigen leaf), dan
#      de eis dat de root van A de leaf van B verwerpt. Zonder die positieve controle zou een
#      kapotte PKI als "isolatie intact" lezen;
#   3. adresschema — elke bind die de overlays declareren luistert, elke peer blijft binnen zijn
#      eigen /24, er luistert niets onbekends binnen het federatie-prefix, en niets buiten loopback;
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

# ok_tenzij <telstand-voor> <melding>: geeft de OK alleen af als er sindsdien niets gemeld is.
# Zonder deze guard drukt een lus eerst zijn FAILs en dan alsnog een OK over dezelfde eigenschap.
# `return 0` is load-bearing: eindigde deze functie op een `&&`-lijst, dan geeft hij 1 terug zodra
# er iets gemeld is, en `set -e` beëindigt de smoke midden in de asserts — zonder samenvatting en
# zonder dat assert 4 en 5 ooit draaien.
ok_tenzij() {
  if [ "$FOUTEN" -eq "$1" ]; then ok "$2"; fi
  return 0
}

# --- 1. Gedeelde group-CA, bewezen op de verbinding ----------------------------------------------
# Een bestandsvergelijking van root.pem zegt niets over wat de draaiende processen geladen hebben.
# Deze call wél, en in BEIDE richtingen tegelijk: hij gaat door de router naar de externe manager
# van de tegenpeer, met ons group-cert als client-cert.
#
# Waarom een echte HTTP-call en niet `openssl s_client`: die laatste rapporteert alleen ónze
# verificatie van HÚN certificaat. Wat de tegenpeer met ons client-cert doet komt onder TLS 1.3 pas
# ná de handshake-samenvatting, dus een bogus client-cert leverde daar nog steeds "Verify return
# code: 0" op. Een HTTP-status krijg je alleen als de handshake écht rond is:
#   geldig group-cert -> een status (welke maakt niet uit, 404 is prima)
#   geen of vreemd cert -> geen status, curl faalt op TLS
#
# curl verifieert de hostnaam standaard, dus misroutering door de router valt hier ook door de mand.
echo "== 1. gedeelde group-CA (wederzijdse mTLS door de router) =="
for peer in $(fsc_alle_peers); do
  for tegen in $(fsc_alle_peers); do
    [ "$peer" != "$tegen" ] || continue

    ANKER="${ENVDIR}/${peer}/pki/ca/root.pem"
    CERT="${ENVDIR}/${peer}/pki/out/${peer}/manager/cert.pem"
    SLEUTEL="${ENVDIR}/${peer}/pki/out/${peer}/manager/key.pem"
    NAAM="${tegen}.fsc-test.local"

    if [ ! -r "$ANKER" ] || [ ! -r "$CERT" ] || [ ! -r "$SLEUTEL" ]; then
      fout "${peer}: group-materiaal ontbreekt (${ANKER} / ${CERT})"
      continue
    fi

    # `-sS` en niet `-s`: die laatste onderdrukt juist de melding die deze assert nodig heeft om
    # tussen zijn drie kandidaat-oorzaken te kiezen. `--noproxy '*'` omdat alles hier loopback is —
    # met een https_proxy in de omgeving zou een 407 van de proxy als geslaagde handshake lezen.
    STATUS="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 --noproxy '*' \
                --resolve "${NAAM}:443:${ADRES_ROUTER}" \
                --cert "$CERT" --key "$SLEUTEL" --cacert "$ANKER" \
                "https://${NAAM}/" 2>"$ERRLOG" || true)"

    if [ "${STATUS:-000}" != "000" ]; then
      ok "${peer} spreekt wederzijdse mTLS met ${tegen} door de router (HTTP ${STATUS})"
    else
      fout "${peer} krijgt geen TLS-verbinding met ${tegen} — group-CA niet gedeeld, cert niet geaccepteerd, of de router routeert niet: $(fsc_last_error)"
    fi

    # Negatieve controle: zonder client-cert hoort er géén verbinding te zijn. Slaagt die tóch, dan
    # dwingt de tegenpeer mTLS niet af en zegt de positieve assert hierboven niets over
    # wederzijdsheid — dan rust hij alleen op onze eigen verificatie van hún certificaat.
    ZONDER="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 --noproxy '*' \
                --resolve "${NAAM}:443:${ADRES_ROUTER}" --cacert "$ANKER" \
                "https://${NAAM}/" 2>/dev/null || true)"

    if [ "${ZONDER:-000}" = "000" ]; then
      ok "${tegen} weigert een verbinding zónder client-cert (mTLS wordt afgedwongen)"
    else
      fout "${tegen} accepteert een verbinding zónder client-cert (HTTP ${ZONDER}) — mTLS wordt niet afgedwongen"
    fi
  done
done

# --- 2. Internal-CA-isolatie ---------------------------------------------------------------------
# De internal-CA is per peer en tekent alleen verkeer bínnen die peer. `openssl verify` faalt óók
# bij een leeg, afgekapt of verlopen bestand, dus zonder positieve controle vooraf zou een volledig
# kapotte internal-PKI als "isolatie intact" rapporteren.
echo "== 2. internal-CA blijft per peer =="
for peer in $(fsc_alle_peers); do
  ANKER="${ENVDIR}/${peer}/pki/internal/${peer}/ca/root.pem"
  LEAF="${ENVDIR}/${peer}/pki/internal/${peer}/manager/cert.pem"

  if openssl verify -CAfile "$ANKER" "$LEAF" >/dev/null 2>"$ERRLOG"; then
    ok "${peer}'s internal-root valideert zijn eigen leaf (positieve controle)"
  else
    fout "${peer}'s internal-root valideert zijn eigen leaf NIET: $(fsc_last_error) — de PKI is stuk, de isolatie-uitkomst hieronder zegt dan niets"
    continue
  fi

  for tegen in $(fsc_alle_peers); do
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

# --- 3. Adresschema ------------------------------------------------------------------------------
# Elke component heeft een eigen adres binnen 127.20.0.0/16 en houdt zijn standaardpoort. De
# verwachte `adres:poort`-paren worden UIT de overlays gelezen: een lijst die daarnaast leeft, drift
# eruit en dekt dan minder dan hij belooft.
#
# Dat de federatie een eigen adresruimte heeft, maakt deze asserts scherper dan een poortcontrole:
# alles binnen het prefix is per definitie van ons, dus een andere stack in dezelfde netns (de
# demo-stack uit compose.yaml, een standalone peer-harness op 127.0.0.1) kan de meting niet
# vertroebelen — en een service die zijn federatie-overlay mist, bindt buiten het prefix en valt
# hieronder juist op.
echo "== 3. adresschema =="
FED_RE="^$(printf '%s' "$FED_PREFIX" | sed 's/\./\\./g')\."
binds_uit()  { grep -oE "${FED_RE#^}[0-9]+\.[0-9]+:[0-9]+" "$1" | sort -u; }
peer_binds() { binds_uit "${HERE}/compose/$1.yaml"; }

LISTENERS="$(ss -ltnH 2>"$ERRLOG" | awk '{print $4}' | sort -u || true)"
fsc_warn_errlog "ss faalde"
[ -n "$LISTENERS" ] || fout "geen enkele listener gevonden — draait de federatie, en deelt deze shell de netns?"

# Eén predicaat voor "is dit loopback", overal gebruikt. Loopback is breder dan de adressen die wij
# configureren: podman's resolver zit op 127.0.0.11, en een dual-stack listener verschijnt als
# `[::ffff:127.0.0.1]` of `[::1]`. Een scope-id (`127.0.0.53%lo`) hoort er ook bij — systemd-resolved
# op een doorsnee Ubuntu levert die vorm.
LOOPBACK_RE='^(127\.[0-9]+\.[0-9]+\.[0-9]+|\[::1\]|\[::ffff:127\.[0-9]+\.[0-9]+\.[0-9]+\])(%[^:]*)?:'
FED_LISTENERS="$(printf '%s\n' "$LISTENERS" | grep -E "$FED_RE" | sort -u || true)"
luistert() { printf '%s\n' "$FED_LISTENERS" | grep -qx "$1"; }

# INFRA_RE: de gastheer draait router, postgres, directory en directory-UI namens iedereen op
# <prefix>.0.x. Die adressen horen bij geen enkele peer-/24 en zijn daarom uitgezonderd van de
# /24-controle — maar alleen in de overlay van de GASTHEER. Declareert een gast ze, dan draait die
# een tweede postgres of router, en dat is precies wat deze opstelling moet uitsluiten.
INFRA_RE="${FED_RE}0\."

VOOR=$FOUTEN
GEZIEN=""
VERWACHT_BINDS=""
for peer in $(fsc_alle_peers); do
  NET="$(fsc_peer_waarde NET "$peer")"
  [ -n "$NET" ] || { fout "geen NET_$(fsc_peer_var "$peer") in peers.env"; continue; }

  # Nul treffers is niet "schoon" maar "de overlay ontbreekt of declareert niets" — anders wordt
  # een peer stilzwijgend niet gemeten. `grep` geeft onder pipefail non-zero bij nul treffers, dus
  # beide gevallen komen hier samen uit.
  if ! BINDS="$(peer_binds "$peer")" || [ -z "$BINDS" ]; then
    fout "${peer}: geen ${FED_PREFIX}-adressen leesbaar uit compose/${peer}.yaml"
    continue
  fi

  AANTAL=0
  for bind in $BINDS; do
    VERWACHT_BINDS="${VERWACHT_BINDS} ${bind}"
    ADRES="${bind%:*}"

    if printf '%s' "$ADRES" | grep -qE "$INFRA_RE"; then
      if [ "$peer" != "$GASTHEER" ]; then
        fout "${peer} is gast maar declareert het infra-adres ${ADRES} — draait die een eigen postgres of router?"
      fi

      luistert "$bind" || fout "federatie-infra: ${bind} staat in de overlay maar luistert niet"
      continue
    fi

    AANTAL=$((AANTAL + 1))

    case "$ADRES" in
      "${NET}."*) ;;
      *) fout "${peer}: ${bind} valt buiten zijn eigen ${NET}.0/24" ;;
    esac

    # Twee peers op hetzelfde adres botsen alsnog op hun standaardpoort — het enige wat de
    # adresscheiding moet uitsluiten. Eén peer die hetzelfde adres meermaals claimt is juist normaal:
    # een component heeft meerdere listeners (manager: extern, intern, intern-unauth, monitoring).
    # Vandaar `$2 != p` in de selectie, en niet een vergelijking achteraf op de hele uitkomst.
    ANDERE="$(printf '%s\n' "$GEZIEN" | awk -v a="$ADRES" -v p="$peer" '$1 == a && $2 != p {print $2}' | sort -u | head -n 1)"
    if [ -n "$ANDERE" ]; then
      fout "adres ${ADRES} wordt zowel door ${ANDERE} als door ${peer} geclaimd"
    fi
    GEZIEN="${GEZIEN}
${ADRES} ${peer}"

    luistert "$bind" || fout "${peer}: ${bind} staat in de overlay maar luistert niet"
  done

  [ "$AANTAL" -eq 0 ] || ok "${peer}: ${AANTAL} binds in ${NET}.0/24 gecontroleerd"
done

# De router opent zijn poort uit de gemounte haproxy-config en postgres drukt zijn bind uit als
# `listen_addresses=` zonder poort; beide ontsnappen dus aan de extractie uit de overlays.
VOOR_INFRA=$FOUTEN
# Alleen de `bind`-regels: `binds_uit` grept élk adres in het bestand, en dat zijn ook de
# `server s1 <adres>`-backends. Die horen bij de peers, niet bij de router — zonder dit filter zou
# een liggende inway hier als "de router luistert niet" gemeld worden, en dubbel geteld bovenop de
# terechte melding uit de peer-lus.
if ! ROUTER_BINDS="$(grep -E '^[[:space:]]*bind[[:space:]]' "${HERE}/haproxy.federatie.cfg" \
                       | grep -oE "${FED_RE#^}[0-9]+\.[0-9]+:[0-9]+" | sort -u)" \
   || [ -z "$ROUTER_BINDS" ]; then
  fout "geen ${FED_PREFIX}-bind leesbaar uit haproxy.federatie.cfg — deze assert meet niets"
else
  for bind in $ROUTER_BINDS; do
    VERWACHT_BINDS="${VERWACHT_BINDS} ${bind}"
    luistert "$bind" || fout "federatie-infra: de router luistert niet op ${bind}"
  done
fi

VERWACHT_BINDS="${VERWACHT_BINDS} ${ADRES_POSTGRES}:5432"
luistert "${ADRES_POSTGRES}:5432" || fout "federatie-infra: postgres luistert niet op ${ADRES_POSTGRES}:5432"
ok_tenzij "$VOOR_INFRA" "federatie-infra: router en postgres luisteren op hun eigen adres"

# Andersom óók: een luisteraar binnen ons prefix die in geen enkele overlay staat. Dat is een
# component uit een eerdere incarnatie of een handmatig gestarte container — beide maken de
# metingen hierboven onbetrouwbaar zonder zelf een assert te breken.
VOOR_ONBEKEND=$FOUTEN
for adres in $FED_LISTENERS; do
  case " $VERWACHT_BINDS " in
    *" $adres "*) ;;
    *) fout "${adres} luistert binnen ${FED_PREFIX}. maar staat in geen enkele overlay" ;;
  esac
done
ok_tenzij "$VOOR_ONBEKEND" "geen onbekende luisteraar binnen ${FED_PREFIX}."

# Negatieve assert: in een gedeelde netns is een niet-loopback bind de hele machine. Beperkt tot de
# poorten die de federatie claimt — `ss` ziet ook de dev-server en sshd van de gebruiker, en daar
# rood op gaan zou de assert op termijn versoepeld krijgen. TCP-only; de componenten zijn HTTP/TLS.
VOOR_BUITEN=$FOUTEN
# `LISTENERS` leeg is hierboven al gemeld; hier alleen de OK onderdrukken, niet dubbel tellen.
# Regelgewijs lezen en niet `for adres in $BUITEN`: dat splitst op spaties én globt, en `ss` schrijft
# een IPv6-wildcard als `*:8080` — bash vervangt dat dan door bestandsnamen uit de huidige map, en
# juist deze negatieve assert zou daardoor stil verzwakken.
BUITEN="$(printf '%s\n' "$LISTENERS" | grep -vE "$LOOPBACK_RE" || true)"
while IFS= read -r adres; do
  [ -n "$adres" ] || continue
  poort="${adres##*:}"

  for bind in $VERWACHT_BINDS; do
    if [ "${bind##*:}" = "$poort" ]; then
      fout "federatie-poort ${poort} luistert buiten loopback: ${adres}"
      break
    fi
  done
done <<EOF
$BUITEN
EOF

if [ -n "$LISTENERS" ]; then
  ok_tenzij "$VOOR_BUITEN" "geen federatie-poort luistert buiten loopback (TCP)"
fi
ok_tenzij "$VOOR" "adresschema consistent met de overlays"

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
    for peer in $(fsc_alle_peers) directory; do
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
    for _ in $(fsc_alle_peers); do VERWACHT=$((VERWACHT + 1)); done
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
# Bewust via de peer-eigen scripts, met de adressen uit env: dat toetst meteen dat die scripts in
# deze opstelling bruikbaar blijven.
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
PROV_OIN="$(fsc_peer_waarde OIN "$PROVIDER")"
CONS_OIN="$(fsc_peer_waarde OIN "$CONSUMER")"

if [ -z "$PROV_OIN" ] || [ -z "$CONS_OIN" ]; then
  fout "OIN ontbreekt voor ${PROVIDER}/${CONSUMER} in peers.env"
elif [ "$PROV_OIN" = "$CONS_OIN" ]; then
  # Anders is dit weer de zelf-bevraging die deze assert juist verving: `smoke-discover.sh` valt
  # zónder FSC_PROVIDER_OIN terug op de eigen OIN, en dan bewijst een groene uitkomst niets over
  # de federatie.
  fout "consumer en provider hebben dezelfde OIN (${PROV_OIN}) — dit toetst geen cross-peer discovery"
else
  # De poorten zijn de standaardpoorten uit de basis-compose — met een adres per component hoeft er
  # niets meer te schuiven. Toch expliciet meegegeven en niet op de defaults van de scripts
  # geleund: dan toetst dit meteen dat de de-hardcoding werkt en dat een peer die zijn adressen
  # ooit anders belegt, deze scripts nog steeds kan gebruiken.
  FSC_CONTROLLER="https://controller.${PROVIDER}.fsc-test.local:9444" \
  FSC_MANAGER="https://manager.${PROVIDER}.fsc-test.local:9443" \
  FSC_STUB_URL="http://stub-upstream:8080" \
    draai "${PROVIDER} publiceert berichtenmagazijn" \
      "${ENVDIR}/${PROVIDER}/deploy/local/publish-service.sh"

  FSC_MANAGER="https://manager.${CONSUMER}.fsc-test.local:9443" \
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
