# Toepassingsprofiel stelseldocument

Dit document legt vast hoe het Federatief Berichtenstelsel (FBS) het stelseldocument uit
[BK Connect draft-00](https://vorijk.nl/standaard/connect/draft-bk-connect-00.html) invult. De
standaard laat pad, vorm van de handtekening, sleuteldistributie en geldigheidsduur aan een
toepassingsprofiel (sectie 2.2.2).

**Dit is het stelseldocument-deel van dat profiel, niet het hele profiel.** Sectie 2.2.2 vraagt
van een toepassingsprofiel ook welke diensten door welke partijen geleverd worden, welke
implementatievariant geldt en welke aanvullende afspraken er zijn. Die delen bestaan nog niet;
ze staan onderaan bij de afwijkingen.

| | |
|---|---|
| Versie van dit document | 0.1 |
| Gelezen tekst van de standaard | draft-00, zoals gepubliceerd op 2026-10-08 |
| Stelselbeheerder | Logius |
| Dienst | `services/stelselregister` |
| Machineleesbare beschrijving | `/openapi.json` van de dienst |

Draft-00 is een werkversie. Een volgende draft die hiermee breekt, leidt tot een nieuwe versie
van dit profiel; de afwijkingen onderaan gaan als terugkoppeling naar de auteurs.

## Wat het document is

Het stelseldocument noemt de organisaties die aan het stelsel deelnemen en het adres van hun
berichtenmagazijn. De stelselbeheerder ondertekent het. Een app die berichten rechtstreeks bij de
magazijnen ophaalt, leest hier welke magazijnen er zijn en controleert aan de handtekening dat de
lijst van de stelselbeheerder komt.

Het document is een **ondertekende adreslijst**. Het bevat nog geen sleutels van de deelnemende
organisaties. Een app mag er daarom geen vertrouwen in een magazijn aan ontlenen: de verbinding
met een magazijn is zo betrouwbaar als de TLS eronder.

## Ophalen

| | |
|---|---|
| Pad | `GET /api/v1/stelseldocument` |
| Mediatype | `application/jose` |
| Authenticatie | geen; het document is publiek |
| Cache | `Cache-Control: public, max-age=300`, zwakke `ETag`, `If-None-Match` geeft `304` |
| CORS | elke origin, zonder credentials |

Het antwoord is een JWS in compacte serialisatie (RFC 7515): `header.payload.signature`, elk deel
base64url zonder opvulling. De standaard eist dat het stelseldocument JSON is; in dit profiel is
de **payload** dat JSON-document en is de JWS de envelop eromheen.

## Header

Precies vier velden. Een document met een ander of een extra veld is ongeldig.

| Veld | Waarde |
|---|---|
| `alg` | `ES256` (ECDSA met P-256 en SHA-256) |
| `typ` | `stelseldocument+jwt` |
| `kid` | RFC 7638-thumbprint van de ondertekensleutel |
| `x5c` | de certificaatketen van het ondertekencertificaat, zonder de root; het ondertekencertificaat staat voorop |

`jku`, `jwk`, `x5u` en `crit` komen niet voor. Een afnemer weigert een document dat er een
bevat en haalt nooit een sleutel op via zo'n veld. `kid` is er voor wie de sleutel in de
sleutelset wil terugvinden; bij de verificatie speelt het geen rol.

## Payload

| Veld | Betekenis |
|---|---|
| `iss` | OIN van de stelselbeheerder |
| `iat` | moment van uitgifte (seconden sinds 1970) |
| `exp` | moment waarna het document niet meer geldt |
| `version` | kenmerk van de inhoud; gelijk bij gelijke inhoud. Zie "Versienummering" |
| `environment` | de omgeving waarvoor het document is uitgegeven |
| `organizations` | de deelnemende organisaties, gesorteerd op OIN: `oin`, `name`, `magazijn_url` |
| `app_managers` | de toegelaten app-beheerders; nog leeg |
| `document_types` | de soorten documenten in dit stelsel; nu alleen `bericht` |

`magazijn_url` is het publieke adres van het berichtenmagazijn van de organisatie. De standaard
toont in zijn voorbeeld een `discovery_url` naar een vindbaarheidsconfiguratie; die laag bestaat
in FBS nog niet, en het veld draagt daarom de naam van wat het werkelijk is.

De veldnamen zijn Engels en snake_case omdat ze uit de standaard komen.

## Versienummering

De standaard eist dat het stelseldocument versienummering ondersteunt (4.1.1.2), zonder te zeggen
hoe. Hier vullen twee velden dat samen in: `version` zegt óf de inhoud anders is, `iat` zegt welk
van twee exemplaren het nieuwste is. Er is geen oplopend nummer omdat de dienst geen toestand
bewaart: hij bouwt het document bij elke uitgifte opnieuw uit het register.

## Geldigheid en verversen

- Een document geldt 24 uur vanaf `iat`, en nooit langer dan de certificaatketen waarmee het is
  ondertekend.
- De dienst geeft elk uur een nieuw exemplaar uit. `version` verandert alleen als de inhoud
  verandert; `iat` bij elke uitgifte.
- Een afnemer haalt ruim vóór `exp` een nieuw exemplaar op, bijvoorbeeld elk uur, en gebruikt
  `If-None-Match` om alleen een nieuw exemplaar te krijgen als er één is. Wie precies eens per
  dag ververst, zit rond dat moment met een verlopen document.
- Een wijziging in het register komt in het document zodra de stelselbeheerder de dienst opnieuw
  heeft gestart: het register is configuratie van de dienst. Een organisatie die uit het register
  verdwijnt, staat daarna uiterlijk 24 uur nog in het exemplaar dat een app al had.

## Verifiëren

Een afnemer voert deze zes stappen in deze volgorde uit en weigert het document zodra er één
faalt. Dezelfde stappen staan in de API-beschrijving.

1. **Header.** De header bevat uitsluitend `alg`, `typ`, `kid` en `x5c`; `alg` is exact `ES256`
   en `typ` exact `stelseldocument+jwt`.
2. **Certificaatpad.** Het pad in `x5c` valideert tot de vastgelegde root volgens RFC 5280 §6:
   elk certificaat na het eerste is een CA (`basicConstraints` `CA:TRUE`, sleutelgebruik
   `keyCertSign`), een padlengte-beperking wordt gerespecteerd, het eerste certificaat is geen
   CA en heeft sleutelgebruik `digitalSignature`, en elk certificaat is op dit moment geldig.
3. **Handtekening.** De handtekening klopt met de publieke sleutel uit het eerste certificaat in
   `x5c`.
4. **Uitgever.** Het subject van dat certificaat bevat precies één `serialNumber` (OID 2.5.4.5),
   dat is de OIN van de verwachte stelselbeheerder, en `iss` heeft dezelfde waarde.
5. **Omgeving.** `environment` is de omgeving waarvoor de app gebouwd is.
6. **Tijd.** `iat` ligt niet meer dan 60 seconden in de toekomst, `exp` niet meer dan 24 uur na
   `iat`, `exp` is niet voorbij, en `iat` is niet ouder dan dat van het laatst geaccepteerde
   exemplaar.

Waarom deze stappen zo streng zijn:

- **Stap 2 is meer dan "elke handtekening in de keten klopt".** Onder een root die meer
  certificaten uitgeeft — PKIoverheid is het voorbeeld — kan de houder van een willekeurig
  eindcertificaat er zelf een onder uitgeven, met de OIN van de stelselbeheerder erin. Elke
  handtekening in die keten klopt. Alleen de controle dat de uitgever een CA is, houdt dat tegen.
  **WebCrypto en de meeste JOSE-bibliotheken doen deze stap niet**; hij vraagt een
  X.509-bibliotheek. Zonder stap 2 bewijst stap 3 niets: het document levert de sleutel zelf aan.
- **Stap 4 bindt het document aan de organisatie.** Zonder die stap volstaat elk geldig
  certificaat onder de root. De dienst weigert om dezelfde reden een uitgever die niet in zijn
  ondertekencertificaat staat.
- **Stap 5 houdt omgevingen uit elkaar.** Een test- of acceptatieomgeving kan een certificaat
  onder dezelfde root en met dezelfde OIN hebben.
- **De grenzen in stap 6 kiest een aanvaller anders zelf.** Wie de sleutel in handen krijgt,
  bepaalt `iat` en `exp`. Een `iat` ver in de toekomst zou bovendien elk later, echt exemplaar
  laten weigeren.

De handtekening is in de JWS-vorm: R en S achter elkaar, 64 bytes. De 60 seconden in stap 6 zijn
de marge voor klokverschil; houd dezelfde marge aan op de geldigheid van de certificaten.

### Bekende beperking: organisatie, niet doel

Stap 4 bindt aan de organisatie, niet aan het doel van het certificaat. Een organisatie heeft
onder een publieke PKI vaak meer certificaten met dezelfde OIN. Lekt de sleutel van één daarvan
— en heeft dat certificaat sleutelgebruik `digitalSignature` en een P-256-sleutel — dan is
daarmee een stelseldocument te ondertekenen. Met de eigen test-root van de demo speelt dit niet:
die geeft één certificaat uit. Vóór een overstap naar een gedeelde root hoort hier een binding
aan het doel bij, zoals een eigen certificate policy of een vastgelegd subject. Dat volgt met de
sleutels van de organisaties.

## Vertrouwen: de root

Een afnemer legt de **root** vast, niet de ondertekensleutel. De root komt niet van de dienst en
staat niet in `x5c`: wie de app bouwt, neemt hem op in de build. Zo kan de stelselbeheerder het
ondertekencertificaat vervangen zonder dat een app daar iets van merkt.

`/.well-known/jwks.json` toont de publieke sleutels van de dienst (RFC 7517). Dat is een gemak
voor wie ze wil inzien en geen bron van vertrouwen: het bestand komt van hetzelfde adres als het
document, dus wie het document kan vervalsen, kan de sleutelset dat ook.

| Omgeving | Root | SHA-256-vingerafdruk |
|---|---|---|
| lokaal | per ontwikkelmachine gemaakt door `maak-keten.sh` | staat in de uitvoer van het script |
| demo (ZAD) | test-root van de demo-omgeving | staat in de operator-handleiding zodra de omgeving is ingericht |
| productie | nog niet ingericht | — |

Voor productie ligt een certificaat van een erkende uitgever voor de hand. Of PKIoverheid een
certificaat levert dat aan de eisen hier voldoet (EC P-256, de OIN in `subject.serialNumber`,
sleutelgebruik `digitalSignature`) is niet getoetst.

De demo-omgeving gebruikt een eigen test-root en een test-OIN (`00000000000000001000`). Een
document uit die omgeving draagt `environment: demo`; stap 5 is wat het buiten de demo
onbruikbaar maakt, ook als een andere omgeving ooit dezelfde root en OIN zou krijgen.

## Sleutelwissel

- **Nieuw ondertekencertificaat onder dezelfde root:** onzichtbaar voor afnemers. Tijdens de
  overgang toont de sleutelset de oude en de nieuwe sleutel.
- **Gelekte ondertekensleutel:** de stelselbeheerder vervangt het certificaat en geeft opnieuw uit.
  Er is geen intrekkingslijst. Wie de gelekte sleutel heeft, kan documenten blijven ondertekenen
  die een afnemer accepteert tot het bijbehorende certificaat verloopt; de geldigheid van 24 uur
  beschermt daar niet tegen, want de aanvaller geeft zelf nieuwe uit. De looptijd van het
  ondertekencertificaat is daarom het venster, en hoort kort te zijn: `maak-keten.sh` geeft
  90 dagen. Bij een ernstig lek is een nieuwe root de enige harde maatregel.
- **Nieuwe root:** vraagt een nieuwe build van elke app en wordt vooraf aangekondigd.

## Afwijkingen van BK Connect draft-00

| Eis | Stand |
|---|---|
| Het toepassingsprofiel specificeert de diensten en wie ze levert, de implementatievariant en alle aanvullende afspraken (2.2.2) | alleen het stelseldocument is beschreven. De dienst waar het om gaat is de ophaal-API van het magazijn (`berichtenmagazijn-api.yaml`); een variant (HTTPS-JWT of OpenID4VP) is nog niet gekozen |
| Publieke sleutel per organisatie in het stelseldocument (2.2.6.4, 4.1.1.2, 4.1.3) | ontbreekt. Een app kan een organisatie daardoor nog niet verifiëren; volgt met de sleutels van de magazijnen |
| Verwijzing naar een vindbaarheidsconfiguratie per organisatie (4.1.1.2) | ontbreekt; `magazijn_url` wijst naar het magazijn zelf |
| Het profiel specificeert hoe intrekking en verloopdatum van organisatiesleutels in het document staan (4.5, 4.6) | niet gespecificeerd; er zijn nog geen organisatiesleutels |
| Toegestane legalisatiemethoden en vertrouwde legalisators aanwijzen en opnemen (2.2.7, 4.1.2) | ontbreekt |
| App Managers (toegestaan in 4.1.1.1; aanwijzen volgens 2.2.7 en 4.1.2) | het veld bestaat en is leeg |
| Deelname-eisen stellen en handhaven, kwaliteit van diensten meten (2.2.3, 4.1.2) | organisatorisch; niet in deze dienst |
| Endpoints gebruiken TLS 1.3 of hoger (3.5) | de dienst termineert zelf geen TLS; de versie is een eis aan de ingress van de omgeving en wordt hier niet afgedwongen |

Wat de standaard wel eist van het document zelf en hier is ingevuld: JSON, publiek bereikbaar,
ondertekend door de stelselbeheerder, met versienummering (4.1.1.2), en een JWS met ECDSA (3.1).
Hoe een app aan de sleutel van de stelselbeheerder komt, schrijft de standaard niet voor; de
keten in `x5c` met een vastgelegde root is de keuze van dit profiel.

## Afwijkingen van de NL API Design Rules

| Regel | Afwijking | Reden |
|---|---|---|
| `Cache-Control: no-store` | korte `max-age` op document en sleutelset | publieke gegevens die apps regelmatig verversen |
| JSON als response | `application/jose` | de handtekening hoort bij de representatie |
| camelCase, Nederlands | snake_case, Engels in de payload | de payload volgt een externe standaard |
| `servers` met een absolute https-URL | relatieve base-path `/api/v1` | de host verschilt per omgeving; zoals bij de andere API's in deze repository |
| alles onder de versie-prefix, met `API-Version` | `/.well-known/jwks.json` en `/.well-known/security.txt` staan op de root, zonder `API-Version` | hun RFC's leggen het pad vast; ze horen niet bij de API |
| signing volgens de ADR-module (JAdES, PS256) — een conceptmodule, geen verplichting | JWS compact met ES256 | BK Connect eist ECDSA of EdDSA |
