# LDV-inzage per betrokkene in magazijn en uitvraag (Fundament Fieldlab)

**Status:** Uitgevoerd

Koppeling: [MinBZK/MijnOverheidZakelijk#1625](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1625)
(LDV voor het berichtenmagazijn op Fundament).

## Context

Voor de demo op het Fundament Fieldlab willen collega's laten zien dat een beheerder het
Logboek Dataverwerkingen (LDV) van één betrokkene kan opvragen, zoals bij een verzoek tot
inzage (AVG art. 15). Magazijn en uitvraag draaien allebei op Fundament en schrijven elk hun
eigen logboek naar PostgreSQL, maar de library `logboekdataverwerking-wrapper` kan alleen
schrijven. Dit plan voegt aan beide diensten hetzelfde leesendpoint toe; de demo voegt de twee
lijsten samen.

Dit is een demonstratietak (`feature/ldv-inzage-fieldlab`): hij toont wat kan en wordt naar
verwachting niet naar `main` gemerged. Daarom bewust licht: geen afscherming, beperkte tests.

## Ontwerp

Leesregel en SQL staan in `fbs-common` (`common/logboek/`: `Leesregel`, `LogboekLezing`); elke
dienst levert alleen de verbinding naar zijn eigen logboek en een resource.

- **Uitvraag:** zelfde endpoint en antwoordvorm (`GET /api/v1/logboek/verwerkingen`), activiteit
  `urn:nl:moz:fbs:verwerkingsactiviteit:berichtenuitvraag:inzage`. Zonder sessie: het pad staat in
  de vrijstellingen van `SessieFilter`, want een beheerder heeft geen sessie. De uitvraag heeft
  geen eigen datasource; de inzage opent per aanroep een JDBC-verbinding met
  `logboekdataverwerking.postgresql.*`, de gegevens waarmee de wrapper schrijft. Geen
  `BERICHT_ID_ONLY` en geen publicatievelden: `berichtId`, `verstrektAan` en `poging` blijven leeg.

Magazijn:

- **Endpoint:** `GET /api/v1/logboek/verwerkingen` in `berichtenmagazijn-api.yaml` (tag
  `Logboek`, eigen resource). De betrokkene komt mee in de header `X-Betrokkene: <TYPE>:<WAARDE>`
  (zelfde formaat en validatie als `X-Ontvanger`, via `Identificatienummer.fromHeader`), zodat
  het BSN niet in URL of toegangslog staat. Optioneel `van`/`tot` (date-time) als query.
- **Lezen:** native SQL via de gewone datasource. Op Fundament staat het logboek in dezelfde
  database als de berichten; de tabelnaam komt uit `logboekdataverwerking.postgresql.table`
  (in `%prod` schema-gekwalificeerd). Werkt alleen met `LDV_DBMS=postgresql`.
- **Leesregel** (`docs/operator-handleiding.md`, "Uitkomst van een verwerking in het logboek"):
  een rij met `moza.ldv.uitkomst` is een uitkomst-child en geen eigen verwerking; hij hangt via
  `trace_id` + `parent_span_id` onder een logregel. Een logregel op `UNSET` zonder ERROR-child is
  `geslaagd`; een logregel op `ERROR`, of met een ERROR-child met `moza.ldv.uitkomst=mislukt`, is
  `mislukt`.
- **Uitvoer per verwerking:** `tijdstip`, `handeling` (span-naam, bv. `aanleveren-bericht`,
  `publicatie-default`), `verwerkingsactiviteit`, `uitkomst`, `berichtId`, `verstrektAan`
  (`publicatie.doel`), `poging` en bij een mislukking `foutcategorie`. Nieuwste eerst.
  Foutcategorie: `exception.message` van de ERROR-child (bij publiceren een categorie zoals
  `NetwerkFout`, bij aanleveren de klassenaam van de fout); anders `publicatie.fout` of
  `exception.type` van de logregel zelf. Nooit de ruwe `exception.message` van een logregel op
  ERROR: die kan in theorie gegevens van het verzoek bevatten.
- **`BERICHT_ID_ONLY`-regels** (publicatie van een al soft-deleted bericht; `data_subject_id` is
  dan het bericht-id) worden meegenomen via `berichten`, inclusief soft-deleted rijen: een rij
  telt mee als het bericht-id hoort bij een bericht van deze betrokkene.
- **De inzage zelf** is een verwerking: `@Logboek` met een nieuwe activiteit
  `urn:nl:moz:fbs:verwerkingsactiviteit:berichtenmagazijn:inzage`. Niet `:beheer`: dat is de
  ontvanger die zijn eigen bericht bijwerkt of verwijdert, een ander doel dan inzage door een
  beheerder. De logregel van de inzage verschijnt pas bij een volgende inzage (de span sluit na
  het antwoord).
- **Geen PII in de applicatielog:** het endpoint logt niets over de betrokkene.

## Bewust niet gedaan

- **Afscherming.** Het endpoint geeft BSN's terug en staat open, ook via de Gateway-route van de
  plug-in (`PathPrefix /` naar poort `http`). Acceptabel omdat de demo met fictieve BSN's werkt.
  Voor echt gebruik: een aparte poort op loopback (alleen bereikbaar via `kubectl port-forward`;
  de NetworkPolicy van `charts/fbs-dienst` opent alleen poort `http`) of autorisatie via de
  AuthZEN-PEP. De management-interface van Quarkus valt af: daarmee verhuist `/q/health` en
  breken de probes van de chart.
- **Hard-deleted berichten.** Na een hard-delete door de retentie is een `BERICHT_ID_ONLY`-regel
  niet meer aan een betrokkene te koppelen. Structurele oplossing: in `PublicatieClaimVerwerker`
  de ontvanger van het soft-deleted bericht als betrokkene vastleggen in plaats van het
  bericht-id.
- **Bericht-id bij aanleveren en ophalen.** Alleen publicatieregels dragen `publicatie.bericht_id`;
  bij aanleveren en ophalen blijft `berichtId` leeg.
- **Retentie in het logboek.** Een hard-delete staat alleen in de applicatielog
  (`HardDeleteLdvLogger`), niet in het LDV, en verschijnt dus niet in de inzage.
- **Index** op `attributes->>'dpl.core.data_subject_id'`: de wrapper maakt de tabel aan; bij
  demovolumes is een sequentiële scan geen probleem.
- **Leesfunctie in de library `moza-logboekdataverwerking`**, zodat NMC en de profielservice hem
  kunnen hergebruiken: wenselijk vervolg.
- Runbookstap; er zijn Bruno-requests (`bruno/berichtenmagazijn/logboek/`, `bruno/berichtenuitvraag/logboek/`).
- Eén inzage over beide logboeken heen: dat vergt een verbinding van de ene dienst naar het
  logboek van de andere.

## Uitrol op Fundament

De plug-in `fundament-fbs-magazijn` pint de chart op een commit (`FBS_CHART_COMMIT`) en het image
op `main-<sha7>` (`FBS_IMAGE`; `Dockerfile` controleert dat prefix). Route:

1. PR van deze tak (#488) uit draft halen: een draft-PR bouwt en rolt niets uit. De CI publiceert
   dan de images van magazijn en uitvraag als `pr-<n>-<sha7>[-r<n>]` (en rolt als bijwerking een
   ZAD-preview uit).
2. In `fundament-fbs-magazijn` (aparte sessie): `FBS_CHART_COMMIT` op de commit van deze tak,
   `FBS_IMAGE` op `pr-<n>-<sha7>@<digest>`, en de `main-`-controle in het `Dockerfile` versoepelen.
   Geen nieuwe values of rechten nodig; de chart verandert niet.
3. De uitvraag op Fundament op het nieuwe uitvraag-image zetten, langs de weg waarlangs hij
   daar is uitgerold.

## Verificatie

- `LeesregelTest` en `LogboekLezingTest` in `fbs-common` (leesregel en SQL, zonder database).
- Uitvraag: `LogboekInzageTest` (`@QuarkusTest`, lezer gemockt): zonder sessie bereikbaar,
  leesregel toegepast, periode doorgegeven, 400 bij een ongeldige header.
- Magazijn: `LogboekInzageIntegrationTest` (`@QuarkusTest`, echte PostgreSQL): zoekvraag per betrokkene
  (andere betrokkenen niet zichtbaar), leesregel (UNSET, ERROR, ERROR-child), een
  `BERICHT_ID_ONLY`-regel via een soft-deleted bericht, en dat de inzage zelf een logregel
  oplevert.
- Handmatig: aanleveren, ophalen en publiceren, dan `GET /api/v1/logboek/verwerkingen` met
  `X-Betrokkene`.
