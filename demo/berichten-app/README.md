# Berichten-app

De app-kant van rechtstreeks ophalen, als webpagina. Het ontwerp staat in
`docs/plans/2026-10-27-rechtstreeks-ophalen-design.md` (ontwerpkeuze 12).

- `js/sleutelkluis.js`: de root app key (WebCrypto, P-256, niet te exporteren) en de tokens in
  IndexedDB.
- `js/activeren.js`: `CreateSession` met een self-signed certificaat en het activeren bij de App
  Manager. Zonder DOM, zodat de tests onder Node dezelfde code draaien.
- `js/app.js`: de pagina.

## Meegeleverde `jose`

`vendor/jose/` is `dist/webapi/` uit het npm-pakket `jose` (panva, MIT, licentie ernaast),
ongewijzigd. Welke versie, staat in `vendor/jose/BRON.json`, met de integrity uit de lockfile.

Meegeleverd en niet van een CDN: de CSP staat alleen script van deze origin toe, en een script van
buiten zou kunnen ondertekenen met de sleutel van de app.

`package.json` en `package-lock.json` staan er alleen om de kopie bij te houden: Dependabot leest
ze en biedt een nieuwe versie of een security-fix aan als PR. Zo'n PR blijft rood (`VendorJoseTest`)
tot de kopie is ververst:

```bash
cd demo/berichten-app
npm ci --ignore-scripts   # toetst het pakket aan de integrity in package-lock.json
npm run vendor            # vervangt vendor/jose en schrijft BRON.json
```

De importpaden noemen geen versie, dus verder hoeft er niets te veranderen.
