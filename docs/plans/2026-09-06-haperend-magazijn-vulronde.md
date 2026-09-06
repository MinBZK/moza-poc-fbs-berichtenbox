# Een haperend magazijn laat de vulronde niet meer stranden

**Status:** Uitgevoerd

Lost [MinBZK/MijnOverheidZakelijk#1066](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1066) op.

## Context

Het bedieningspaneel vult de magazijnen via `AanleverService.leverAan(...)`. Die lus is bedoeld om
per bericht te falen zonder de ronde af te breken — het comment boven de `try` in `lever()` zei dat
met zoveel woorden — maar de `try`/`catch (ProcessingException)` stond alleen om
`client.leverAan(...)`. De regel die het antwoord uitleest viel erbuiten:

```kotlin
return response.use {
    if (it.status != 201) { ...; return@use null }

    it.readEntity(AanleverRespons::class.java).berichtId   // <-- buiten de catch
}
```

Een magazijn dat de 201-statusregel wél stuurt maar de body afkapt, leeg laat of niet als JSON
aanlevert, laat `readEntity` gooien. Die exception vloog door `lever()` en `leverAan()` heen naar
`DemoFoutMapper`, werd HTTP 500, en het paneel meldde "actie mislukt" zonder één cijfer over wat er
al wél afgeleverd was. Het paneel heeft zelf knoppen om een magazijn traag of onbereikbaar te maken,
dus dit is een demo-realistisch geval.

Raakt alle vulknoppen tegelijk — *Basisvulling*, *Random berichten*, *Bericht plaatsen*,
*Herstel demo* en de stroom uit `TempoService` — want die gaan alle vijf door dezelfde `lever()`.

Concrete gaten in het oude `lever()`:

| Antwoord van het magazijn | Was | Is |
|---|---|---|
| 201 + afgekapte body | `ProcessingException` → HTTP 500, ronde breekt af | ronde loopt door |
| 201 + lege body | idem (of `NullPointerException` als de runtime `null` teruggeeft) | ronde loopt door |
| 201 + niet-JSON body (proxy-foutpagina) | idem | ronde loopt door |
| 201 + JSON zonder `berichtId` | idem | ronde loopt door |
| 201 + `{"berichtId": ""}` of whitespace | telde als geslaagd, PATCH ging naar `/berichten/` | ronde loopt door, geen zinloze PATCH |
| gesloten/ontkoppelde entity | `IllegalStateException` → HTTP 500 | ronde loopt door |
| `Response.close()` gooit | HTTP 500 op een bericht dat al is afgeleverd | ronde loopt door |
| een exception buiten die twee types | HTTP 500 | ronde loopt door |

## Ontwerpkeuze 1: een onleesbaar antwoord op een 201 telt als *geslaagd*

De issue noemt als oplossingsrichting "als `mislukt` tellen". Dat doen we bewust niet, omdat het het
probleem uit de issue-titel niet oplost.

De Aanlever-API belooft bij een 201 dat het bericht is opgeslagen (`berichtenmagazijn-api.yaml`:
*"201: Bericht succesvol opgeslagen"*). Het bericht *staat* op dat moment in het magazijn. Meldt het
paneel dat bericht als `mislukt`, dan leest de bediener "1 mislukt", drukt opnieuw, en krijgt precies
de dubbele berichten waar de issue over gaat. `geslaagd` is de eerlijke telling: het bericht is er.

## Ontwerpkeuze 2: een eigen teller `zonderBerichtId`

Wat we wél kwijt zijn, is het `berichtId`. Dat hebben we nodig om het bericht op *gelezen* te zetten,
dus de eerste opzet telde het geval alleen als `markeringMislukt` — en dus alleen wanneer om gelezen
gevraagd was. Dat verbergt het in de meerderheid van de gevallen: `Basisdataset` zet `gelezen` op
`volgnummer % 4 == 1`, dus drie van de vier berichten in de basisvulling vallen erbuiten, en de
random-vulling en *Bericht plaatsen* gebruiken de default `gelezen = false`. Het paneel toont dan
groen "100 van 100 aangeleverd" terwijl er honderd waarschuwingen in de log staan.

`AanleverResultaat` krijgt daarom een vijfde teller, `zonderBerichtId`, die los van `gelezen`
meetelt. Het paneel noemt hem in `vullingTekst` ("N zonder bevestigd berichtnummer") en
`vullingSoort` kleurt de melding *let-op* zodra hij niet nul is. `markeringMislukt` blijft daarnaast
bestaan en telt onveranderd het bericht dat wél te markeren viel maar waarvan de PATCH mislukte —
plus, wanneer om gelezen gevraagd was, het bericht zonder berichtId, want daar is de leesstatus ook
echt niet gezet.

## Ontwerpkeuze 3: `lever()` geeft drie uitkomsten terug, geen `String?`

`String?` kan "afgeleverd, id onbekend" niet uitdrukken — `null` betekende "niet afgeleverd". Een
`sealed interface` met drie varianten maakt de telling in `leverAan` een uitputtende `when`, zodat
een vierde uitkomst er later niet stilletjes bij kan.

## Ontwerpkeuze 4: het vangnet is breed, niet opgesomd

Elke stap in `lever()` valt in een vangnet: de aanroep, het uitlezen én het sluiten. Die vangnetten
zijn `catch (fout: Exception)` en niet een opsomming van bekende types.

Voor `readEntity` is nagegaan wat de Quarkus REST-client (3.38) werkelijk kan gooien — een
`IOException` gewikkeld in `ProcessingException`, en `IllegalStateException` uit `checkClosed()` of
`BlockingNotAllowedException` — dus een opsomming zóú vandaag volstaan. Maar dan hangt de garantie
"één bericht kan de ronde niet afbreken" aan een implementatiedetail dat met een upgrade verschuift,
en dat is precies de klasse fout die dit issue is. `detekt.yml` zet `TooGenericExceptionCaught`
bewust uit met dezelfde motivatie, en `SwallowedException` blijft actief: elke catch logt.

`Response.close()` hoort daar ook bij. Kotlins `use` laat een exception uit `close()` doorvliegen
wanneer het blok zélf slaagde, en de JAX-RS-javadoc staat `ProcessingException` uit `close()`
expliciet toe — juist bij het afronden van een half afgekapte stream. Daarom `try`/`finally` met een
`sluitStil(...)` die zijn eigen fout logt, in plaats van `use`.

## Ontwerpkeuze 5: de clients komen via de constructor binnen

`AanleverService` bouwde zijn REST-clients in de constructor met `QuarkusRestClientBuilder`. Daar
valt geen haperend magazijn tegen te zetten zonder Quarkus-runtime, Docker of een echte socket — en
`demo-console` heeft bewust geen van drieën in zijn testsuite.

Daarom een tweede, `internal` constructor die de kant-en-klare map met clients aanneemt; de
`@Inject`-constructor bouwt hem uit de config. Geen gedragsverandering in productie. Bewust géén
test-subklasse van de bean: ArC vlagt anonieme subklassen van CDI-beans als eigen bean.

## Tests

Alle in `demo/demo-console/src/test/.../aanlever/AanleverServiceTest.kt`, pure JVM. Zowel het
magazijn als zijn `jakarta.ws.rs.core.Response` zijn MockK-mocks — alleen zo valt een `readEntity`
die gooit of `null` geeft na te bootsen zonder een echte, half afgekapte HTTP-stream. Het magazijn
mag bewust géén eigen klasse zijn die `MagazijnAanleverClient` implementeert: die interface draagt
JAX-RS-annotaties, dus een geïndexeerde implementatie wordt in de Quarkus-testapplicatie van deze
module als serverresource geregistreerd en botst daar op `POST /api/v1/aanleveringen`.

In de meerstapstests staat de hapering in het **middelste** bericht, zodat "de ronde loopt door" ook
echt getoetst wordt en niet alleen "de laatste opdracht slaagde". Elke test asserteert de hele
`AanleverResultaat`, niet één veld.

Eenentwintig testmethodes, drieëntwintig gevallen (de blanco-`berichtId`-test is een
`@ParameterizedTest` over `""`, `" "` en `"\t"`):

| Geval | Verwacht |
|---|---|
| afgekapte body midden in de ronde (`ProcessingException`) | 3 aangeboden, 3 geslaagd, 1 zonderBerichtId |
| lege body (`readEntity` geeft null) | idem |
| berichtId `""`, `" "`, `"\t"` | idem, en géén PATCH met die waarde |
| ontkoppelde entity (`IllegalStateException`) | idem |
| onbruikbaar antwoord, `gelezen = true` | geslaagd 1, markeringMislukt 1, zonderBerichtId 1, geen PATCH |
| onbruikbaar antwoord, `gelezen = false` | geslaagd 1, zonderBerichtId 1 — zichtbaar zónder markering |
| `close()` wordt aangeroepen ook als het uitlezen gooit | `close()` aangeroepen |
| `close()` gooit zelf | ronde loopt door, alles geslaagd |
| het markeer-antwoord wordt gesloten | `close()` aangeroepen |
| onbereikbaar magazijn (`ProcessingException`) midden in de ronde | mislukt 1, geslaagd 2 |
| een fout búiten `ProcessingException` midden in de ronde | mislukt 1, geslaagd 2 |
| HTTP 400 midden in de ronde | mislukt 1, geslaagd 2; geen `readEntity`, wél `close()` |
| onbekende OIN midden in de ronde | mislukt 1, geslaagd 2 |
| twee magazijnen, elk bericht naar zijn eigen OIN | elk magazijn krijgt precies zijn eigen verzoeken |
| twee magazijnen, A hapert | B levert door; geslaagd 3, zonderBerichtId 1 |
| markering per bericht | PATCH met het berichtId én de ontvanger van dát bericht |
| PATCH geeft HTTP 500 | geslaagd 1, markeringMislukt 1 |
| PATCH gooit | geslaagd 1, markeringMislukt 1 |
| ronde met vier soorten hapering door elkaar | 5 aangeboden, 3 geslaagd, 2 mislukt, 2 markeringMislukt, 1 zonderBerichtId |
| lege opdrachtenlijst | alles 0 |
| één opdracht, alles goed | geslaagd 1 |

### Mutatietest

Er staat geen mutatietest-plugin in de build, dus met de hand: mutant erin, alleen
`AanleverServiceTest` draaien, noteren wélke tests omvallen, mutant eruit. Twintig mutanten,
negentien gedood; elke test in het bestand doodt er minstens één, ook de tests die bestaand gedrag
vastleggen.

| Mutant in `AanleverService` | Gedood door |
|---|---|
| vangnet om `readEntity` versmald tot een niet-matchend type | afgekapt; ontkoppeld; beide onbruikbaar-tests; gesloten-antwoord; A-hapert; gemengde ronde |
| `isNullOrBlank()` → `isNullOrEmpty()` | **berichtId zonder inhoud** (alleen dankzij `" "`/`"\t"`) |
| `isNullOrBlank()` → `== null` | **berichtId zonder inhoud** |
| `zonderBerichtId++` weg | alle zeven onbruikbaar-antwoord-gevallen |
| `AfgeleverdZonderId` telt als `mislukt` | idem |
| `markeringMislukt++` onvoorwaardelijk (ook zonder `gelezen`) | afgekapt; leeg; ontkoppeld; onbruikbaar-zonder-gelezen; A-hapert |
| `try`/`finally` om het sluiten weg | gesloten-antwoord; geweigerde aanlevering |
| `sluitStil` gooit door in plaats van te loggen | **antwoord dat niet te sluiten is** |
| vangnet om `client.leverAan` versmald tot `ProcessingException` | **fout buiten ProcessingException** |
| `response.status != 201` → `== 201` | negentien van de eenentwintig testmethodes |
| onbekend magazijn telt `geslaagd++` | **onbekende OIN** |
| markeer-status `== 200` → `!= 200` | berichtId zonder inhoud; geweigerde markering; markering per bericht; gemengde ronde |
| vangnet om `client.markeer` versmald | **PATCH gooit** |
| `opdrachten.size` → `.coerceAtLeast(1)` | **lege ronde** |
| `opdrachten.forEach` → `.drop(1).forEach` | alle eenentwintig testmethodes |
| `clients[oin]` → `clients.values.firstOrNull()` | **beide routeringstests** + onbekende OIN |
| `finally { sluitStil(...) }` weg in `markeerGelezen` | **markeer-antwoord gesloten** |
| `X-Ontvanger` zonder de waarde | markering per bericht; gemengde ronde |
| `gelezen`-guard om `markeerGelezen` weg | markering per bericht; gemengde ronde; ronde van één |

Vetgedrukt = de enige test die die mutant doodt.

**Eén overlevende mutant, en die is equivalent.** `response.readEntity(...)?.berichtId` →
`response.readEntity(...).berichtId` (de safe call weg) overleeft alle tests. Dat is geen testgat: de
`NullPointerException` die daaruit volgt valt in hetzelfde brede vangnet (ontwerpkeuze 4), dus het
waarneembare gedrag is identiek — alleen de logregel noemt dan `NullPointerException` in plaats van
"geen berichtId in het antwoord". De safe call blijft staan omdat hij de juiste diagnose oplevert en
niet op het vangnet leunt.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am` groen: 294 tests in de module, detekt 0 bevindingen.
- Mutatietest: 19/20 gedood, 1 equivalent (hierboven verantwoord).
- Geen nieuwe warnings. De `WARN Proxy … niet te verzoenen`-regels komen uit de al bestaande
  `StoringService`-tests (geen Toxiproxy in de testomgeving) en stonden er vóór deze wijziging ook.
- Nieuwe logregels noemen alleen `magazijnOin` (publiek), het ontvanger-*type* en het berichtId
  (UUID) — nooit een BSN. De fout van een mislukt `readEntity` gaat als *type* de log in en niet als
  melding: de body die daar niet te lezen viel draagt de ontvanger, en een parserfout mag zijn bron
  in de melding meenemen.

## Buiten scope, wel gezien

Uit de review, niet in deze PR opgelost omdat het losse defecten zijn met hun eigen afweging:

- `TempoService.tik()` telt `geleverd++` per tik in plaats van per aflevering, dus de chip van de
  berichtenstroom blijft doortellen terwijl een magazijn uit staat. Ook de bovengrens
  (`geleverd >= MAX_BERICHTEN`) hangt aan die telling, dus het is geen wijziging van één regel.
- Een `ProcessingException` op de aanroep zelf telt onvoorwaardelijk als `mislukt`, ook als het
  magazijn het bericht wél opsloeg en alleen het antwoord wegviel (read-timeout). Onderscheid maken
  vergt classificatie van de oorzaak en een eigen "onzeker"-teller in het paneel.
- De `application/problem+json`-body van een niet-201 wordt niet gelezen, dus "40× HTTP 400" zegt de
  bediener niet wát er mis was, terwijl het magazijn `title`/`detail`/`instance` meestuurt.
- `FoutieveAanleverService` heeft een ongeschermde `readEntity` — daar is het één actie en geen
  ronde, dus de uitwerking is kleiner, maar het is dezelfde valkuil.
