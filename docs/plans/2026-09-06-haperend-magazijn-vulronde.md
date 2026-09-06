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
`vullingSoort` kleurt de melding *let-op* zodra hij niet nul is. Bij het nalopen daarvan bleek het
merkteken naast de knop maar twee van de drie uitkomstsoorten te kennen: `fout` viel in de
`gelukt`-tak, dus een volledig mislukte vulling kreeg een groen vinkje naast een rode melding. Dat
loopt nu via één vertaaltabel. `markeringMislukt` blijft daarnaast
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

Een breed vangnet kost wel iets, en dat wordt apart teruggegeven:

- **Een bedradingsfout mag niet als magazijnstoring lezen.** Vóór de verbreding vloog een fout in de
  console zelf — een ontbrekende provider, een geweigerde header — naar `DemoFoutMapper` en werd een
  luide 500 mét stacktrace. Nu telt hij als één mislukt bericht, precies zoals een magazijn dat uit
  staat, en tijdens een demo gaat de bediener dan de storingsknoppen controleren. `meldStoring(...)`
  scheidt de twee: een `ProcessingException`/`IOException`/`TimeoutException` is een storing en gaat
  op `WARNING`, al het andere op `SEVERE` met de mededeling dat dit géén magazijnstoring is.
- **Een melding is geen veilige logregel.** `catch (Exception)` vangt nu ook fouten die de aanroep
  zelf afwijzen, en die dragen de request-body of de `X-Ontvanger`-header in hun melding — dus een
  BSN. `oorzaakketen(...)` schrijft daarom alleen de klassennamen door de cause-keten
  (`ProcessingException <- ConnectException`): de diagnose die `toString()` juist weglaat, zonder
  ooit een `message`. Een test vangt de logregels op en faalt zodra er een identificatienummer in
  staat.

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

Zevenentwintig testmethodes, negenentwintig gevallen (de blanco-`berichtId`-test is een
`@ParameterizedTest` over `""`, `" "` en `"\t"`):

| Geval | Verwacht |
|---|---|
| afgekapte body midden in de ronde (`ProcessingException`) | 3 aangeboden, 3 geslaagd, 1 zonderBerichtId |
| lege body (`readEntity` geeft null) | idem |
| berichtId `""`, `" "`, `"\t"` | idem, en géén PATCH met die waarde |
| ontkoppelde entity (`IllegalStateException`) | idem |
| onbruikbaar antwoord, `gelezen = true` | geslaagd 1, markeringMislukt 1, zonderBerichtId 1, geen PATCH |
| onbruikbaar antwoord, `gelezen = false` | geslaagd 1, zonderBerichtId 1 — zichtbaar zónder markering |
| `close()` wordt aangeroepen ook als het uitlezen gooit | precies één keer |
| `close()` wordt aangeroepen als alles goed gaat | precies één keer — het pad dat een ronde honderd keer loopt |
| `close()` gooit zelf | ronde loopt door, alles geslaagd |
| het markeer-antwoord wordt gesloten, ook bij HTTP 500 | precies één keer |
| onbereikbaar magazijn (`ProcessingException`) midden in de ronde | mislukt 1, geslaagd 2 |
| een fout búiten `ProcessingException` midden in de ronde | mislukt 1, geslaagd 2 |
| HTTP 400 midden in de ronde | mislukt 1, geslaagd 2; geen `readEntity`, wél `close()` |
| HTTP 200 midden in de ronde | mislukt 1 — het contract kent maar één succesvorm |
| onbekende OIN midden in de ronde | mislukt 1, geslaagd 2 |
| twee magazijnen, elk bericht naar zijn eigen OIN | elk magazijn krijgt precies zijn eigen verzoeken |
| twee magazijnen, A hapert | B levert door; geslaagd 3, zonderBerichtId 1 |
| markering per bericht | PATCH met het berichtId én de ontvanger van dát bericht |
| PATCH geeft HTTP 500 | geslaagd 1, markeringMislukt 1 |
| PATCH gooit | geslaagd 1, markeringMislukt 1 |
| ronde met vier soorten hapering door elkaar | 5 aangeboden, 3 geslaagd, 2 mislukt, 2 markeringMislukt, 1 zonderBerichtId |
| twee onbruikbare antwoorden in één ronde | zonderBerichtId 2 — een teller, geen vlag |
| geen enkele logregel draagt een identificatienummer | vier onderdrukte fouten, elk een regel mét OIN en zonder waarde |
| de logregel noemt waarom het berichtId ontbrak | `ProcessingException` respectievelijk "geen berichtId in het antwoord" |
| een fout die geen magazijnstoring is klinkt luider | `WARNING` versus `SEVERE` |
| lege opdrachtenlijst | alles 0 |
| één opdracht, alles goed | geslaagd 1 |

### Mutatietest

Er staat geen mutatietest-plugin in de build, dus met de hand: mutant erin, alleen
`AanleverServiceTest` draaien, noteren wélke tests omvallen, mutant eruit. **Achtentwintig mutanten,
achtentwintig gedood**; elke test in het bestand doodt er minstens één, ook de tests die bestaand
gedrag vastleggen.

| Mutant in `AanleverService` | Gedood door |
|---|---|
| vangnet om `readEntity` versmald tot een niet-matchend type | afgekapt; ontkoppeld; onbruikbaar-tests; A-hapert |
| `readEntity(...)?.berichtId` → zonder safe call | **de logregel noemt waarom het berichtId ontbrak** |
| `isNullOrBlank()` → `isNullOrEmpty()` | **berichtId zonder inhoud** (alleen dankzij `" "`/`"\t"`) |
| `isNullOrBlank()` → `== null` | **berichtId zonder inhoud** |
| `onleesbaar(...)` zonder de oorzaak | **de logregel noemt waarom het berichtId ontbrak** |
| `zonderBerichtId++` weg | alle onbruikbaar-antwoord-gevallen |
| `zonderBerichtId++` → `= 1` | **twee onbruikbare antwoorden in één ronde** |
| `AfgeleverdZonderId` telt als `mislukt` | alle onbruikbaar-antwoord-gevallen |
| `markeringMislukt++` onvoorwaardelijk (ook zonder `gelezen`) | afgekapt; leeg; ontkoppeld; onbruikbaar-zonder-gelezen; A-hapert |
| `finally { sluitStil(...) }` weg na aanleveren | gesloten-antwoord; geweigerde aanlevering |
| sluiten alleen wanneer de uitkomst géén `Afgeleverd` is | **gesloten als alles goed gaat** |
| `sluitStil` gooit door in plaats van te loggen | **antwoord dat niet te sluiten is** |
| `close()` twee keer aangeroepen | vier close-tests (dankzij `exactly = 1`) |
| `finally { sluitStil(...) }` weg in `markeerGelezen` | markeer-antwoord gesloten |
| markeer-antwoord alleen sluiten bij HTTP 200 | **markeer-antwoord bij HTTP 500 gesloten** |
| vangnet om `client.leverAan` versmald tot `ProcessingException` | fout buiten ProcessingException; luider-test |
| vangnet om `client.markeer` versmald | PATCH gooit; logregel-test |
| `response.status != 201` → `== 201` | het merendeel van de suite |
| `response.status != 201` → `!in 200..299` | **een andere 2xx dan 201** |
| onbekend magazijn telt `geslaagd++` | **onbekende OIN** |
| markeer-status `== 200` → `!= 200` | vier markeer-tests |
| `opdrachten.size` → `.coerceAtLeast(1)` | **lege ronde** |
| `opdrachten.forEach` → `.drop(1).forEach` | vrijwel de hele suite |
| `clients[oin]` → `clients.values.firstOrNull()` | **beide routeringstests** + onbekende OIN |
| `X-Ontvanger` zonder de waarde | markering per bericht; gemengde ronde |
| `gelezen`-guard om `markeerGelezen` weg | markering per bericht; gemengde ronde; ronde van één |
| ontvanger-*waarde* in plaats van -*type* in de logregel | **geen logregel draagt een identificatienummer** |
| `oorzaakketen` logt `toString()` in plaats van de types | **geen logregel draagt een identificatienummer** |
| magazijn-OIN valt uit de markeer-logregel | **geen logregel draagt een identificatienummer** |
| onverwachte fout op `WARNING` in plaats van `SEVERE` | **een fout die geen magazijnstoring is klinkt luider** |
| storing op `SEVERE` in plaats van `WARNING` | **een fout die geen magazijnstoring is klinkt luider** |

Vetgedrukt = de enige test die die mutant doodt.

Twee mutanten die in de vorige ronde nog overleefden — de safe call weghalen en de oorzaak uit de
`onleesbaar`-logregel laten vallen — waren toen als "equivalent" afgeboekt omdat het waarneembare
verschil alleen in de logtekst zat. Dat was precies het gat: de logtekst was nergens vastgelegd. Met
de logtests erbij zijn het gewoon gedode mutanten, en dat is de nettere uitkomst.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am` groen: 297 tests in de module (waarvan 29 in
  `AanleverServiceTest`), detekt 0 bevindingen.
- Mutatietest: 28/28 gedood, geen overlevers.
- Geen nieuwe build-warnings. De `WARN Proxy … niet uit te lezen`- en `gesimuleerde magazijnen
  niet gevuld`-regels zijn logoutput van tests die foutpaden uitlokken, geen bouwmelding.
- Geen enkele logregel draagt een identificatienummer — en dat is nu een test, geen leescontrole.

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
