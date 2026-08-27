# Bedieningspaneel van de demo-console bruikbaar maken

**Status:** Uitgevoerd

## Aanleiding

Sinds het paneel naast de berichtenbox van de proeftuin in een frame staat
(`2026-08-24-proeftuin-koppeling-design.md`), is het bedieningsoppervlak nog 26rem breed. Wat in een
centerkolom van 40rem net werkte, werkt daar niet meer. Drie klachten uit het gebruik:

1. **Je ziet niet of je een knop hebt ingedrukt.** Elke knop schreef zijn uitkomst naar één
   `<pre>`; de knop zelf veranderde niet, ging niet op disabled en toonde geen voortgang.
2. **De uitkomst stond onderaan.** Dat `<pre>` kwam na vijf uitgeklapte `fieldset`s en viel in een
   kolom van 26rem in de praktijk altijd buiten beeld — ook het "Bezig…" dat er als eerste in kwam.
3. **Alles stond altijd open.** Twintig knoppen van gelijk gewicht, zonder rangorde tussen wat je
   in elke demo doet en wat bij één scenario hoort.

Daar kwamen bij het doorlopen nog vier dingen bovenop:

4. Er was **geen toestandsbeeld**: of de stroom liep, of een magazijn uitstond en hoeveel
   stub-magazijnen actief waren, kon je alleen te weten komen door het te vragen en het antwoord
   onderaan te lezen. Dat is dezelfde blinde vlek waar de runbook-valkuil "vergeet de reset niet"
   op wijst.
5. De knoplabels droegen **interne projectindeling** ("fase 3", "scenario 12") in plaats van
   bedieningstaal.
6. De bevestiging liep via `confirm()`, met één tekst voor knoppen die verschillende dingen doen —
   bij *Herstel demo* stond er dat hij berichten verwijdert, niet dat hij ook de stroom stopt en
   opnieuw vult.
7. De ontdubbeling vroeg een BSN als vrij tekstveld, terwijl `GET /api/demo/personas` de lijst met
   identiteiten al levert.

## Ontwerpkeuzes

### Eigen tokenlaag, niet die van de proeftuin

De proeftuin bouwt zijn stijl met NL Design System-tokens onder de prefix `--toepassing-*`, uit
style-dictionary. Het paneel neemt die *opbouw* over — kleur, ruimte, rand en typografie als
benoemde tokens, componenten met de rollen die NLDS kent, contrast op WCAG AA — maar met een eigen
prefix (`--bediening-*`), eigen waarden en een eigen bestand.

Delen van hún stylesheet is bewust niet gedaan, om twee redenen. Het paneel zou dan meebewegen met
een proeftuin-release die wij niet in de hand hebben. En belangrijker: het paneel staat naast een
frame met de échte berichtenbox erin. Wie meekijkt in een demo moet in één blik zien wat het
product is en wat de regiekamer ernaast. Vandaar een donkere chrome met een accent dat met opzet
niet het hemelblauw of lintblauw van de proeftuin is, plus een kop die het uitschrijft: "Geen
onderdeel van de proeftuin".

Kleur is nergens de enige drager: elke chip en melding zegt in tekst wat er aan de hand is.

### Feedback op twee plekken tegelijk

De melding bovenaan zegt *wat* er gebeurde; het merkteken in de knop zegt *welke knop* het deed.
Dat tweede is het antwoord op "heb ik hem nou ingedrukt?" — één plek volstond niet, want de melding
kan bij twee opeenvolgende klikken hetzelfde blijven.

De knop gaat tijdens de actie op `disabled` met een draaiend teken, en houdt daarna vier seconden
een ✓ of ✗ vast. Het teken heeft een vaste breedte, ook leeg: anders verspringt de hele knoppenrij
op het moment dat de uitkomst binnenkomt.

### Toestandsbalk in plaats van status-knoppen

Vier chips bovenaan, bijgewerkt na elke actie en elke vijf seconden: berichten per magazijn, de
stroom, de storingen en het aantal actieve stub-magazijnen. Het tabblad waar iets aanstaat krijgt
een stip.

Elke chip leest zijn eigen endpoint met een eigen terugval. Een Toxiproxy die niet antwoordt mag de
berichtentelling niet meeslepen — juist dan wil je weten hoeveel er nog staat.

Er wordt alleen gepolld terwijl er iemand kijkt (`document.hidden`) en niet terwijl een actie loopt:
de balk zou anders de toestand van halverwege een herstel tonen.

### Twee nieuwe lees-endpoints

De balk had twee dingen nodig die de console nog niet kon vertellen.

`GET /api/demo/storing` geeft per geconfigureerde proxy `normaal` / `traag` / `uit` / `onbekend`.
Eén Toxiproxy-aanroep per instantie in plaats van per proxy, omdat dit gepolld wordt. `onbekend` is
geen sierstand: een instantie die niet antwoordt of een geconfigureerde proxy die Toxiproxy niet
kent, laat verkeer nergens langs — dat als "normaal" tonen verbergt precies de misconfiguratie die
je zoekt. Net als bij `reset()` maakt één onbereikbare instantie alleen zijn eigen proxies onbekend.

`GET /api/demo/veel-magazijnen` geeft `{actief, totaal}`, geteld uit de WireMock-mappings en niet
uit een veld in het geheugen: zo klopt het paneel ook na een herstart van de console of van de
stubs. Alleen onze eigen overlay-id's tellen als storing, en dan op unieke id — de base-mappings
van schijf hebben eigen id's, en dubbele overlays zouden het actieve aantal onder nul duwen.

Beide endpoints maken van twijfel een fout in plaats van een telling. `mappings()` levert daarom
een `Response`: de default rest-client-mapper staat uit, dus zonder de status zelf te lezen komt
een foutbody binnen als een lege mappings-lijst — en dat leest als "alles actief", precies de
richting die dit paneel niet mag hebben. Een lege lijst wordt om dezelfde reden geweigerd: stubs
zonder mappings serveren niets.

`GET /api/demo/omgeving` kreeg er `stubMagazijnen` bij, het ingerichte aantal uit de configuratie.
Daarmee weet het paneel vóór de eerste uitlezing of deze omgeving die chip kent. Zonder dat
onderscheid is "niet ingericht" niet te scheiden van "niet kunnen lezen" — dan verdwijnt de chip
juist wanneer er iets stuk is — en pollt het paneel elke vijf seconden een endpoint dat in zo'n
omgeving alleen maar een fout in het log kan opleveren.

### Waar je gebleven was

Een refresh zette je terug op het eerste tabblad, met het paneel weer open over de berichtenbox
heen en de getalvelden terug op hun default. Het paneel bewaart daarom het actieve tabblad, de
in-/uitgeklapte stand en de invoervelden (aantal, interval, actief aantal, gekozen persona).

In `sessionStorage` en niet in `localStorage`: dit overleeft een refresh maar niet het sluiten van
het tabblad, zodat een volgende demo schoon begint in plaats van stilzwijgend de instellingen van
de vorige te erven. De sleutel draagt een prefix, want via de demo-proxy staan de proeftuin en het
paneel op dezelfde origin en delen ze dezelfde storage.

Wat er niet in gaat: de scrollpositie (verspringt op een tabblad dat korter is dan waar je stond)
en de melding en chips, die per definitie van het moment zijn. Onbekende waarden — een tabblad dat
niet meer bestaat, een persona die uit de configuratie is — vallen stil terug op de standaard, en
storage die gooit (een private window) laat het paneel gewoon werken, alleen zonder geheugen.

### Tabbladen, en drie knopgewichten

Vier bladen: **Demo** (wat je in elke demo doet), **Storingen**, **Scenario's**, **Info**. Tabs en
geen uitklappers, omdat je er tijdens een demo met één klik en zonder scrollen moet zijn.

De knoppen kregen drie gewichten — primair, gewoon, subtiel — zodat de knop die je zoekt eruit
springt en de rest niet meeschreeuwt. Een destructieve knop is nooit primair: die moet opvallen als
waarschuwing, niet als voorstel. "fase N" is uit de labels verdwenen; de scenario-nummers blijven
als klein bijschrift, want de runbook verwijst ernaar.

De bevestiging staat nu in het paneel zelf. `confirm()` valt buiten het scherm dat je deelt en
dwingt tot één tekst voor knoppen die verschillende dingen doen; nu draagt elke knop zijn eigen
zin. De focus springt naar de veilige keuze: wie hier met een Enter belandt, mag met een tweede
Enter geen twee magazijnen leegtrekken.

## Structuur

`index.html` (11 KB, alles inline, `onclick=` op elke knop) is gesplitst in drie bestanden onder
`demo/demo-console/src/main/resources/META-INF/resources/`:

| Bestand | Wat |
|---|---|
| `index.html` | Alleen opmaak. Elke knop draagt zijn methode en pad als data-attribuut |
| `bediening.css` | Tokenlaag + componenten |
| `bediening.js` | Eén listener op het paneel voert de knoppen uit; melding, chips, tabbladen |

Een `{veldnaam}` in `data-pad` komt uit het invoerveld met die id (`/api/demo/random?aantal={aantal}`).
Ongeldige invoer wordt in de browser gestopt, die het veld dan zelf aanwijst.

## Verificatie

- `./mvnw clean verify -pl demo/demo-console -am` — 161 tests groen, detekt zonder bevindingen.
  Nieuw: acht tests op `StoringService.status()` en zes op `VeelMagazijnenService.status()`, met
  de cardinaliteiten nul/één/meerdere, een onbereikbare instantie, een onbekende proxy en de
  base-mappings die niet mogen meetellen.
- Beide endpoints tegen de draaiende demo-stack: `traag` en `uit` worden herkend, `k=3` van 12
  wordt geteld, en beide resets brengen de stack terug op normaal.
- Het paneel met jsdom tegen de draaiende console: chips gevuld, tabbladen en pijltjesnavigatie,
  de knop-feedback (bezig → gelukt), de samengevatte melding met ruwe JSON eronder, en de
  bevestiging die zonder "Ja" niets leegt. Voor het bewaren van de stand ook de heenweg en de
  terugweg apart — wegschrijven, en een verse pagina met die stand erin — plus een onbekend
  tabblad, een verdwenen persona en storage die gooit.

Een browser is er in deze omgeving niet, dus uitlijning en of het geheel in 26rem past zijn met
het oog te beoordelen op <http://127.0.0.1:8097/bediening/>. Contrastverhoudingen zijn wél
nagerekend: alle tekstparen halen 4.5:1 en de focusringen 3:1, met een eigen donkere ring voor de
klapknop omdat de gele ring van het paneel op de lichte kolom maar 1,3:1 haalt.

### Wat de review opleverde

Een ronde met vijf review-agents bracht drie dingen aan het licht die alle drie dezelfde vorm
hadden — het paneel zou "gezond" melden terwijl er iets stuk was:

- Een afgebroken body-read liet `bezig` op `true` staan. De poll viel dan permanent uit en de
  chips bleven de rest van de sessie op hun laatste waarde staan, zonder dat iets dat verried.
  Opgelost met een `finally`, plus een aparte terugval voor een respons waarvan het lezen afbreekt.
- Een foutantwoord van WireMock kwam binnen als een lege mappings-lijst en werd "alles actief".
- Een mislukte eerste uitlezing verborg de magazijnen-chip, wat niet te onderscheiden was van een
  omgeving zonder stub-magazijnen.

Daarnaast: een 200 met `mislukt > 0` kreeg een groene melding met vinkje (nu geel of rood), een
gecrashte samenvatter viel stil terug op "Gelukt" (nu een waarschuwing met de JSON open), een
onbereikbare console meldde "geen persona met een BSN ingericht", en de bevestiging kondigde
zichzelf niet aan voor een schermlezer. De oorzaak van `ONBEKEND` gaat nu naar het log; zonder die
regel was in een demo niet te achterhalen of het om een weggevallen instantie of een timeout ging.

## Wat niet gedaan is

- **Geen JS-testframework in de module.** De jsdom-smoketest is een hulpmiddel geweest, geen
  toevoeging aan de build: `demo-console` is wegwerpcode zonder JaCoCo-gate, en een tweede
  testketen erbij kost meer dan hij hier oplevert.
- **Geen componentbibliotheek.** De console heeft geen Node-build, en die erbij halen voor een
  paneel van vier tabbladen zou de wegwerp-module zwaarder maken dan het stelsel eromheen.
