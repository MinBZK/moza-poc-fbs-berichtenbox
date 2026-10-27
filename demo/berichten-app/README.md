# Berichten-app

De app-kant van rechtstreeks ophalen, als webpagina. Het ontwerp staat in
`docs/plans/2026-10-27-rechtstreeks-ophalen-design.md` (ontwerpkeuze 12).

- `js/sleutelkluis.js`: de root app key (WebCrypto, P-256, niet te exporteren) en de tokens in
  IndexedDB.
- `js/activeren.js`: `CreateSession` met een self-signed certificaat en het activeren bij de App
  Manager. Zonder DOM, zodat de tests onder Node dezelfde code draaien.
- `js/app.js`: de pagina.

## Meegeleverde `jose`

`vendor/jose-6.2.13/` is `dist/webapi/` uit het npm-pakket `jose@6.2.13` (panva, MIT, licentie
ernaast), ongewijzigd. Uit het pakket gehaald met:

```bash
npm pack jose@6.2.13   # integrity sha512-7TIg10ZE1x82YediN+lcrPNvGMKge9yFHQD+Qho/b8y2adfugU3AsvvHfxV0fzyFllT6l9xCjf4RWY9g/Z9PJQ==
tar xzf jose-6.2.13.tgz
cp -r package/dist/webapi src/main/resources/META-INF/resources/vendor/jose-6.2.13
cp package/LICENSE.md src/main/resources/META-INF/resources/vendor/jose-6.2.13/
```

Meegeleverd en niet van een CDN: de CSP staat alleen script van deze origin toe, en een script van
buiten zou kunnen tekenen met de sleutel van de app. Bij een nieuwe versie: de map vervangen, de
importpaden in `js/`, `src/test/js/` en `PaginaTest` aanpassen, en de hash hierboven bijwerken.
Dependabot ziet deze map niet: een advisory voor `jose` volg je via
<https://github.com/panva/jose/security/advisories>.
