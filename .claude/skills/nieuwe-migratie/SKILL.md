---
name: nieuwe-migratie
description: Scaffold een nieuwe Flyway-migratie met rollback-script en de JPA-conventies erbij
disable-model-invocation: true
---

# Nieuwe migratie

Een bestaande `V*.sql` wijzigen wordt geblokkeerd door een PreToolUse-hook, en terecht: Flyway
bewaart per migratie een checksum, dus een wijziging laat de service pas bij de volgende boot
vallen — ver weg van de edit. Blokkeren is alleen nuttig als het juiste pad makkelijk is, en dat is
wat deze skill doet.

## 1. Bepaal module en volgnummer

Migraties staan per module onder `src/main/resources/db/migration/`:

- `services/berichtenmagazijn`
- `demo/magazijn-simulator`

```bash
ls services/berichtenmagazijn/src/main/resources/db/migration/
```

Het nieuwe bestand is `V<hoogste+1>__<beschrijving>.sql`, met snake_case in de beschrijving. Loopt
er een andere branch met een migratie op hetzelfde nummer, dan botsen die bij het mergen — kijk
even of dat speelt:

```bash
git log --all --oneline --name-only -- '*/db/migration/V*.sql' | head -20
```

## 2. Schrijf de migratie

De conventies, en waarom ze er zijn:

- **Surrogate PK per tabel:** `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`. Business-keys
  (UUID, OIN, bericht-id) zijn `UNIQUE`-constrained kolommen, nooit de PK — dan blijft de
  identiteit van een rij los van wat het domein erover besluit.
- **FK's op de surrogate PK:** een child verwijst via `<parent>_db_id BIGINT` naar `parent.id`,
  niet naar de business-key.
- **Geen `ON DELETE CASCADE` tenzij opzettelijk.** Default is RESTRICT. Soft-delete is de default
  voor `berichten`, en CASCADE ondergraaft die semantiek — het is bovendien een voetkanon zodra er
  ooit hard-delete bij komt. Kies je toch CASCADE, zet dan in het script waarom.
- **PostgreSQL 18, geen H2.** Tests draaien tegen een echte Postgres via Dev Services; syntax die
  alleen op H2 werkt, valt daar om.

Zet bovenaan het script kort de ontwerpkeuzes die niet uit de DDL blijken — waarom een kolom TEXT
is, waarop een unique-constraint slaat, welke fout hij afvangt. Bestaande migraties doen dat ook;
dat is de plek waar zo'n keuze terug te vinden is.

## 3. Schrijf het rollback-script

Naast de migratie, met dezelfde naam, onder `src/main/resources/db/rollback/`:

```
services/berichtenmagazijn/src/main/resources/db/rollback/V<n>__<beschrijving>.sql
```

Dat script draai je met de hand, plus het opruimen van de rij in `flyway_schema_history` — Flyway
kent het niet. Zet erin onder welke voorwaarde het veilig is; een kolomverkleining is dat
bijvoorbeeld alleen zolang er geen te lange waarden in staan.

Is de migratie echt niet terug te draaien, zeg dat dan in het script in plaats van het weg te
laten.

## 4. Pas de JPA-kant aan

- `@ManyToOne(fetch = FetchType.LAZY)` met `@JoinColumn(name = "<parent>_db_id")` voor de relatie
  naar de parent.
- **Geen `@Lob` op een `byte[]`.** Dat mapt op PostgreSQL naar `oid` (Large Object) in plaats van
  `bytea`. De Hibernate 6-defaultmapping — `byte[]` zónder annotatie — geeft VARBINARY → BYTEA en
  is de juiste.

## 5. Werk de test-cleanup bij

Met RESTRICT-FK's loopt cleanup van child naar parent. Komt er een child-tabel bij, dan gaat zijn
repository vóór de parent:

```kotlin
statusRepository.deleteAll()
bijlageRepository.deleteAll()
berichtRepository.deleteAll()
```

Een verkeerde volgorde faalt pas als er data staat — vaak pas in een latere test, en dan lijkt die
test de schuldige.

## 6. Draai het

```bash
./mvnw clean test -pl services/berichtenmagazijn -am
```

Docker of Podman moet draaien (Dev Services start Testcontainers). Draait de demo-stack, voeg dan
`-Dquarkus.http.test-port=0` toe — anders faalt elke `@QuarkusTest` op de bezette poort 8081.

## 7. Laat de conventies nalopen

Draai de subagent `migratie-reviewer` over de wijziging.
