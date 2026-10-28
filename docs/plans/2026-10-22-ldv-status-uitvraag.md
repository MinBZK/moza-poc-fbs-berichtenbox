# Logboekstatus van de uitvraag volgt de uitkomst — implementatieplan

**Status:** Uitgevoerd

**Doel:** de logregels van de berichtenuitvraag in het Logboek Dataverwerkingen (LDV) geven de
uitkomst van de verwerking weer zoals de standaard het vraagt. Gevonden bij de toetsing aan de
overheidsstandaarden van de fan-out-meting
([MinBZK/moza-poc-fbs-berichtenbox#396](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/396));
daar is het buiten de wijziging gehouden, omdat het de dienst zelf raakt.

## Context

Twee afwijkingen van de standaard (LDV v1.0.0), allebei al langer aanwezig:

1. **Een verwachte weigering staat als `ERROR`.** De wrapper zet `ERROR` en `exception.*` bij elke
   exceptie die een `@Logboek`-methode verlaat, tenzij die vooraf met
   `LogboekContext.expectException` is aangekondigd. De uitvraag kondigt nooit iets aan. Een `409`
   `nog-niet-opgehaald` op `GET /berichten` — het normale antwoord vóór of tijdens een ophaalronde,
   dat de uitvraag zelf alleen op debug logt — staat daardoor als mislukte verwerking in het
   logboek. §3.2.2.3 vraagt `UNSET` "wanneer de dataverwerking technisch correct is afgerond, ook
   als er geen resultaat beschikbaar is"; `ERROR` alleen bij een fout in het interne proces.
   Bijwerking: op dit pad vangt de wrapper een mislukte logregel-schrijfactie af in plaats van de
   aanroep te laten falen, dus deze logregels vallen buiten fail-closed.
2. **De logregel van `_ophalen` staat altijd op `UNSET`.** `ophalen()` geeft een `Multi` terug; de
   interceptor schrijft de logregel weg zodra de methode terugkeert, vóór het eerste event. De
   statuswijzigingen bij het slotevent (`logboekStatusVoor`) en bij een streamfout komen daarna en
   hebben geen effect. §3.3.1 eist dat de applicatie bijhoudt of een actie geslaagd of mislukt is.
   `docs/operator-handleiding-uitvraag.md` belooft `OK` bij `mislukt = 0`, en dat klopt dus niet.

## Ontwerp

- **Een weigering is een uitkomst.** `LogboekWeigeringInterceptor` kondigt een exceptie aan die de
  verwerking als weigering beëindigt: een `WebApplicationException` met een 4xx-status, een
  `ConstraintViolationException` uit Bean Validation of een `DomainValidationException` (die de
  mappers als 400 teruggeven). De wrapper legt die dan als `UNSET` vast, zonder `exception.*`, en
  onder fail-closed. Een 5xx en elke andere exceptie blijven `ERROR`: daar faalde het interne proces.
  - Een interceptor op `@Logboek` (de leden zijn `@Nonbinding`), met prioriteit `APPLICATION + 1`:
    binnen de LDV-interceptor (`APPLICATION`) en buiten de validatie-interceptors van Quarkus
    (`PLATFORM_AFTER + 800`). Zo komt ook een 400 uit Bean Validation erlangs, die vóór de methode
    ontstaat — een `X-Ontvanger` buiten het patroon, een `paginaGrootte` buiten 1–200, een te korte
    zoekterm. Een aanroep in elke methode zou die missen, en een methode die later bijkomt vergeten.
  - Eén regel die overal geldt, en niet per endpoint een lijst van "verwachte" codes: of iets een
    weigering is, zegt de status al. Gekozen boven een lijst van cache-uitkomsten (`!isStoring()`):
    die dekt alleen de leespaden, terwijl een 404 van het magazijn of een 400 bij een aanmelding net
    zo goed een correct afgeronde weigering is.
- **Een mislukte ophaalronde krijgt een ERROR-child**, dezelfde vorm als in het magazijn
  (`docs/plans/2026-09-29-ldv-uitkomst-vastleggen.md`): de logregel van `uitvraag-ophalen-sse`
  blijft `UNSET`, en `ProcessingHandler.recordFailedOutcome` zet er een child met status `ERROR`
  onder. Leesregel: `UNSET` zonder ERROR-child is geslaagd.
  - Mislukt is: een slotevent met `mislukt > 0`, een `OphalenFout`, of een stream die faalt. Dat
    is de bestaande, bedoelde betekenis van `logboekStatusVoor`, die nu als `mislukkingVan` effect
    krijgt;
    `nietOpgehaald` telt bewust niet mee (een capaciteitskeuze van de uitvraag, geen mislukte
    verwerking).
  - De verwijzing naar de logregel komt uit de span die de interceptor tijdens de methode actief
    heeft (`Span.current()`), met de naam, de verwerkingsactiviteit en de betrokkene; de wrapper
    staat dat expliciet toe voor een afnemer die alleen een `SpanContext` heeft.
  - In het logboek komt alleen een samenvatting: het aantal mislukte organisaties of de soort
    eindfout (`OphaalrondeMislukt`), of bij een streamfout het fouttype (`LdvFoutSamenvatting`).
    Geen foutmelding: die rijen gaan bij een inzageverzoek naar buiten.
  - Op een worker-thread: het slotevent ontstaat ná een reactieve Redis-schrijfactie, dus op de
    event-loop, en `recordFailedOutcome` schrijft synchroon naar de database.
  - Gooit nooit, en een verloren uitkomst-logregel gaat met het token `LDV_UITKOMST_ONTBREEKT` de
    applicatielog in, net als in het magazijn, met alleen het type van de ontvanger.
  - Ook als de client al weg is: de aggregatie loopt door, en de uitkomst hoort bij de verwerking,
    niet bij de verbinding.
- De dode statuswijzigingen in `OphalenSseResource` gaan eruit.

## Tests

- `LogboekWeigeringTest` (unit, met een echte `LogboekContext`): 400, 404, 409, 410 en 499 worden
  aangekondigd, 500, 502 en 503 niet, een `ConstraintViolationException` en een
  `DomainValidationException` wel, een andere exceptie niet; steeds hetzelfde exemplaar doorgegooid,
  en de returnwaarde ongemoeid.
- `LogboekWeigeringInterceptorTest` (`@QuarkusTest`, met de `ProcessingHandler` per test vervangen):
  via de echte interceptorketen leest de test wat de LDV-interceptor als aangekondigd ziet. Een lijst
  vóór de eerste ophaalronde (409) en een `paginaGrootte=0` (400 uit Bean Validation) zijn
  weigeringen, een onbereikbare opslag (503) niet, een geslaagde lijst geeft geen exceptie door, en
  een al lopende ronde op `_ophalen` (409) is ook een weigering.
- `OphaalMislukkingTest` (unit, vervangt `LogboekStatusVoorTest`): per soort event een samenvatting
  of `null`, en geen foutmelding uit het event in de samenvatting.
- `OphaalUitkomstTest` (unit, MockK): de juiste logregel en samenvatting naar
  `recordFailedOutcome`, op de uitvoerder en niet op de aanroepende thread, het token bij een
  verloren uitkomst, en geen exceptie naar buiten — ook niet bij een wrapper-fout.
- `OphalenLogboekUitkomstTest` (`@QuarkusTest`, met de `ProcessingHandler` per test vervangen): een
  ronde met een mislukte organisatie, alle organisaties mislukt, een `OphalenFout` en een
  streamfout geven elk één `recordFailedOutcome` met de naam, activiteit en betrokkene van de
  logregel; een geslaagde ronde, een ronde zonder organisaties, een ronde met alleen
  `nietOpgehaald` en een geweigerde start geven er geen.

Bewust niet:

- een integratietest tegen een echte LDV-tabel, zoals het magazijn heeft. De uitvraag heeft geen
  datasource en geen PostgreSQL in haar tests; dat vraagt een eigen container-resource. Of die
  toevoeging het waard is, leggen we voor. Het gedrag van `expectException` en
  `recordFailedOutcome` zelf is in de wrapper en in het magazijn al tegen PostgreSQL getoetst.
- het magazijn en de uitvraag één gedeelde helper voor `recordFailedOutcome` laten delen: dat raakt
  het magazijn, en de uitvraag-variant is klein.

## Stappen

1. `LogboekWeigering.kt` met de interceptor.
2. `OphaalUitkomst.kt` (samenvatting, vastleggen) en `OphalenSseResource` erop aansluiten.
3. Tests.
4. `docs/operator-handleiding-uitvraag.md`: de leesregel in plaats van `OK`, wat een weigering is,
   de foutattributen van een ERROR-child en het token `LDV_UITKOMST_ONTBREEKT` voor de uitvraag.

## Verificatie

- `./mvnw clean test -pl services/berichtenuitvraag -am` (JaCoCo-gate) en `detekt:check`.
