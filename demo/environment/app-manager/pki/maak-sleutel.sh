#!/usr/bin/env bash
# Maakt de sleutel waarmee de demo-App Manager credentials ondertekent, en de regels waarmee het
# stelselregister die App Manager in het stelseldocument zet.
#
#   ./maak-sleutel.sh    maakt een sleutel als die er nog niet is; de properties elke keer opnieuw
#
# Uitvoer (alles buiten git, zie .gitignore), per afnemer een eigen map:
#   out/app-manager/sleutel.pem                   private key (PKCS#8) en public key (SPKI), voor
#                                                 APP_MANAGER_SLEUTEL_PAD; alleen voor de eigenaar
#   out/stelselregister/app-managers.properties   alleen de publieke coördinaten, voor
#                                                 SMALLRYE_CONFIG_LOCATIONS van het stelselregister
#
# OIN, kid en geldigheid zijn de defaults van de App Manager, zodat die alleen het pad nodig heeft.
# Een bestaande sleutel blijft staan: document en App Manager houden zo na elke start dezelfde. Voor
# een nieuwe sleutel: gooi out/ weg, draai dit script en start beide diensten opnieuw.
set -euo pipefail

cd "$(dirname "$0")"

OIN="00000000000000001100"
NAAM="Demo-App Manager"
KID="app-manager-1"
GELDIG_TOT="2099-01-01T00:00:00Z"

SLEUTEL=out/app-manager/sleutel.pem
PROPERTIES=out/stelselregister/app-managers.properties

command -v openssl >/dev/null || { echo "openssl ontbreekt" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 ontbreekt" >&2; exit 1; }

umask 077
mkdir -p out/app-manager out/stelselregister

tijdelijk=()
trap 'rm -f "${tijdelijk[@]+"${tijdelijk[@]}"}"' EXIT

if [[ ! -s "$SLEUTEL" ]]; then
    ec="$(mktemp out/app-manager/.ec.XXXXXX)"
    nieuw="$(mktemp out/app-manager/.sleutel.XXXXXX)"
    tijdelijk+=("$ec" "$nieuw")

    # Via ecparam en pkcs8 in plaats van genpkey: dat werkt ook met de LibreSSL van macOS, en de
    # App Manager leest alleen een PRIVATE KEY-blok (PKCS#8), geen EC PRIVATE KEY.
    openssl ecparam -name prime256v1 -genkey -noout -out "$ec"
    openssl pkcs8 -topk8 -nocrypt -in "$ec" -out "$nieuw"
    openssl ec -in "$ec" -pubout 2>/dev/null >> "$nieuw"
    # Pas op zijn plek als hij compleet is: een half bestand zou de volgende run voor een sleutel houden.
    mv "$nieuw" "$SLEUTEL"
    echo "nieuwe sleutel gemaakt: $(pwd)/$SLEUTEL"
fi

# Uit de private key afgeleid en niet uit het PUBLIC KEY-blok: de App Manager ondertekent met de
# private key, dus het document hoort naar díe sleutel te wijzen. Een P-256-SPKI is altijd 91
# bytes: een vaste kop van 27 bytes tot en met de 04 van een ongecomprimeerd punt, dan x en y van
# 32 bytes. Een afwijkende kop zou coördinaten van verkeerde plekken opleveren.
coordinaten="$(openssl pkey -in "$SLEUTEL" -pubout -outform DER | python3 -c '
import base64, sys

der = sys.stdin.buffer.read()
kop = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004")

if len(der) != 91 or not der.startswith(kop):
    sys.exit("de sleutel is geen P-256-sleutel in de verwachte vorm")

b64url = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
print(b64url(der[27:59]), b64url(der[59:91]))
')"
read -r X Y <<<"$coordinaten"

nieuw="$(mktemp out/stelselregister/.properties.XXXXXX)"
tijdelijk+=("$nieuw")

cat > "$nieuw" <<PROPERTIES
# Gemaakt door demo/environment/app-manager/pki/maak-sleutel.sh, uit out/app-manager/sleutel.pem.
stelseldocument.app-managers."${OIN}".naam=${NAAM}
stelseldocument.app-managers."${OIN}".sleutels."${KID}".x=${X}
stelseldocument.app-managers."${OIN}".sleutels."${KID}".y=${Y}
stelseldocument.app-managers."${OIN}".sleutels."${KID}".geldig-tot=${GELDIG_TOT}
PROPERTIES

# De container van het stelselregister draait onder een eigen gebruiker en leest de map via een
# bind-mount; de coördinaten zijn publiek. De map van de App Manager blijft alleen voor de eigenaar.
chmod 644 "$nieuw"
mv "$nieuw" "$PROPERTIES"
chmod 755 out out/stelselregister

echo "sleutel van de App Manager: $(pwd)/$SLEUTEL"
echo "voor het stelselregister:   $(pwd)/$PROPERTIES"
echo "  ${OIN} kid=${KID} x=${X} y=${Y}"
