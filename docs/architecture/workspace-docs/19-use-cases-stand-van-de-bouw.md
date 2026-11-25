# Stand van de bouw

Voor ontwikkelaars: waar de software nog afwijkt van de use cases, met de plek in de code. De use
cases zelf beschrijven het stelsel zoals het moet werken; wat voor alle use cases nog niet gebouwd
is (eHerkenning, pseudoniemen, machtigingen, FSC, autorisatiebeleid), staat op de pagina met
PoC-afwijkingen.

## Afwijkingen per use case

| Use case | Wat nu anders werkt | Waar | Issue |
|---|---|---|---|
| 1 | Een bericht is vóór zijn publicatiedatum al op te halen; alleen aanmelding en melding wachten | `BerichtRepository.lijstVoorOntvanger` en het detail filteren niet op `publicatietijdstip` | [#1586](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1586) |
| 1 | Het magazijn controleert niet of de afzender-OIN de eigen organisatie is; de uitvraag kiest bij een aanmelding het magazijn op die OIN | `BerichtOpslagService`, `AanmeldService.parse` | [#1587](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1587) |
| 1 | Aanmelding en melding bevatten `ontvanger` en `onderwerp` onversleuteld | `CloudEventBuilder.BerichtData`. Het NL GOV-profiel voor CloudEvents wijst versleuteling van `data` of een claim-check via `dataref` aan | [#1588](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1588) |
| 1, 6 | Alleen `application/pdf` als bijlage toegestaan. Geen grens aan het aantal bijlagen, geen controle op de inhoud. Wel 25 MiB per bijlage en 40 MiB per request (inclusief Base64) | `Bijlage.MAX_CONTENT_BYTES`, `quarkus.http.limits.max-body-size` | [#1589](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1589) |
| 1 | Aanleveren is niet idempotent | `PublicatieOutbox` | – (open vraag) |
| 1 | De Publicatie Stream geeft na 5 pogingen per bestemming op, in de praktijk na zo'n 5 minuten | `magazijn.publicatie.polling.interval` (60s), `max-pogingen` | – (open vraag) |
| 1 | Profiel Service onbereikbaar na de retries geeft `500`, geen `503` | `BerichtValidatieService` | [#1590](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1590) |
| 2 | Een voorkeur voor een OIN zonder magazijn in het register wordt stil overgeslagen | Sessiecache-aggregatie via `Magazijnregister` | [#1591](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1591) |
| 2, 11 | Het logboek van het ophalen legt alleen het aantal mislukte magazijnen vast, niet welke | `uitvraag-ophalen-sse` | [#1592](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1592) |
| 2 | Een tweede ophaalronde geeft `409` met `type` `conflict`, niet `ophalen-bezig` | `BerichtensessiecacheService` | [#1590](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1590) |
| 4 | Niet gebouwd: de magazijn-API kent geen zoekfunctie. De bestaande `_zoeken` van de uitvraag zoekt alleen in `onderwerp`, wordt door de berichtenbox niet gebruikt en wordt opgeheven ([#1287](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1287)). Die zoekfunctie volgt Engelse stemming en stopwoorden, vindt geen accenten, geen meerdere woorden en geen leestekens | `BerichtenCache`: RediSearch-index zonder `LANGUAGE`; `escapeRedisSearch` escapet elk teken buiten `[a-zA-Z0-9]`, ook de spatie | [#1598](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1598), [#1287](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1287) |
| 5, 9 | De logregel van de uitvraag bij openen legt de uitkomst niet vast: een 4xx krijgt status `Unset`, net als een geslaagde opening. `Unset` is volgens de LDV-standaard juist voor een functionele weigering, dus niet "repareren" naar `Error`; wat ontbreekt is de uitkomst in de logregel. Geen van beide logregels bevat het berichtnummer | `uitvraag-bericht`, magazijn `ophalen-bericht` | [#1592](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1592), [#1381](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1381) |
| 7 | Mislukt na een geslaagde magazijnwijziging het bijwerken van de sessiecache, dan compenseert de uitvraag met `Sessiecache.verwijder`: die schrijft een tombstone, en een volgende opening geeft `410 bericht-verwijderd` | `BerichtBeheerService.invalideerCacheNaMagazijnWrite` | [#1593](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1593) |
| 7, 8 | `PATCH` en `DELETE` gaan in de uitvraag zonder controle op de sessielijst naar het magazijn. Dat geeft `403` voor andermans actieve bericht en `404` voor een onbekend bericht, en de uitvraag geeft die `403` door als `GEEN_TOEGANG`. Lezen en bijlagen kijken eerst in de lijst en geven in beide gevallen `404` | `BerichtBeheerService`, `BerichtAutorisatie.vereisOntvanger`, `UpstreamFault` | [#1594](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1594) |
| 8, 10 | Het definitief opruimen logt alleen naar de applicatielog, met berichtnummer maar zonder ontvanger. Het model zegt dat de Retentie Service naar het LDV logt | `HardDeleteJob` | [#549](https://github.com/MinBZK/MijnOverheidZakelijk/issues/549), [#1489](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1489) |
| 9, 10 | Geen bewaartermijn op LDV-regels | LDV-configuratie per dienst | [#1595](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1595) |
| 10 | Geen opdracht om alle berichten van één ontvanger te wissen | Magazijn-API | [#1596](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1596) |
| 10 | Niet-verwijderde berichten worden nooit opgeruimd | `BerichtRepository`: hard-delete selecteert alleen `verwijderd_op IS NOT NULL` | [#1597](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1597) |
| 11 | Een bericht uit een magazijn dat bij het ophalen niet leverde, geeft bij openen `404 bericht-onbekend` | Uitvraag `BerichtOphaalService`: eerst de sessielijst | [#1591](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1591) |
| 11 | Geen alert per magazijn dat lang niet levert | Sessiecache-aggregatie, circuit breaker per magazijn | [#1480](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1480), [#1478](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1478) |
| 13, 14 | Het register is configuratie, apart in uitvraag en stelselregister. In- en uitschrijven vraagt een uitrol van beide; niet te herleiden wie wat wijzigde | `magazijnen."<OIN>".{url,naam,grantHash}` in `application.properties` van uitvraag en stelselregister; `ConfigMagazijnregister` valideert bij het opstarten, `MagazijnClientFactory` bouwt de clients eenmalig | [#1484](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1484), [#1485](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1485) |
| 13 | Geen zicht op de ingeschreven magazijnen, en geen controle dat uitvraag en stelselregister dezelfde lijst hebben | – | [#1483](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1483) |
| 13 | Een magazijn neerzetten en aanmelden is handwerk: database, `MAGAZIJN_OIN`, downstream-URL's, grant-hashes, registerregel | Plug-in op Fundament in beproeving | [#1538](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1538) |
| 13 | De Profiel Service kent geen lijst van deelnemende organisaties; een voorkeur noemt alleen een OIN | `ProfielVoorkeuren.optedInAfzenderOinStrings` | – (open vraag) |
| 14 | Een magazijn kan niet op een datum stoppen met aannemen terwijl ophalen en beheren doorgaan | Magazijn-API | – (open vraag) |
| 14 | Geen melding aan alle ontvangers van een magazijn; de Publicatie Stream meldt alleen per bericht | `PublicatieOutbox`, `CloudEventBuilder` | – (open vraag) |
| 14 | Een vervallen voorkeur haalt ook eerder rechtmatig gestuurde berichten uit de berichtenbox | `ProfielMagazijnResolver`: alleen actuele voorkeuren | [#1413](https://github.com/MinBZK/MijnOverheidZakelijk/issues/1413) |

## Wanneer het logboek vastlegt

De use cases zeggen dat elke verwerking in het logboek staat. De volgorde ten opzichte van de
handeling verschilt per pad:

- **Aanleveren en publiceren (magazijn):** vóór de handeling. Mislukt de vastlegging, dan gebeurt
  er niets.
- **Overige handlers met `@Logboek`:** bij het verlaten van de methode, vóór het antwoord. Mislukt
  de vastlegging, dan krijgt de aanroeper een fout, terwijl een wijziging al is doorgevoerd.
- **Ophalen (uitvraag):** bij de start, met later een kind-regel met `ERROR` als het mislukt.
- **Volgen (uitvraag):** alleen bij de start; een afgebroken verbinding staat niet in het logboek.
- **Wissen bij einde of start van een sessie:** gaat altijd door; mislukt de vastlegging, dan volgt
  een alert. Alleen de Redis-TTL (vangnet) wist zonder logregel.

## Koppelvlakken per use case

| Use case | Koppelvlak |
|---|---|
| 1 | Magazijn `POST /api/v1/aanleveringen`; Profiel Service `POST /api/profielservice/v1/partij`; uitvraag `POST /api/v1/aanmeldingen` (CloudEvent `nl.rijksoverheid.fbs.bericht.gepubliceerd`); CloudEvent naar `NOTIFICATIE_URL` |
| 2 | Uitvraag `POST /api/v1/sessies`, `GET /api/v1/berichten/_ophalen` (SSE), `GET /api/v1/berichten`, `POST /api/v1/sessies/huidige/_verlengen`, `DELETE /api/v1/sessies/huidige`; magazijn `GET /api/v1/berichten` |
| 3 | Uitvraag `GET /api/v1/berichten/_volgen` (SSE); doorgeven tussen instanties via Redis pub/sub |
| 4 | Nog geen; nu alleen uitvraag `GET /api/v1/berichten/_zoeken?q=` (onderwerp, op te heffen) |
| 12 | Uitvraag `GET /api/v1/berichten?pagina&paginaGrootte` (tot 200 per pagina, `_links.next` volgen); filteren en sorteren in de browser, zie `docs/plans/2026-09-30-filteren-zoeken-sorteren-in-de-browser.md` |
| 5 | Uitvraag `GET /api/v1/berichten/{berichtId}?magazijnId=`; magazijn `GET /api/v1/berichten/{berichtId}` |
| 6 | Uitvraag `GET /api/v1/berichten/{berichtId}/bijlagen/{bijlageId}?magazijnId=`; magazijn idem zonder `magazijnId` |
| 7 | Uitvraag `PATCH /api/v1/berichten/{berichtId}?magazijnId=` (`application/merge-patch+json`); magazijn `PATCH /api/v1/berichten/{berichtId}` |
| 8 | Uitvraag `DELETE /api/v1/berichten/{berichtId}?magazijnId=`; magazijn `DELETE /api/v1/berichten/{berichtId}` (soft-delete); `HardDeleteJob` dagelijks 03:00 Europe/Amsterdam |
| 13, 14 | Geen API: registerregels `magazijnen."<OIN>".{url,naam}` in uitvraag en stelselregister; stelselregister `GET /api/v1/stelseldocument`; per magazijn `MAGAZIJN_OIN`, `AANMELD_URL`, `NOTIFICATIE_URL` en de grant-hashes voor FSC |
