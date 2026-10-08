# Operator-handleiding stelselregister

Het stelselregister publiceert het magazijnregister als ondertekend stelseldocument. Wat een
afnemer ermee doet, staat in [het toepassingsprofiel](stelseldocument-toepassingsprofiel.md); dit
document is voor wie de dienst uitrolt en de sleutel beheert.

De dienst verwerkt geen persoonsgegevens, heeft geen database en roept niets aan. Het enige
geheim is de ondertekensleutel.

## Configuratie

| Variabele | Verplicht | Betekenis |
|---|---|---|
| `STELSELDOCUMENT_UITGEVER_OIN` | ja | OIN van de stelselbeheerder; komt als `iss` in het document en moet gelijk zijn aan `subject.serialNumber` van het ondertekencertificaat |
| `STELSELDOCUMENT_OMGEVING` | ja | komt als `environment` in het document (`demo`, `productie`, …) |
| `STELSELDOCUMENT_KEYSTORE_PAD` | ja | pad naar de PKCS#12-keystore |
| `STELSELDOCUMENT_KEYSTORE_WACHTWOORD` | ja | wachtwoord van de keystore; zonder backslash, zodat het ongewijzigd door een env-var komt |
| `STELSELDOCUMENT_KEYSTORE_ALIAS` | nee | alias van de ondertekensleutel; standaard `stelseldocument` |
| `MAGAZIJN_A_URL`, `MAGAZIJN_B_URL` | ja | de **publieke** adressen van de twee vaste magazijnen, nooit die van een outway |
| `SMALLRYE_CONFIG_LOCATIONS` | nee | extra registerregels (`magazijnen."<OIN>".{url,naam}`), zoals de gesimuleerde magazijnen van de demo |
| `HTTP_TLS_TERMINATION` | bij een ingress | `mesh` als de ingress TLS termineert; anders verwacht de dienst zelf een TLS-keystore |
| `SECURITY_TXT_URL` | nee | doel van `/.well-known/security.txt`; standaard het centrale bestand van het NCSC |

Geldigheid (`stelseldocument.geldigheid`, 24 uur) en ververs-interval
(`stelseldocument.verversen`, 1 uur) zijn instelbaar; de geldigheid moet langer zijn dan het
interval en mag niet boven de 24 uur uitkomen, anders start de dienst niet. Een afnemer weigert
een document dat langer geldt.

**Het register staat in de config van elke dienst die het leest.** De berichtenuitvraag en het
stelselregister moeten dezelfde organisaties noemen. Voor de twee vaste magazijnen bewaakt een
test dat; een registerbestand dat via `SMALLRYE_CONFIG_LOCATIONS` wordt meegegeven, moet op beide
plekken hetzelfde zijn. Een wijziging komt in het document na een herstart van de dienst.

## Wanneer de dienst niet start

Alles wat een document zou opleveren dat geen afnemer kan verifiëren, blokkeert de start. De
melding in de log noemt de oorzaak:

| Melding | Oorzaak |
|---|---|
| `stelseldocument.keystore.pad ontbreekt` | geen keystore geconfigureerd; alleen een ontwikkel- of testbuild valt dan terug op een wegwerpketen |
| `bestaat niet of is niet leesbaar` | het pad klopt niet, of de gebruiker van de container mag het bestand niet lezen |
| `niet te openen` | verkeerd wachtwoord, of het bestand is geen PKCS#12 |
| `geen sleutel onder alias` | de alias klopt niet |
| `geen EC P-256-sleutel` | de sleutel is RSA of een andere curve; ES256 vraagt P-256 |
| `is zelfondertekend` | het ondertekencertificaat is niet door een root uitgegeven |
| `hoort niet bij de sleutel` | certificaat en sleutel zijn geen paar |
| `is niet geldig op` | het ondertekencertificaat is verlopen of nog niet ingegaan |
| `sluit niet of is niet geldig` | de keten valideert niet: een schakel is verlopen, hoort er niet bij, of een tussencertificaat is geen CA |
| `is een CA-certificaat` | onder de alias staat een CA-certificaat in plaats van een eindcertificaat |
| `mist het sleutelgebruik digitalSignature` | het certificaat is niet voor ondertekenen uitgegeven |
| `draagt in subject.serialNumber` | de OIN in het certificaat is niet `STELSELDOCUMENT_UITGEVER_OIN`, of het subject heeft geen of meer dan één `serialNumber` |

De terugval op een wegwerpketen hangt aan de manier waarop de applicatie gebouwd is, niet aan het
profiel waarmee hij start. Een uitgerold image ondertekent dus nooit met een wegwerpsleutel, ook
niet met `QUARKUS_PROFILE=dev`.

## Bewaken

- **Readiness** (`/q/health/ready`, check `stelseldocument`) is `UP` zolang er een onverlopen
  document klaarstaat. De check zegt niets over de sleutel: het pad is publiek bereikbaar.
- **Bij elke uitgifte** staat er een regel `Stelseldocument uitgegeven` in de log met `kid`,
  `version`, `iat`, `exp`, het aantal organisaties en de herkomst van de sleutel
  (`KEYSTORE` of `WEGWERP`). `WEGWERP` hoort in een uitgerolde omgeving nooit voor te komen.
- **Vanaf dertig dagen voor het verlopen** van de certificaatketen waarschuwt de dienst bij elke
  uitgifte, dus elk uur (`De certificaatketen van de ondertekensleutel ... verloopt over`). Koppel
  daar een melding aan. Er is geen uitloop: een document geldt nooit langer dan de keten, dus op
  het moment van verlopen gaat de dienst direct naar `503`.
- **Mislukt het verversen**, dan staat er een `ERROR` `Het stelseldocument is niet ververst`, met
  erbij of er nog een geldend exemplaar is. Zonder geldend exemplaar geeft de dienst `503` en
  meldt hij zich niet meer gereed.
- **Loopt de klok een stukje terug** (hooguit een minuut, de gewone correctie), dan blijft het
  bestaande exemplaar staan tot de klok het inhaalt: een afnemer weigert een exemplaar met een
  oudere `iat` dan het laatste dat hij accepteerde. Er staat dan een `WARN`
  `De klok staat vóór de vorige uitgifte`.
- **Stond de klok bij een uitgifte meer dan een minuut vooruit**, dan accepteert geen afnemer dat
  exemplaar. Zodra de klok weer klopt, telt het niet meer als geldend: de dienst geeft `503` en
  meldt zich niet gereed, tot de eerstvolgende verversing het vervangt. Die verversing logt een
  `ERROR` `Het vorige exemplaar is uitgegeven op ..., in de toekomst`. Een herstart vervangt het
  direct. Dezelfde `503` volgt als de klok ten onrechte meer dan een minuut terugspringt; de dienst
  kan die twee gevallen niet uit elkaar houden.
- **Een adres zonder TLS in het document** geeft bij de start een `WARN`
  `Het stelseldocument wijst voor ... organisatie(s) naar een adres zonder TLS`. Het register
  weigert zulke adressen, behalve onder de profielen `dev` en `test`; in een uitgerolde omgeving
  hoort deze regel dus niet voor te komen.

## Sleutelbeheer

De keten bestaat uit een root en een ondertekencertificaat daaronder. Apps leggen de root vast.

### Maken

Voor de demo-omgeving maakt
[`demo/environment/stelselregister/pki/maak-keten.sh`](../demo/environment/stelselregister/pki/maak-keten.sh)
beide, en bundelt het ondertekencertificaat met zijn sleutel en de root tot `out/keystore.p12`.
Het script zet de OIN van de stelselbeheerder in `subject.serialNumber`.

Voor productie komt het ondertekencertificaat van een erkende uitgever (PKIoverheid); de eisen
aan het certificaat blijven dezelfde, en de dienst dwingt ze bij de start af: EC P-256, precies
één `subject.serialNumber` met de OIN, sleutelgebruik `digitalSignature`, geen CA, en een keten
die volgens RFC 5280 valideert.

### Bewaren

- **De sleutel van de root** (`ca/root.key`) blijft op de machine waar hij gemaakt is, of gaat
  naar een offline medium. Hij hoort niet bij de dienst, niet in CI en niet op het platform.
- **De keystore** gaat als bestand naar de dienst, het wachtwoord als aparte waarde. Op ZAD zijn
  dat een attachment en een env-var in hetzelfde project. Wie het project kan beheren, kan beide
  lezen: het wachtwoord beschermt tegen een los zwervend bestand, niet tegen een projectbeheerder.
  De toegangslijst van het project is daarmee de toegangslijst van de sleutel.
- **Niets hiervan komt in git.** `ca/` en `out/` staan in `.gitignore`.

### Geplande wissel van het ondertekencertificaat

1. `maak-keten.sh --roteer` maakt een nieuw certificaat onder dezelfde root.
2. Wil je dat de sleutelset tijdens de overgang ook de oude sleutel toont, voeg het oude
   certificaat dan als los certificaat aan de nieuwe keystore toe
   (`keytool -importcert -alias vorige ...`).
3. Vervang de keystore bij de dienst en herstart. Apps merken niets: zij vertrouwen de root.

### Noodvervanging bij een gelekte sleutel

1. Maak direct een nieuw certificaat (`--roteer`) en vervang de keystore.
2. Er is geen intrekkingslijst. Wie de gelekte sleutel heeft, kan documenten blijven
   ondertekenen die een afnemer accepteert tot het ondertekencertificaat verloopt. Houd de
   looptijd van dat certificaat daarom kort — `maak-keten.sh` geeft 90 dagen — en overweeg bij
   een ernstig lek een nieuwe root; dat vraagt een nieuwe build van elke app.

### De root van de demo-omgeving

De root die `maak-keten.sh` op een ontwikkelmachine maakt, is van die machine. De root van de
demo-omgeving op ZAD wordt bij de inrichting één keer gemaakt; zijn vingerafdruk hoort daarna
hier te staan en in het toepassingsprofiel:

| Omgeving | SHA-256-vingerafdruk van de root | Gemaakt op |
|---|---|---|
| demo (ZAD) | nog niet ingericht | — |

## Beveiligingscontact

`/.well-known/security.txt` verwijst door naar het centrale bestand van het NCSC. Dat volgt het
advies van het Forum Standaardisatie voor Rijksorganisaties die het centrale CVD-beleid volgen:
contact, vervaldatum en ondertekening blijven zo op één beheerde plek. Een organisatie met een
eigen CVD-beleid zet `SECURITY_TXT_URL` op haar eigen bestand.
