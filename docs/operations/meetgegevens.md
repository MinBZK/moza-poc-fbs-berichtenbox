# Meetgegevens van de stelseldiensten

De berichtenuitvraag, het berichtenmagazijn en het stelselregister leveren meetgegevens in
Prometheus-formaat (Micrometer). Dit bestand beschrijft waar ze staan, wat erin zit en wat nog
openligt. Waar ze heen gaan (Prometheus, OTLP, welke voorziening) beslist #1473; welke labels
mogen beslist #1472.

## Waar

| Dienst | Applicatiepoort | Beheerpoort | Meetgegevens |
|---|---|---|---|
| berichtenuitvraag | 8086 | 28086 | `http://<pod>:28086/q/metrics` |
| berichtenmagazijn | 8090 (magazijn-b lokaal 8091) | 28090 (28091) | `http://<pod>:28090/q/metrics` |
| stelselregister | 8094 | 28094 | `http://<pod>:28094/q/metrics` |

- De beheerpoort is de applicatiepoort met een `2` ervoor (`quarkus.management.port=2${quarkus.http.port}`).
  Wie `QUARKUS_HTTP_PORT` zet, verschuift de beheerpoort mee. Zo botsen twee instanties in één
  netwerk-namespace (podman-hostnet) niet. De `1` ervoor is al bezet door Toxiproxy. Dit werkt
  voor een applicatiepoort van 1000 tot en met 9999; daarbuiten zet je `QUARKUS_MANAGEMENT_PORT`.
- De beheerpoort luistert op dezelfde interface als de applicatiepoort
  (`quarkus.management.host=${quarkus.http.host}`). Zonder die regel blijft hij in prod op
  `0.0.0.0`, ook waar `QUARKUS_HTTP_HOST` de applicatie op `127.0.0.1` zet.
- Op de applicatiepoort geeft `/q/metrics` een `404`. Er gaat geen route, Service-poort of
  ZAD-ingress naar de beheerpoort, dus van buiten is hij niet te bereiken.
- `/q/health/*` en `/openapi.json` blijven op de applicatiepoort. De probes van ZAD, Kubernetes
  en het statusdashboard vragen ze daar op.
- Overschrijven kan met `QUARKUS_MANAGEMENT_PORT`. Of de beheerpoort er is, ligt bij de build vast
  (`quarkus.management.enabled`); een omgevingsvariabele verandert dat niet. Bouw nooit zonder:
  dan staat `/q/metrics` op de applicatiepoort, en op ZAD is `/q` via het publieke adres
  bereikbaar (het statusdashboard leest daar `/q/health/ready`).
- In de chart (`charts/fbs-dienst`) laat de NetworkPolicy alleen de applicatiepoort toe. Een
  scraper binnen het cluster heeft een eigen regel nodig; dat hoort bij #1473.

## Wat

Standaard uit Quarkus:

- `http_server_requests_seconds_*` per `method`, `uri`, `status` en `outcome`. `uri` is het
  endpoint-template zonder het root-pad `/api/v1`: `GET /api/v1/berichten/<id>` telt als
  `uri="/berichten/{berichtId}"`. Een antwoord zonder template telt onder een vaste waarde, zodat
  een aanroeper met willekeurige paden geen reeksen kan aanmaken: een `404` als `uri="NOT_FOUND"`,
  andere `4xx` en `5xx` (zoals een `405`) als `uri="UNKNOWN"` (`suppress4xx-errors`), en elk
  `OPTIONS`-verzoek, ook een CORS-preflight, als `uri="OPTIONS"` (`Meetfilters`, per dienst).
  `/q/*` telt niet mee.
- `http_client_requests_seconds_*` voor de REST-clients, per `clientName`, `address`, `method`,
  `uri` (template) en `status`. De Profiel-service heet `profiel-service`; de magazijnclients
  bouwen hun client zelf en heten `none`, ze zijn te herkennen aan `address`.
- `agroal_*` (magazijn): connections in gebruik, wachtend, het maximum, wachttijd.
- `redis_*` (uitvraag): commando's en de pool van de tijdelijke opslag.
- `jvm_*`, `process_*`, `system_*`: geheugen, threads, GC, CPU.
- `ft_*`: de circuit breakers, retries en time-outs van SmallRye Fault Tolerance, per methode.

Eigen meetwaarden:

| Meetwaarde | Dienst | Labels | Betekenis |
|---|---|---|---|
| `fbs_publicatie_aflevering_seconds_*` | magazijn | `doel`, `uitkomst`, `status` | Elke poging om een CloudEvent bij de aanmeldservice of notificatieservice af te leveren. `doel` is de key uit `magazijn.publicatie.downstreams`, of `_onbekend` voor een doel dat niet (meer) geconfigureerd is. `uitkomst`: `geslaagd`, `http_fout`, `timeout`, `netwerkfout`, `configuratiefout`, `serialisatiefout`, `opbouwfout`. `status`: de statusklasse (`2xx`, `4xx`, `5xx`) of `geen`. |
| `fbs_stelseldocument_geldig_tot_seconds`, `fbs_stelseldocument_uitgegeven_op_seconds` | stelselregister | — | Einde van de geldigheid en moment van uitgifte van het geldende stelseldocument, in seconden sinds 1970; `0` als er geen geldend exemplaar is. |
| `fbs_stelseldocument_organisaties` | stelselregister | — | Organisaties in het geldende document; `0` als er geen geldend exemplaar is. |
| `fbs_stelseldocument_sleutel_geldig_tot_seconds` | stelselregister | `herkomst` (`keystore`, `wegwerp`) | Het vroegste einde van de certificaatketen van de ondertekensleutel. Een melding hoort te volgen ruim vóór dit moment; de dienst waarschuwt in de log vanaf 30 dagen, alleen bij een sleutel uit een keystore. |
| `fbs_magazijnregister_magazijnen` | stelselregister | — | Ingeschreven magazijnen in het register van de dienst. |
| `fbs_publicatie_achterstand` | magazijn | `status` (`te_publiceren`, `mislukt`), `doel` | Aanmeldingen in de outbox die niet gepubliceerd zijn. `te_publiceren` telt alleen aanmeldingen waarvan het publicatietijdstip voorbij is. `mislukt` is terminaal en blijft staan tot de retentie het bericht wist (de FK cascadeert), dus de waarde kan dalen; meld daarom op de counter `fbs_publicatie_mislukt_total` hieronder, niet op deze gauge. Elke pod telt dezelfde tabel: neem `max()` over de pods, niet `sum()`. |
| `fbs_publicatie_achterstand_oudste_seconds` | magazijn | `status` (alleen `te_publiceren`), `doel` | Hoe lang de oudste aanmelding al had mogen gaan, ook tijdens een retry; `0` = geen. Een stijgende waarde is het teken dat de publicatie stilstaat. |
| `fbs_publicatie_mislukt_total` | magazijn | `doel` | Aanmeldingen die definitief mislukt zijn: alle pogingen op, of een fout die geen retry krijgt. Vanaf de start op 0 per bestemming; meld op `increase(...[15m]) > 0`. |
| `fbs_publicatie_achterstand_gemeten_op_seconds` | magazijn | — | Laatste geslaagde telling (sinds 1970). De telling draait elke `magazijn.publicatie.achterstand.interval` (60s); loopt deze waarde achter, dan zijn de twee hierboven verouderd. |
| `fbs_retentie_laatste_ronde_seconds`, `fbs_retentie_laatste_geslaagde_ronde_seconds` | magazijn | — | Einde van de laatste ronde van het definitief wissen, en van de laatste ronde zonder fouten (sinds 1970; `0` = nog geen sinds de start). Per pod: neem `max()` over de pods. De ronde draait volgens `retentie.hard-delete.cron`; een geslaagde ronde die meer dan twee periodes oud is, betekent dat het wissen stilstaat of steeds faalt. Na een herstart staan beide op `0` tot de eerste ronde; een melding op "het wissen staat stil" hoort daarom op `fbs_retentie_achterstand_oudste_seconds`. Een ronde die de grens van 100.000 haalt, telt als geslaagd: let dan op `fbs_retentie_wachtend`. |
| `fbs_retentie_verwijderd_total` | magazijn | — | Definitief gewiste berichten. |
| `fbs_retentie_fouten_total` | magazijn | `soort` (`wissen`, `logboek`, `ronde`) | `wissen`: een bericht dat niet te wissen was; `logboek`: gewist, maar de registratie in het logboek mislukte; `ronde`: de hele ronde brak af. |
| `fbs_retentie_wachtend` | magazijn | — | Berichten die na de laatste ronde nog aan beide bewaardrempels voldeden. Hoort na een ronde 0 te zijn, tenzij een bericht niet te wissen was of de ronde de grens van 100.000 haalde; blijft het over meerdere rondes boven 0, dan loopt het wissen achter. Draaien twee pods tegelijk een ronde, dan kan één pod rijen tellen die de ander nog wist: neem `min()` over de pods. |
| `fbs_retentie_achterstand_oudste_seconds` | magazijn | — | Hoe lang het oudste bericht dat aan beide bewaardrempels voldoet al gewist had moeten zijn; `0` = geen. Gemeten bij de start van de dienst en na elke ronde, uit de database: anders dan de tijdstippen hierboven begint deze na een herstart niet op 0. Het signaal voor "het wissen staat stil": ouder dan twee cron-periodes, ongeacht hoe vaak de pods herstarten. Neem `min()` over de pods, om dezelfde reden als bij `wachtend`. |

Een TLS-handshake die faalt, telt als `uitkomst="configuratiefout"`, net als een ontbrekende of
ongeldige configuratie. Een aparte teller per oorzaak volgt met #1488. Een CloudEvent dat niet op
te bouwen is, bereikt `DownstreamClient` niet en telt hier dus niet mee; ook dat hoort bij #1488.

## Wat er niet in mag

Geen ontvanger (BSN, RSIN), geen bericht-id, geen sessie-id, niets wat daarnaar terug te rekenen
is. Labels hebben alleen begrensde waarden. De tests (`BeheerpoortTest` per dienst en
`UitvraagKetenE2eTest`) toetsen dat een bericht-id en een BSN niet in de uitvoer staan.

## Open punten

- **`address` per magazijn.** De magazijnclients labelen elk magazijn met zijn host. Dat is geen
  persoonsgegeven, maar het aantal reeksen groeit met het aantal aangesloten magazijnen. Of dat
  mag, of het label naar één waarde moet, beslist #1472.
- **Waarheen.** Er is nog geen scraper. De registry is Prometheus; overstappen op OTLP is één
  dependency. Dat volgt uit #1473.
