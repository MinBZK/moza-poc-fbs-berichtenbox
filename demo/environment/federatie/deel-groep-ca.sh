#!/usr/bin/env bash
# Geeft alle peers in de federatie dezelfde group-CA.
#
# Elke peer-harness heeft zijn eigen `pki/init-ca.sh` en dus zijn eigen group-root. Standalone is
# dat precies goed, maar in één federatie moet het externe (:443) mTLS-verkeer van álle peers naar
# één anker ketenen — anders vertrouwt de directory de peers niet en vice versa. Dit script
# kopieert de group-CA van de bron-peer naar de doel-peers en geeft daar de group-certs opnieuw
# uit. Dezelfde ingreep die `<peer>/deploy/zad/README.md` voorschrijft om op de testfederatie van
# `moza-fsc-testnet` aan te sluiten.
#
# De INTERNAL-CA blijft per peer apart en wordt hier NIET aangeraakt: die tekent alleen verkeer
# binnen één peer. Vandaar `rm -rf out/` + `issue.sh` zónder `-f` — dat hergeeft precies de zes
# group-leaves uit, terwijl `issue.sh -f` óók de internal-root en alle internal-leaves zou roteren
# (acht RSA-4096-sleutels voor niets, en het zou draaiende containers hun internal-anker afnemen).
#
# DESTRUCTIEF op de doel-peers: hun eigen group-root wordt overschreven en hun group-certs opnieuw
# uitgegeven. Standalone draaien blijft daarna werken (de keten is intern consistent), maar certs
# die elders op de óúde root zijn uitgedeeld gelden niet meer. Een AFGEBROKEN run is erger dan een
# voltooide: de nieuwe root staat er dan al terwijl de leaves nog van de oude zijn. Het script
# detecteert dat bij de volgende run en maakt het af; herstellen naar standalone kan altijd met
# `(cd <peer>/pki && ./init-ca.sh && ./issue.sh -f)`.
#
# Gebruik `--check` om te zien wat er zou gebeuren.
#
# Usage:
#   ./deel-groep-ca.sh [--check] [bron-peer] [doel-peer...]
#     bron-peer   default: de GASTHEER uit peers.env   (levert de group-CA)
#     doel-peer   default: de GASTEN uit peers.env     (krijgen hem, en geven opnieuw uit)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

# shellcheck source=../lib/fsc-harness.sh
. "${ENVDIR}/lib/fsc-harness.sh"
# shellcheck source=peers.env
. "${HERE}/peers.env"

fsc_errlog_init

# --- Argumenten ---------------------------------------------------------------------------------
# `--check` op élke positie herkennen, niet alleen als $1: de natuurlijke vorm
# `./deel-groep-ca.sh logius magazijn-a --check` zou anders `--check` als doel-peer lezen en de
# destructieve run gewoon uitvoeren voordat hij erover klaagt.
CHECK=0
ARGS=""
for a in "$@"; do
  case "$a" in
    --check) CHECK=1 ;;
    -*)      echo "FAIL: onbekende optie '$a'." >&2; exit 2 ;;
    *)       ARGS="${ARGS} ${a}" ;;
  esac
done
# `set -f` eromheen: zonder globbing-uit zou een peernaam met `*` tegen de cwd expanderen en zo
# langs de validatie hieronder glippen.
set -f
# shellcheck disable=SC2086  # bewuste word splitting: ARGS is een spatie-gescheiden peer-lijst.
set -- ${ARGS}
set +f

BRON="${1:-$GASTHEER}"
shift || true
DOELEN="$*"
[ -n "$DOELEN" ] || DOELEN="$GASTEN"

# Peernaam gaat ongevalideerd de padopbouw in; een slash zou de CA-sleutel buiten
# `demo/environment/<peer>/pki/` schrijven, waar de `.gitignore`-glob hem niet meer dekt.
for p in $BRON $DOELEN; do
  case "$p" in
    */*|.|..|"") echo "FAIL: ongeldige peernaam '${p}'." >&2; exit 2 ;;
  esac
done

BRON_CA="${ENVDIR}/${BRON}/pki/ca"
[ -r "${BRON_CA}/root.pem" ] || {
  echo "FAIL: geen group-CA bij de bron-peer '${BRON}' (${BRON_CA}/root.pem)." >&2
  echo "  Draai eerst ${BRON}/pki/init-ca.sh." >&2
  exit 1
}

# De drie bestanden die de doel-peer nodig heeft. `root-key.pem` zit er bewust NIET bij: alleen
# `init-ca.sh` gebruikt die, en dat draait hier niet — `issue.sh` en `gen-crl.sh` tekenen met de
# intermediate. Weglaten scheelt geen schijfruimte maar wel blast radius: geen enkele doel-peer
# kan de federatie-root namaken. De CRL hoort er evenmin bij; die wordt per peer opnieuw
# gegenereerd uit de zojuist gedeelde intermediate.
CA_FILES="root.pem intermediate.pem intermediate-key.pem"
for f in $CA_FILES; do
  [ -r "${BRON_CA}/${f}" ] || { echo "FAIL: ${BRON_CA}/${f} ontbreekt of is onleesbaar." >&2; exit 1; }
done

# --- Helpers ------------------------------------------------------------------------------------
# Onafhankelijk van shell-opties: een `cmd | cut || echo -` levert zónder `pipefail` altijd cuts
# status (0), waardoor een ontbrekend bestand een lege string geeft in plaats van "-". Twee peers
# die beide leeg opleveren zouden dan als "gelijk" gelden.
fingerprint() {
  local uit
  uit="$(openssl x509 -in "$1" -noout -fingerprint -sha256 2>"$ERRLOG")" || { echo -; return; }
  [ -n "$uit" ] || { echo -; return; }
  printf '%s' "$uit" | cut -d= -f2
}

# Bewijst dat de peer de gedeelde CA écht draagt: elke group-leaf moet naar de gedeelde root
# ketenen. Alleen `root.pem` vergelijken is niet genoeg — de vier CA-bestanden worden in een
# niet-atomaire lus gekopieerd en de her-uitgifte komt daarná, dus een afgebroken run laat een
# peer achter met de nieuwe root en leaves van de oude. `openssl verify` kost milliseconden.
leaves_ketenen() {  # $1 = pki-map van de doel-peer
  local pki="$1" leaf gevonden=0 verwacht=0 csr
  for leaf in "${pki}"/out/*/*/cert.pem; do
    [ -r "$leaf" ] || continue
    gevonden=$((gevonden + 1))
    openssl verify -CAfile "${pki}/ca/root.pem" -untrusted "${pki}/ca/intermediate.pem" \
      "$leaf" >/dev/null 2>&1 || return 1
  done

  # Het aantal endpoints staat in pki/peers/<peer>/<endpoint>/csr.json. Alleen tellen wat er ís
  # zou een afgebroken her-uitgifte (twee van de zes leaves geschreven, allebei correct) als
  # "al gedaan" laten lezen — precies de halve staat die dit script zegt te detecteren.
  for csr in "${pki}"/peers/*/*/csr.json; do
    [ -r "$csr" ] && verwacht=$((verwacht + 1))
  done

  [ "$verwacht" -gt 0 ] || return 1
  [ "$gevonden" -eq "$verwacht" ] || return 1
  return 0
}

BRON_FP="$(fingerprint "${BRON_CA}/root.pem")"
[ "$BRON_FP" != "-" ] || {
  echo "FAIL: ${BRON_CA}/root.pem is geen leesbaar certificaat: $(fsc_last_error)" >&2
  exit 1
}
echo "group-CA van '${BRON}': ${BRON_FP}"

GEWIJZIGD=0
BEKEKEN=0
for doel in $DOELEN; do
  DOEL_PKI="${ENVDIR}/${doel}/pki"
  [ -d "$DOEL_PKI" ] || { echo "FAIL: geen pki/-map voor peer '${doel}' (${DOEL_PKI})." >&2; exit 1; }
  [ "$doel" != "$BRON" ] || { echo "  ${doel}: is de bron zelf, overslaan."; continue; }
  BEKEKEN=$((BEKEKEN + 1))

  DOEL_FP="$(fingerprint "${DOEL_PKI}/ca/root.pem")"
  if [ "$DOEL_FP" = "$BRON_FP" ] && leaves_ketenen "$DOEL_PKI"; then
    echo "  ${doel}: draagt de group-CA al en zijn leaves ketenen ernaar (idempotent, overslaan)."
    continue
  fi

  if [ "$DOEL_FP" = "$BRON_FP" ]; then
    echo "  ${doel}: root is al gedeeld maar de leaves ketenen er niet naar — afgebroken eerdere run, afmaken..."
  elif [ "$CHECK" -eq 1 ]; then
    echo "  ${doel}: ZOU de group-CA overschrijven (nu: ${DOEL_FP}) en de group-certs opnieuw uitgeven."
    continue
  else
    echo "  ${doel}: group-CA overschrijven (was ${DOEL_FP})..."
  fi

  [ "$CHECK" -eq 0 ] || { echo "  ${doel}: ZOU de group-certs opnieuw uitgeven."; continue; }

  # `install -m` en niet `cp`: die laatste érft de mode (van de bron bij een nieuw bestand, van het
  # doel bij een bestaand). Bestaat het doelbestand al met een ruimere mode, dan zou daar
  # stilzwijgend een CA-privésleutel in belanden met díé mode.
  # Mode apart zetten: met `-p` geldt `-m` alleen voor de diepste map, en die bestaat mogelijk al.
  mkdir -p "${DOEL_PKI}/ca"
  chmod 700 "${DOEL_PKI}/ca"
  for f in $CA_FILES; do
    case "$f" in
      *-key.pem) m=600 ;;
      *)         m=644 ;;
    esac

    install -m "$m" "${BRON_CA}/${f}" "${DOEL_PKI}/ca/${f}"
  done

  # Een group-`root-key.pem` op een doel-peer heeft geen enkele functie: `issue.sh` en `gen-crl.sh`
  # tekenen met de intermediate, `verify.sh` leest alleen het root-CERT, en de internal-CA heeft
  # zijn eigen sleutel elders. Wat er wél ligt is de sleutel waarmee de peer de federatie-root kan
  # namaken — of, na deze swap, een sleutel bij een root die niemand meer gebruikt. Weg ermee.
  rm -f "${DOEL_PKI}/ca/root-key.pem"

  # cfssl logt elke uitgifte op stderr; dat is voortgang, geen fout. Wegschrijven en alleen tonen
  # als de stap faalt — anders verdrinkt het resultaat in tientallen INFO-regels.
  LOG=$(mktemp)
  echo "  ${doel}: group-certs opnieuw uitgeven onder de gedeelde CA..."
  if ! ( cd "$DOEL_PKI" && rm -rf out && ./issue.sh && ./gen-crl.sh ) >"$LOG" 2>&1; then
    echo "FAIL: opnieuw uitgeven mislukt voor ${doel}:" >&2
    tail -n 20 "$LOG" >&2; rm -f "$LOG"; exit 1
  fi

  echo "  ${doel}: verifiëren..."
  if ! ( cd "$DOEL_PKI" && ./verify.sh ) >"$LOG" 2>&1; then
    echo "FAIL: ${doel}/pki/verify.sh niet groen na het delen van de CA:" >&2
    tail -n 20 "$LOG" >&2; rm -f "$LOG"; exit 1
  fi
  rm -f "$LOG"
  echo "  ${doel}: OK."
  GEWIJZIGD=$((GEWIJZIGD + 1))
done

if [ "$BEKEKEN" -eq 0 ]; then
  echo "FAIL: elk opgegeven doel is de bron-peer zelf; niets te doen." >&2
  exit 2
elif [ "$CHECK" -eq 1 ]; then
  echo "CHECK: niets gewijzigd."
elif [ "$GEWIJZIGD" -eq 0 ]; then
  echo "GROUP-CA AL GEDEELD (niets te doen)."
else
  echo "GROUP-CA GEDEELD (${GEWIJZIGD} peer(s) bijgewerkt)."
fi
