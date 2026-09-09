# Een bijlage die getoond mag worden, mag ook ingesloten worden

**Status:** Uitgevoerd

## Context

PR "Een bijlage die veilig te tonen is, mag inline" laat de keten `Content-Disposition:
inline` sturen voor `application/pdf`, `image/png` en `image/jpeg`. Een berichtenbox kan die
bijlage daarmee in een nieuw tabblad tonen in plaats van hem te laten opslaan.

Wat nog niet kan is hem in de pagina zelf tonen, in een ingesloten viewer. Beide diensten
zetten op élke response `X-Frame-Options: DENY` en `frame-ancestors 'none'`, en `DENY`
blokkeert framing óók same-origin. De berichtenbox van de proeftuin heeft dat nagegaan en het
kader daarom dichtgelaten; de code daar zegt er letterlijk bij: *"Zodra het stelsel `inline`
kan leveren, kan dit aan."*

## Wat mag, binnen de richtlijnen

Nagekeken in de bron in plaats van aangenomen:

- **NCSC ICT-beveiligingsrichtlijnen voor webapplicaties (juli 2024), U/PW.03 maatregel 04.**
  Voor CSP: *"`frame-ancestors: none of self`"* — beide zijn aanbevolen waarden. Voor
  `X-Frame-Options`: *"Aanbevolen waarde: DENY. Indien functioneel noodzakelijk:
  SAMEORIGIN."*
- **internet.nl** (`checks/tasks/http_headers.py`): `X-Frame-Options` slaagt op `DENY` óf
  `SAMEORIGIN`; de CSP-check eist dat `frame-ancestors` aanwezig is en `'self'` of `'none'`
  bevat. Beide leveren volle punten.

Een *benoemde* vreemde origin zou de internet.nl-toets níét halen. Dat hoeft ook niet: de
berichtenbox proxyt de keten server-side, dus de browser ziet het bijlage-adres onder de
origin van de berichtenbox zelf. `'self'` dekt precies dat geval.

## Wat er is gebouwd

`SecurityHeaders.voorInlineBijlage(contentDisposition)` geeft de versmalde headers terug, of
`null` wanneer er niets te versmallen valt. `SecurityHeadersRegistratie` past ze toe ná de
vaste set.

| | `X-Frame-Options` | `Content-Security-Policy` |
|---|---|---|
| `inline`-bijlage | `SAMEORIGIN` | `default-src 'none'; img-src 'self'; object-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'` |
| Al het overige | `DENY` | `default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'` |

### De beslissing hangt aan de dispositie, niet aan het pad

Met opzet. "Mag getoond worden" en "mag ingesloten worden" horen dezelfde verzameling te
zijn: de typen waarvan een browser de weergave afhandelt zonder aangeleverde code uit te
voeren. Die verzameling staat al in `BijlageContentDisposition.INLINE_VEILIGE_TYPEN` en komt
via de `Content-Disposition` de response op. Door dáárop te beslissen is er één bron, en
kunnen ze niet uit elkaar lopen — een type dat ooit van de inline-lijst wordt gehaald,
verliest tegelijk het frame-recht, zonder dat iemand daar apart aan hoeft te denken.

Het levert bovendien de goede plek op. De dispositie wordt gezet door
`BijlageContentTypeFilter` (JAX-RS), maar die laag kan de HTTP-laag niet overstemmen — dat is
in de vorige PR gemeten. In de `headersEndHandler` staat de dispositie er al, en daar is
vervangen wél mogelijk.

### `img-src` en `object-src`

`default-src 'none'` blokkeert niet alleen scripts. Navigeert een browser top-level naar een
afbeelding, dan bouwt hij daar een document omheen en valt het plaatje zelf onder `img-src`;
bij een PDF doet de ingebouwde viewer iets vergelijkbaars via `object-src`. Zonder die twee
kan de weergave leeg blijven: de bytes zijn er, maar je ziet niets. Wat blijft staan is wat
ertoe doet — geen `script-src`, dus `default-src 'none'` geldt daarvoor.

Dit is beredeneerd, niet in een echte browser nagemeten; die stap hoort bij het moment dat de
berichtenbox het kader aanzet. Houd er dan rekening mee dat `default-src 'none'` ook
`frame-src` dekt en dat Chrome zijn PDF-viewer in een intern child-frame laadt — of dat frame
onder de page-CSP valt verschilt per Chrome-versie, dus er kan nog `frame-src 'self'` bij
moeten. Meet in Chrome, Firefox én Safari, niet in één.

### Alleen op een geslaagde response

De dispositie staat op de response zodra de bytes opgehaald zijn. Ontstaat er daarná een
fout, dan draagt de foutbody hem nog steeds: het logboek is in productieprofielen bewust
fail-closed en gooit ná de resource-methode, waarna een exception mapper er een
`application/problem+json` van maakt. Zo'n response hoort de versoepeling niet te erven, dus
de versmalling staat achter een statuscheck op 2xx.

De exploitwaarde daarvan is klein — de body is JSON en `nosniff` staat er nog — maar het is
het geval waar de vraag naar was, en de guard kost één regel.

Wat níét in deze wijziging is opgelost: op datzelfde pad gaat zo'n foutresponse ook uit met
`Content-Type: application/pdf`, want `BijlageContentTypeFilter` kijkt niet naar de status.
Dat bestaat los van deze wijziging en hoort in het filter zelf thuis.

### Wat níét verandert

De dispositie zelf, de MIME-allowlist en `X-Content-Type-Options: nosniff` blijven zoals ze
zijn. De versmalling raakt precies twee headers, en alleen op responses die toch al `inline`
waren — een download verandert niets, want daar valt niets te tonen en dus ook niets te
framen.

De **magazijn-simulator** volgt deze wijziging bewust niet. Hij heeft geen `fbs-common` en zou
dus een derde kopie van de policy nodig hebben. De browser bereikt hem in de demo ook niet
rechtstreeks: de berichtenbox haalt bijlagen bij de uitvraag op. Wel een afwijking van de
regel "houd simulator en magazijn gelijk"; mee te nemen zodra
[#1054](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1054) de gedeelde-code-vraag
oplost.

## Verificatie

- `SecurityHeadersTest` (puur) — inline mét en zónder bestandsnaam, elke niet-inline vorm
  (inclusief de RFC-legale schrijfwijzen die we bewust weigeren en een bestandsnaam die de
  dispositie probeert te vervalsen), `null`/leeg, en dat de versmalling precies twee headers
  raakt.
- `SecurityHeadersRegistratieTest` (beide diensten) — de versmalling op een geslaagde
  response, en dat een foutresponse (301/400/403/404/500/503) met dezelfde dispositie hem
  niet erft.
- `BijlageContentTypeFilterQuarkusTest` — op de draad, geparameteriseerd over álle drie de
  inline-veilige typen (`img-src` en `object-src` zijn twee verschillende renderpaden) en
  over drie typen die download blijven. Mét de assertie dat `nosniff` blijft staan: daar
  leunt het hele veiligheidsargument onder `inline` op.
- `BerichtenOphalenIntegrationTest` (magazijn) — dezelfde gevallen op de echte
  bijlage-download, tegen PostgreSQL.

Volledig: uitvraag **298** tests groen, magazijn **474** tests groen, detekt 0 bevindingen,
JaCoCo-gates gehaald.

**Mutation testing** (pitest, tijdelijk toegevoegd, niet gecommit) op `SecurityHeaders`:
**22 mutanten, 22 gedood (100%)**. Twee handmatige mutaties op de bedrading, die pitest niet
kan aansturen: de dispositie niet doorgeven (nooit versmallen) en de statuscheck altijd waar
maken — beide gedood.

Het schrijven van de tests leverde meteen een aanscherping op: `startsWith("inline")` liet
`inlineaardig` meetellen. De dispositie is een token, dus de grens hoort erbij; nu is het
`inline` of `inline;`, hoofdlettergevoelig omdat het een waarde is die we zélf zetten.

## Vervolg

Hiermee kan `MinBZK/moza-poc` het ingesloten kader aanzetten. Dat vraagt daar twee dingen: de
lijst-link niet langer met een `download`-attribuut forceren, en `viewer` op het keten-pad
aanzetten. Beide staan in `assets/javascript/berichtenbox.js`.
