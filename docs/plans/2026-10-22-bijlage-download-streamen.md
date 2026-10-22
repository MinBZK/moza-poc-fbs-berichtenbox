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

- **`getBijlage` geeft een `Response` terug, met een `byte[]` of een `StreamingOutput`.**
  - De parent-pom genereert een binaire response als `${api.binary.type}`, standaard `byte[]`; de
    uitvraag zet `jakarta.ws.rs.core.Response`. Het magazijn houdt `byte[]`.
  - Quarkus kiest de writer per antwoord. Een `byte[]` schrijft hij in één keer, zonder een
    worker-thread vast te houden; een `StreamingOutput` schrijft op de worker-thread, die blokkeert
    zolang de afnemer niet leest.
  - **Gekozen boven alleen `StreamingOutput`** (de eerste versie): de security-review van ronde 2
    wees aan dat een afnemer die niet leest dan ook bij een kleine bijlage een worker vasthoudt.
    Op `origin/main` deed die dat niet.
  - **Gekozen boven het endpoint buiten codegen halen** (zoals `_ophalen`, en zoals het issue
    voorstelt): de resource blijft de gegenereerde interface implementeren, dus het pad blijft
    aan de spec gebonden.

### Begin en rest

- **Eerst 1 MiB binnen de methode, dan de rest gestreamd.** Kleiner dan 1 MiB gaat als `byte[]`;
  vanaf 1 MiB streamt de bijlage.
  - `haalBijlage` leest tot 1 MiB in (`BEGIN_BYTES`), nog binnen de `@Logboek`-methode.
  - Past de bijlage daarin, en dat geldt voor wat in de praktijk voorkomt, dan is de
    magazijn-response meteen dicht en gaat ze als `byte[]` de deur uit. Alles gedraagt zich dan als
    op `origin/main`, en een afgekapte stream geeft een 502 met de logregel op `ERROR`.
  - Alleen wat groter is, streamt daarna door.
  - **Geheugen per download:** hooguit 1 MiB, kortstondig 2 MiB (`readNBytes` leest in stukken en
    kopieert die aan het eind naar één array), plus een kopieerbuffer. Op `origin/main` was dat
    tot 25 MiB.
- **Na het begin ligt de status vast.**
  - `schrijfNaar` schrijft het begin en flusht meteen, vóór het eerste stuk van de rest. Daarmee is
    het antwoord begonnen, en wordt elke fout daarna een gereset connection.
  - Na het laatste stuk flusht `schrijfNaar` nog eens, onder de bewaking. Anders schrijft Quarkus
    de laatste buffer pas bij het sluiten, als de plek al vrij is en niets meer op de afnemer let.
  - `BijlageDoorgifte` reset het antwoord zelf voordat ze een fout doorgooit. Zo eindigt een afgebroken
    overdracht nooit netjes, ook als Quarkus het antwoord anders zou afronden (bijvoorbeeld met een
    `WriterInterceptor` erbij).
- **Fouten worden ingedeeld naar oorzaak:**
  - leesfout bij het magazijn → `BijlageOverdrachtAfgebroken`, op error;
  - de bewaking reset de download → warn "afnemer las langer dan … niet";
  - interrupt → warn;
  - anders, meestal een annulering → debug.
  - Een write naar een gesloten connection mislukt niet altijd, dus `kopieer` kijkt elke ronde of de
    afnemer nog verbonden is; anders zou de rest stil uit het magazijn worden leeggelezen.
- **`BijlageOverdrachtGestoptMapper`** mapt de stop-excepties stil.
  - Het antwoord is al gereset, dus het bereikt niemand.
  - Zonder de mapper kwam elke afgebroken download nog een keer als "onverwachte exception" op
    error in de log.
- **`Content-Length` gaat mee.**
  - Ligt de hele bijlage in het begin, dan is dat de lengte daarvan; anders de `Content-Length` van
    het magazijn, mits een geldig niet-negatief getal.
  - De Vert.x-client leest nooit meer dan die lengte. Komt er minder, dan breekt het antwoord af.
  - `BijlageContentTypeFilter` zet hem via een derde request-property, alleen op een geslaagde
    response.
- **Een 3xx van het magazijn is geen bijlage.** `haalBijlage` toetst op `200..299`, en logt bij een
  niet-2xx die geen 4xx is de status van het magazijn.

### Logboek

- **Een afgebroken overdracht krijgt geen ERROR-child.**
  - Breekt het magazijn de rest af, dan staat de logregel al op `UNSET`, want de
    `@Logboek`-methode is dan terug. Die blijft zo staan.
  - De afnemer kreeg al minstens 1 MiB, dus de bijlage is deels verstrekt.
  - Daarmee volgt de uitvraag het besluit van de opdrachtgever uit
    [`2026-09-29-ldv-uitkomst-vastleggen.md`](2026-09-29-ldv-uitkomst-vastleggen.md): liever te veel
    dan te weinig. Een ERROR-child komt er alleen als vaststaat dat er niets verstrekt is.
  - Het geval "zeker niets verstrekt" eindigt binnen de methode, als 502 met de logregel zelf op
    `ERROR`.
  - De eerste versie zette er wél een ERROR-child onder. Dezelfde vorm zou dan in het magazijn
    "niet verstrekt" betekenen en in de uitvraag "deels verstrekt". De standaardenreview wees dat
    aan.
- **De applicatielog verwijst naar de logregel.** De errorregels bij een afgebroken overdracht en
  bij een leesfout in het begin, en de warn bij een stilgevallen afnemer, dragen de
  `trace_id:span_id` van de logregel. De interceptor maakt zijn span current, dus `Span.current()`
  in de methode is die logregel. Zo is de oorzaak aan het logboek te koppelen (LDV §3.3.2).

### Resources

- **Een plafond op gestreamde downloads** (`Bijlagestreams`): 20 per pod en 3 per ontvanger.
  - Daarboven volgt een 503 met `te-veel-gelijktijdige-downloads` en `Retry-After`. De spec
    declareerde die 503 al.
  - De plek wordt gereserveerd na het lezen van het begin; pas dan staat vast dat er gestreamd
    wordt. Een geweigerde download kost dus wel een magazijn-aanroep, maar een kleine bijlage
    wordt nooit door het plafond tegengehouden.
  - Zelfde opzet als de plafonds van `_volgen`; dat stuk is niet gedeeld, omdat het in
    `VolgenSseResource` ingebed zit en de berichten per plafond verschillen.
- **Een bewaking op de afnemer.** Wacht één write langer dan `berichtenuitvraag.bijlage.max-stilstand`
  (60 s) op de afnemer, dan reset een Vert.x-timer de download. Zonder die bewaking houdt een
  afnemer die niet leest de worker vast tot `quarkus.http.idle-timeout` (standaard 30 minuten).
- **Een eigen client per magazijn voor bijlagen** (`MagazijnRouter.voorBijlagen`), met een eigen
  connection pool en een idle-timeout van dezelfde `max-stilstand`.
  - Trage downloads verdringen zo het openen, bijwerken en verwijderen van berichten niet.
  - De read-timeout stopt zodra de headers binnen zijn; zonder de idle-timeout hield een magazijn
    dat midden in een bijlage stilvalt zijn plek in de pool onbeperkt vast.
- **De magazijn-response en de plek komen altijd vrij.**
  - In de `finally` van de writer.
  - Bij het einde van het verzoek via `RoutingContext.addEndHandler`, voor als de writer nooit draait:
    een HEAD, of een fout ná de methode, zoals een logregel die fail-closed niet geschreven kon
    worden. Die end-handler alleen bleek niet genoeg: na een gestreamd antwoord kwam de plek
    daarlangs niet terug.
  - Meteen in de methode als de afnemer al weg is: een end-handler die pas daarna geregistreerd
    wordt, vuurt nooit.
  - Alles is idempotent.
  - De client sluit door de rest te lezen en weg te gooien (`VertxClientInputStream.close` →
    `discard`). Dat kost bandbreedte maar geen heap; ook bij een HEAD haalt de uitvraag de bijlage
    dus bij het magazijn op.
- **De grenzen worden bij het opstarten gevalideerd** (`Bijlagestreams` is `@Startup`): de
  stilstand moet groter dan 0 zijn en in een int aan milliseconden passen, en de plafonds moeten
  positief zijn.

### Bewust niet

- **Een totale deadline per download:** die zou ook een trage maar werkende afnemer afbreken.
  - Een afnemer die heel traag maar steeds leest, houdt zijn plek zolang de download duurt.
  - Het plafond begrenst hoeveel dat er tegelijk zijn.
- **Een bovengrens op wat het magazijn doorgeeft:** het magazijn begrenst bij het aanleveren, en de
  uitvraag kent het magazijn via het register. `Bijlage.MAX_CONTENT_BYTES` naar `fbs-kern`
  verhuizen raakt het magazijn.
- **Een 502 bij een gestreamde bijlage zonder `Content-Length`.**
  - Achter een HTTP/1.0-hop is een afgebroken overdracht zonder lengte niet van een volledige te
    onderscheiden.
  - In plaats daarvan staat in de spec en de operator-handleiding dat de proxy HTTP/1.1 moet
    spreken.
  - Het eigen magazijn stuurt altijd een lengte.
- **Een HEAD als HEAD naar het magazijn doorzetten, en een afgebroken download resetten in plaats
  van leeglezen.** Dat scheelt bandbreedte en het onnodig ophalen van persoonsgegevens, maar vraagt
  een eigen pad in de client. Op `origin/main` gebeurde hetzelfde.
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
    - de varianten van `Content-Length`;
    - een leesfout in het begin;
    - 1xx en 3xx;
    - de bijlage-client, en dat de berichttekst die niet gebruikt.
  - **`BijlageInhoudTest`:**
    - volgorde;
    - lezen tegenover schrijven op drie momenten;
    - een afnemer die weg is;
    - een interrupt;
    - sluiten vóór, tijdens, na en dubbel;
    - de meting van een wachtende write;
    - `volledig`.
  - **`BijlagestreamsTest`:** plafond per ontvanger en per pod, een vrijgave die precies één keer
    werkt, en verkeerde instellingen.
  - **`BijlageContentTypeFilterTest`** en **`MagazijnRouterTest`.**
- **`BijlageStreamenTest` (`@QuarkusTest`, met de stub `BijlageMagazijnStub` op `com.sun.net.httpserver`):**
  - Na elke test staan er nul streams open.
  - Groottes 0, 1, net onder, op en boven het begin, en groot, met en zonder `Content-Length`;
    byte-gelijk, langs de contractvalidator, met `API-Version`.
  - **Streamt echt:** de afnemer krijgt het begin terwijl het magazijn de rest nog vasthoudt.
  - **Halverwege afgebroken, met en zonder lengte:** een onvolledige download en geen timeout; de
    errorregel met `trace_id:span_id`, de OIN en geen BSN; de logregel zonder fout en zonder
    ERROR-child; geen "onverwachte exception".
  - **Afgebroken in het begin, met en zonder lengte, ook net onder 1 MiB:** een 502 langs de
    contractvalidator, de logregel krijgt de fout zelf mee.
  - **Een afnemer die afbreekt:** op debug, en het magazijn wordt vrijgegeven.
  - **HEAD:** de headers, en de magazijn-response gaat dicht.
  - **Een logregel die na de methode niet geschreven kan worden (fail-closed):** een 500, en de
    plek en de magazijn-response komen toch vrij.
  - **Een afnemer die vertrekt vóór het magazijn antwoordt (HEAD en GET, ruwe socket):** de
    magazijn-response gaat dicht.
- **`BijlageGrenzenTest`** (stilstand 2 s, 2 streams per pod, 1 per ontvanger):
  - een magazijn dat stilvalt, breekt af tussen de grens en 10 s;
  - een afnemer die niet leest, wordt na de grens afgebroken, op warn;
  - het plafond per ontvanger en per pod geeft een 503 langs de validator, en na afloop is er weer
    plek.
- **Mutaties nagegaan:** zonder de stille mapper, zonder de bewaking, zonder de opruiming in de
  `finally` of zonder de closed-controle faalt telkens minstens één test.

## Verificatie

- `./mvnw clean verify -pl services/berichtenuitvraag -am`: tests, JaCoCo-gate en detekt.
- Spectral-lint van de spec.
- Twee multi-agent reviewrondes, ook tegen de overheidsstandaarden (LDV, API Design Rules, BIO,
  AVG).
