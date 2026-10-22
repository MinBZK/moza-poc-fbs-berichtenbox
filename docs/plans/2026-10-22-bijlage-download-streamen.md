# Bijlage-download streamen in de uitvraag — implementatieplan

**Status:** Uitgevoerd

**Issue:** [MinBZK/MijnOverheidZakelijk#572](https://github.com/MinBZK/MijnOverheidZakelijk/issues/572)

**Doel:** de uitvraag geeft een bijlage door zonder haar eerst volledig in het geheugen te laden.
Het geheugen per download is begrensd, ongeacht de grootte van de bijlage.

## Context

- **Hoe het was:** `BerichtOphaalService.haalBijlage` las de hele bijlage in een `ByteArray`
  (`response.readEntity(ByteArray::class.java)`), en `getBijlage` gaf die array terug.
  - Het magazijn staat bijlagen tot 25 MiB toe (`Bijlage.MAX_CONTENT_BYTES`).
  - Per gelijktijdige download stond de hele bijlage dus in de heap van de uitvraag, tot het
    antwoord geschreven was.
- **De client streamt al.** De Quarkus REST-client (3.40.1) leest de body bij een synchroon
  `Response`-returntype niet vooraf in.
  - `ClientSendRequestHandler` kiest dan een `VertxClientInputStream` (`isJakartaResponseDownload`).
  - Die vraagt telkens één buffer op (`pause`/`fetch(1)`). Pas `readEntity(ByteArray)` haalt
    alles binnen.
- **Hoe de serverkant een fout afhandelt:**
  - **`InputStream` als entity:** `InputStreamMessageBodyHandler.writeTo` sluit in een `finally`
    óók de output. Een fout halverwege eindigt dan als een netjes afgesloten antwoord: een
    ingekorte bijlage.
  - **`StreamingOutput` als entity:** `StreamingOutputMessageBodyWriter` vangt de fout af en kijkt
    of de head al geschreven is.
    - Is dat zo (`headWritten`), dan reset hij de connection.
    - Anders gaat de fout de gewone foutafhandeling in.
- **Codegen:** de gegenereerde interface gaf `ByteArray` terug, door de projectbrede
  `typeMapping` `File=byte[]`.
- **De read-timeout stopt bij de headers.**
  - Vert.x zet de request-timeout stop zodra de headers binnen zijn
    (`HttpClientRequestBase.handleResponse`).
  - Voor de body gelden daarna alleen 100 s per read (`VertxClientInputStream`).
  - Een connection-idle-timeout staat niet aan.

## Ontwerp

### Codegen en endpoint

- **`getBijlage` geeft een `StreamingOutput` terug.**
  - De parent-pom genereert een binaire response als `${api.binary.type}`, standaard `byte[]`. De
    uitvraag zet die property op `jakarta.ws.rs.core.StreamingOutput`.
  - De uitvraag-spec heeft één binair schema (deze response). Het magazijn houdt `byte[]`.
  - De resource blijft de gegenereerde interface implementeren, dus het endpoint blijft
    spec-gedreven.
  - **Gekozen boven het endpoint buiten codegen halen** (zoals `_ophalen`, en zoals het issue
    voorstelt): dat zou de compile-time-binding aan de spec voor dit pad opgeven, en dat is
    hier niet nodig.
  - **Gekozen boven een volledige `typeMappings`-override in de uitvraag:** dan zou een mapping
    die later in de parent bijkomt, de uitvraag stil niet bereiken.

### Begin en rest

- **Eerst 1 MiB binnen de methode, dan de rest gestreamd.**
  - `haalBijlage` leest tot 1 MiB in (`BEGIN_BYTES`), nog binnen de `@Logboek`-methode.
  - Past de bijlage daarin, dan is de magazijn-response meteen gesloten. Dat geldt voor de bijlagen
    die in de praktijk voorkomen, een paar kilobyte tot een gewone PDF. Een afgekapte stream geeft
    dan een 502, en de logregel krijgt de fout zelf mee (`ERROR`).
  - Alleen wat groter is, streamt daarna door.
  - **Geheugen per download:** hooguit 1 MiB plus een kopieerbuffer, in plaats van tot 25 MiB.
  - **Gekozen boven 64 KiB**, de eerste versie: een gestreamde download houdt een worker-thread en
    een magazijn-connection vast zolang de afnemer leest (zie hieronder). Met 1 MiB doet alleen
    een uitzonderlijk grote bijlage dat.
- **Na het begin ligt de status vast.**
  - `schrijfNaar` schrijft het begin en flusht meteen, vóór het eerste stuk van de rest. Daarmee
    is het antwoord begonnen, en wordt elke fout daarna een gereset connection.
  - Zonder die flush kon een leesfout binnen de outputbuffer van Quarkus (8 KiB) nog naar de
    foutafhandeling gaan, met de `Content-Type`, `Content-Disposition` en `Content-Length` van de
    bijlage nog op het antwoord.
- **Ook een schrijffout gaat door als exceptie** (`BijlageNietAfgeleverd`).
  - Normaal terugkeren zou een chunked antwoord netjes afsluiten als de connection nog open staat,
    bijvoorbeeld na een interrupt tijdens het wachten op de afnemer.
  - Is de connection al dicht (de afnemer annuleerde), dan logt de uitvraag op debug: geen fout
    van de verwerking. Staat ze nog open, dan op warn.
- **`BijlageOverdrachtGestoptMapper`** mapt beide excepties stil.
  - Het antwoord is dan al begonnen, of de afnemer is weg, dus het bereikt niemand.
  - Zonder de mapper kwam elke afgebroken download nog een keer als "onverwachte exception" op
    error in de log.
  - Een globale mapper: een `@ServerExceptionMapper` in de resource geldt niet voor wat er na de
    methode, bij het schrijven, misgaat.
- **`Content-Length` gaat mee.**
  - De `ByteArray` gaf hem vanzelf; zonder zou een download chunked worden, zonder voortgang in de
    browser.
  - Ligt de hele bijlage al in het begin, dan is dat de lengte daarvan; anders de `Content-Length`
    van het magazijn, mits een geldig niet-negatief getal.
  - De Vert.x-client leest nooit meer dan die lengte. Komt er minder, dan breekt het antwoord af.
  - `BijlageContentTypeFilter` zet hem via een derde request-property, net als `Content-Type`, en
    alleen op een geslaagde response.
- **Een 3xx van het magazijn is geen bijlage.** `haalBijlage` toetst nu op `200..299` en niet
  meer op `>= 400`. Een 3xx met een `Content-Type` ging anders als bijlage onder een 200 door,
  terwijl de klasse-KDoc een 502 belooft.

### Logboek

- **Een afgebroken overdracht krijgt geen ERROR-child.**
  - Breekt het magazijn de rest af, dan staat de logregel al op `UNSET`, want de
    `@Logboek`-methode is dan terug. Die blijft zo staan.
  - De afnemer kreeg al minstens 1 MiB, dus de bijlage is deels verstrekt.
  - Daarmee volgt de uitvraag het besluit van de opdrachtgever uit
    [`2026-09-29-ldv-uitkomst-vastleggen.md`](2026-09-29-ldv-uitkomst-vastleggen.md): liever te veel
    dan te weinig. Een ERROR-child komt er alleen als vaststaat dat er niets verstrekt is, en een
    verbroken antwoord blijft als verstrekking staan.
  - Het geval "zeker niets verstrekt" eindigt hier binnen de methode, als 502 met de logregel zelf
    op `ERROR`.
  - De eerste versie zette er wél een ERROR-child onder. Dezelfde vorm zou dan in het magazijn
    "niet verstrekt" betekenen en in de uitvraag "deels verstrekt", en dat is voor een lezer van
    het logboek niet te onderscheiden. De standaardenreview wees dat aan.
- **De applicatielog verwijst naar de logregel.**
  - De errorregel bij een afgebroken overdracht draagt de `trace_id:span_id` van de logregel. De
    interceptor maakt zijn span current, dus `Span.current()` in de methode is die logregel.
  - Zo is de oorzaak, die alleen in de applicatielog staat, aan het logboek te koppelen (LDV
    §3.3.2).

### Resources

- **De magazijn-response sluit altijd.**
  - Na het schrijven, in een `finally`.
  - Bij het einde van het verzoek via `RoutingContext.addEndHandler`, voor het geval de writer nooit
    draait (een filter dat gooit).
  - Meteen in de methode bij een HEAD-verzoek, en als de afnemer al weg is: een end-handler die
    pas daarna geregistreerd wordt, vuurt nooit.
  - Sluiten is idempotent (een compare-and-set in `BijlageInhoud`).
  - De client sluit door de rest te lezen en weg te gooien (`VertxClientInputStream.close` →
    `discard`). Dat kost bandbreedte maar geen heap.
- **Een eigen client per magazijn voor bijlagen** (`MagazijnRouter.voorBijlagen`), met een eigen
  connection pool.
  - Een gestreamde download houdt zijn connection vast zolang de afnemer leest.
  - In de gedeelde pool zouden trage downloads het openen, bijwerken en verwijderen van berichten
    bij dat magazijn verdringen.
- **`magazijn-router.bijlage-idle-timeout` (standaard 60 s)** op die client.
  - Een connection die zo lang stilligt, gaat dicht: een magazijn dat midden in een bijlage
    stilvalt, of een half-open connection na het leeglezen.
  - Zonder die grens hield zo'n connection haar plek in de pool onbeperkt vast.
  - De grens geldt ook als de afnemer zo lang niet leest. De uitvraag logt dat als een afgebroken
    overdracht.
- **Wat een gestreamde download vasthoudt:** zolang de afnemer leest, één worker-thread en één
  connection uit de bijlage-pool van dat magazijn.
  - Op `origin/main` hield een trage afnemer in plaats daarvan de hele bijlage in de heap.
  - De operator-handleiding raadt aan de ingress te laten bufferen (bij nginx de standaard): die
    neemt een trage afnemer dan over.

### Bewust niet

- **Een plafond op het aantal gelijktijdige gestreamde downloads** (zoals bij `_volgen`).
  - Alleen bijlagen boven 1 MiB streamen, en die zijn in de praktijk zeldzaam.
  - De eigen pool houdt de andere aanroepen naar het magazijn vrij, en de idle-timeout begrenst
    een stilgevallen connection.
  - Een plafond vraagt een eigen foutcode in de spec en per-ontvanger-boekhouding. Dat hoort bij
    een concrete productieaanleiding.
  - **Restrisico:** een afnemer die veel grote bijlagen tegelijk heel traag leest, houdt even zoveel
    worker-threads vast. Daartegen helpt een bufferende ingress.
- **Een totale deadline per download:** die zou ook een trage maar werkende afnemer afbreken, en de
  idle-timeout dekt het stilgevallen geval.
- **Een bovengrens op wat het magazijn doorgeeft:** het magazijn begrenst bij het aanleveren, en de
  uitvraag kent het magazijn via het register. `Bijlage.MAX_CONTENT_BYTES` naar `fbs-kern`
  verhuizen raakt het magazijn.
- **De werkelijke grootteverdeling van bijlagen eerst meten**, zoals het issue voorstelt.
  - Productie bestaat nog niet, dus die verdeling is er niet.
  - Het begin van 1 MiB houdt het gedrag gelijk voor wat in de praktijk voorkomt, en begrenst het
    geheugen voor het plafond van 25 MiB. Met die keuze hangt de wijziging niet af van de meting.
- **Het magazijn streamen:** het magazijn laadt een bijlage uit PostgreSQL als `bytea`. Dat valt
  buiten dit issue, dat over de doorgifte in de uitvraag gaat.

## Tests

- **Unit:**
  - **`BerichtOphaalServiceTest`:**
    - een bijlage kleiner dan, gelijk aan en groter dan het begin;
    - de lengte uit het magazijn: geldig, met spaties, 0, ontbrekend, leeg, geen getal, negatief,
      overflow;
    - een leesfout in het begin geeft een 502 en sluit;
    - een antwoord dat geen 2xx is (1xx, 3xx) geeft een 502;
    - de bijlage gaat via de eigen client.
  - **`BijlageInhoudTest`:**
    - het begin, dan de flush, dan de rest;
    - een leesfout in de rest is een afgebroken overdracht;
    - een schrijffout bij het begin, bij de flush en in de rest is dat niet;
    - sluiten vóór, na en dubbel;
    - een falende close maskeert niets.
  - **`BijlageContentTypeFilterTest`:** `Content-Length` bij 0, 1 en 25 MiB; geen bij een
    ontbrekende lengte en op een foutantwoord.
  - **`MagazijnRouterTest`:** de bijlage-client is een eigen instantie, en per magazijn hergebruikt.
- **`@QuarkusTest` met een eigen magazijn-stub (`com.sun.net.httpserver`, `BijlageStreamenTest`):**
  - **Streamt echt:** de afnemer krijgt het begin terwijl het magazijn de rest nog vasthoudt. Met
    een `ByteArray` faalt dat.
  - **Groot en byte-gelijk, met en zonder `Content-Length`:** langs de contractvalidator.
  - **Halverwege afgebroken, met en zonder `Content-Length`:**
    - de afnemer krijgt een onvolledige download, geen timeout;
    - de errorregel draagt de `trace_id:span_id` van de logregel;
    - er komt geen ERROR-child en geen "onverwachte exception".
    - Zonder lengte houdt alleen de reset een ingekorte 200 tegen.
  - **Afgebroken in het begin:** een 502 langs de contractvalidator, zonder de headers van de
    bijlage.
  - **Een afnemer die afbreekt:** niet op error, geen ERROR-child, en het magazijn wordt vrijgegeven.
  - **HEAD:** de headers van de bijlage, en de magazijn-response gaat dicht.
  - **Een afnemer die vertrekt vóór het magazijn antwoordt (HEAD en GET, via een ruwe socket):** de
    magazijn-response gaat dicht.
- **`BijlageStilstandTest`:** een magazijn dat midden in een bijlage stilvalt, breekt de download af
  na de idle-timeout, en niet pas na 100 s.

## Verificatie

- `./mvnw clean verify -pl services/berichtenuitvraag -am`: tests, JaCoCo-gate en detekt.
- Spectral-lint van de spec.
- Twee multi-agent reviewrondes, ook tegen de overheidsstandaarden (LDV, API Design Rules, BIO,
  AVG).
