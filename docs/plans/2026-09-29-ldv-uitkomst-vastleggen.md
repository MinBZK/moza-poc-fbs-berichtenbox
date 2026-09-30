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

- **Leesregel:** een logregel op `UNSET` zonder ERROR-child is geslaagd; een logregel die zelf
  op `ERROR` staat, is mislukt. Een MOZa-afspraak; de standaard kent hem nog niet.
- **Liever te veel dan te weinig (besluit opdrachtgever):** false positives in het logboek zijn
  acceptabel, een gemiste verstrekking niet. Een leverpoging krijgt daarom alleen een ERROR-child
  als vaststaat dat de afnemer niets kreeg (`DownstreamResultaat.Mislukt.zekerNietVerzonden`):
  fout bij opbouwen van bericht of verbinding. Read-timeout, verbroken antwoord en elk
  HTTP-foutantwoord blijven als verstrekking staan. Een derde uitkomstwaarde ("onzeker") is
  overwogen en verworpen: de wrapper kent hem niet en de leesregel zou driewaardig worden.
- **`MislukteUitkomst`** (`ldv/`) is de enige plek die `recordFailedOutcome` aanroept en vat de
  fout zelf samen (`LdvFoutSamenvatting`, `LeveringMislukt`), zodat geen aanroeper een ruwe
  exceptie-message het logboek in kan sturen. Hij gooit nooit, ook niet bij een afwijkende
  wrapper-versie. Een verloren uitkomst-logregel is onder-rapportage; die wordt gelogd met het
  token `LDV_UITKOMST_ONTBREEKT` en kenmerken zonder persoonsgegevens. Geen retry: een
  retry-mechanisme hoort bij een concrete aanleiding.
- **Aanleveren:** elke `Throwable` uit `slaBerichtOp` krijgt een ERROR-child; ook een `Error`
  rolt de opslag terug. Een afwijzing vóór de bevestiging staat al als ERROR in de logregel zelf.
- **Publiceren:** een fout bij het opbouwen van het CloudEvent krijgt een ERROR-child, een fout
  uit `lever` zelf niet (onbekend of er iets verstuurd is). Een onbekend doel niet: die logregel
  staat al op ERROR. Een fout ná een 2xx (`markeerGeslaagd`) ook niet, want dan is er verstrekt.
- **Pogingen herkenbaar:** elke poging behoudt een eigen logregel (samenvoegen kan niet in een
  insert-only logboek) en draagt `publicatie.poging`, naast `publicatie.bericht_id` en
  `publicatie.doel`. Na een teruggedraaide verwerking kan een nummer terugkomen; unieke nummers
  vragen een teller buiten de transactie en wachten op een concrete aanleiding.

## Verificatie

- Unit-tests (MockK): volgorde bevestiging → verwerking → uitkomst; geen uitkomst bij succes,
  bij een al-ERROR-logregel of bij een onzekere levering; welke leverfouten "zeker niet
  verzonden" zijn; geen reden of BSN in de meegegeven fout; `publicatie.poging` per poging;
  token en kenmerken bij verloren uitkomst; niet gooien bij een wrapper-fout.
- `LdvUitkomstIntegrationTest` tegen PostgreSQL (Dev Services, profiel van
  `LdvPostgresIntegrationTest`): parent-koppeling, uitkomst-attribuut en foutattributen zoals
  ze in de tabel landen, voor een mislukte opslag en voor drie mislukte leverpogingen; een
  geweigerde logregel houdt de levering tegen (fail-closed); een geweigerde uitkomst-logregel
  laat de claim-afhandeling en de volgende poging ongemoeid.

## Buiten deze wijziging

- moza-logboekdataverwerking#93 (interceptor buiten `@Transactional`) raakt de
  `@Logboek`-resources, niet deze twee paden met eigen span-beheer.
