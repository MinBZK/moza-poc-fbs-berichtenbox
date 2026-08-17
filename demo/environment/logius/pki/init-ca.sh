#!/usr/bin/env bash
# Genereert root + intermediate test-CA (#722). NIET voor productie.
set -euo pipefail

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
CONFIG="${BASE_DIR}/config.json"
CA_DIR="${BASE_DIR}/ca"
# 700: hier ligt de group-root-key. De bestanden zelf zijn 0600 (cfssljson), maar een 0755-map
# geeft elke lokale gebruiker een listing van het CA-materiaal.
mkdir -p "${CA_DIR}"
chmod 700 "${CA_DIR}"

# Root: self-signed CA
cfssl genkey -initca "${BASE_DIR}/ca.json" | cfssljson -bare "${CA_DIR}/root"

# Intermediate: self-signed, daarna her-tekenen door root met profiel 'intermediate'
cfssl genkey -initca "${BASE_DIR}/intermediate.json" | cfssljson -bare "${CA_DIR}/intermediate"
cfssl sign -config "${CONFIG}" \
  -ca "${CA_DIR}/root.pem" -ca-key "${CA_DIR}/root-key.pem" \
  -profile intermediate "${CA_DIR}/intermediate.csr" \
  | cfssljson -bare "${CA_DIR}/intermediate"

rm -f "${CA_DIR}/root.csr" "${CA_DIR}/intermediate.csr"
echo "OK: root.pem (trust-anchor) + intermediate.pem in ${CA_DIR}"
