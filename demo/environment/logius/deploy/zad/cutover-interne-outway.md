# Cutover — uitvraag naar de cluster-interne outway

> Draaiboek voor het omzetten van `berichtenuitvraag` van de publieke ingress-URL van de outway
> naar zijn cluster-interne Service-adres. Hoort bij de wijziging die `LISTEN_HTTPS` op
> `logius-fscoutway` zet; achtergrond in `docs/plans/2026-08-19-outway-https-clusterip.md`.

Doeladres:

```
https://fsc-logius-logius-fscoutway.rig-prd-mpfb-8wh.svc.cluster.local:8443
```

De Service heet `<deployment>-<component>` en leeft in namespace `rig-prd-<project>`. Let op dat
`zadctl deployment describe` `mpfb-8wh` als "Namespace" toont; dat is het project-id.

## Dit is een cutover, geen toevoeging

Twee dingen maken dat de stappen in één venster horen en niet los uitgerold kunnen worden.

**De bestaande ingress-route breekt zodra de outway TLS spreekt.** De gerenderde Ingress
(`logius-fscoutway-ingress.yaml`) termineert TLS aan de rand (`tls: - {}`) en praat plain HTTP
naar poort 8443; er staat geen `backend-protocol: HTTPS`-annotatie op. Zet je `LISTEN_HTTPS=true`,
dan spreekt de pod TLS en levert de publieke route 502.

**Het trust-anker vervangt de JVM-default trust-store, het vult die niet aan.** Zodra
`quarkus.tls.outway` bestaat valideert élk magazijn-endpoint tegen de interne CA — ook een
endpoint dat nog op de publieke ingress staat, met een publiek certificaat. Vandaar dat het anker
per deployment gaat zolang niet elke deployment mee is.

## Voorwaarden

- De NetworkPolicy-uitzondering tussen de `test`- en `fsc-logius`-deployment staat; zonder die
  uitzondering is het interne adres onbereikbaar en helpt geen van onderstaande stappen.
- `ZAD_API_KEY` voor project `mpfb-8wh` staat in de omgeving (`.env.zadctl`); `zadctl deployment
  list` werkt.
- De wijziging is uitgerold, zodat de app de TLS-configuratie kent.

## Stappen

**1. De outway laat TLS toe op zijn serve-poort.**

Niet via `upsert-peer.sh apply`: ZAD past `env_vars` uit een component-body alleen toe bij
component-*creatie*, dus een re-POST verandert niets. De user-env-laag werkt wel op een bestaande
component:

```bash
zadctl env add -c logius-fscoutway \
  LISTEN_HTTPS=true \
  TLS_SERVER_CERT=/etc/fsc/internal/logius/outway/cert.pem \
  TLS_SERVER_KEY=/etc/fsc/internal/logius/outway/key.pem
```

Beide certificaat-paden zijn de bijlagen die er al hangen (`cert-manifest.md`); er hoeft niets
geüpload te worden. Vanaf hier is de publieke route stuk — de rest van de stappen hoort direct
achter deze aan.

**2. De uitvraag krijgt de interne CA als bestand.**

De bijlage staat al in de catalogus van het project (`logius-internal-ca-root-cert`), alleen nog
niet gekoppeld aan `uitvraag`:

```bash
zadctl attachment assign logius-internal-ca-root-cert uitvraag \
  --provide-as file \
  --mount-path /etc/fsc/internal/logius/ca/root.pem
```

**3. De uitvraag krijgt het anker en de URL's, in één stap.**

Per deployment, zodat de PR-previews op hun eigen (nog publieke) adres blijven werken tot ze mee
verhuizen:

```bash
OUTWAY=https://fsc-logius-logius-fscoutway.rig-prd-mpfb-8wh.svc.cluster.local:8443

zadctl env add -c uitvraag --deployment test \
  QUARKUS_TLS_OUTWAY_TRUST_STORE_PEM_CERTS=/etc/fsc/internal/logius/ca/root.pem \
  QUARKUS_REST_CLIENT_PROFIEL_SERVICE_TLS_CONFIGURATION_NAME=outway

zadctl env set -c uitvraag --deployment test \
  MAGAZIJN_A_URL="$OUTWAY" \
  PROFIEL_SERVICE_URL="$OUTWAY"
```

`add` voor de twee nieuwe sleutels, `set` voor de twee die al bestaan — `add` op een bestaande
sleutel is een conflict, geen overschrijving. Beide rollen standaard uit; met `--no-rollout` kun
je ze stapelen en daarna één keer `zadctl deployment refresh test` doen.

**4. Verifiëren.**

```bash
zadctl logs test -c logius-fscoutway | grep -i "HTTPS server"   # verwacht: starting HTTPS server
zadctl logs test -c uitvraag | grep -iE "handshake|PKIX|SSL"    # verwacht: niets
```

Daarna de functionele smoke: een ophaal-request door de keten
`berichtenuitvraag → logius-fscoutway → logius-fscinway → magazijn-a`, met een verse BSN zodat de
sessiecache de keten niet maskeert. Een nieuwe transactie in beide txlogs is het bewijs dat het
verkeer écht door de outway liep.

**5. De publieke ingress intrekken.**

Werkt de interne route, haal dan "Publicatie op het web" van `logius-fscoutway` weg in de ZAD-UI.
Hij is dan niet meer in gebruik, en een outway met een publiek adres is oppervlak dat niemand
nodig heeft. Werk `verify-zad.md` bij als dit gebeurd is.

## Terugrollen

Stap 1 en 3 zijn elkaars tegenhanger; draai ze samen terug:

```bash
zadctl env unset -c logius-fscoutway LISTEN_HTTPS TLS_SERVER_CERT TLS_SERVER_KEY
zadctl env unset -c uitvraag --deployment test \
  QUARKUS_TLS_OUTWAY_TRUST_STORE_PEM_CERTS QUARKUS_REST_CLIENT_PROFIEL_SERVICE_TLS_CONFIGURATION_NAME
zadctl env set -c uitvraag --deployment test \
  MAGAZIJN_A_URL=https://logius-fscoutway-fsc-logius-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl \
  PROFIEL_SERVICE_URL=https://logius-fscoutway-fsc-logius-mpfb-8wh.rig.prd1.gn2.quattro.rijksapps.nl
```

De bijlage uit stap 2 mag blijven hangen: zonder de env-var uit stap 3 doet een gemount
CA-bestand niets. Laat 'm staan, dan is een tweede poging één commando korter.
