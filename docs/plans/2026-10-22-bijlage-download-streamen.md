# Bijlage-download streamen in de uitvraag — implementatieplan

**Status:** Concept

**Issue:** [MinBZK/MijnOverheidZakelijk#572](https://github.com/MinBZK/MijnOverheidZakelijk/issues/572)

**Doel:** de uitvraag geeft een bijlage door zonder hem eerst volledig in het geheugen te laden.
Het geheugen per download is begrensd, ongeacht de grootte van de bijlage.

## Context

- **Hoe het nu werkt:** `BerichtOphaalService.haalBijlage` leest de hele bijlage in een
  `ByteArray` (`response.readEntity(ByteArray::class.java)`), en `getBijlage` geeft die array
  terug.
  - Het magazijn staat bijlagen tot 25 MiB toe (`Bijlage.MAX_CONTENT_BYTES`).
  - Per gelijktijdige download staat de hele bijlage dus in de heap van de uitvraag, tot het
    antwoord geschreven is.
- **De client streamt al:** de Quarkus REST-client (3.40.1) leest de body bij een synchroon
  `Response`-returntype niet vooraf in. `ClientSendRequestHandler` kiest dan een
  `VertxClientInputStream`, die de body op verzoek leest (`isJakartaResponseDownload`). Pas
  `readEntity(ByteArray)` haalt alles binnen.
  - Aan de clientkant hoeft dus alleen het lezen te veranderen, niet de interface.
- **De serverkant:**
  - **`InputStream` als entity:** `InputStreamMessageBodyHandler.writeTo` sluit in een
    `finally` óók de output. Een fout halverwege eindigt daardoor als een netjes afgesloten
    antwoord: een ingekorte bijlage onder een 200.
  - **`StreamingOutput` als entity:** `StreamingOutputMessageBodyWriter` vangt een fout af.
    Is het antwoord al begonnen (`headWritten`), dan reset hij de verbinding. Anders gaat
    de fout de gewone foutafhandeling in.
- **Codegen:** de gegenereerde interface geeft `ByteArray` terug, door de projectbrede
  `typeMapping` `File=byte[]`.

## Ontwerp

- **`getBijlage` geeft een `StreamingOutput` terug.**
  - De uitvraag zet daarvoor in haar eigen pom `File=jakarta.ws.rs.core.StreamingOutput`,
    met `combine.self="override"` zodat de lijst niet afhangt van hoe Maven twee lijsten met
    gelijke sleutel samenvoegt.
  - De uitvraag-spec heeft één binair schema (deze response), dus er verandert niets anders.
  - De magazijn-spec houdt `byte[]`.
  - De resource blijft de gegenereerde interface implementeren, dus het endpoint blijft
    spec-gedreven.
  - Gekozen boven het endpoint buiten codegen halen, zoals `_ophalen`. Dat raadt het issue
    aan, maar het zou de compile-time-binding aan de spec voor dit pad opgeven, en dat is
    hier niet nodig.
- **Eerst een begin binnen de methode, dan de rest gestreamd.**
  - `haalBijlage` leest tot 64 KiB (`BEGIN_BYTES`) in, nog binnen de `@Logboek`-methode.
    Past de bijlage daarin, dan is de magazijn-response meteen gesloten, en gedraagt alles
    zich als nu: een afgekapte stream geeft een 502, en de logregel staat op `ERROR`.
  - Dat geldt voor de bijlagen die in de praktijk voorkomen: een paar kilobyte.
  - Alleen wat groter is, streamt daarna door.
  - Het geheugen per download is zo hooguit het begin plus een kopieerbuffer, in plaats van
    tot 25 MiB.
- **Na het begin ligt de status vast.**
  - `schrijfNaar` schrijft het begin en flusht meteen, vóór het eerste stuk van de rest.
    Daarmee is het antwoord begonnen; een fout in de rest wordt daarna een gereset
    verbinding.
  - Zonder die flush kon een leesfout binnen de outputbuffer van Quarkus (8 KiB) nog naar
    de foutafhandeling gaan, met de `Content-Type`, `Content-Disposition` en
    `Content-Length` van de bijlage nog op het antwoord.
- **`Content-Length` gaat mee.**
  - De `ByteArray` gaf hem nu vanzelf; zonder zou een download chunked worden, zonder
    voortgang in de browser.
  - Ligt de hele bijlage al in het begin, dan is dat de lengte daarvan; anders de
    `Content-Length` van het magazijn, mits een geldig niet-negatief getal.
  - De Vert.x-client leest nooit meer dan die lengte. Komt er minder, dan breekt het
    antwoord af en ziet de afnemer een onvolledige download.
  - `BijlageContentTypeFilter` zet hem via een derde request-property, net als
    `Content-Type`, en alleen op een geslaagde response.
- **Een afgebroken overdracht staat in het logboek.**
  - Breekt het magazijn de rest af, dan was de logregel al geschreven op `UNSET`, want de
    `@Logboek`-methode is dan terug.
  - `BijlageUitkomst` zet er een ERROR-child onder (`ProcessingHandler.recordFailedOutcome`),
    met alleen het fouttype (`LdvFoutSamenvatting`), op dezelfde manier als het magazijn
    (`MislukteUitkomst`).
  - Leesregel: `UNSET` zonder ERROR-child is correct afgerond.
  - Een verloren uitkomst logt `LDV_UITKOMST_ONTBREEKT`.
  - Synchroon: de writer draait op de worker-thread van het verzoek, niet op de event-loop.
- **Een afgebroken download door de afnemer is geen fout van de verwerking.**
  - Een schrijffout naar de afnemer geeft geen ERROR-child en geen error-log.
  - `schrijfNaar` onderscheidt lezen (magazijn) van schrijven (afnemer) met een eigen
    exceptie voor een leesfout (`BijlageOverdrachtAfgebroken`).
- **De magazijn-response sluit altijd.**
  - Na het schrijven, in een `finally`.
  - En bij het einde van het verzoek via `RoutingContext.addEndHandler`, voor het geval de
    writer nooit draait: een HEAD-verzoek, een filter dat gooit, of een afnemer die weg is
    vóór het schrijven.
  - Sluiten is idempotent.
  - De client sluit door de rest te lezen en weg te gooien (`VertxClientInputStream.close`
    → `discard`). Dat kost bandbreedte maar geen heap.

Bewust niet:

- **De werkelijke grootteverdeling van bijlagen eerst meten**, zoals het issue voorstelt.
  - Productie bestaat nog niet, dus die verdeling is er niet.
  - Het begin van 64 KiB houdt het gedrag gelijk voor wat in de praktijk voorkomt, en
    begrenst het geheugen voor het plafond van 25 MiB. Met die keuze hangt de wijziging
    niet af van de meting.
- **Het magazijn streamen:** het magazijn laadt een bijlage uit PostgreSQL als `bytea`. Dat
  valt buiten dit issue, dat over de doorgifte in de uitvraag gaat.
- **De helper voor de uitkomst samenvoegen** met `OphaalUitkomst` uit
  [MinBZK/moza-poc-fbs-berichtenbox#397](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/397).
  Die PR staat nog open; samenvoegen kan zodra beide gemerged zijn.

## Stappen

1. Uitvraag-pom: `typeMappings` met `File=jakarta.ws.rs.core.StreamingOutput`.
2. `BerichtOphaalService.haalBijlage`: begin inlezen, `BijlageInhoud` met `schrijfNaar` en
   `sluit`.
3. `BijlageUitkomst` (ERROR-child) en `UitvraagResource.getBijlage` (`StreamingOutput`,
   logregel, sluiten bij het einde van het verzoek).
4. `BijlageContentTypeFilter`: `Content-Length` uit een request-property.
5. Spec: in de beschrijving wat een afnemer ziet als de overdracht halverwege afbreekt.
6. Tests, operator-handleiding, de TODO(#572) weg.

## Tests

- **Unit (`BerichtOphaalServiceTest`, `BijlageInhoudTest`):**
  - een bijlage kleiner dan, gelijk aan en groter dan het begin;
  - een leesfout in het begin geeft een 502 en sluit;
  - een leesfout in de rest geeft `BijlageOverdrachtAfgebroken` en sluit;
  - een schrijffout geeft geen `BijlageOverdrachtAfgebroken` en sluit;
  - een falende close maskeert de echte fout niet;
  - de lengte uit het magazijn: geldig, ontbrekend, ongeldig, negatief.
- **`@QuarkusTest` met een eigen magazijn-stub (`com.sun.net.httpserver`):**
  - **Streamt echt:** de afnemer krijgt de eerste bytes terwijl het magazijn de rest nog
    vasthoudt. Met een `ByteArray` faalt dat, want dan komt er niets voordat alles binnen is.
  - **Groot en byte-gelijk:** de bijlage komt byte voor byte aan, met de juiste
    `Content-Length`.
  - **Afgebroken halverwege:** de afnemer ziet een onvolledige download, geen volledige 200,
    en er is precies één `recordFailedOutcome` met de naam, activiteit en betrokkene van de
    logregel.
  - **Afgebroken in het begin:** een 502 met problem+json, zonder de headers van de bijlage.
  - **HEAD:** de magazijn-response wordt gesloten.
- **Bestaande WireMock-tests** (`ServiceCoverageTest`, `OpenApiContractTest`): de foutpaden
  en het contract blijven gelijk.

## Verificatie

- `./mvnw clean verify -pl services/berichtenuitvraag -am`: tests, JaCoCo-gate en detekt.
- Spectral-lint van de spec.
