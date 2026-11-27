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
