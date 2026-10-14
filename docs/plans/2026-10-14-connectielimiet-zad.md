# Connectielimiet van de ZAD-database passend maken

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#1210.

## Context

Elke deployment krijgt van de Operations Manager (OM) één PostgreSQL-rol `<project>_<deployment>`
met een `CONNECTION LIMIT`. Die stond hardgecodeerd op twintig. In `mpfm-w3h` delen `magazijna`,
`magazijnb`, `democonsole` en `magazijnsimulator` die rol; bij een volle ophaalronde over de
gesimuleerde magazijnen was twintig te weinig (pr-315, 2026-09-15: ~43 time-outs). Noodmaatregel
(#325): `DB_POOL_MAX=10` componentbreed op de simulator, override `50` op `test`.

Sinds de RIG-Cluster-release van 2026-09-28 (#194, RC-201) is de limiet een instelling van de
dienst `postgresql-database`: veld `connection-limit`, 1–500, standaard 20, op project- of
deploymentniveau (deployment wint). OM leest bij elke verwerking `rolconnlimit` en zet de rol bij
een verschil op de berekende waarde, ook bestaande rollen en de `_ro`-rol.

Gevolg dat het issue nog niet kende: de handmatig opgehoogde rol van `test` is bij de eerste
verwerking na die release terug naar twintig gezet, terwijl de simulator daar op pool 50 staat.
`test` loopt dus waarschijnlijk tegen hetzelfde probleem aan als de previews.

## Budget

Wat elk component maximaal tegelijk openhoudt op de gedeelde rol:

| component | pools | max |
|---|---|---|
| `magazijna` | datasource (Agroal-default 20) + logboek (één connection) | 21 |
| `magazijnb` | idem | 21 |
| `democonsole` | `magazijn-a-db` + `magazijn-b-db`, nu Agroal-default 2 × 20 | 40 → **4** |
| `magazijnsimulator` | `DB_POOL_MAX`, default 50 | 50 |
| **som** | | 132 → **96** |

De console gebruikt zijn datasources alleen voor tellen en legen, met korte queries; twintig per
datasource is daar nooit nodig. Begrenzen op twee brengt de som onder de honderd.

## Keuzes

- **`connection-limit: 100` op projectniveau in `mpfm-w3h`**, niet per deployment. Een nieuwe preview
  erft de projectwaarde vanzelf; een deployment-override zou bij elke `pr-<n>` handwerk of een
  `deploy.yml`-wijziging vragen. Honderd dekt de som van de pools (96) met een kleine marge voor
  een database-console of ad-hoc-taak; de servicebeschrijving vraagt niet hoger te gaan dan nodig,
  omdat elke rol ruimte op de gedeelde server claimt (`max_connections` 500 op productie).
- **De `DB_POOL_MAX`-overrides verwijderen** (componentbreed `10` en `test` `50`), zodat de default
  van 50 uit `application.properties` overal geldt. Eén waarde in de code in plaats van twee lagen
  env die je niet kunt uitlezen.
- **Console-pools op `jdbc.max-size=2`** in `demo/demo-console/src/main/resources/application.properties`.
- **`mpfb-8wh` en `mpfpsm-lcl` blijven op de standaard.** De uitvraag houdt alleen de ene
  logboek-connection open; `mpfpsm-lcl` heeft geen database.

## Stappen

1. Console-pools begrenzen (code), via PR uitrollen.
2. Live stand vastleggen: `zadctl -p mpfm-w3h service config get postgresql-database` en de
   huidige `user-env-vars` van `magazijnsimulator` (project describe).
3. `zadctl -p mpfm-w3h service config set postgresql-database --target project` met de bestaande
   velden plus `connection-limit=100` (`set` vervangt het hele document). Niet tijdens een lopende
   deploy.
4. `DB_POOL_MAX` verwijderen op beide lagen.
5. Meten op een verse preview en op `test`: na opwarmen drie ophaalrondes; geen time-out bij
   magazijnen die niet op storing staan, geen `too many connections` in de log, poolregel van de
   simulator.
6. Documentatie bijwerken: `docs/operations/zad-gitops.md` (sectie over de rol) en
   `demo/environment/zad-demo/magazijn-simulator.md`, plus de verwijzing in
   `demo/magazijn-simulator/src/main/resources/application.properties`.

## Verificatie

- Acceptatiecriteria uit het issue, gemeten en met getallen in de PR.
- `./mvnw clean test -pl demo/demo-console -am` groen.
