# Een haperend magazijn laat de vulronde niet meer stranden

**Status:** Uitgevoerd

Lost [MinBZK/MijnOverheidZakelijk#1066](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1066) op.

## Context

Het bedieningspaneel vult de magazijnen via `AanleverService.leverAan(...)`. Die lus is bedoeld om
per bericht te falen zonder de ronde af te breken — het comment boven de `try` in `lever()` zegt dat
met zoveel woorden — maar de `try`/`catch (ProcessingException)` staat alleen om
`client.leverAan(...)`. De regel die het antwoord uitleest valt erbuiten:

```kotlin
return response.use {
    if (it.status != 201) { ...; return@use null }

    it.readEntity(AanleverRespons::class.java).berichtId   // <-- buiten de catch
}
```

Een magazijn dat de 201-statusregel wél stuurt maar de body afkapt, leeg laat of niet als JSON
aanlevert, laat `readEntity` gooien. Die exception vliegt door `lever()` en `leverAan()` heen naar
`DemoFoutMapper`, wordt HTTP 500, en het paneel meldt "actie mislukt" zonder één cijfer over wat er
al wél afgeleverd is. Het paneel heeft zelf knoppen om een magazijn traag of onbereikbaar te maken
en *Bericht plaatsen* levert tot honderd berichten per ronde, dus dit is een demo-realistisch geval.

Raakt alle vulknoppen tegelijk — *Basisvulling*, *Random berichten*, *Bericht plaatsen*,
*Herstel demo* en de stroom uit `TempoService` — want die gaan alle vijf door dezelfde `lever()`.

Concrete gaten in het huidige `lever()`:

| Antwoord van het magazijn | Nu | Gewenst |
|---|---|---|
| 201 + afgekapte body | `ProcessingException` → HTTP 500, ronde breekt af | ronde loopt door |
| 201 + lege body | idem (of `NullPointerException` als de runtime `null` teruggeeft) | ronde loopt door |
| 201 + niet-JSON body (proxy-foutpagina) | idem | ronde loopt door |
| 201 + JSON zonder `berichtId` | idem (`jackson-module-kotlin` eist het niet-nullable veld) | ronde loopt door |
| 201 + `{"berichtId": ""}` | telt als geslaagd, PATCH gaat naar `/berichten/` | ronde loopt door, geen zinloze PATCH |
| gesloten/ontkoppelde entity | `IllegalStateException` → HTTP 500 | ronde loopt door |

## Ontwerpkeuze 1: een onleesbaar antwoord op een 201 telt als *geslaagd*

De issue noemt als oplossingsrichting "als `mislukt` tellen". Dat doen we bewust niet, omdat het het
probleem uit de issue-titel niet oplost.

Een 201 is er alleen als het magazijn de aanlevering heeft verwerkt en de statusregel al heeft
weggeschreven. Het bericht *staat* op dat moment in het magazijn. Meldt het paneel dat bericht als
`mislukt`, dan leest de bediener "1 mislukt", drukt opnieuw, en krijgt precies de dubbele berichten
waar de issue over gaat. `geslaagd` is de eerlijke telling: het bericht is er.

Wat we wél kwijt zijn, is het `berichtId` — en dat hebben we alleen nodig om het bericht op *gelezen*
te zetten. Daarvoor bestaat `markeringMislukt` al, met precies deze betekenis in zijn KDoc:
"berichten die wél zijn afgeleverd maar niet op gelezen konden worden gezet". Dus:

- `opdracht.gelezen == true` → `geslaagd++` én `markeringMislukt++`; het paneel toont
  "N van N aangeleverd, 1 niet op gelezen gezet" en kleurt *let-op* (`vullingSoort` in
  `bediening.js` kijkt al naar `markeringMislukt`).
- `opdracht.gelezen == false` → `geslaagd++`; er is niets misgegaan wat de bediener raakt. De
  logregel legt het wel vast.

Dat is geen frontend-wijziging: `vullingTekst` en `vullingSoort` dekken deze uitkomst al.

## Ontwerpkeuze 2: `lever()` geeft drie uitkomsten terug, geen `String?`

`String?` kan "afgeleverd, id onbekend" niet uitdrukken — `null` betekent nu "niet afgeleverd". Een
`sealed interface` met drie varianten maakt de drie tellingen in `leverAan` een uitputtende `when`,
zodat een vierde uitkomst er later niet stilletjes bij kan.

```kotlin
private sealed interface LeverUitkomst {
    /** Afgeleverd, met het door het magazijn toegekende berichtId. */
    data class Afgeleverd(val berichtId: String) : LeverUitkomst
    /** 201 ontvangen — het bericht staat in het magazijn — maar het antwoord droeg geen bruikbaar berichtId. */
    data object AfgeleverdZonderId : LeverUitkomst
    /** Niet afgeleverd. */
    data object Mislukt : LeverUitkomst
}
```

## Ontwerpkeuze 3: de clients komen via de constructor binnen

`AanleverService` bouwt zijn REST-clients nu in de constructor met `QuarkusRestClientBuilder`. Daar
valt geen haperend magazijn tegen te zetten zonder Quarkus-runtime, Docker of een echte socket — en
`demo-console` heeft bewust geen van drieën in zijn testsuite (pure JVM plus één `@QuarkusTest`).

Daarom een tweede, `internal` constructor die de kant-en-klare map met clients aanneemt; de
`@Inject`-constructor bouwt hem uit de config. Geen gedragsverandering in productie, en de test kan
een magazijn neerzetten dat precies het afgekapte antwoord teruggeeft. Bewust géén test-subklasse
van de bean: ArC vlagt anonieme subklassen van CDI-beans als eigen bean.

## Stappen

1. **Tests eerst** (`AanleverServiceTest.kt`, nieuw, pure JVM + MockK) — rood op de huidige code.
2. `AanleverService`: `internal constructor(clients: Map<String, MagazijnAanleverClient>)` +
   `@Inject constructor(config: DemoConfig)`; het bouwen van de clients naar een private companion-
   functie.
3. `lever()` geeft `LeverUitkomst` terug: `readEntity` binnen een `try`, `catch (ProcessingException)`
   en `catch (IllegalStateException)`, resultaat null-safe uitlezen en een blanco `berichtId` als
   onbruikbaar behandelen. Logregel noemt magazijn-OIN, ontvanger-*type* (nooit de waarde) en dat het
   antwoord geen berichtId droeg.
4. `leverAan()`: `when` over de drie uitkomsten.
5. Comment boven de `try` bijwerken zodat het de nieuwe grens beschrijft.
6. Handmatige mutatietest op álle tests in het nieuwe bestand (zie hieronder).
7. `./mvnw clean verify -pl demo/demo-console -am`, warnings nalopen.

## Tests

Alle in `demo/demo-console/src/test/.../aanlever/AanleverServiceTest.kt`, pure JVM. Zowel het
magazijn als zijn `jakarta.ws.rs.core.Response` zijn MockK-mocks — alleen zo valt een `readEntity`
die gooit of `null` geeft na te bootsen zonder een echte, half afgekapte HTTP-stream. Het magazijn
mag bewust géén eigen klasse zijn die `MagazijnAanleverClient` implementeert: die interface draagt
JAX-RS-annotaties, dus een geïndexeerde implementatie wordt in de `@QuarkusTest` van deze module als
serverresource geregistreerd en laat de boot stuklopen op een dubbele `POST /api/v1`.

Elke test die een ronde nabootst gebruikt **meerdere** opdrachten met de hapering in het midden,
zodat "de ronde loopt door" ook echt getoetst wordt en niet alleen "de laatste opdracht slaagde".

| # | Geval | Verwacht |
|---|---|---|
| 1 | 3 opdrachten, middelste geeft 201 + afgekapte body (`ProcessingException`) | aangeboden 3, geslaagd 3, mislukt 0; alle drie aangeroepen |
| 2 | idem, `readEntity` geeft `null` (lege body) | idem |
| 3 | idem, `readEntity` geeft `berichtId = ""` | idem, en géén PATCH voor dat bericht |
| 4 | idem, `readEntity` gooit `IllegalStateException` | idem |
| 5 | onbruikbaar antwoord met `gelezen = true` | geslaagd 1, markeringMislukt 1, geen markeer-aanroep |
| 6 | onbruikbaar antwoord met `gelezen = false` | geslaagd 1, markeringMislukt 0 |
| 7 | de response wordt gesloten, ook als `readEntity` gooit | `close()` aangeroepen |
| 8 | onbereikbaar magazijn (`ProcessingException` uit `leverAan`) midden in de ronde | mislukt 1, geslaagd 2 |
| 9 | HTTP 400 midden in de ronde | mislukt 1, geslaagd 2; geen `readEntity` |
| 10 | onbekende OIN midden in de ronde | mislukt 1, geslaagd 2; geen aanroep voor die opdracht |
| 11 | happy path, `gelezen = true` | geslaagd, markeringMislukt 0, PATCH met het juiste berichtId en `X-Ontvanger` |
| 12 | mislukte PATCH (HTTP 500) | geslaagd 1, markeringMislukt 1 |
| 13 | lege opdrachtenlijst | alles 0 |
| 14 | één opdracht, alles goed | geslaagd 1 (cardinaliteit leeg/één/meer gedekt) |

### Mutatietest

Er staat geen mutatietest-plugin in de build, dus met de hand: mutant erin, alleen
`AanleverServiceTest` draaien, noteren wélke tests omvallen, mutant eruit. Alle vijftien mutanten
zijn gedood, en elke test in het bestand doodt er minstens één — ook de tests die bestaand gedrag
vastleggen.

| Mutant in `AanleverService` | Gedood door |
|---|---|
| `catch (ProcessingException)` om `readEntity` → een type dat niet matcht | afgekapt antwoord; gesloten-antwoord; beide onbruikbaar-antwoord-tests; ronde van één |
| `catch (IllegalStateException)` om `readEntity` → idem | **ontkoppelde entity**; ronde van één |
| `respons?.berichtId` → `respons.berichtId` | **leeg antwoord**; ronde van één |
| `berichtId.isNullOrBlank()` → `berichtId == null` | **blanco berichtId**; ronde van één |
| `AfgeleverdZonderId` telt als `mislukt` | alle zes onbruikbaar-antwoord-tests; ronde van één |
| `berichtId == null \|\|` uit de markeer-guard | blanco berichtId; onbruikbaar-met-gelezen; ronde van één |
| `if (!opdracht.gelezen) return@forEach` weg | afgekapt; leeg; ontkoppeld; onbruikbaar-zonder-gelezen; geslaagde ronde; ronde van één |
| `response.use { }` → `response.let { }` | **gesloten ook als het uitlezen gooit**; ronde van één |
| `catch (ProcessingException)` om `client.leverAan` → idem | **onbereikbaar magazijn**; ronde van één |
| `it.status != 201` → `== 201` | dertien van de veertien tests |
| onbekend magazijn telt `geslaagd++` i.p.v. `mislukt++` | **onbekend magazijn**; ronde van één |
| `it.status != 200` → `== 200` in `markeerGelezen` | blanco berichtId; geslaagde ronde; geweigerde markering; ronde van één |
| `catch (ProcessingException)` om `client.markeer` → idem | **onbereikbaar bij markeren**; ronde van één |
| `opdrachten.size` → `opdrachten.size.coerceAtLeast(1)` | **lege ronde** |
| `opdrachten.forEach` → `opdrachten.drop(1).forEach` | dertien van de veertien tests |

Vetgedrukt = de enige test die die mutant doodt.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am` groen: 294 tests, detekt 0 bevindingen.
- Mutatietabel hierboven volledig afgevinkt: 15/15 gedood.
- Geen nieuwe warnings. De `WARN Proxy … niet te verzoenen`-regels komen uit de al bestaande
  `StoringService`-tests (geen Toxiproxy in de testomgeving) en stonden er vóór deze wijziging ook.
- Nieuwe logregels noemen alleen `magazijnOin` (publiek) en het ontvanger-*type*, nooit een BSN.
