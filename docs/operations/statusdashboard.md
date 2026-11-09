# Statusdashboard

De onderdelen van de berichtenbox-keten op de testomgeving staan op het gezamenlijke statusdashboard
van MijnOverheid Zakelijk:
<https://gatus-stable-mozag-on9.rig.prd1.gn2.quattro.rijksapps.nl>, onder de groep `fbs-test`. Het
dashboard is [Gatus](https://github.com/TwiN/gatus), ingericht in
[`MinBZK/moza-gatus`](https://github.com/MinBZK/moza-gatus); onze endpoints staan daar in
`config/fbs-test/endpoints.yaml`.

Bij een vastgelopen deployment is het dashboard de eerste blik: welk onderdeel valt weg, en sinds
wanneer. Het onderzoek daarna loopt via [`zad-gitops.md`](zad-gitops.md).

## Meldingen

Valt een onderdeel uit, dan plaatst Gatus een melding in het Mattermost-kanaal van moza-gatus; bij
herstel volgt een tweede. Dat kanaal delen we bewust met de andere MOZa-teams, zodat we elkaar
kunnen helpen. <!-- TODO: wie reageert er op een melding -->

Een controle loopt elke twee minuten en meldt na drie mislukte op rij: een storing staat er na vier
tot zes minuten, en een uitrol die een paar minuten `503` geeft, levert geen melding op. Alleen
deployment `test` staat erop; previews (`pr-<n>`) zijn tijdelijk en blijven buiten beeld.

## Wat er gecontroleerd wordt

| Endpoint | Component | Pad | Geslaagd als |
|---|---|---|---|
| `fbs uitvraag` | `uitvraag` (`mpfb-8wh`) | `/q/health/ready` | 200 en `status` = `UP` |
| `fbs magazijn-a`, `fbs magazijn-b` | `magazijna`, `magazijnb` (`mpfm-w3h`) | `/q/health/ready` | 200 en `status` = `UP` |
| `fbs magazijn-simulator` | `magazijnsimulator` (`mpfm-w3h`) | `/q/health/ready` | 200 en `status` = `UP` |
| `fbs demo-personas` | `demopersonas` (`mpfm-w3h`) | `/q/health/ready` | 200 en `status` = `UP` |
| `fbs stelselregister` | `stelselregister` (`mpfs-rab`) | `/q/health/ready` | 200 en `status` = `UP` |
| `fbs profiel-stub`, `fbs notificatie-stub` | `profiel`, `notificatie` (`mpfpsm-lcl`) | `/gezondheid` | 200 en `status` = `healthy` |
| `fbs berichtenbox` | `proeftuin` (`mpfm-w3h`) | `/` | 200 en de titel `MijnOverheid Zakelijk` |

Readiness en niet een willekeurig pad: readiness telt de opslag van een dienst mee (Redis,
PostgreSQL), dus een dienst die draait maar zijn werk niet kan doen, telt als storing. Een `UP`-antwoord
noemt alleen de namen van de checks. Een `DOWN`-antwoord draagt de foutmelding van de verbinding, met
host en poort; publiek komt dat nauwelijks mee, want een NotReady-pod valt uit de endpoints en de
router antwoordt dan zelf met `503`.

Bewust niet gecontroleerd:

- **`democonsole`** staat achter de authorization-wall; elk pad geeft zonder sessie `403` met de
  inlogpagina, en een muur die antwoordt zegt niets over het paneel erachter.
- **`redis`** en de **Toxiproxy's** hebben geen publieke route of geen eigen functie: hun uitval
  zie je aan de uitvraag. De Toxiproxy's controleren zou bovendien elke storingsknop als storing
  melden.
- **De FSC-peers** (`fsc-logius`, `fsc-magazijna`) spreken mTLS-passthrough; Gatus heeft daar geen
  clientcertificaat voor.

## De Redis-storingsknop

De Redis-knop in het demopaneel maakt de uitvraag bewust NotReady, waarna de router op elk pad
`503` geeft (zie "Readiness op de uitvraag zakt mee met de berichtenopslag" in
`demo/environment/zad-demo/README.md`). Van buitenaf is dat een echte storing, en Gatus meldt hem.
Daarom kondigt het paneel hem vooraf aan in hetzelfde kanaal, onder de afzender **FBS Demo**: bij
`traag` of `uit` dat de melding verwacht is, bij het opheffen dat de herstelmelding volgt.

Dat gebeurt alleen op `test`. De webhook staat als env-override op die deployment, en het paneel
vergelijkt bovendien zijn `$DEPLOYMENT_NAME` met `test`; inrichten staat in hoofdstuk 6 van
`demo/environment/zad-demo/README.md`. De andere storingsknoppen raken geen gecontroleerde
readiness en kondigen niets aan.

## Een onderdeel toevoegen

1. Kies het pad. Een Quarkus-dienst: `/q/health/ready`, met `[BODY].status == UP`. Iets zonder
   health-pad: een pad dat zonder sessie 200 geeft, plus een conditie op de inhoud, zodat een
   foutpagina met 200 niet als gezond telt.
2. Controleer dat het pad publiek antwoordt. Gatus draait in een eigen ZAD-project en komt alleen
   bij een publieke route op 443:
   `https://<component>-test-<project-id>.rig.prd1.gn2.quattro.rijksapps.nl<pad>`.
3. Voeg in `MinBZK/moza-gatus` een endpoint toe aan `config/fbs-test/endpoints.yaml` (naam
   `fbs <onderdeel>`, `group: fbs-test`, zelfde `interval` en alert-instellingen als de rest) en
   zet de naam in `EXPECTED_ENDPOINTS` in `test/smoke.sh`; anders faalt de smoke test.
4. Kan een storingsknop in het demopaneel de nieuwe controle laten falen, breid dan `GERAAKT` in
   `StoringAankondiging` uit, anders levert die knop een onaangekondigde melding op.
5. Werk de tabel hierboven bij.
