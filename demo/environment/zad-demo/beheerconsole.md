# De beheerconsole op ZAD

De beheerconsole draait in een eigen project, `fb-m6c` ("FBS Beheerconsole"), met één component
`beheerconsole` op poort 8093. Een eigen project en geen component bij het stelselregister: de
toegangsmuur van ZAD geldt voor een heel project. De console krijgt schermen met niet-openbare
gegevens (magazijnen beheren, inzage in het logboek) en moet dan achter die muur kunnen, terwijl het
stelselregister publiek moet blijven.

Wat de console doet staat in [`services/beheerconsole/README.md`](../../../services/beheerconsole/README.md)
en in het plan [`docs/plans/2026-11-27-beheerconsole.md`](../../../docs/plans/2026-11-27-beheerconsole.md).
Dit bestand is de eenmalige inrichting.

`zadctl project use` wist de API-key van het actieve project uit `.env.zadctl`. Werk voor dit
project daarom vanuit een eigen map met een eigen `.env.zadctl`, niet vanuit de root van de repo.

**Doe dit niet terwijl er een uitrol loopt** (`gh run list --workflow "Deploy ZAD"`): Operations
Manager vergrendelt op project.

## Wat er staat

Ingericht op 2026-11-27:

- project `fb-m6c`, aangemaakt met `zadctl project create "FBS Beheerconsole"`. De projectsleutel
  uit dat antwoord staat als repo-secret `ZAD_API_KEY_BEHEERCONSOLE`;
- component `beheerconsole`, poort 8093, geheugenlimiet 384 MiB;
- de env-namen hieronder, de alias, de attachment met de root, de health-check en `publish-on-web`;
- deployment `test`, nog zonder component: de eerste uitrol naar main zet het component erin met zijn image.

Poorten zijn alleen bij het aanmaken van een component te zetten. Health staat op dezelfde poort
als de app; de beheerpoort (28093) heeft geen route.

## 1. Env

```bash
zadctl component add beheerconsole --port 8093 --memory-limit 384Mi \
  -e HTTP_TLS_TERMINATION=mesh \
  -e STELSELDOCUMENT_UITGEVER_OIN=00000000000000001000 \
  -e STELSELDOCUMENT_OMGEVING=demo
```

Uitgever en omgeving zijn die waarmee het stelselregister van deze omgeving ondertekent
([`stelselregister.md`](stelselregister.md), hoofdstuk 3). `HTTP_TLS_TERMINATION=mesh` zegt dat de
ingress TLS termineert.

## 2. Het stelselregister

Een alias, omdat alleen een alias `$DEPLOYMENT_NAME` kent: een preview leest zo het
stelseldocument van het stelselregister van dezelfde preview. Tussen projecten loopt verkeer alleen
over de publieke route, dus https:

```bash
Z=rig.prd1.gn2.quattro.rijksapps.nl
zadctl alias add -c beheerconsole \
  "STELSELREGISTER_URL=https://stelselregister-\$DEPLOYMENT_NAME-mpfs-rab.$Z"
```

## 3. De root van de stelselbeheerder

De publieke root uit [`stelselregister.md`](stelselregister.md), hoofdstuk 1 (`out/root.pem`), als
env-var. Zonder root weigert de console buiten dev en test te starten.

```bash
zadctl attachment add stelsel-root --from-file demo/environment/stelselregister/pki/out/root.pem
zadctl attachment assign stelsel-root -c beheerconsole \
  --provide-as env-var --env-name STELSELDOCUMENT_ROOT_PEM --yes
```

De vingerafdruk moet gelijk zijn aan die in de
[operator-handleiding van het stelselregister](../../../docs/operator-handleiding-stelselregister.md):
`openssl x509 -in <bestand> -noout -fingerprint -sha256`.

## 4. Health-check en publiek adres

```bash
zadctl service config set health-check -c beheerconsole \
  --set scheme=http --set port=8093 \
  --set liveness-path=/q/health/live --set readiness-path=/q/health/ready
zadctl service assign publish-on-web -c beheerconsole
```

Dezelfde probe staat in `gezondheidscontrole.sh` hiernaast.

## De eerste uitrol

Een draft-PR bouwt geen image en krijgt geen preview. Loop bij de eerste run (zodra de PR ready for
review staat) deze punten na:

- **Het ghcr-pakket `fbs-beheerconsole` moet publiek zijn**, net als de andere images. Een nieuw
  pakket kan privé beginnen; dan kan ZAD het niet trekken.
- **De eerste preview kloont een lege `test`.** Env, alias, attachment en health-check staan op het
  component en gaan dus mee; het image geeft de action zelf mee. Faalt de kloon toch, zet het
  component dan met de hand in `test`: `zadctl deployment create test -c beheerconsole --image <image>`.
- **Komt de attachment mee in een preview?** Zo niet, dan start de preview niet ("root ontbreekt").
- **Na de merge hangt elke PR naar main aan dit vijfde project.** `uitrol-poort` is verplicht;
  controleer direct na de merge `deploy-test-beheerconsole`.

Daarna:

```bash
U=https://beheerconsole-test-fb-m6c.rig.prd1.gn2.quattro.rijksapps.nl
curl -s "$U/q/health/ready"
curl -s "$U/api/v1/stelsel" | jq '{status: .verificatie.status, organisaties: (.organisaties | length)}'
```

De status hoort `GELDIG` te zijn, met evenveel organisaties als het stelselregister publiceert.

## Opruimen van previews

`cleanup-preview.yml` ruimt de preview in dit project op zodra de leg voor `fb-m6c` op main staat.
Previews van de PR die dit project introduceert, ruim je met de hand op
(`zadctl deployment delete pr-<n>`). Het project heeft geen database.
