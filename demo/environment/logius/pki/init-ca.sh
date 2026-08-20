#!/usr/bin/env bash
# Genereert root + intermediate test-CA (#722). NIET voor productie.
set -euo pipefail

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
CONFIG="${BASE_DIR}/config.json"
CA_DIR="${BASE_DIR}/ca"

# Draait de peer tegen de fsc-testnet-directory, dan staat in ca/ NIET onze eigen CA maar die van
# het testnet — de enige waarnaar onze group-leafs mogen ketenen. Overschrijven levert een verse,
# vreemde CA op: de directory wijst de peer af, en omdat de oude sleutel dan weg is kan niemand nog
# een cert uitgeven dat de draaiende mesh vertrouwt. Alleen leeg beginnen gaat vanzelf; hergebruik
# van deze map vraagt een expliciete -f.
if [ -s "${CA_DIR}/root.pem" ] && [ "${1:-}" != "-f" ]; then
  echo "ca/ bevat al een group-CA:" >&2
  openssl x509 -in "${CA_DIR}/root.pem" -noout -subject -dates 2>/dev/null | sed 's/^/  /' >&2
  echo "Vervangen wist die CA onherstelbaar. Is dit de testnet-CA, laat 'm staan en draai issue.sh." >&2
  echo "Wil je hier echt een eigen CA (alleen voor de lokale compose-proof): init-ca.sh -f" >&2
  exit 1
fi

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
