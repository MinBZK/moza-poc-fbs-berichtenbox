#!/usr/bin/env bash
# Geeft alle peers in de federatie dezelfde group-CA.
#
# Elke peer-harness heeft zijn eigen `pki/init-ca.sh` en dus zijn eigen group-root. Standalone
# is dat precies goed, maar in één federatie moet het externe (:443) mTLS-verkeer van álle
# peers naar één anker ketenen — anders vertrouwt de directory de peers niet en vice versa.
# Dit script kopieert de group-CA van de bron-peer naar de doel-peers en geeft daar de certs
# opnieuw uit. Dezelfde ingreep die `<peer>/deploy/zad/README.md` voorschrijft om op repo A's
# testfederatie aan te sluiten.
#
# De INTERNAL-CA blijft per peer apart: die tekent alleen verkeer binnen één peer, dus daar is
# een gedeeld anker niet nodig en juist onwenselijk (`pki/verify.sh` toetst die isolatie).
#
# DESTRUCTIEF op de doel-peers: hun eigen group-root wordt overschreven en al hun certs worden
# opnieuw uitgegeven. Standalone draaien blijft daarna werken (de keten is intern consistent),
# maar certs die elders zijn uitgedeeld op de óúde root gelden niet meer. Gebruik `--check` om
# te zien wat er zou gebeuren.
#
# Usage:
#   ./deel-groep-ca.sh [--check] [bron-peer] [doel-peer...]
#     bron-peer   default: logius            (levert de group-CA)
#     doel-peer   default: magazijn-a        (krijgt hem, en geeft opnieuw uit)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi

BRON="${1:-logius}"
shift || true
DOELEN=("$@")
[ "${#DOELEN[@]}" -gt 0 ] || DOELEN=(magazijn-a)

BRON_CA="${ENVDIR}/${BRON}/pki/ca"
[ -r "${BRON_CA}/root.pem" ] || {
  echo "FAIL: geen group-CA bij de bron-peer '${BRON}' (${BRON_CA}/root.pem)." >&2
  echo "  Draai eerst ${BRON}/pki/init-ca.sh." >&2
  exit 1
}

# De vier bestanden die samen de group-CA vormen. De CRL hoort er NIET bij: die wordt per peer
# opnieuw gegenereerd uit de zojuist gekopieerde intermediate (gen-crl.sh hieronder).
CA_FILES=(root.pem root-key.pem intermediate.pem intermediate-key.pem)
for f in "${CA_FILES[@]}"; do
  [ -r "${BRON_CA}/${f}" ] || { echo "FAIL: ${BRON_CA}/${f} ontbreekt of is onleesbaar." >&2; exit 1; }
done

vingerafdruk() {  # $1 = pad naar een cert; echoot de SHA-256-fingerprint of "-"
  openssl x509 -in "$1" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 || echo -
}

BRON_FP="$(vingerafdruk "${BRON_CA}/root.pem")"
echo "group-CA van '${BRON}': ${BRON_FP}"

for doel in "${DOELEN[@]}"; do
  DOEL_PKI="${ENVDIR}/${doel}/pki"
  [ -d "$DOEL_PKI" ] || { echo "FAIL: geen pki/-map voor peer '${doel}' (${DOEL_PKI})." >&2; exit 1; }
  [ "$doel" != "$BRON" ] || { echo "  ${doel}: is de bron zelf, overslaan."; continue; }

  DOEL_FP="$(vingerafdruk "${DOEL_PKI}/ca/root.pem")"
  if [ "$DOEL_FP" = "$BRON_FP" ]; then
    echo "  ${doel}: draagt de group-CA al (idempotent, overslaan)."
    continue
  fi

  if [ "$CHECK" -eq 1 ]; then
    echo "  ${doel}: ZOU de group-CA overschrijven (nu: ${DOEL_FP}) en alle certs opnieuw uitgeven."
    continue
  fi

  echo "  ${doel}: group-CA overschrijven (was ${DOEL_FP})..."
  mkdir -p "${DOEL_PKI}/ca"
  for f in "${CA_FILES[@]}"; do
    cp "${BRON_CA}/${f}" "${DOEL_PKI}/ca/${f}"
  done

  # cfssl logt elke uitgifte op stderr; dat is voortgang, geen fout. Wegschrijven en alleen
  # tonen als de stap faalt — anders verdrinkt het echte resultaat in ~60 INFO-regels.
  LOG=$(mktemp)
  echo "  ${doel}: certs opnieuw uitgeven onder de gedeelde CA..."
  if ! ( cd "$DOEL_PKI" && ./issue.sh -f && ./gen-crl.sh ) >"$LOG" 2>&1; then
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
done

[ "$CHECK" -eq 1 ] && { echo "CHECK: niets gewijzigd."; exit 0; }
echo "GROUP-CA GEDEELD."
