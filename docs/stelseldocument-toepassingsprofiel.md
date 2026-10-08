# Toepassingsprofiel stelseldocument

Dit profiel legt vast hoe het Federatief Berichtenstelsel (FBS) het stelseldocument uit
[BK Connect draft-00](https://vorijk.nl/standaard/connect/draft-bk-connect-00.html) invult. De
standaard laat pad, vorm van de handtekening, sleuteldistributie en geldigheidsduur aan een
toepassingsprofiel (sectie 2.2.2); dit document is dat profiel.

| | |
|---|---|
| Versie van dit profiel | 0.1 |
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

`jku`, `jwk`, `x5u` en `crit` komen niet voor en mogen door een afnemer nooit gevolgd worden.

## Payload

| Veld | Betekenis |
|---|---|
| `iss` | OIN van de stelselbeheerder |
| `iat` | moment van uitgifte (seconden sinds 1970) |
| `exp` | moment waarna het document niet meer geldt |
| `version` | kenmerk van de inhoud; gelijk bij gelijke inhoud |
| `environment` | de omgeving waarvoor het document is uitgegeven |
| `organizations` | de deelnemende organisaties, gesorteerd op OIN: `oin`, `name`, `magazijn_url` |
| `app_managers` | de toegelaten app-beheerders; nog leeg |
| `document_types` | de soorten documenten in dit stelsel; nu alleen `bericht` |

`magazijn_url` is het publieke adres van het berichtenmagazijn van de organisatie. De standaard
toont in zijn voorbeeld een `discovery_url` naar een vindbaarheidsconfiguratie; die laag bestaat
in FBS nog niet, en het veld draagt daarom de naam van wat het werkelijk is.

De veldnamen zijn Engels en snake_case omdat ze uit de standaard komen.

## Geldigheid en verversen

- Een document geldt 24 uur vanaf `iat`, en nooit langer dan het ondertekencertificaat.
- De dienst geeft elk uur een nieuw exemplaar uit. `version` verandert alleen als de inhoud
  verandert; `iat` bij elke uitgifte.
- Een afnemer ververst minstens dagelijks en gebruikt `If-None-Match` om alleen een nieuw exemplaar
  op te halen als er één is.
- Een organisatie die uit het register verdwijnt, staat dus binnen 24 uur in geen enkele app meer.

## Verifiëren

Een afnemer voert deze stappen in deze volgorde uit en weigert het document zodra er één faalt.

1. `alg` is exact `ES256` en `typ` exact `stelseldocument+jwt`.
2. De keten in `x5c` leidt naar de vastgelegde root, en elk certificaat is op dit moment geldig.
3. De handtekening klopt met de publieke sleutel uit het eerste certificaat in `x5c`.
4. `subject.serialNumber` van dat certificaat is de OIN van de verwachte stelselbeheerder, en
   `iss` heeft dezelfde waarde.
5. `exp` is niet voorbij, en `iat` is niet ouder dan het laatst geaccepteerde exemplaar.

Stap 4 is geen formaliteit. Onder een root die ook andere certificaten uitgeeft — PKIoverheid is
het voorbeeld — kan elke houder van zo'n certificaat anders een document ondertekenen dat zich als
de stelselbeheerder voordoet. De dienst zelf weigert om dezelfde reden een uitgever die niet in
zijn ondertekencertificaat staat.

De handtekening is in de JWS-vorm: R en S achter elkaar, 64 bytes. Elke JOSE-bibliotheek die
ES256 ondersteunt, verifieert hem; in een browser kan dat met WebCrypto.

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
| productie | nog niet ingericht; een PKIoverheid-certificaat past in dezelfde vorm | — |

De demo-omgeving gebruikt een eigen test-root en een test-OIN (`00000000000000001000`). Een
document uit die omgeving draagt `environment: demo` en is daarbuiten niets waard.

## Sleutelwissel

- **Nieuw ondertekencertificaat onder dezelfde root:** onzichtbaar voor afnemers. Tijdens de
  overgang toont de sleutelset de oude en de nieuwe sleutel.
- **Gelekte ondertekensleutel:** de stelselbeheerder vervangt het certificaat en geeft opnieuw uit.
  Er is geen intrekkingslijst; de bescherming zit in de korte geldigheid van het document en van
  het ondertekencertificaat.
- **Nieuwe root:** vraagt een nieuwe build van elke app en wordt vooraf aangekondigd.

## Afwijkingen van BK Connect draft-00

| Eis | Stand in dit profiel |
|---|---|
| Publieke sleutel per organisatie (4.1.1.2) | ontbreekt; volgt met de sleutels van de magazijnen |
| Verwijzing naar een vindbaarheidsconfiguratie (4.1.1.2) | ontbreekt; `magazijn_url` wijst naar het magazijn zelf |
| Intrekking en verloopdatum van organisatiesleutels (4.5, 4.6) | ontbreekt, er zijn nog geen organisatiesleutels |
| App Managers | het veld bestaat en is leeg |
| Toegestane legalisatiemethoden (2.2.7) | ontbreekt |

## Afwijkingen van de NL API Design Rules

| Regel | Afwijking | Reden |
|---|---|---|
| `Cache-Control: no-store` | korte `max-age` op document en sleutelset | publieke gegevens die apps regelmatig verversen |
| JSON als response | `application/jose` | de handtekening hoort bij de representatie |
| camelCase, Nederlands | snake_case, Engels in de payload | de payload volgt een externe standaard |
| signing volgens de ADR-module (JAdES, PS256) | JWS compact met ES256 | BK Connect eist ECDSA of EdDSA |
