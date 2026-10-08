#!/usr/bin/env bash
# Maakt de sleutelketen waarmee het stelselregister het stelseldocument ondertekent:
# een root, een ondertekencertificaat eronder, en een PKCS#12-keystore voor de dienst.
#
#   ./maak-keten.sh            maakt wat ontbreekt; een bestaande root blijft staan
#   ./maak-keten.sh --roteer   maakt een nieuw ondertekencertificaat onder de bestaande root
#
# Uitvoer (alles buiten git, zie .gitignore):
#   ca/root.key, ca/root.pem          de root; root.key hoort NIET bij de dienst en niet op ZAD
#   out/keystore.p12                  ondertekensleutel + keten, alias `stelseldocument`
#   out/wachtwoord                    wachtwoord van de keystore (0600)
#   out/keystore.env                  hetzelfde wachtwoord als env-bestand voor compose (0600)
#   out/root.pem                      de root die een app vastlegt
#
# Een app vertrouwt de root, niet het ondertekencertificaat. `--roteer` is daarom voor apps
# onzichtbaar; een nieuwe root is dat niet en vraagt een nieuwe build van elke app.
set -euo pipefail

cd "$(dirname "$0")"

OIN="${STELSELDOCUMENT_UITGEVER_OIN:-00000000000000001000}"
ORGANISATIE="${STELSELDOCUMENT_UITGEVER_NAAM:-Logius}"
OMGEVING="${STELSELDOCUMENT_OMGEVING:-demo}"
ROOT_DAGEN="${ROOT_DAGEN:-3650}"
# Kort: er is geen intrekkingslijst, dus de looptijd van het ondertekencertificaat is het venster
# waarin een gelekte sleutel bruikbaar blijft. De dienst waarschuwt vanaf dertig dagen vóór het
# verlopen; wissel dan met --roteer.
ONDERTEKEN_DAGEN="${ONDERTEKEN_DAGEN:-90}"
MINIMALE_WACHTWOORDLENGTE=32
ALIAS="stelseldocument"

roteer=0

case "${1:-}" in
    "") ;;
    --roteer) roteer=1 ;;
    *) echo "onbekend argument: $1" >&2; exit 2 ;;
esac

command -v openssl >/dev/null || { echo "openssl ontbreekt" >&2; exit 1; }

umask 077
mkdir -p ca out

if [[ ! -f ca/root.key ]]; then
    if (( roteer )); then
        echo "--roteer vraagt een bestaande root in ca/, maar die is er niet" >&2
        exit 1
    fi

    openssl ecparam -name prime256v1 -genkey -noout -out ca/root.key
    openssl req -x509 -new -key ca/root.key -sha256 -days "$ROOT_DAGEN" \
        -subj "/C=NL/O=${ORGANISATIE}/CN=Stelseldocument root (${OMGEVING})" \
        -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" \
        -out ca/root.pem
    echo "nieuwe root gemaakt: ca/root.pem"
elif [[ -f out/keystore.p12 && -f out/keystore.env ]] && (( ! roteer )); then
    echo "out/keystore.p12 bestaat al; gebruik --roteer voor een nieuw ondertekencertificaat"
    exit 0
fi

werk="$(mktemp -d)"
trap 'rm -rf "$werk"' EXIT

openssl ecparam -name prime256v1 -genkey -noout -out "$werk/onderteken.key"
# De OIN in subject.serialNumber, zoals PKIoverheid dat bij een organisatiecertificaat doet.
openssl req -new -key "$werk/onderteken.key" \
    -subj "/C=NL/O=${ORGANISATIE}/serialNumber=${OIN}/CN=Stelselbeheerder stelseldocument (${OMGEVING})" \
    -out "$werk/onderteken.csr"

cat > "$werk/ext.cnf" <<EXT
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid
EXT

openssl x509 -req -in "$werk/onderteken.csr" -CA ca/root.pem -CAkey ca/root.key -CAcreateserial \
    -sha256 -days "$ONDERTEKEN_DAGEN" -extfile "$werk/ext.cnf" -out "$werk/onderteken.pem" 2>/dev/null

# Het wachtwoord gaat via een bestand naar openssl, niet over de commandoregel: daar is het voor
# elke gebruiker op de machine te zien. Zonder backslash, zodat het ongewijzigd door een
# env-var op het platform komt.
if [[ -n "${STELSELDOCUMENT_KEYSTORE_WACHTWOORD:-}" ]]; then
    # De keystore is leesbaar voor anderen; alleen het wachtwoord beschermt de sleutel erin.
    if (( ${#STELSELDOCUMENT_KEYSTORE_WACHTWOORD} < MINIMALE_WACHTWOORDLENGTE )); then
        echo "STELSELDOCUMENT_KEYSTORE_WACHTWOORD is korter dan ${MINIMALE_WACHTWOORDLENGTE} tekens; laat hem weg voor een gegenereerd wachtwoord" >&2
        exit 1
    fi

    printf '%s' "$STELSELDOCUMENT_KEYSTORE_WACHTWOORD" > out/wachtwoord
elif [[ ! -s out/wachtwoord ]]; then
    openssl rand -hex 24 | tr -d '\n' > out/wachtwoord
fi

openssl pkcs12 -export -name "$ALIAS" \
    -inkey "$werk/onderteken.key" -in "$werk/onderteken.pem" -certfile ca/root.pem \
    -passout file:out/wachtwoord -out out/keystore.p12

printf 'STELSELDOCUMENT_KEYSTORE_WACHTWOORD=%s\n' "$(cat out/wachtwoord)" > out/keystore.env

cp ca/root.pem out/root.pem
chmod 600 out/wachtwoord out/keystore.env
# De keystore en de map zijn leesbaar voor anderen: de container draait onder een eigen gebruiker
# en moet het bestand via een bind-mount kunnen lezen. Het wachtwoord beschermt de sleutel erin,
# en dat blijft samen met ca/ alleen voor de eigenaar.
chmod 755 out
chmod 644 out/keystore.p12 out/root.pem

echo "keystore:      $(pwd)/out/keystore.p12 (alias ${ALIAS})"
echo "wachtwoord:    $(pwd)/out/wachtwoord"
echo "root voor apps: $(pwd)/out/root.pem"
echo "vingerafdruk van de root (SHA-256):"
openssl x509 -in ca/root.pem -noout -fingerprint -sha256 | sed 's/^.*=/  /'
echo "ondertekencertificaat geldig tot: $(openssl x509 -in "$werk/onderteken.pem" -noout -enddate | cut -d= -f2)"
