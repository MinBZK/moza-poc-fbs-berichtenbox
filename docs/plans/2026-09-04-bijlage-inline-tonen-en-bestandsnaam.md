# Bijlage inline tonen en bestandsnaam meegeven

**Status:** Uitgevoerd

Issue: [MinBZK/MijnOverheidZakelijk#1048](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1048)

## Context

Het bijlage-download-endpoint van zowel `berichtenmagazijn` als `berichtenuitvraag` zet
onvoorwaardelijk `Content-Disposition: attachment`, zonder bestandsnaam. Dat sluit een
stored-XSS-pad af (een aangeleverde `text/html` of `image/svg+xml` zou bij top-level
navigatie onder onze origin kunnen draaien; CSP `frame-ancestors 'none'` dekt alleen
iframes), maar het dwingt óók een PDF die niets uitvoert naar de downloadmap. En zonder
bestandsnaam houdt een afnemer die de naam niet zelf uit de berichtdetails aanvult een
bestand zonder herkenbare naam over.

`X-Content-Type-Options: nosniff` staat globaal op beide diensten, dus een inline
aangeboden PDF wordt niet alsnog als HTML geïnterpreteerd. Daarmee is een allowlist van
typen die een browser veilig kan tonen een reële optie, met `attachment` als fallback.

**Wie de dispositie ziet.** Beide endpoints eisen de header `X-Ontvanger`, en die is bij
top-level navigatie niet te zetten — een browser bereikt ze dus niet rechtstreeks. De
dispositie telt voor de afnemer die de keten server-side aanroept en de bytes onder zijn
eigen origin doorgeeft: die krijgt met `inline` het signaal dat hij de bijlage mag tonen,
en met de bestandsnaam iets bruikbaars om hem onder weg te schrijven. Rechtstreeks de
bijlage-URL insluiten vanaf een andere origin blijft geblokkeerd — zie Buiten scope.

## Aanpak

Eén gedeelde beslisfunctie in `fbs-common`, gebruikt door beide diensten, zodat een
rechtstreekse magazijn-afname en de route via de uitvraag hetzelfde antwoord geven.

### 1. `libraries/fbs-common` — `bijlage/BijlageContentDisposition.kt`

Pure object (geen CDI-bean), met:

- `INLINE_VEILIGE_TYPEN = setOf("application/pdf", "image/png", "image/jpeg")` —
  typen waarvan de weergave geen code uitvoert die bij de DOM of de cookies van onze
  origin kan. (Een PDF-viewer draait het JavaScript in een PDF wél, maar afgeschermd.)
  Alles daarbuiten (`text/html`, `image/svg+xml`, `application/xhtml+xml`, onbekend,
  `octet-stream`) blijft `attachment`.
- `fun waarde(mediaType: MediaType, bestandsnaam: String?): String` — bouwt de
  volledige headerwaarde: `inline`/`attachment`, plus als er een naam is
  `; filename="<ascii>"; filename*=UTF-8''<pct-encoded>` (RFC 6266 + RFC 5987).

Sanitatie van de naam gebeurt hier, niet bij de aanroeper, zodat geen enkele route
een onbewerkte naam in de header kan krijgen:

- Vóór beide coderingen gaan control- en format-tekens eruit en worden `/`, `\` en `:`
  een `_`. Saneren in de ASCII-vorm alleen zou niets afdwingen: een client decodeert
  `filename*` terug en geeft die parameter voorrang.
- `filename=`: alles buiten `[A-Za-z0-9._-]` wordt `_`, zodat een aanhalingsteken de
  quoted-string niet kan sluiten.
- `filename*=`: percent-encoding van de UTF-8-bytes, alles buiten de RFC 5987
  `attr-char`-set gecodeerd. Draagt de niet-Latijnse naam correct over.
- Lege of blanco naam → geen filename-parameters, alleen `inline`/`attachment`.
- Naam langer dan 255 tekens wordt afgekapt (surrogate-veilig), zodat een extreme
  naam de header niet opblaast.

Beide parameters worden altijd samen gezet: `filename=` voor clients die
`filename*` niet kennen, `filename*` als de exacte naam. Een tak "alleen `filename`
als de naam al ASCII is" zou gedrag toevoegen zonder iets op te lossen.

Waarom control- en format-tekens: de eerste dragen `\r\n`, de tweede de bidi-overrides
waarmee `salaris<U+202E>fdp.exe` in een downloadlijst als `salarisexe.pdf` verschijnt. Het
lopen gebeurt per code point, want de tag-tekens (U+E0000-blok) staan buiten de BMP en zijn
per `Char` alleen maar surrogates.

Naast de dispositie krijgt het parsen van het MIME-type een guard: `MediaType.valueOf`
accepteert `application/pdf;name="a<CR><LF>b"` en houdt de regeleindes in de parameter,
waarna de HTTP-laag de headerwaarde pas bij het schrijven weigert — élke download van die
bijlage klapt dan zonder dat iets uitlegt waarom. `BijlageMediaType` weigert zo'n waarde
meteen, zodat beide diensten hun eigen fout-tak volgen.

### 2. `services/berichtenmagazijn`

- `BerichtenResource.getBijlage` zet naast het bestaande MIME-type-property een
  tweede property met `bijlage.naam`.
- `BijlageContentTypeFilter` leest beide en zet de header via de gedeelde functie.
  Een onbruikbaar MIME-type valt fail-closed terug op `application/octet-stream` en dus
  op een download — gelijk aan de uitvraag. De onderhandelde `Content-Type` laten staan
  zou de bytes de deur uit laten gaan onder het type uit het `Accept` van de aanroeper.
  De bestandsnaam gaat wél mee: die staat los van het type en is even goed gesaneerd.

### 3. `services/berichtenuitvraag`

- `BerichtOphaalService.haalBijlage` levert naast mimeType en bytes ook de
  bestandsnaam. Die komt uit het bericht-detail dat de service tóch al uit de
  sessiecache leest om te routeren — niet uit de `Content-Disposition` van het
  magazijn: die terugparsen zou de sanitatie moeten omkeren, terwijl de cache
  dezelfde naam uit dezelfde bron draagt. Kent de cache de bijlage niet, dan gaat de
  response zonder naam de deur uit (de bytes komen alsnog uit het magazijn).
- Het fail-closed-gedrag blijft: onparsebaar MIME-type → `application/octet-stream`
  + `attachment`. Octet-stream staat niet op de allowlist, dus dat volgt vanzelf.

### 4. `demo/magazijn-simulator`

De simulator serveert de magazijn-spec en moet dus hetzelfde antwoord geven, anders
vertelt de demo een ander verhaal dan het product. Hij heeft bewust geen
`fbs-common`-dependency (de JAX-RS-filters daarin vragen om de LDV-wrapper), dus komt
er — net als de OIN-controle en de API-Version-header — een kleine lokale variant.

### 5. Specs

`berichtenmagazijn-api.yaml` en `berichtenuitvraag-api.yaml`: beschrijving van
`getBijlage` en van de `Content-Disposition`-header bijwerken. Vastleggen wélke typen
inline mogen en waarom de rest dat niet mag, plus dat de bestandsnaam meekomt.

## Tests

- `fbs-common`: parameterized unit-tests over de allowlist (inline-typen, uitvoerbare
  typen, suffix-varianten als `application/pdf+xml`, wildcard, hoofdletters, type met
  parameters) en over de naam (leeg, blanco, ASCII, niet-Latijns, aanhalingsteken/
  backslash, `\r\n`, bidi-tekens, precies 255 / 256 / veel te lang, alleen onzichtbare
  tekens). Plus `BijlageMediaType`: parsebaar, onparsebaar en control-teken-in-parameter.
- `berichtenmagazijn`: filter-unit-tests voor beide takken plus een naam met
  bijzondere tekens; de bestaande `@QuarkusTest` op de download-route uitbreiden zodat
  de coverage-gate de takken meet.
- `berichtenuitvraag`: unit-tests op het filter, `BijlageMimeTestResource` uitbreiden
  met een naam-parameter zodat de `@QuarkusTest` beide takken end-to-end raakt, en
  `BerichtOphaalServiceTest` voor de naam-lookup (geen bijlagen / niet gevonden /
  gevonden tussen meerdere).
- `magazijn-simulator`: dezelfde gevallen als in `fbs-common`, letterlijk gespiegeld —
  de kopie heeft geen andere borging dan dat de testsets gelijk blijven.

## Verificatie

```bash
./mvnw clean test -pl libraries/fbs-common -am
./mvnw clean verify -pl services/berichtenmagazijn -am
./mvnw clean verify -pl services/berichtenuitvraag -am
./mvnw clean test -pl demo/magazijn-simulator -am
npx @stoplight/spectral-cli lint <spec> --ruleset https://static.developer.overheid.nl/adr/ruleset.yaml
```

## Afwegingen

**De bestandsnaam staat nu in een response-header.** Een naam als `aanslag-J-Jansen.pdf`
kan persoonsgegevens dragen en stond eerder alleen in de JSON-body. Response-headers
worden zelden gelogd en de keten logt hem nergens, maar het is nieuwe blootstelling en
hoort bij de afweging genoemd. Het carrier-object in de uitvraag is daarom bewust geen
`data class`: de gegenereerde `toString` zou de naam in elke logregel zetten waarin het
object per ongeluk belandt.

**`inline` reikt niet verder dan het vertrouwen in het magazijnregister.** De uitvraag
beslist op het `Content-Type` dat het bronmagazijn meestuurt. Een deelnemend magazijn kan
dus bytes van eigen keuze laten tonen onder de origin van de berichtenbox. Met `nosniff`
blijft dat beperkt tot de PDF- en afbeeldingsviewer — geen HTML-uitvoering — maar het is
nieuw gedrag: eerder was alles een download. Wie een magazijn in het register zet, zet
daarmee ook dit vertrouwen.

**De demo blijft downloaden.** De berichtenbox in `demo/demo-console` haalt de bytes met
`fetch` op (nodig voor de `X-Ontvanger`-header) en zet de downloadnaam zelf; hij leest de
`Content-Disposition` niet. Hij saneert de naam daarom net als de keten. Het effect van
deze wijziging is in de demo dus niet te zien — dat vraagt een afnemer die de dispositie
wél volgt.

**De extensie in de naam blijft die van de aanleveraar.** `mimeType=application/pdf` met
`naam=jaaropgave.pdf.hta` levert een download die `.hta` heet. De naam wordt echter al
ongewijzigd teruggegeven in `BijlageMetadata` en door afnemers als downloadnaam gebruikt,
dus dat oppervlak bestaat los van deze wijziging. De extensie afdwingen uit het MIME-type
verandert aangeleverde gegevens en is een productbeslissing, geen header-detail; wie dat
wil, hoort de naam bij aanlevering te begrenzen.

## Open punt voor de refinement

Het magazijn controleert de vórm van een aangeleverd `mimeType` niet: `Bijlage` eist
alleen niet-leeg en ≤ 127 tekens, en de spec zet er geen `pattern` op. Een aanleveraar kan
daarmee `pdf` of `application/pdf;name="a<CR><LF>b"` wegschrijven, waarna élke download van
die bijlage een 500 is — de bytes gaan immers niet onder een type dat niet klopt de deur
uit. De simulator wéigert zo'n aanlevering al (`MEDIATYPE_VORM`), dus die accepteert nu
strikt minder dan het echte magazijn.

Dat gat bestond vóór deze wijziging en dichten betekent het aanlever-contract aanscherpen
(een 400 waar nu een 201 volgt). Dat is een productbeslissing, geen header-detail: voorleggen
aan de opdrachtgever als eigen issue, zodat de 500-tak wordt wat hij hoort te zijn — een
vangnet dat je met een geldige aanlevering niet kunt raken.

## Buiten scope

Een afnemer die de bijlage-URL rechtstreeks in een `iframe`, `object` of `embed` zet
vanaf een andere origin loopt tegen `X-Frame-Options: DENY` en CSP
`frame-ancestors 'none'` aan; die staan globaal op elke response van beide diensten. Dat
pad blijft dus dicht. Een berichtenbox die de keten server-side aanroept en de bytes
onder de eigen origin doorgeeft raakt het niet — die leest de dispositie en beslist zelf.
Wil een afnemer de URL wél rechtstreeks insluiten, dan vraagt dat om het versoepelen van
beide headers op dit ene endpoint, met de origins erbij benoemd: een eigen afweging en
een eigen issue.
