# Image voor de contract-bootstrap als ZAD-component: één per peer, in de deployment van díé peer.
#
# Waarom een component en geen CI-stap: de interne manager-API heeft op ZAD geen route en de
# tenant-baseline-NetworkPolicy laat alleen verkeer binnen hetzelfde deployment toe. Alleen een pod
# náást de manager kan hem bereiken. En omdat ZAD geen component-args toestaat, leest het
# entrypoint zijn rol uit env in plaats van uit een argument.
#
# Build-context is demo/environment/ (niet deze map): de scripts leunen op ../../lib.
FROM docker.io/library/alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce

# bash — de scripts gebruiken arrays en `read -a`; curl — de manager-API; jq — de idempotentie leest
# contract-JSON; openssl — de outway-thumbprint uit een certificaat; ca-certificates — voor de
# publieke trust store. De manager-API zelf loopt op de interne PKI, die als CA-bestand meekomt.
RUN apk add --no-cache bash curl jq openssl ca-certificates

COPY lib/ /opt/fsc/lib/
COPY federatie/contracts/ /opt/fsc/contracts/

# ZAD/OpenShift start containers onder een willekeurige UID in groep 0. Bestanden dus groep-0-eigen
# en groepsrechten gelijk aan de eigenaarsrechten, anders kan die UID de scripts niet lezen.
RUN chgrp -R 0 /opt/fsc && chmod -R g=u /opt/fsc && chmod +x /opt/fsc/contracts/*.sh

USER 1001
ENV FSC_LIBDIR=/opt/fsc/lib

ENTRYPOINT ["/opt/fsc/contracts/zad-lus.sh"]
