# Image voor de contract-bootstrap als ZAD-component: één per peer, in de deployment van díé peer.
#
# Waarom een component en geen CI-stap: de interne manager-API heeft op ZAD geen route en de
# tenant-baseline-NetworkPolicy laat alleen verkeer binnen hetzelfde deployment toe. Alleen een pod
# náást de manager kan hem bereiken. En omdat ZAD geen component-args toestaat, leest het entrypoint
# zijn rol uit env in plaats van uit een argument. Zie contracts/zad-runbook.md.
#
# Build-context is demo/environment/ (niet deze map): de scripts leunen op ../../lib.
FROM docker.io/library/alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce

# bash — de scripts gebruiken arrays; curl — de manager-API; jq — de idempotentie leest contract-JSON;
# openssl — de outway-thumbprint uit een certificaat, alleen nodig als FSC_OUTWAY_THUMBPRINT niet
# gezet is. Géén ca-certificates: elke call geeft zijn eigen --cacert mee (de interne PKI), dus de
# publieke trust store wordt nergens geraadpleegd.
RUN apk add --no-cache bash curl jq openssl

# Alleen wat draait. De hele map kopiëren zou ook de fixture-tests meenemen en, erger, `bootstrap.sh`
# en `fbs-contracten.sh` uitvoerbaar in de pod zetten — twee scripts die hier niets te zoeken hebben
# omdat ze met beide managers tegelijk praten.
#
# ZAD/OpenShift start containers onder een willekeurige UID in groep 0, vandaar groep-0-eigendom en
# leesrechten voor die groep.
COPY --chown=1001:0 lib/fsc-harness.sh lib/fsc-contract.sh /opt/fsc/lib/
COPY --chown=1001:0 federatie/contracts/bootstrap-consumer.sh \
                    federatie/contracts/bootstrap-provider.sh \
                    federatie/contracts/zad-lus.sh /opt/fsc/contracts/

RUN chmod -R g=u /opt/fsc && chmod 0755 /opt/fsc/contracts/*.sh

USER 1001
ENV FSC_LIBDIR=/opt/fsc/lib

ENTRYPOINT ["/opt/fsc/contracts/zad-lus.sh"]
