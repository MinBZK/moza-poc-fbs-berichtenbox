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

Bij de stroom kwam daar een eigen variant van hetzelfde probleem bij: `TempoService.tik()` gooide het
`AanleverResultaat` weg en telde `geleverd++` per tik. De chip meldde dus "42 geleverd" terwijl er
niets aankwam. Die telt nu de afleveringen; de bovengrens telt de pogingen, zodat een uitstaand
magazijn de stroom niet oneindig rekt.

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
meetelt en `markeringMislukt` uitsluit: een aflevering zonder berichtId telt alleen als
`zonderBerichtId`, ook wanneer om gelezen was gevraagd. Anders noemt het paneel twee problemen over
één bericht ("1 niet op gelezen gezet, 1 zonder bevestigd berichtnummer") en zijn de cijfers niet
meer op te tellen. `markeringMislukt` houdt daarmee één betekenis over: de PATCH is geprobeerd en
mislukt. Het paneel noemt hem in `vullingTekst` ("N zonder bevestigd berichtnummer") en
`vullingSoort` kleurt de melding *let-op* zodra hij niet nul is. Bij het nalopen daarvan bleek het
merkteken naast de knop maar twee van de drie uitkomstsoorten te kennen: `fout` viel in de
`gelukt`-tak, dus een volledig mislukte vulling kreeg een groen vinkje naast een rode melding. Dat
loopt nu via één vertaaltabel, die bij een onbekende soort op *let-op* terugvalt en niet op groen,
en `PaneelTellersTest` bewaakt dat elke teller én elke uitkomstsoort aan beide kanten bekend is. 

## Ontwerpkeuze 3: `lever()` geeft drie uitkomsten terug, geen `String?`

`String?` kan "afgeleverd, id onbekend" niet uitdrukken — `null` betekende "niet afgeleverd". Een
`sealed interface` met drie varianten maakt de telling in `leverAan` een uitputtende `when`, zodat
een vierde uitkomst er later niet stilletjes bij kan.

## Ontwerpkeuze 4: het vangnet is breed, niet opgesomd

Elke stap in `lever()` valt in een vangnet: de aanroep, het uitlezen én het sluiten. Die vangnetten
zijn `catch (fout: Exception)` en niet een opsomming van bekende types.

Voor `readEntity` is nagegaan wat de Quarkus REST-client (3.39.1) werkelijk kan gooien — een
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
  staat. `meld(...)` scheidt de twee: een storing gaat op `WARNING`, al het andere op `SEVERE` met de
  mededeling dat dit géén magazijnstoring is, plus het bovenste stackframe (namen en regelnummers,
  geen gegevens).

  De scheiding is `isStoring(...)`, en die kijkt naar het bovenste type. Dat mag, want de REST-client
  wikkelt alles: `InvocationBuilderImpl.unwrap` gooit een `ProcessingException` bij een
  `InterruptedException`, geeft een cause die zelf `ProcessingException` of `WebApplicationException`
  is ongewijzigd door, en wikkelt élke andere cause in een `ProcessingException` — nagelopen in de
  bytecode van `resteasy-reactive-client` 3.39.1. Een `ConnectException` of een timeout komt hier dus
  als `ProcessingException` binnen — óók een fout uit de asynchrone pipeline, zoals het schrijven
  van de request-body. Wat ongewikkeld doorkomt is een `WebApplicationException` (die draagt een
  statuscode van het magazijn en wordt dáárop beoordeeld) en `BlockingNotAllowedException`.

  Daarmee is het oordeel simpel: een `ProcessingException` is de overkant, al het andere is onze
  kant. Een `IllegalStateException` uit het uitlezen betekent dat de console het antwoord al gelezen
  had, en bij de aanroep dat er op de event-loop geblokkeerd wordt — allebei van ons. Ook de
  HTTP-status splitst mee: een 4xx betekent dat de console iets ongeldigs stuurde en klinkt luid, een 5xx is het
  magazijn. Alleen een 5xx en de wacht-en-probeer-later-codes 408 en 429 zijn de overkant; al het
  andere — een 4xx, maar ook een 2xx of 3xx die hier belandt en dus het contract breekt — komt van
  onze kant en treft elk bericht van de ronde. Het magazijn hanteert voor zijn eigen retries dezelfde
  lijst wachtcodes. Een 404 op de
  status-PATCH telt ook als storing: het magazijn is dan het bericht kwijt dat het één aanroep eerder
  zelf bevestigde. Een opdracht voor een OIN die niet in `demo.magazijnen` staat is per definitie een
  inrichtingsfout en gaat op `SEVERE`.

  Dat de default-exception-mapper uitstaat (`microprofile.rest.client.disable.default.mapper`) is
  wat de statuscodes als antwoord binnen laat komen in plaats van als exception. Die property is nu
  gepind in `ApplicationPropertiesTest`; valt hij weg, dan wordt de hele splitsing dode code.

  Het oordeel op het type hangt wél aan het wikkelgedrag van de client, en dat staat in geen enkele
  test — alle tests bouwen hun exception met de hand. Zou de client onbewerkte I/O-fouten gaan
  doorgeven, wat het reactieve `Uni`-pad doet, dan kantelt elke magazijnstoring naar de luide tak
  zonder dat er iets rood wordt. De KDoc bij `isStoring` zegt dat; een test die het vastpint zou een
  echte socket vergen en staat hieronder bij wat er niet in deze wijziging zit.
- **Een melding is geen veilige logregel.** `catch (Exception)` vangt nu ook fouten die de aanroep
  zelf afwijzen, en die dragen de request-body of de `X-Ontvanger`-header in hun melding — dus een
  BSN. `oorzaakketen(...)` schrijft daarom alleen de klassennamen door de cause-keten
  (`ProcessingException <- ConnectException`): de diagnose die `toString()` juist weglaat, zonder
  ooit een `message`. Het gaat via Java-reflectie en niet via `::class`: deze functie draait binnen
  elke catch, en Kotlin-reflectie kan zelf gooien — dan ontsnapt er alsnog een fout uit de ronde die
  dit vangnet moest houden. Een test vangt de logregels op — melding, throwable én parameters — en
  faalt zodra er een identificatienummer in staat.

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

Tweeënveertig testmethodes, drieënvijftig gevallen (drie ervan zijn `@ParameterizedTest`en), plus
zes in `PaneelTellersTest` en twee in `TempoServiceTest`. Wat ze dekken:

- **de ronde loopt door** — afgekapte body, lege body, blanco berichtId, ontkoppelde entity, een
  `close()` die zelf gooit, een onbereikbaar magazijn, een fout buiten `ProcessingException`, HTTP
  400, een 2xx die geen 201 is, een onbekende OIN. In elk daarvan zit de hapering in het middelste
  bericht van drie.
- **de tellers** — met en zonder `gelezen`, twee onbruikbare antwoorden in één ronde (een teller, geen
  vlag), een ronde met vier soorten hapering door elkaar, en de cardinaliteiten leeg/één/meer.
- **de routering** — twee magazijnen, elk bericht naar zijn eigen OIN, en een haperende A die B niet
  meesleept.
- **de markering** — elk bericht met zijn eigen berichtId en ontvanger (één BSN en één KVK, zodat het
  type niet vast kan staan), een geweigerde PATCH, een onbereikbaar magazijn, en een 204 die geen
  geslaagde markering is.
- **het sluiten** — precies één keer, op het succespad, het foutpad, het niet-201-pad en beide
  markeer-paden; een falende `close()` die de ronde niet raakt maar wel een logregel oplevert, zowel
  bij een transportfout als bij een stream die al dicht was.
- **de logregels** — geen enkele draagt een identificatienummer (melding, throwable én parameters),
  en dat scenario raakt élke plek waar een fout wordt onderdrukt: negen regels in één ronde, waarvan
  één op de luide tak mét throwable. Elke regel wijst zijn magazijn, ontvanger-type, statuscode of
  berichtId aan; de oorzaakketen loopt drie diep door; een fout die
  zichzelf als oorzaak noemt levert één naam op; een fout zonder eenvoudige naam wordt alsnog
  benoemd; en de luide tak wijst naar het frame waar de fout ontstond.
- **de ernst-splitsing** — storing versus bedradingsfout, een afgekapte stream versus een antwoord
  dat de console al gelezen had, een `WebApplicationException` op zijn status, de onbekende OIN, en
  twee `@ParameterizedTest`en over de statuscodes: 408/429/500/503/599 klinken als storing,
  200/302/400/409/425/499 als een fout van onze kant.
- **het paneel** — `PaneelContractTest` pint de vijf veldnamen in het antwoord; `PaneelTellersTest`
  pint dat `vullingTekst` elke teller noemt, `vullingSoort` elke foutteller meeweegt en een rode
  uitkomst kán geven, dat elke uitkomstsoort naar het juiste merkteken wijst, dat dat merkteken op
  de soort van de samenvatting wordt opgezocht, en dat een onbekende soort niet op het
  geslaagd-teken terugvalt.
- **de stroom** — `TempoServiceTest` pint dat de chip afleveringen telt en niet tikken, en dat de
  bovengrens aan de pogingen hangt zodat een uitstaand magazijn de stroom niet rekt.

### Mutatietest

Er staat geen mutatietest-plugin in de build, dus met de hand: mutant erin, de betrokken testklassen
draaien, noteren wélke tests omvallen, mutant eruit. Tegen de eindstand zijn **vijfenzeventig mutanten**
gedraaid en alle vijfenzeventig worden gedood.

Dat is een gerichte steekproef en geen volledige mutatiedekking: de mutanten zijn met de hand
gekozen op de plekken waar het gedrag van deze wijziging zit. De lijst staat in het scriptje dat hem
draaide, zodat een volgende ronde niet hoeft te raden wat al geprobeerd is. Waar ze op zitten:

| Groep | Wat er gemuteerd is |
|---|---|
| het uitlezen | vangnet versmald; safe call weg; blanco-check naar leeg of naar null; oorzaak niet gelogd; ernst-splitsing weg |
| de ernst-splitsing | ontkoppelde stream telt niet meer als hapering; élke fout telt als hapering; `WebApplicationException` niet op status; wachtcodes tellen als onze fout; wachtcodes zonder 408 of zonder 429; 5xx-grens naar 501 of naar 598; alles-buiten-4xx als storing; aanleveren, markeren en sluiten zonder splitsing; onbekend magazijn als storing |
| de tellers | `zonderBerichtId` niet geteld; blijft op 1; `AfgeleverdZonderId` telt als mislukt; onbekend magazijn telt als geslaagd; `aangeboden` minstens 1; `gelezen`-guard weg |
| het sluiten | niet gesloten; alleen bij een niet-afgeleverd antwoord; falende `close()` ontsnapt; `sluitStil` zwijgt; twee keer gesloten; markeer-antwoord niet gesloten |
| de aanroep en de status | beide vangnetten versmald; `!= 201` omgedraaid of verruimd tot 2xx; markeer-status omgedraaid of verruimd tot 2xx; markeer-statusmelding weg |
| de ronde | eerste opdracht overgeslagen; routering pakt het eerste magazijn; `X-Ontvanger` zonder waarde of met een vast type |
| de logregels | ontvanger-*waarde* in vijf verschillende regels; ontvanger-type weg of vast op BSN; statuscode weg; `oorzaakketen` logt `toString()`; luide tak logt de melding erbij; terugval op de volledige klassenaam weg; keten stopt na twee; ander scheidingsteken; cyclus-guard weg; `plek` geeft niets, pakt het onderste frame of laat klasse en regel weg; magazijn-OIN of berichtId weg uit vier regels |
| het paneel | `fout` krijgt het geslaagd-merkteken; twee merktekens verwisseld; het merkteken op een vaste sleutel opgezocht; geen weg meer naar een rode melding; terugval op groen; `zonderBerichtId` weegt niet meer mee in de kleur; `zonderBerichtId` of `markeringMislukt` niet meer genoemd in de tekst |

Twee mutanten die eerder als "equivalent" waren afgeboekt — de safe call weghalen en de oorzaak uit
de `onleesbaar`-regel laten vallen — bleken dat niet te zijn zodra de logtekst zelf werd vastgelegd.
Het verschil zat in de diagnose, en die is nu getoetst.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am` groen: 327 tests in de module (waarvan 53 in
  `AanleverServiceTest`), detekt 0 bevindingen.
- Mutatietest: 75 met de hand gekozen mutanten tegen de eindstand, alle 75 gedood. Een gerichte
  steekproef, geen volledige mutatiedekking.
- Geen nieuwe build-warnings. De `WARN Proxy … niet uit te lezen`- en `gesimuleerde magazijnen niet
  gevuld`-regels zijn logoutput van tests die foutpaden uitlokken, geen bouwmelding.
- Geen enkele logregel draagt een identificatienummer — melding, throwable én parameters — en dat is
  een test, geen leescontrole.

## Buiten scope, wel gezien

Losse defecten met hun eigen afweging; ze horen niet bij deze wijziging thuis:

- Een `ProcessingException` op de aanroep zelf telt onvoorwaardelijk als `mislukt`, ook als het
  magazijn het bericht wél opsloeg en alleen het antwoord wegviel (read-timeout). Onderscheid maken
  vergt classificatie van de oorzaak en een eigen "onzeker"-teller in het paneel.
- De `application/problem+json`-body van een niet-201 wordt niet gelezen, dus "40× HTTP 400" zegt de
  bediener niet wát er mis was, terwijl het magazijn `title`/`detail`/`instance` meestuurt.
- `FoutieveAanleverService` heeft een ongeschermde `readEntity` — daar is het één actie en geen
  ronde, dus de uitwerking is kleiner, maar het is dezelfde valkuil.
- Het oordeel "een `ProcessingException` is de overkant" leunt op het wikkelgedrag van de
  REST-client en wordt door geen enkele test geraakt. Eén test met een echte socket die de body
  halverwege afkapt zou dat vastpinnen; dat is de enige plek waar een upgrade dit kan verraden.
- `bediening.js` heeft geen gedragstests: er is geen JS-runtime in de build. `PaneelTellersTest`
  klemt de namen en de merktekens vast, maar wát `vullingTekst` van een uitkomst maakt blijft
  ongetoetst. Een JS-engine als test-dependency zou dat oplossen; dat is een eigen afweging en geen
  onderdeel van deze wijziging.
- De REST-clients worden programmatisch gebouwd zonder expliciete read-timeout. Een magazijn dat
  hángt in plaats van weigert houdt de ronde dan vast, en dan komt geen enkel vangnet eraan te pas.
- Een mislukte `close()` komt in geen enkele teller. Verdedigbaar — het bericht is afgeleverd — maar
  herhaald lekken put de connection-pool uit, en de ronde die daarna strandt wijst niet hierheen.
