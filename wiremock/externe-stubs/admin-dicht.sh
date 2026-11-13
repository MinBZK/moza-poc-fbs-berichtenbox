#!/bin/bash
# Zet de admin-API van WireMock dicht met een wachtwoord dat bij elke start opnieuw getrokken en
# nergens bewaard wordt. Op ZAD staat een stub achter een publieke route, en een open admin-API laat
# iedereen de mappings wijzigen, de stub stilleggen en het request-journaal lezen. Niemand op ZAD
# heeft die API nodig: probe en statusdashboard gebruiken de stub-mapping `GET /gezondheid`.
#
# Via WIREMOCK_OPTIONS, want ZAD geeft een component geen startargumenten; /docker-entrypoint.sh
# van het basisimage plakt die variabele achter de opdracht. Lokaal draait compose het kale
# wiremock-image met de mappings als bind mount, en blijft de admin-API dus open.
set -euo pipefail

wachtwoord="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
export WIREMOCK_OPTIONS="${WIREMOCK_OPTIONS:-} --admin-api-basic-auth beheer:${wachtwoord}"

exec /docker-entrypoint.sh "$@"
