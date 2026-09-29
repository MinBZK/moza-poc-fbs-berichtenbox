# Uitkomst van een verwerking vastleggen in het logboek

**Status:** Uitgevoerd

## Context

Aanleveren en publiceren schrijven hun logregel vóór de verwerking: `span-processor=simple` +
`write-failure-policy=fail-closed`, en pas na `enforceWriteAcknowledgement` volgt de opslag of
levering. Zo vindt er nooit een verwerking plaats zonder logregel. Mislukt de verwerking daarna,
dan bleef die logregel staan met status `UNSET`, wat de standaard leest als "afgerond zonder
systeemfout". Het logboek vermeldde dan een verwerking die niet had plaatsgevonden
(MinBZK/MijnOverheidZakelijk#924).

Een geëxporteerde logregel is definitief (insert-only, eigen JDBC-commit buiten de
JTA-transactie). Via MinBZK/MijnOverheidZakelijk#930 en
Logius-standaarden/logboek-dataverwerkingen#314 is gekozen voor de vorm die de standaard al
kent: een child-span met status `ERROR` en `exception.*` onder de oorspronkelijke logregel.
De wrapper levert daarvoor vanaf 2.0.0 `ProcessingHandler.recordFailedOutcome`
(moza-logboekdataverwerking#92). De gepubliceerde 1.1.0 heeft hem niet, ook al staat hij in
de bron op `main` van vóór die versie-bump: die jar is gebouwd vóór de merge.

## Ontwerpkeuzes

- **Leesregel:** een logregel zonder ERROR-child is geslaagd. Een MOZa-afspraak; de standaard
  kent hem nog niet.
- **`MislukteUitkomst`** (`ldv/`) is de enige plek die `recordFailedOutcome` aanroept. Een
  verloren uitkomst-logregel is onder-rapportage; die wordt gelogd met het token
  `LDV_UITKOMST_ONTBREEKT` voor alert-routing. Geen retry: de wrapper heeft dan net zelf een
  schrijfactie zien mislukken, en een retry-mechanisme hoort bij een concrete aanleiding.
- **Aanleveren:** alleen een fout uit `slaBerichtOp` krijgt een ERROR-child. Een afwijzing vóór
  de bevestiging staat al als ERROR in de logregel zelf.
- **Publiceren:** een `DownstreamResultaat.Mislukt` en een exceptie vóór of tijdens de levering
  krijgen een ERROR-child. Een onbekend doel niet: die logregel staat al op ERROR. Een fout ná
  een 2xx (`markeerGeslaagd`) ook niet, want dan is er wél verstrekt.
- **Pogingen herkenbaar:** elke poging behoudt een eigen logregel (samenvoegen kan niet in een
  insert-only logboek) en draagt `publicatie.poging`. Samen met `publicatie.bericht_id` en
  `publicatie.doel` zijn meerdere regels zo als pogingen voor één verstrekking te lezen.
- **Geen persoonsgegevens in `exception.message`:** aanleveren geeft `LdvFoutSamenvatting`
  mee (alleen het type), publiceren `LeveringMislukt` (categorie en HTTP-status, nooit de
  `reden`, die tekst van de afnemer kan bevatten).

## Verificatie

- Unit-tests (MockK): volgorde bevestiging → verwerking → uitkomst, geen uitkomst bij succes
  of bij een al-ERROR-logregel, geen reden of BSN in de meegegeven fout, `publicatie.poging`
  per poging, token bij verloren uitkomst.
- `LdvUitkomstIntegrationTest` tegen PostgreSQL (Dev Services, profiel van
  `LdvPostgresIntegrationTest`): parent-koppeling, uitkomst-attribuut en foutattributen zoals
  ze in de tabel landen, voor een mislukte opslag en voor een mislukte leverpoging.

## Buiten deze wijziging

- moza-logboekdataverwerking#93 (interceptor buiten `@Transactional`) raakt de
  `@Logboek`-resources, niet deze twee paden met eigen span-beheer.
