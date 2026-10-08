# Het stelselregister op ZAD

Het stelselregister draait in een eigen project, `mpfs-rab`, met één component `stelselregister`
op poort 8094. Een eigen project en geen component in een bestaand project, om twee redenen: de
ondertekensleutel blijft buiten de andere projecten, en het project kan publiek staan — de
toegangsmuur van ZAD is projectbreed, en dit document is juist bedoeld om door iedereen gelezen
te worden.

Wat de dienst doet staat in
[`docs/stelseldocument-toepassingsprofiel.md`](../../../docs/stelseldocument-toepassingsprofiel.md);
sleutelbeheer en foutmeldingen in
[`docs/operator-handleiding-stelselregister.md`](../../../docs/operator-handleiding-stelselregister.md).
Dit bestand is de eenmalige inrichting.

`zadctl project use` wist de API-key van het actieve project uit `.env.zadctl`. Werk voor dit
project daarom vanuit een eigen map met een eigen `.env.zadctl`, niet vanuit de root van de repo.

**Doe dit niet terwijl er een uitrol loopt** (`gh run list --workflow "Deploy ZAD"`): Operations
Manager vergrendelt op project.

## Wat er al staat

Bij het aanmaken van het project, op 2026-10-08:

- project `mpfs-rab`, component `stelselregister`, poort 8094, service `publish-on-web`;
- de env-namen `STELSELDOCUMENT_KEYSTORE_ALIAS`, `STELSELDOCUMENT_KEYSTORE_PAD` en
  `STELSELDOCUMENT_KEYSTORE_WACHTWOORD`;
- deployment `test`, nog zonder image;
- de repo-secret `ZAD_API_KEY_STELSELREGISTER`.

Poorten zijn alleen bij het aanmaken van een component te zetten. Health staat daarom op dezelfde
poort als de API; een aparte beheerpoort kan niet meer zonder het component opnieuw te maken.

## 1. De sleutelketen

Eén keer, op een machine die de sleutel van de root mag bewaren:

```bash
STELSELDOCUMENT_OMGEVING=demo demo/environment/stelselregister/pki/maak-keten.sh
```

Dat levert onder `demo/environment/stelselregister/pki/`:

- `ca/root.key` — blijft op deze machine. **Niet uploaden.**
- `out/keystore.p12` en `out/wachtwoord` — gaan naar ZAD.
- `out/root.pem` — gaat naar wie een app bouwt.

Zet de vingerafdruk die het script afdrukt in de operator-handleiding en in het
toepassingsprofiel: dat is de waarde waaraan een app-bouwer de root controleert.

## 2. De keystore

```bash
zadctl attachment add stelsel-keystore \
  --from-file demo/environment/stelselregister/pki/out/keystore.p12
zadctl attachment assign stelsel-keystore -c stelselregister \
  --provide-as file --mount-path /etc/stelselregister/keystore.p12

zadctl env set -c stelselregister \
  STELSELDOCUMENT_KEYSTORE_PAD=/etc/stelselregister/keystore.p12 \
  STELSELDOCUMENT_KEYSTORE_ALIAS=stelseldocument \
  "STELSELDOCUMENT_KEYSTORE_WACHTWOORD=$(cat demo/environment/stelselregister/pki/out/wachtwoord)"
```

Een attachment is na het uploaden niet meer terug te lezen en mag hooguit 64 KB zijn; de keystore
is een paar kilobyte.

**Nog niet beproefd:** of een binair bestand ongewijzigd als attachment aankomt. De eerste start
laat het zien. Meldt de dienst `niet te openen` terwijl het wachtwoord klopt, dan is het bestand
onderweg veranderd; de terugval is een sleutel en een keten in PEM, en dat vraagt een aanpassing
aan de dienst.

## 3. Uitgever, omgeving en TLS

```bash
zadctl env set -c stelselregister \
  STELSELDOCUMENT_UITGEVER_OIN=00000000000000001000 \
  STELSELDOCUMENT_OMGEVING=demo \
  HTTP_TLS_TERMINATION=mesh
```

`00000000000000001000` is de test-OIN die de FSC-peer `logius` in deze omgeving al voert. De
echte OIN van Logius hoort pas in een document onder een echte root. `HTTP_TLS_TERMINATION=mesh`
zegt dat de ingress TLS termineert; zonder die waarde verwacht de dienst zelf een TLS-keystore en
start hij niet.

## 4. Het register

De dienst moet dezelfde magazijnen noemen als de berichtenuitvraag. De adressen zijn de
**publieke** adressen van de magazijnen: een app bereikt ze van buiten. Alle drie zijn aliassen,
omdat alleen een alias `$DEPLOYMENT_NAME` kent en een preview zo naar de magazijnen van dezelfde
preview wijst:

```bash
Z=rig.prd1.gn2.quattro.rijksapps.nl
zadctl alias add -c stelselregister \
  "MAGAZIJN_A_URL=https://magazijna-\$DEPLOYMENT_NAME-mpfm-w3h.$Z" \
  "MAGAZIJN_B_URL=https://magazijnb-\$DEPLOYMENT_NAME-mpfm-w3h.$Z" \
  "MAGAZIJN_SIMULATOR_URL=https://magazijnsimulator-\$DEPLOYMENT_NAME-mpfm-w3h.$Z"
```

De gesimuleerde magazijnen komen uit hetzelfde bestand als bij de uitvraag
(`magazijn-simulator.md`, hoofdstuk 3). Genereer het met dezelfde aanroep, zodat beide diensten
dezelfde set dragen:

```bash
zadctl attachment add magazijnen-register \
  --from-file demo/generated/magazijnen-register.properties
zadctl attachment assign magazijnen-register -c stelselregister \
  --provide-as file --mount-path /config/magazijnen-register.properties
zadctl env set -c stelselregister \
  SMALLRYE_CONFIG_LOCATIONS=/config/magazijnen-register.properties
```

Het bestand draagt `${MAGAZIJN_SIMULATOR_URL}` in elk adres; daarom eerst de alias, dan het
bestand. Wordt het register bij de uitvraag vervangen, vervang het dan ook hier
(`zadctl attachment update magazijnen-register ...`): niets bewaakt dat de twee gelijk blijven.

## 5. Health-check

```bash
zadctl service config set health-check -c stelselregister \
  --set scheme=http --set port=8094 \
  --set liveness-path=/q/health/live --set readiness-path=/q/health/ready
```

Readiness is `UP` zolang er een onverlopen stelseldocument klaarstaat. Dezelfde regel staat in
`gezondheidscontrole.sh` hiernaast, dat de probes van alle componenten in één keer terugzet.

## 6. Geheugen

Het project is aangemaakt met een limiet van 256 MiB. Lokaal gebruikt de dienst na de start rond
200 MiB. Laat de eerste uitrol een dag draaien en stel de limiet daarna bij op gemeten gebruik
(`zadctl resource tune --dry-run`), zoals bij de andere componenten.

Houd het component op één replica: elke instantie ondertekent op haar eigen moment, en een
afnemer weigert een exemplaar met een oudere `iat` dan het vorige
([operator-handleiding](../../../docs/operator-handleiding-stelselregister.md)).

## De eerste uitrol

Een draft-PR bouwt geen image en krijgt geen preview. De hele keten — de jib-build van het nieuwe
image, het trekken ervan door ZAD, het klonen van `test` — draait dus voor het eerst zodra de PR
ready for review staat. Loop dan deze punten na, en merge pas na een groene run:

- **Het ghcr-pakket `fbs-stelselregister` moet publiek zijn**, net als de andere images. Een
  nieuw pakket kan privé beginnen; dan kan ZAD het niet trekken. Zet het na de eerste build op
  public (Package settings → Change visibility).
- **Na de merge hangt elke PR naar main aan dit vierde project.** `uitrol-poort` is een verplichte
  check: faalt de preview hier structureel, dan staat hij voor elke PR rood. Controleer daarom
  direct na de merge `deploy-test-stelselregister`, vóór andere PR's opnieuw draaien.

Het image komt uit de deploy-workflow: een preview per PR, `test` bij een merge naar main. De
allereerste preview van een PR kloont `test`, en `test` heeft dan nog geen image. Controleer
daarom bij die eerste preview twee dingen die op ZAD niet eerder zijn beproefd:

1. **Krijgt een `pr-<n>` de attachments van `test` mee?** Zo niet, dan start de preview niet
   (`bestaat niet of is niet leesbaar`) en moeten de attachments per deployment worden toegewezen.
2. **Komt de keystore ongewijzigd aan?** Zie hoofdstuk 2.

Daarna, via het publieke adres:

```bash
U=https://stelselregister-test-mpfs-rab.rig.prd1.gn2.quattro.rijksapps.nl

curl -s "$U/q/health/ready"
curl -s "$U/api/v1/stelseldocument" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{iss, environment, aantal: (.organizations | length)}'
curl -sI "$U/.well-known/security.txt" | grep -i '^location'
curl -sv --tlsv1.3 -o /dev/null "$U/api/v1/stelseldocument" 2>&1 | grep -i 'SSL connection'
```

Het aantal organisaties hoort gelijk te zijn aan dat van de uitvraag in dezelfde deployment. De
handtekening controleer je met de root uit hoofdstuk 1, volgens de vijf stappen in het
toepassingsprofiel.

## Opruimen van previews

`cleanup-preview.yml` ruimt de preview in dit project mee op zodra de leg voor `mpfs-rab` op main
staat. Die workflow draait altijd de versie van main: de previews van de PR die dit project
introduceert, ruim je met de hand op (`zadctl deployment delete pr-<n>`). Het project heeft geen
database, dus daar gaat niets mee verloren.
