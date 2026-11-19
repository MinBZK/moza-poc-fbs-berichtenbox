# Berichten-app

De app-kant van rechtstreeks ophalen, als webpagina. Het ontwerp staat in
`docs/plans/2026-10-27-rechtstreeks-ophalen-design.md` (ontwerpkeuze 12).

- `js/sleutelkluis.js`: de root app key (WebCrypto, P-256, niet te exporteren) en de tokens in
  IndexedDB.
- `js/activeren.js`: `CreateSession` met een self-signed certificaat en het activeren bij de App
  Manager. Zonder DOM, zodat de tests onder Node dezelfde code draaien.
- `js/app.js`: de pagina.

## `jose` uit npm

De app gebruikt `jose` (panva, MIT) uit npm, met de versie vast in `package.json` en
`package-lock.json`. Er staat geen kopie in git: de Maven-build draait `npm ci --ignore-scripts`,
dat het pakket toetst aan de integrity in de lockfile, en zet `dist/webapi/` met de licentie op
`/vendor/jose/` in de jar. Dependabot houdt de versie bij; een nieuwe versie is een gewone PR.

Op de eigen origin en niet van een CDN: de CSP staat alleen script van deze origin toe, en een
script van buiten zou kunnen ondertekenen met de sleutel van de app.

De tests onder Node laden de browsercode uit `src/main/resources`, waar `/vendor/jose` niet staat.
`src/test/js/jose-uit-npm.mjs` (met `node --import`) laat dat pad naar `node_modules/jose` wijzen.
Wie een test met de hand draait, doet eerst `npm ci --ignore-scripts` in deze map:

```bash
node --import ./src/test/js/jose-uit-npm.mjs --test src/test/js/*.test.mjs
```
