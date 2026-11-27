# Beheerconsole

Beheerschermen voor het berichtenstelsel, gebouwd met het NLDD-designsysteem
(`@nldd/design-system`). Achtergrond, keuzes en de weg naar samenvoegen met het NMC-portaal:
[`docs/plans/2026-11-27-beheerconsole.md`](../../docs/plans/2026-11-27-beheerconsole.md).

## Draaien

```bash
./mvnw compile quarkus:dev -pl services/beheerconsole -am   # http://localhost:8093
```

Quarkus start de Vite-devserver erachter (Quinoa); een wijziging in `src/main/webui` is direct
zichtbaar. Quinoa installeert zelf Node `24.22.2` in `.quinoa/`, dus een eigen Node is niet nodig.

Het stelseloverzicht (`/stelsel`) leest het stelseldocument bij een stelselregister. Lokaal:

| Variabele | Standaard in dev | Betekenis |
|---|---|---|
| `STELSELREGISTER_URL` | `http://localhost:8094` | Het stelselregister; buiten dev en test alleen `https://` |
| `STELSELDOCUMENT_ROOT_PEM` | leeg | De root van de stelselbeheerder (PEM). In dev leeg toegestaan (`NIET_GETOETST`); daarbuiten weigert de start zonder root |
| `FBS_BEHEERCONSOLE_UNSAFE_ZONDER_ROOT` | `false` | Start buiten dev en test BEWUST zonder root; logt bij elke start `STELSELDOCUMENT_NIET_GETOETST` voor alert-routing |
| `STELSELDOCUMENT_UITGEVER_OIN` | `00000000000000001000` | De OIN van de stelselbeheerder |
| `STELSELDOCUMENT_OMGEVING` | `lokaal` | De omgeving waarvoor de console het document accepteert |

Een stelselregister in `quarkus:dev` ondertekent met een wegwerpketen die bij elke start verandert.
Die root is niet vooraf vast te leggen: laat `STELSELDOCUMENT_ROOT_PEM` dan leeg (`NIET_GETOETST`),
of start het stelselregister met een eigen keystore (`docs/operator-handleiding-stelselregister.md`).

## Opbouw

| Pad | Wat |
|---|---|
| `src/main/webui/src/shell/` | Opmaak die elk beheerportaal deelt: navigatiebalk, pagina, voet. Geen FBS-begrippen. |
| `src/main/webui/src/onderdelen/<naam>/` | De schermen van één onderdeel; `onderdeel.tsx` exporteert ze. |
| `src/main/webui/src/main.tsx` | Voegt de onderdelen samen. |
| `src/main/kotlin/` | De backend: security-headers, en straks de API van de schermen. |

## Afhankelijkheden

Alle npm-versies staan exact vast en `npm ci` installeert tegen de ingecheckte lockfile.
`@nldd/design-system` zit onder 1.0 en brengt vaak brekende minors uit; bump hem bewust en kijk de
schermen na. Dependabot stelt de bumps voor.

Een nieuw pakket toevoegen zonder versies van minder dan twee weken oud:

```bash
npm install --prefix services/beheerconsole/src/main/webui --save-exact --before=<datum van twee weken terug> <pakket>@<versie>
```

## Security-headers

De app krijgt een eigen CSP (`default-src 'self'`, niets inline); de API onder `/api` en
`/openapi.json` houden de strenge policy van de andere diensten. Gebruik in de app daarom geen
`style`-attributen of inline scripts: die blokkeert de browser.

Uitzondering: `quarkus:dev` staat inline script en style toe, want Vite zet daar een inline
preamble en laadt CSS als `<style>`. Een CSP-overtreding in eigen code zie je in dev dus niet;
controleer een nieuw scherm op de gebouwde app (`./mvnw package` en de jar starten).
