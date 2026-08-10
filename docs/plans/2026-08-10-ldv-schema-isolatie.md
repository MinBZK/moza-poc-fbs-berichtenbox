# Afscherming van het verwerkingenlogboek tussen organisaties

**Status:** Concept

Issue: [MinBZK/MijnOverheidZakelijk#928](https://github.com/MinBZK/MijnOverheidZakelijk/issues/928)
Bouwt voort op: PR #168 (`feature/736-ldv-postgresql`)

## Context

Op ZAD levert de `postgresql-database`-service één database **per deployment**, niet per
component. `magazijna` en `magazijnb` mounten hetzelfde `<deployment>-database`-secret en
delen dus host, database én DB-user. Sinds #736 staat het logboek per magazijn in een eigen
schema:

```properties
%prod.logboekdataverwerking.postgresql.table=${DB_SCHEMA}.logboek_dataverwerkingen
```

Die scheiding beschermt tegen vergissingen, niet tegen opzet: één gedeelde DB-user heeft
rechten op beide schema's, dus `SELECT * FROM magazijnb.logboek_dataverwerkingen` slaagt
vanuit magazijn-a. De `attributes`-jsonb bevat `dpl.core.data_subject_id`, dat de BSN mag
bevatten — het gevoeligste gegeven dat we vastleggen.

## Doel

- Een organisatie kan uitsluitend haar eigen verwerkingenlogboek benaderen.
- Een poging tot het logboek van een andere organisatie mislukt en is achteraf vast te stellen.
- Een nieuwe organisatie krijgt de afscherming automatisch, zonder handwerk per omgeving.
- Bestaande logboekgegevens blijven beschikbaar voor de organisatie waar ze bij horen.
- De afscherming is aantoonbaar met een test die faalt zodra ze wegvalt.

## Oplossingsrichtingen (nog te kiezen)

1. **Rol per component** — eigen DB-user per magazijn, `REVOKE ALL ON SCHEMA <ander> FROM <rol>`.
   Vergt dat ZAD meerdere users per database uitgeeft, of dat wij ze zelf aanmaken in een
   migratie die met verhoogde rechten draait.
2. **Database per organisatie** — schoonste scheiding, maar ZAD levert nu één database per
   deployment.
3. **Row-level security** op de logboektabel met de organisatie als policy-kolom. Houdt één
   tabel intact, maar de sessie moet de organisatie meegeven — lastig omdat de LDV-wrapper de
   verbinding beheert.

## Openstaande vraag (blokkeert de keuze)

Kan ZAD een tweede DB-user per deployment uitgeven, of kunnen wij die zelf aanmaken met de
geleverde credentials? Zonder antwoord is richting 1 niet te bevestigen. Het issue draagt nog
het label `refine`.

## Stappen

Volgt na refinement en het antwoord van ZAD.

## Verificatie

Volgt.
