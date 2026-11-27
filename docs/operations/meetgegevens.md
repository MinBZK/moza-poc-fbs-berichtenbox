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
  netwerk-namespace (podman-hostnet) niet. De `1` ervoor is al bezet door Toxiproxy.
- Op de applicatiepoort geeft `/q/metrics` een `404`. Er gaat geen route, Service-poort of
  ZAD-ingress naar de beheerpoort, dus van buiten is hij niet te bereiken.
- `/q/health/*` en `/openapi.json` blijven op de applicatiepoort. De probes van ZAD, Kubernetes
  en het statusdashboard vragen ze daar op.
- Overschrijven kan met `QUARKUS_MANAGEMENT_PORT`. Zet `QUARKUS_MANAGEMENT_ENABLED` niet op
  `false`: dan staat `/q/metrics` op de applicatiepoort, en op ZAD is `/q` via het publieke adres
  bereikbaar (het statusdashboard leest daar `/q/health/ready`).

## Wat

Standaard uit Quarkus:

- `http_server_requests_seconds_*` per `method`, `uri`, `status` en `outcome`. `uri` is het
  endpoint-template zonder het root-pad `/api/v1`: `GET /api/v1/berichten/<id>` telt als
  `uri="/berichten/{berichtId}"`. Een `4xx` op een pad dat geen endpoint is, telt als
  `uri="NOT_FOUND"` (`suppress4xx-errors`), zodat een aanroeper met willekeurige paden geen
  reeksen kan aanmaken. `/q/*` telt niet mee.
- `http_client_requests_seconds_*` voor de REST-clients, per `clientName`, `address`, `method`,
  `uri` (template) en `status`. De Profiel-service heet `profiel-service`; de magazijnclients
  bouwen hun client zelf en heten `none`, ze zijn te herkennen aan `address`.
- `agroal_*` (magazijn): verbindingen in gebruik, wachtend, het maximum, wachttijd.
- `redis_*` (uitvraag): commando's en de pool van de tijdelijke opslag.
- `jvm_*`, `process_*`, `system_*`: geheugen, threads, GC, CPU.

Eigen meetwaarden:

| Meetwaarde | Dienst | Labels | Betekenis |
|---|---|---|---|
| `fbs_publicatie_aflevering_seconds_*` | magazijn | `doel`, `uitkomst`, `status` | Elke poging om een CloudEvent bij de aanmeldservice of notificatieservice af te leveren. `doel` is de key uit `magazijn.publicatie.downstreams`, of `_onbekend` voor een doel dat niet (meer) geconfigureerd is. `uitkomst`: `geslaagd`, `http_fout`, `timeout`, `netwerkfout`, `configuratiefout`, `serialisatiefout`, `opbouwfout`. `status`: de statusklasse (`2xx`, `4xx`, `5xx`) of `geen`. |

Een TLS-handshake die faalt, telt als `uitkomst="configuratiefout"`, net als een ontbrekende of
ongeldige configuratie. Een aparte teller per oorzaak volgt met #1488.

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
