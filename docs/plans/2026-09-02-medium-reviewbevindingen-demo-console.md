> **Status:** Uitgevoerd

# De medium reviewbevindingen op het bedieningspaneel

## Context

Vervolg op [de vijf hoge bevindingen](2026-09-02-hoge-reviewbevindingen-demo-console.md). Waar die
vijf het paneel lieten liegen, gaat het hier om drie kleinere soorten: knoppen die het verkeerde
melden, koppelingen tussen bestanden die niets bewaakt, en comments die naar verdwenen code wijzen.

Twee aannames uit de review zijn eerst tegen een draaiende Toxiproxy 2.12.0 gehouden, want een fix
op een vermoed statuscodegedrag is geen fix:

| Handeling | Antwoord |
|---|---|
| Dezelfde proxy nogmaals aanmaken | `409 proxy already exists` |
| Dezelfde toxic nogmaals toevoegen | `409 toxic already exists` |
| Toxic op een **uitgezette** proxy | `200` — de toxic staat er, de proxy blijft uit |
| Proxy tweemaal uitzetten | `200` |
| Toxic verwijderen die er niet is | `404 toxic not found` |

## De bevindingen en hun aanpak

| # | Bevinding | Aanpak |
|---|---|---|
| 6 | Eén falende proxy laat de volgende proxies van die instantie op storing staan | `herstel()` vangt per proxy en verzamelt; een 404 op het verwijderen van een toxic is geen fout meer (die toxic is al weg) |
| 7 | Tweemaal "Traag" geeft 409 → "Mislukt" terwijl de storing aanstaat | 409 telt als bereikt: de gewenste toestand staat er |
| 8 | "Traag" op een uitgezette proxy meldt succes | `traag()`/`uit()` lezen de toestand terug; de resource meldt wat er staat en weigert "traag" op een uitgezette proxy met een bruikbare aanwijzing |
| 9 | `demo/smoke.sh` noemt nog `KVK:12345678` | Nummer bijwerken; `OndernemersConsistentieTest` leest voortaan ook `smoke.sh`, inclusief de aantallen |
| 10 | `/api/demo/legen` mengt twee sleutelvocabulaires in één melding | Benoemd antwoordtype plus een eigen samenvatting; daardoor kan ook hier een overgeslagen simulator gemeld worden in plaats van te falen |
| 11 | `SimulatorService` belooft als toekomst wat de simulator al kan | Comment naar de zeven bestaande modi |
| 12 | Twee comments spreken elkaar tegen over de magazijn-proxies op ZAD | Eén formulering: lokaal houden A en B hun proxy, op ZAD staan hun URL's leeg |
| 13 | Motivatie van `disable.default.mapper` noemt verwijderde WireMock-code | Vervangen door de gevallen die er nu zijn |
| 14 | Het poort-comment is losgeraakt van zijn property en is verouderd | Terugzetten boven `quarkus.http.port` en bijwerken |
| 15 | Niets bindt `data-proxy`/`data-pad` aan de configuratie en de routes | `PaneelPadenTest` vergelijkt de proxynamen; `PaneelContractTest` laat elk knop-pad in de draaiende applicatie oplossen |
| 16 | `"NORMAAL"`/`"STUK"` staan los tegenover `GedragModus` | Consistentietest die `Gedrag.kt` van de simulator leest |
| 17 | `listen`/`upstream` worden niet op vorm getoetst | `ProxyDefinitie` eist `host:poort`; een schending landt in `onvolledig()` |
| 18 | Twee definities van "bestaande proxy"; het paneel leest de ruimste | `ToxiproxyRegister.namen()` komt uit `ProxyDefinities` |
| 19 | `SimulatorResource` heeft geen test; `getValue` is een stringkoppeling | Benoemde typen voor stand en herstel, plus tests |
| 20 | Partieel falen ná de destructieve stappen is ongetest | Tests die vastleggen wat er dan gebeurt |

## Ontwerpkeuzes

**Een storingsknop meldt de toestand die hij terugleest, niet de toestand die hij bedoelde.** Dat is
dezelfde keuze die "Alles normaal" bij de hoge bevindingen al kreeg. Een latency-toxic op een
uitgezette proxy levert `200` op terwijl er geen byte doorheen komt; alleen teruglezen maakt dat
zichtbaar.

**409 is geen fout maar een bereikte toestand.** Zowel bij het aanmaken van een proxy als bij het
toevoegen van een toxic betekent 409 dat staat wat er moest staan. `ProxyBootstrap` deed dat al voor
proxies; `traag()` deed het nog niet voor toxics.

**`SimulatorService` krijgt twee ingangen naar hetzelfde werk.** `herstel()` gooit door — dat hoort
bij de knop die expliciet op de simulator gericht is. `herstelZoMogelijk()` kijkt naar
`demo.omgeving.simulator`, vangt, en draagt de reden mee — dat hoort bij de knoppen waar de
simulator een deelstap is. Twee namen in plaats van een vlag, zodat de call-site laat zien welke
semantiek hij wil.

**De padcontrole draait tegen de applicatie, niet tegen een lijst.** Een test die `@Path`-annotaties
uit de broncode regext, bewaakt zijn eigen regex. De probe stuurt `DELETE` — een methode die geen
enkele resource van deze module aanbiedt — zodat de router het pad wél matcht (405 als het bestaat,
404 als niet) zonder een resource-methode uit te voeren; ook de POST-knoppen zijn zo veilig te
toetsen. `OPTIONS` bleek daarvoor onbruikbaar: Quarkus beantwoordt dat zelf met 200, ook voor een pad
dat nergens op uitkomt. Dat kwam aan het licht doordat de test eerst zichzelf toetst op een pad dat
gegarandeerd niet bestaat.

## Verificatie

- `./mvnw clean test -pl demo/demo-console -am`
- `./mvnw detekt:check -pl demo/demo-console`
- `.github/scripts/demo-grens.sh`
