#!/usr/bin/env bash
# Smoke: bewijst dat de peers écht één federatie vormen en niet twee losse harnessen die
# toevallig tegelijk draaien. Vier asserts:
#
#   1. alle peers dragen dezelfde group-CA (anders vertrouwt niemand elkaar op :443);
#   2. elke poort uit het blokschema luistert, en geen enkele wordt door twee peers geclaimd;
#   3. alle peers plus de directory staan in ÉÉN peers.peers, aangemeld op :443;
#   4. een gast-peer kan een dienst publiceren en die is vindbaar in de gedeelde catalogus.
#
# Draai na `./federatie.sh up`. bash 3.2-compatibel (macOS-default).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVDIR="$(cd "${HERE}/.." && pwd)"

GASTHEER=logius
FOUTEN=0

fout() { echo "FAIL: $*" >&2; FOUTEN=$((FOUTEN + 1)); }
ok()   { echo "OK: $*"; }

# --- 1. Gedeelde group-CA ---------------------------------------------------------------------
echo "== 1. group-CA gedeeld =="
vingerafdruk() { openssl x509 -in "$1" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 || echo -; }
REF=""
for peer in logius magazijn-a; do
  fp="$(vingerafdruk "${ENVDIR}/${peer}/pki/ca/root.pem")"
  if [ -z "$REF" ]; then REF="$fp"; fi
  if [ "$fp" = "$REF" ] && [ "$fp" != "-" ]; then
    ok "${peer} draagt de gedeelde group-root"
  else
    fout "${peer} heeft een afwijkende (of ontbrekende) group-root: ${fp} != ${REF}"
  fi
done

# --- 2. Poortschema ----------------------------------------------------------------------------
# Elke regel: <peer> <poort> <rol>. Spiegelt README.md en de twee compose-overlays; wijken die
# af, dan faalt deze assert — dat is het punt. Een dubbel geclaimde poort zou zich anders pas
# uiten als een raadselachtige TLS- of routeringsfout.
echo "== 2. poortschema =="
POORTEN="
logius 61000 manager-extern
logius 61001 manager-intern
logius 61002 manager-intern-unauth
logius 61010 controller-ui
logius 61011 controller-registratie
logius 61012 controller-administratie
logius 61020 txlog
logius 61030 inway
logius 61040 outway
logius 61050 stub-upstream
magazijn-a 61100 manager-extern
magazijn-a 61101 manager-intern
magazijn-a 61102 manager-intern-unauth
magazijn-a 61110 controller-ui
magazijn-a 61111 controller-registratie
magazijn-a 61112 controller-administratie
magazijn-a 61120 txlog
magazijn-a 61130 inway
magazijn-a 61150 stub-upstream
federatie 443 router
federatie 5432 postgres
federatie 18443 directory-extern
federatie 19443 directory-intern
"
LUISTERAARS="$(ss -ltn 2>/dev/null | awk 'NR>1 {print $4}' | grep -E '^127\.0\.0\.1:' | sed 's/.*://' | sort -u)"
GEZIEN=""
while read -r peer poort rol; do
  [ -n "${poort:-}" ] || continue
  if printf '%s\n' "$LUISTERAARS" | grep -qx "$poort"; then
    ok "${poort} luistert (${peer} ${rol})"
  else
    fout "${poort} luistert niet, verwacht voor ${peer} ${rol}"
  fi
  # Dubbel gebruik van hetzelfde poortnummer in het schema zelf is een ontwerpfout, ook al
  # merkt `ss` daar niets van (de tweede bind zou simpelweg falen).
  if printf '%s\n' "$GEZIEN" | grep -qx "$poort"; then
    fout "${poort} staat twee keer in het schema (blok-botsing)"
  fi
  GEZIEN="${GEZIEN}
${poort}"
done <<EOF
$POORTEN
EOF

# --- 3. Eén directory, alle peers aangemeld ----------------------------------------------------
echo "== 3. één directory =="
RIJEN="$(podman exec "fsc-${GASTHEER}-postgres-1" psql -U postgres -d fsc_directory -tA \
  -c "SELECT id FROM peers.peers WHERE manager_address LIKE '%:443' ORDER BY id" 2>/dev/null || true)"
for oin in 00000000000000000010 00000000000000001000 00000000000000100000; do
  if printf '%s\n' "$RIJEN" | grep -qx "$oin"; then
    ok "${oin} aangemeld op :443"
  else
    fout "${oin} ontbreekt in peers.peers (of niet op :443)"
  fi
done

# --- 4. Publiceren + vindbaar in de gedeelde catalogus -----------------------------------------
# Bewust via de peer-eigen scripts, met de federatie-poorten uit env: dat toetst meteen dat de
# de-hardcoding werkt en dat de standalone-scripts in deze opstelling bruikbaar blijven.
echo "== 4. dienst publiceren + ontdekken (magazijn-a) =="
if CONTROLLER=https://controller.magazijn-a.fsc-test.local:61112 \
   MANAGER=https://manager.magazijn-a.fsc-test.local:61101 \
   STUB_URL=http://stub-upstream:61150 \
     "${ENVDIR}/magazijn-a/deploy/local/publish-service.sh" >/dev/null 2>&1; then
  ok "berichtenmagazijn gepubliceerd"
else
  fout "publish-service.sh (magazijn-a) niet groen"
fi

if MANAGER=https://manager.magazijn-a.fsc-test.local:61101 \
     "${ENVDIR}/magazijn-a/deploy/local/smoke-discover.sh" >/dev/null 2>&1; then
  ok "berichtenmagazijn vindbaar in de catalogus"
else
  fout "smoke-discover.sh (magazijn-a) niet groen"
fi

echo
if [ "$FOUTEN" -eq 0 ]; then
  echo "== FEDERATIE-SMOKE GROEN =="
else
  echo "== FEDERATIE-SMOKE ROOD: ${FOUTEN} bevinding(en) ==" >&2
  exit 1
fi
