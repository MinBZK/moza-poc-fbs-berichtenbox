# Outway over https op de cluster-interne Service

**Status:** Uitgevoerd

## Context

`berichtenuitvraag` bereikt de FSC-outway van de `logius`-peer op ZAD via de publieke
ingress-URL (`https://logius-fscoutway-fsc-logius-mpfb-8wh.<base-domain>`). Dat is een omweg:
bron en doel zijn twee pods in dezelfde namespace, maar het verkeer verlaat het cluster en komt
via de ingress-controller weer binnen. De aanleiding voor die omweg — de tenant-baseline
-NetworkPolicy die deployments onderling isoleert — is inmiddels op te lossen met een gerichte
NetworkPolicy-uitzondering.

De voor de hand liggende vervanging, de cluster-interne Service-URL, wordt door onze eigen
configuratie geweigerd:

```
http://fsc-logius-logius-fscoutway.rig-prd-mpfb-8wh.svc.cluster.local:8443
```

`ConfigMagazijnregister.parseUrl` roept `OutboundTlsValidator.requireHttps(...)` aan, en die eist
`https://` in elk profiel buiten `dev`/`test`. De outway serveert die poort standaard plain HTTP.

Er zijn drie uitwegen overwogen:

1. de outway zelf https laten spreken op zijn luisterpoort;
2. de ingress-URL blijven gebruiken;
3. een bewuste `unsafeAllowPlaintext`-klep toevoegen naar analogie van Redis/LDV.

Dit plan werkt (1) uit. (2) is de huidige toestand en lost de omweg niet op. (3) ruilt echte
encryptie in voor een configuratieregel, terwijl (1) beschikbaar is; bovendien heeft #211 bij de
magazijn-downstreams bewust besloten dat een grant-hash wél de SSRF-blocklist overslaat maar niet
de TLS-eis — een klep hier zou tegen die lijn in werken.

## Wat er al klopt

Voorwerk dat níét nodig blijkt:

- **De outway kan https.** `fsc-outway serve` kent `--listen-https`, `--tls-server-cert` en
  `--tls-server-key`; de env-varianten zijn `LISTEN_HTTPS`, `TLS_SERVER_CERT`, `TLS_SERVER_KEY`.
  Lokaal beproefd met een wegwerp-outway naast de draaiende federatie: hij logt
  `starting HTTPS server on 127.20.1.9:8443` en het volledige pad naar magazijn-a blijft werken
  (HTTP 200, JWT ongewijzigd aanwezig).
- **Het certificaat bestaat al.** De interne outway-cert hangt al als bijlage aan het component
  (`/etc/fsc/internal/logius/outway/cert.pem`, zie `cert-manifest.md`), en `gen-csr.sh` zet er al
  de juiste SANs in: `fsc-logius-logius-fscoutway` en
  `fsc-logius-logius-fscoutway.rig-prd-mpfb-8wh.svc.cluster.local`. Geen her-uitgifte nodig.

Wat resteert: de outway die vlag laten zetten, en de aanroepende app de interne CA laten
vertrouwen.

## Ontwerpkeuzes

**Eén TLS-configuratie met de naam `outway`.** Zowel het magazijn-verkeer als de profiel-service
lopen door dezelfde outway en dus achter hetzelfde interne CA-anker. Eén named Quarkus-TLS-config
(`quarkus.tls.outway.*`) dekt beide; de naam staat als constante in `fbs-magazijnregister` zodat
de twee modules die 'm gebruiken niet uiteen kunnen lopen.

**Aanwezigheid van de config is de schakelaar.** Geen aparte aan/uit-property: de client vraagt
de configuratie op bij de `TlsConfigurationRegistry` en gebruikt 'm als hij bestaat. Ontbreekt hij
— elke omgeving die vandaag draait — dan blijft het gedrag exact wat het was (JVM-default
trust-store). Een tweede knop die alleen maar in sync gehouden moet worden met de eerste voegt
niets toe.

**`MagazijnRouter` stapt over op `QuarkusRestClientBuilder`.** De MicroProfile-`RestClientBuilder`
kent geen `tlsConfiguration(...)`. `MagazijnClientFactory` gebruikt de Quarkus-variant al; hiermee
bouwen beide plekken hun client op dezelfde manier.

**De profiel-service is config-only, maar per omgeving.** Die client is
`@RegisterRestClient(configKey = "profiel-service")`, dus
`quarkus.rest-client.profiel-service.tls-configuration-name=outway` volstaat — geen code. Die
regel staat bewust **niet** in `application.properties`: een `tls-configuration-name` die naar een
niet-bestaande configuratie wijst laat de client falen, en dan gaat elke omgeving zónder anker
stuk op een knop die ze niet gebruikt. De koppeling gaat daarom per omgeving mee als env-var,
naast de trust-store zelf.

**Lokaal blijft http de default.** De harness draait in `dev`/`test`, waar de TLS-eis niet geldt,
en alle smokes bellen `http://127.20.1.5:8443`. De compose krijgt `LISTEN_HTTPS` als env met
default `false`, zodat het https-pad reproduceerbaar is zonder de bestaande smokes om te gooien.

## Stappen

1. **`logius/deploy/zad/upsert-peer.sh`** — `LISTEN_HTTPS=true`, `TLS_SERVER_CERT` en
   `TLS_SERVER_KEY` toevoegen aan de env van `logius-fscoutway`, wijzend naar de bijlage-paden die
   er al zijn.
2. **`logius/deploy/zad/cert-manifest.md`** — de outway-tabel uitbreiden met de twee nieuwe
   env-vars op hetzelfde bronbestand, met de reden erbij.
3. **`logius/deploy/local/docker-compose.yaml`** — `LISTEN_HTTPS`, `TLS_SERVER_CERT` en
   `TLS_SERVER_KEY` op de outway, https default uit.
4. **`fbs-magazijnregister`** — constante voor de TLS-configuratienaam.
5. **`MagazijnClientFactory`** en **`MagazijnRouter`** — de configuratie opvragen bij de
   `TlsConfigurationRegistry` en toepassen wanneer aanwezig.
6. **`application.properties` (uitvraag)** — toelichting bij de `quarkus.tls.outway.*`-config en
   bij de env-var die de profiel-service-client eraan koppelt; geen property zetten.
7. **Tests** — de tak "configuratie aanwezig → toegepast" dekken, plus een roundtrip over echte
   TLS zodat het niet bij een aangeroepen setter blijft.
8. **`logius/deploy/zad/verify-zad.md`** — het draaiboek bijwerken: de interne https-URL wordt de
   normale route. Sectie (b) beweert nu nog dat de outway niet op het web gepubliceerd is; dat
   spreekt stap 2 in hetzelfde document tegen en klopt niet meer met de werkelijkheid.
9. **`docs/operator-handleiding-uitvraag.md`** — de nieuwe env-vars en wat er gebeurt als ze
   ontbreken.

## Verificatie

Gedaan:

- `./mvnw clean verify` groen voor `fbs-magazijnregister`, `fbs-berichtensessiecache` en
  `berichtenuitvraag`, inclusief JaCoCo-gate en detekt.
- **Negatieve controle op de roundtrip-test.** Met de regel
  `outwayTlsConfiguratie()?.let { builder.tlsConfiguration(it) }` tijdelijk uitgezet faalt
  `MagazijnRouterTlsTest` met `PKIX path building failed: unable to find valid certification path`.
  De test hangt dus aan de koppeling en niet aan een toevallig vertrouwd certificaat.
- **De outway-kant, met een wegwerp-outway naast de draaiende federatie**: met `LISTEN_HTTPS=true`
  en het interne cert als server-cert logt hij `starting HTTPS server`, en een aanroep over https
  met `internal/logius/ca/root.pem` als anker levert HTTP 200 uit magazijn-a, JWT ongewijzigd.
- Beide CI-guards uit `.github/workflows/fsc-harness-overlays.yml` lokaal gedraaid op de
  gewijzigde compose (hostnet-merge én federatie-merge): 14/14 services, alle listeners op
  loopback.
- `docker compose config` rendert `LISTEN_HTTPS: "false"` zonder env en `"true"` met
  `OUTWAY_LISTEN_HTTPS=true` — de default zet geen bestaande route om.

Op ZAD uitgevoerd (2026-08-19), volgens `cutover-interne-outway.md`:

- `LISTEN_HTTPS` + server-cert op `logius-fscoutway`, CA-bijlage en de twee env-vars op
  `uitvraag`, en de URL's van `test` omgezet naar het interne adres.
- Netwerktoegang via `cross-domain-access`: outbound bij `test`, inbound bij `fsc-logius`, beide
  gerenderd als NetworkPolicy en elkaars spiegelbeeld op poort 8443.
- **Het ophalen-endpoint werkt over de interne route.** Bewezen aan het log van de outway tijdens
  een handmatige aanroep — `received request` gevolgd door `forwarding API request` — en niet aan
  een geslaagde respons: die zegt niets, want het rechtstreekse pad naar magazijn-a werkt ook. Het
  ene milliseconde-verschil tussen die twee regels zegt bovendien dat de grant-hash geaccepteerd
  is; bij een onbekende hash stopt de outway daar met een 400.
- De publieke "Publicatie op het web" van `logius-fscoutway` is ingetrokken; die route bestaat
  niet meer.

Nog open:

- **De route hangt aan een deployment-env die een herschepping niet overleeft.**
  `MAGAZIJN_A_URL` en `PROFIEL_SERVICE_URL` staan in de projectspec als component-alias naar de
  publieke ingressen; het interne adres staat als `user-env-var` op deployment-niveau bij `test`
  en wint daarvan. Valt die env weg, dan gaat het verkeer weer rechtstreeks naar magazijn-a —
  zonder foutmelding, zonder transactielogboek, zonder contractcontrole. De alias omzetten kan
  niet gericht (aliases bestaan alleen op componentniveau), en raakt dus ook de PR-previews, die
  geen inbound-regel en geen grant-hash hebben. Belegd in MinBZK/MijnOverheidZakelijk#953.
- De PR-previews bellen magazijn-a nog rechtstreeks op zijn eigen ingress in `mpfm-w3h` (de
  component-alias met `$DEPLOYMENT_NAME`), dus buiten de outway om. Dat is een andere ingress dan
  die van de outway en staat los van het intrekken daarvan: de previews zijn er niet door geraakt.
  Willen ze mee verhuizen, dan heeft elk van hen een eigen inbound-regel én een grant-hash nodig.
- De bestaande lokale smokes (`smoke-federatie.sh`, `smoke-contract.sh`, `smoke-keten.sh`) een
  keer draaien tegen een verse federatie; ze raken deze wijziging niet (default blijft http),
  maar de bevestiging staat nog open.

## Buiten scope

- De NetworkPolicy-uitzondering tussen de `test`- en `fsc-logius`-deployment zelf; dit plan gaat
  ervan uit dat die er is of komt.
- De ZAD-uitrol (apply + herstart) blijft handmatig vervolgwerk, net als bij de vorige
  peer-wijzigingen.
- `TODO(#552)` (expliciete trust-store voor PKIoverheid-validatie richting publieke endpoints)
  blijft open: dit plan regelt alleen het anker voor de eigen outway.
