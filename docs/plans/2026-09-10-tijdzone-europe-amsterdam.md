# Alle containers in tijdzone Europe/Amsterdam

**Status:** Uitgevoerd

Gestackt op PR #306 (`chore/claude-tooling-uitbreiden`): die voegt de waarschuwing toe dat de
ZAD-logs in UTC staan, en deze wijziging maakt die waarschuwing grotendeels overbodig.

## Context

Geen van onze images zet een tijdzone, dus alles draait in UTC. Wie een logregel naast zijn klok
legt, zit een of twee uur mis — precies op het moment dat je een incident probeert terug te vinden.
De vraag: laat elke container in `Europe/Amsterdam` draaien.

## Eerst uitgezocht: raakt het de database?

`TZ` in een JVM-container verandert de standaardtijdzone van de JVM, en pgjdbc geeft die bij elke
verbinding als sessietijdzone aan PostgreSQL mee. Het magazijn heeft nog twee kolommen zonder
tijdzone uit V2: `berichten.verwijderd_op` en `bericht_status.gewijzigd_op`. Het vermoeden was dat
die onder een Amsterdamse sessie een of twee uur zouden verschuiven, en dat ze eerst naar
`TIMESTAMPTZ` moesten, zoals V5 met `tijdstip_ontvangst` deed.

Een test die de sessietijdzone zelf zet (`SessietijdzoneIntegrationTest`) weerlegt dat: onder UTC,
Amsterdam in zomer- en wintertijd, het dubbele uur bij de wintertijdovergang en New York komen
`verwijderdOp` (soft-delete én hard-delete-claim) en `gewijzigdOp` (upsert én batch-lezen)
ongeschonden terug, op de huidige code. Geen migratie dus. De test blijft staan als bewaking: CI
draait in UTC en zou een verschuiving anders nooit zien.

De magazijn-simulator gebruikt al overal `TIMESTAMPTZ`. De code rekent overal met
`Clock.systemUTC()`; alleen de ontdubbelingsknop van de demo-console zet `OffsetDateTime.now()` in
een CloudEvent, dat nu `+02:00` draagt in plaats van `Z` — beide geldig RFC 3339.

## Stappen

1. **Bewakingstest** `SessietijdzoneIntegrationTest` (zie hierboven).
2. **Tijdzone in de images**:
   - de vijf jib-images (`berichtenmagazijn`, `berichtenuitvraag`, `demo-console`,
     `demo-personas`, `magazijn-simulator`): `quarkus.jib.environment-variables.TZ`. Zo draagt
     het image het zelf, lokaal en op ZAD, zonder dat de deploy iets hoeft te zetten. De JVM heeft
     een eigen tijdzonedatabase, dus het basisimage hoeft geen `tzdata` te hebben;
   - de WireMock-images (`externe-stubs`, `demo-profiel`): `ENV TZ`;
   - het contract-bootstrap-image (alpine): `ENV TZ` plus `tzdata`, want kaal alpine heeft geen
     tijdzonedatabase en valt dan zonder melding terug op UTC.
3. **Compose**: `TZ` op elke service in `compose.yaml` en in de twee FSC-harness-basisbestanden. De
   overlays erven dat — geen van hen reset `environment`.
4. **Docs**: de UTC-waarschuwing in `docs/operations/zad-gitops.md` en de `zad-debug`-skill bijwerken.

## Ontwerpkeuzes

- **In het image, niet in de deploy.** Env voor ZAD-componenten staat in de OM-project-spec buiten
  deze repo. Een image dat zijn tijdzone zelf draagt, draait overal hetzelfde.
- **Eén waarde op elke compose-service**, ook op de eigen images die hem al dragen: "elke service
  heeft `TZ`" is een regel die een nieuwe service vanzelf volgt.
- **Geen POSIX-tijdzonestring** (`CET-1CEST,…`) voor images zonder `tzdata`. Die werkt zonder
  database in glibc en musl, maar een JVM begrijpt hem niet en valt terug op GMT. In images zonder
  tijdzonedatabase heeft `TZ` dus geen effect, en dat is niet schadelijk.

## Per extern image nagegaan

| Image | `TZ` werkt |
|-------|-----------|
| postgres 17/18, wiremock, haproxy, nginx-alpine, proeftuin (alpine mét tzdata) | ja |
| redis-stack-server, curl (toolbox), alpine zonder tzdata | nee — geen tijdzonedatabase |
| toxiproxy, FSC manager/outway/directory-ui/txlog, manager-migrate | nee — Go zonder tijdzonedatabase |
| FSC controller, http-echo | ja — zoneinfo aanwezig |

## Buiten scope

- Testcontainers/Dev Services: tests draaien in de tijdzone van de machine.
- ZAD-componenten uit een extern image (`redis`, `proeftuin`, de FSC-componenten): `TZ` moet daar in
  de project-spec, via OM — en helpt alleen waar het image een tijdzonedatabase heeft.
- `.clusterfuzzlite/`: bouwcontainers, geen draaiende dienst.
- Bestaande lokale PostgreSQL-volumes houden de servertijdzone die ze bij `initdb` kregen. Onze
  diensten merken daar niets van (pgjdbc zet de sessietijdzone zelf); een `psql`-sessie wel, tot
  het volume opnieuw wordt aangemaakt.

## Verificatie

- Bewakingstest groen op alle tijdzones, op ongewijzigde productiecode.
- Volledige magazijnsuite groen, ook met `TZ=Europe/Amsterdam` op de test-JVM.
- Gebouwd jib-image: `TZ` staat in de image-config en de JVM rapporteert `Europe/Amsterdam`.
- `docker compose config` op de gewijzigde compose-bestanden en hun overlays.
