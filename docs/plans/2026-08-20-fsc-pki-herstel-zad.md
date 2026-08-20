# FSC-PKI van de ZAD-peers herstellen

**Status:** Uitgevoerd

## Aanleiding

De contract-bootstrap-componenten (`logius-fscbootstrap` in `mpfb-8wh`, `magazijna-fscbootstrap`
in `mpfm-w3h`) kwamen niet langs de mTLS naar hun eigen manager:

```
FAIL: kon de eigen contractenlijst niet ophalen: curl: (56) OpenSSL SSL_read:
error:0A000418:SSL routines::tlsv1 alert unknown ca
```

De aanname bij de overdracht was dat het bootstrap-cert uit de verkeerde werkkopie kwam en dat
`pki/issue.sh` zonder `-f` in de "juiste" werkkopie het zou oplossen. Die werkkopie bestaat niet.

## Wat er werkelijk aan de hand was

De manager-ingress draait TLS-passthrough, dus het cert dat de cluster serveert is van buiten af
leesbaar. Dat gaf de doorslag:

| | cluster serveert | elke lokale `pki/` |
|---|---|---|
| group-cert logius-manager | `notBefore Aug 6 13:30`, `91:DC:70…` | `Aug 17 06:15`, `CF:C2:6E…` |
| group-cert magazijna-manager | `Aug 5 07:51`, `6C:45:A9…` | idem her-uitgegeven op 17 aug |

Een scan over álle `*.pem` onder `/home/claude` en `/tmp` vond de cluster-certs nergens. Op
17 augustus is de `pki/` van beide peers opnieuw gegenereerd; de internal-CA-sleutel die de
draaiende manager-, controller-, inway-, outway- en txlog-certs tekende bestond niet meer. Er viel
dus geen bootstrap-cert uit te geven dat de manager zou accepteren — geen pad- of configfout maar
verloren sleutelmateriaal.

Daaronder lag een tweede fout. In `demo/environment/{logius,magazijn-a}/pki/ca/` stond een
zelfgemaakte group-CA van 17 augustus, terwijl de cluster én de directory (`dirmgr-test-mft-tp9`)
ketenen naar de group-root van het testnet (2 juli, `EB:EE:D6…`, geverifieerd met `openssl verify`).
Iemand had `init-ca.sh` gedraaid, wat `pki/README.md` en `deploy/zad/cert-manifest.md` expliciet
verbieden voor een peer die op de fsc-testnet-directory is aangesloten. Elke `issue.sh` daar
produceerde vanaf dat moment group-certs die de directory afwijst.

Dat de group-CA weg was, maakte ook de tweede blokkade onoplosbaar-op-zichzelf:
`FSC_OUTWAY_THUMBPRINT` is de SPKI-SHA256 van het group-cert van de eigen outway, en de kopie op de
cluster is onleesbaar — de outway-ingress termineert op het rig-wildcard (geen passthrough) en de
OM-API kent geen `GET` op attachment-inhoud, alleen `POST`/`PUT`/`DELETE`.

## Wat wél te redden was

De echte group-CA staat compleet — root, intermediate én beide sleutels — in
`moza-fsc-testnet/pki/ca/`. Daarmee zijn nieuwe group-leafs uit te geven die de directory accepteert.
De internal-CA is per peer self-signed en dus vrij te vervangen, zolang álle internal-certs van die
peer in één keer meegaan.

## Uitgevoerd

1. `moza-fsc-testnet/pki/ca/` (root + intermediate + keys + CRL) teruggezet in de `pki/ca/` van
   beide peers.
2. `pki/issue.sh -f` per peer: verse internal-CA, verse internal-certs, en group-leafs getekend
   door de échte intermediate. De csr's dragen de ZAD-Service-DNS al (`gen-csr.sh` leidt die af uit
   `ZAD_PROJECT`/`ZAD_DEPLOYMENT`), dus de SAN's klopten zonder ingrijpen.
3. `pki/zad-bundle.sh <peer>`.
4. Attachments vervangen met `--no-rollout`, daarna één `zadctl project refresh` per project — 19 in
   `mpfb-8wh`, 15 in `mpfm-w3h`. De group-root (`ca-root` / `ca-root-cert`) bleef ongemoeid: die
   ís de echte federatie-root. Het postgres-initscript ook.
5. `FSC_OUTWAY_THUMBPRINT` op `logius-fscbootstrap` gezet op de SPKI van het verse outway-cert.
6. Na het tekenen strandde de accept-push naar de consumer. De gedocumenteerde uitweg toegepast:
   `FSC_FORCEER_DISTRIBUTIE=1` op het provider-component, één ronde, daarna weer weg.

De koppeling attachment → bronbestand kwam voor `mpfm-w3h` uit het `path`-veld dat ZAD zelf
teruggeeft (`/etc/fsc/<rel>` → `<upload-set>/<rel>`), niet uit een handgeschreven tabel: een
referentienaam zegt niets over waar het bestand landt, het pad wel.

## Ontwerpkeuzes

**Group-leafs mee vervangen, group-root niet.** Alleen de internal-PKI vervangen zou de mTLS naar de
manager repareren maar de outway-thumbprint onbekend laten, en daarmee de consumer-helft
onbruikbaar. De root vervangen was juist onnodig én riskant: die is al goed, en hem aanraken kost
alleen een herstart.

**Geen weg terug.** Attachment-inhoud is write-only in de OM-API, dus de set van 5/6 augustus is
definitief weg. Daarom is vóór de eerste upload gecontroleerd dat elke keten klopt, dat de
internal-certs de cluster-Service-DNS in hun SAN's dragen, dat group-certs twee PEM-blokken hebben
(leaf + intermediate) en internal-certs één, en dat het bootstrap-endpoint wél een internal- en géén
group-cert heeft.

**`init-ca.sh` weigert nu een bestaande CA te overschrijven** (`-f` om het toch te doen). De
waarschuwing stond al in twee documenten; dat heeft deze storing niet voorkomen. De guard toont de
subject/geldigheid van de CA die er staat, zodat zichtbaar is wát er weggegooid zou worden.

## Verificatie

- `openssl verify` per group-cert tegen de federatie-root en per internal-cert tegen de verse
  internal-root: 0 fouten over beide peers.
- Beide managers serveren na de rollout hun nieuwe cert (`3D:39:BC:7E…` resp. `05:80:AC:17…`).
- `magazijna-fscbootstrap`: `PROVIDER OK (1 contract(en) getekend)`.
- `logius-fscbootstrap`: `CONSUMER OK (bestaand contract …)` op diezelfde hash — de keten is rond.
- Beide accept-handtekeningen op dat contract zijn gezet met de hùidige certificaten (gecontroleerd
  via de `x5t#S256` in de JWS-headers tegen de lokale certs); de handtekeningen op de oudere
  contracten dragen nog de certificaten van vóór de rotatie.
- De directory accepteert het nieuwe group-cert als client-cert en kent beide peers met het juiste
  manager-adres — de group-vervanging is dus federatie-breed geaccepteerd.
- `uitvraag` herstart schoon op het vervangen trust anchor
  (`Uitgaand outway-verkeer gebruikt de TLS-configuratie 'outway' als trust anchor`).
- Beide deployments `Healthy`, 0 pending changes.
- **Autorisatieketen bewezen zonder de applicatie aan te raken:** met het outway-group-cert een
  token opgevraagd bij de manager van de provider (`POST /v1/token`, client-credentials, scope = de
  grant-hash). Die geeft een `RS512`-token uit voor dienst `berichtenmagazijn`, met de inway als
  `aud` en een `cnf.x5t#S256` die overeenkomt met het huidige outway-cert. Daarmee zijn contract,
  publicatie én thumbprint-binding in één keer getoetst — geen testverzoek, geen BSN, geen
  LDV-regels.

> Bij het narekenen: `zadctl logs` liep merkbaar achter op de pod. Het contract was al geldig
> terwijl het commando nog minutenlang hetzelfde `CONSUMER WACHT`-staartje teruggaf. Neem bij twijfel
> de manager-API als waarheid (`GET /v1/contracts` op poort 443 van de manager-ingress, met een
> group-cert als client-cert) in plaats van de log.

## Afgerond na het herstel

Een sleutelrotatie raakt méér dan de peer zelf: een grant bindt aan de publieke sleutel van de
outway, dus élk contract dat naar de oude thumbprint (`28ff98f0…`) wees was dood. Voor
`berichtenmagazijn` loste de bootstrap dat vanzelf op door een nieuw contract in te dienen; voor
alles zonder bootstrap was het handwerk. Diezelfde dag afgehandeld op de omgeving:

- Dienst `berichtenmagazijn` gepubliceerd op de magazijna-controller. Die stap is nergens
  geautomatiseerd — niet in `upsert-peer.sh`, niet in CI — en was bij de hernoeming vanaf
  `berichtenmagazijn-a` blijven liggen. Zonder publicatie geeft de provider geen token uit, dus een
  geldig contract alleen is niet genoeg. De directory tekent de publicatie automatisch.
- De drie `berichtenmagazijn-a`-contracten (twee connection-grants plus de publicatie) ingetrokken.
  Zolang die stonden eindigde elke provider-ronde op uitgang 4, wat als wachten telt: na
  `FSC_LUS_MAX_WACHT` rondes op het uurinterval was het component in crashloop gegaan.
- Nieuw `profieldienst`-contract aangemaakt op de huidige outway-thumbprint; het oude is verdwenen
  bij de manager. `MAGAZIJN_A_GRANT_HASH` en `PROFIEL_SERVICE_GRANT_HASH` op de `test`-deployment
  wijzen naar de nieuwe grants — een grant-hash (`$1$3$…`) is iets anders dan een contract-hash
  (`$1$1$…`).
- De achtergebleven service `berichtenmagazijn-a` uit de magazijna-controller verwijderd. Beide
  peers bieden nu precies aan wat er in de directory staat.

## Open punten

- Lokaal en cluster lopen weer gelijk, maar niets bewáákt dat. Een `verify.sh`-variant die de
  fingerprint van het lokale group-cert vergelijkt met wat de manager-ingress serveert, zou deze
  storing binnen een dag zichtbaar hebben gemaakt in plaats van na twee weken.
- De dienstpublicatie is handwerk zonder herinnering. Ze viel hier stil weg bij een hernoeming en
  bleef twee weken onopgemerkt, omdat een ontbrekende publicatie pas zichtbaar wordt op het moment
  dat er verkeer komt. `upsert-peer.sh` zou 'm kunnen zetten, of `verify.sh` kunnen toetsen.
