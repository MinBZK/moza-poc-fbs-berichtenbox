# Oud, gedeeld verwerkingenlogboek opruimen

**Status:** Concept

Issue: [MinBZK/MijnOverheidZakelijk#929](https://github.com/MinBZK/MijnOverheidZakelijk/issues/929)
Bouwt voort op: PR #168 (`feature/736-ldv-postgresql`)
Zie ook: [#928](https://github.com/MinBZK/MijnOverheidZakelijk/issues/928) — afdwingen van de scheiding

## Context

Vóór #736 schreef de LDV-wrapper zijn tabel ongekwalificeerd. De wrapper voert
`CREATE TABLE IF NOT EXISTS <table>` en `INSERT INTO <table>` zonder schema uit, en de
LDV-JDBC-URL zet geen `currentSchema`; de tabel landde daardoor in het eerste schrijfbare
schema van de gedeelde DB-user — op ZAD het deployment-brede schema (bijvoorbeeld
`mpfm_w3h_test`). Omdat `magazijna` en `magazijnb` in het `magazijnen`-project dezelfde
database én DB-user delen, schreven beide magazijnen in diezelfde tabel.

Sinds #736 draagt de tabelnaam in `%prod` een schema-prefix:

```properties
%prod.logboekdataverwerking.postgresql.table=${DB_SCHEMA}.logboek_dataverwerkingen
```

Nieuwe logregels komen daarmee in `magazijna.logboek_dataverwerkingen` respectievelijk
`magazijnb.logboek_dataverwerkingen`. Wat blijft staan is de oude tabel in het gedeelde
schema, met de regels van beide organisaties door elkaar. Op previews verdwijnt die
vanzelf bij cleanup; op de baseline-deployment `test` blijft ze bestaan.

Zolang die tabel er staat, is er meer dan één plek waar "het logboek van organisatie X"
kan lijken te staan. Een uitvraag die op tabelnaam zoekt in plaats van op schema kan
handelingen van de andere organisatie tonen. Het gaat om synthetische testgegevens, dus
er is geen datalek-risico — de omgeving is wel langlevend.

## Besluit: droppen

De oude, vermengde tabel wordt **verwijderd**, niet verdeeld.

Rationale:

- Het gaat om synthetische testgegevens op een testomgeving; niemand heeft deze historie
  nodig voor verantwoording of aantoonbaarheid.
- Verdelen is technisch mogelijk (`resource`-jsonb bevat `service.name`, dus per rij is
  het bronmagazijn af te leiden), maar levert alleen historie op die niemand raadpleegt
  en houdt tot die tijd de dubbelzinnige tabel in stand.
- Elke rij die blijft staan is een rij die een latere uitvraag onterecht kan tonen.
  Verwijderen maakt het acceptatiecriterium "maar één plek waar dat logboek staat"
  onvoorwaardelijk waar.

Dit besluit geldt uitsluitend voor de gedeelde testomgeving. Op een omgeving met echte
persoonsgegevens zou verwijderen zonder bewaartermijn-afweging niet passend zijn; daar
is verdelen de aangewezen route.

## Reikwijdte

| Project | Deployment | Verwachting |
|---------|-----------|-------------|
| `magazijnen` (`mpfm-w3h`) | `test` | Drie tabellen: `magazijna`, `magazijnb` én het oude gedeelde schema. De gedeelde tabel is vermengd en gaat weg. |
| `berichtenuitvraag` (`mpfb-8wh`) | `test` | Eén schrijvend component, dus geen vermenging — maar wél mogelijk een verweesde tabel in het oude schema naast de nieuwe. Zelfde controle, zelfde drop. |

Previews (`pr-<n>`) worden niet opgeruimd: die verdwijnen met de deployment.

## Stappen

Uit te voeren per project op de `test`-deployment, met een psql-sessie tegen de
PostgreSQL van dat deployment (pod-shell via `kubectl exec`, credentials uit het
`<deployment>-database`-secret).

1. **Inventariseren** — welke schema's dragen een logboektabel:

   ```sql
   select table_schema, table_name
   from information_schema.tables
   where table_name = 'logboek_dataverwerkingen'
   order by table_schema;
   ```

2. **Tellen per tabel**, zodat achteraf vaststaat wat verdween en wat bleef:

   ```sql
   select count(*) from <schema>.logboek_dataverwerkingen;
   ```

   En voor de gedeelde tabel de verdeling over de bronnen, als bevestiging dat het
   inderdaad de vermengde tabel is:

   ```sql
   select resource ->> 'service.name' as bron, count(*)
   from <gedeeld_schema>.logboek_dataverwerkingen
   group by bron
   order by bron;
   ```

3. **Droppen** van uitsluitend de tabel in het gedeelde schema — nooit die in
   `magazijna`/`magazijnb`:

   ```sql
   drop table <gedeeld_schema>.logboek_dataverwerkingen;
   ```

4. **Verifiëren** — herhaal stap 1; alleen de schema's per organisatie blijven over.

5. **Rookproef** — lever een bericht aan bij beide magazijnen en controleer dat de
   logregel in het eigen schema landt en de gedeelde tabel niet opnieuw ontstaat
   (de wrapper maakt hem alleen aan als de configuratie terugvalt op ongekwalificeerd).

## Aandachtspunten

- **Alleen tabellen droppen, geen schema's.** Het gedeelde schema is het default-schema
  van de deployment-brede DB-user en huisvest ook `flyway_schema_history` en de
  domeintabellen van andere componenten.
- **Deployment herscheppen is géén alternatief.** `DELETE` + `:upsert-deployment` triggert
  `database_cleanup` en wist álle data van het deployment, inclusief de berichten en de
  nieuwe, correcte logboeken. Veel te grof voor deze opruiming.
- **De drop moet ná #168 gebeuren.** Draait op `test` nog een image van vóór de
  schema-prefix, dan maakt de wrapper de gedeelde tabel bij de eerstvolgende logregel
  opnieuw aan. Controleer de image-tag in de gerenderde manifests voordat je dropt.

## Verificatie

- [ ] `information_schema`-query op `mpfm-w3h`/`test` levert alleen `magazijna` en
      `magazijnb` op.
- [ ] `information_schema`-query op `mpfb-8wh`/`test` levert alleen het schema van de
      uitvraag op.
- [ ] De tellingen per organisatie-schema zijn ongewijzigd t.o.v. stap 2.
- [ ] Een nieuwe aanlevering per magazijn schrijft naar het eigen schema en laat geen
      nieuwe tabel in het gedeelde schema ontstaan.
- [ ] De uitgevoerde queries en tellingen zijn als bewijs in issue #929 vastgelegd.
