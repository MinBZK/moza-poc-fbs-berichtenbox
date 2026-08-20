# Operator-handleiding — FBS Berichtenuitvraag

Deze handleiding beschrijft de **verplichte productie-overrides** en operationele
aandachtspunten voor de Berichtenuitvraag. De tegenhanger voor het magazijn staat in
[`operator-handleiding.md`](operator-handleiding.md); voor lokaal werk geldt
[`ontwikkelen.md`](ontwikkelen.md).

De uitvraag onderscheidt zich operationeel van het magazijn op drie punten: hij houdt een
sessiecache in Redis, hij bevraagt tijdens één ophaalactie meerdere magazijnen tegelijk, en hij
leunt op de externe Profiel-service om te bepalen wélke magazijnen dat zijn. De meeste knoppen
hieronder gaan over die drie.

## Verplichte productie-overrides

Zonder deze waarden start de service niet, of start hij wél maar zonder de bescherming die het
profiel `%prod` hoort te geven. Waar geen default staat, is dat opzet: fail-closed.

| Property / env var | Doel | Faalwijze |
|---|---|---|
| `REDIS_HOSTS` | Adres van de sessiecache. Buiten dev/test verplicht `rediss://` | Geen default in `%prod`: ontbreekt de var, dan faalt de expressie-expansie bij boot. Een `redis://`-adres laat `RedisVerbindingValidator` de start weigeren |
| `REDIS_PASSWORD` | Wachtwoord op de sessiecache; als losse secret, niet in de hosts-URL, zodat het niet in connection-strings of logs meelekt | Geen prod-default; ontbreekt = start geweigerd door dezelfde validator |
| `FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT` | **BEWUST ONVEILIG.** Zet de transport-eis op de sessiecache uit; de wachtwoord-eis blijft staan. Default `false` | Geen faalwijze — dat is het punt. De validator laat door en logt bij élke boot een WARNING met het stabiele token `REDIS_UNPROTECTED` |
| `HTTP_TLS_TERMINATION` | Waar inkomende TLS termineert: `app` (default, TLS in de Quarkus-laag, vereist keystore) of `mesh` (ingress termineert, container krijgt plain http) | `HttpTlsValidator` weigert de start bij een ontbrekende keystore onder `app`. De default is fail-closed: zonder expliciete keuze boot de service niet onversleuteld |
| `PROFIEL_SERVICE_URL` | Endpoint van de Profiel-service, die bepaalt welke magazijnen een ontvanger heeft | Geen prod-default. `ProfielServiceEndpointValidator` dwingt `https://` af in prod/staging/acceptatie |
| `MAGAZIJN_<X>_URL` | Eén URL per deelnemende organisatie, onder `magazijnen."<OIN>".url` | `ConfigMagazijnregister` valideert bij boot en weigert buiten dev/test een niet-https-adres (via `OutboundTlsValidator`) |
| `MAGAZIJN_<X>_GRANT_HASH` | FSC-grant-hash per magazijn. Aanwezig → `Fsc-Grant-Hash`/`-Transaction-Id` op elke call; leeg → het magazijn wordt zónder outway aangeroepen | Geen. **Let op:** een lege waarde is de veilige uitkomst, een *ontbrekende sleutel* niet — dan meldt de contract-bootstrap wel een hash, maar bindt niets die waarde en gaat het verkeer buiten de FSC-keten om terwijl alles groen meldt |
| `PROFIEL_SERVICE_GRANT_HASH` | Idem voor de Profiel-service | Idem |
| `LDV_DBMS`, `LDV_POSTGRES_URL`, `LDV_POSTGRES_USERNAME`, `LDV_POSTGRES_PASSWORD` | Logboek Dataverwerkingen; buiten dev/test TLS-verplicht op de URL | Zie de [magazijn-handleiding](operator-handleiding.md#verplichte-productie-overrides) — de LDV-configuratie en de bijbehorende `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT`-klep zijn voor beide services identiek |

## De onveilige plaintext-override op de sessiecache

`fbs.redis.unsafe-allow-plaintext` (env var `FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT`) schakelt de
transport-eis op de sessiecache uit. De wachtwoord-eis blijft wél staan.

Wat je opgeeft: de sessiecache bevat berichtsamenvattingen gekoppeld aan een identificatienummer
van de ontvanger. Zonder TLS gaan die onversleuteld over het netwerk (BIO 13.2.1 / AVG art. 32).
`rediss://` alleen is trouwens niet genoeg — Quarkus zet standaard géén hostnaam-verificatie aan;
daarom staan `quarkus.redis.tls.hostname-verification-algorithm=HTTPS` en `tls.trust-all=false`
vast in alle drie de deploy-profielen. Versleutelen zonder peer-verificatie bewijst niet dát de
opslag aan de andere kant zit.

Alleen verantwoord wanneer het netwerkpad zelf transport-security levert (mesh-mTLS of een
niet-routeerbaar intern segment binnen één cluster), of wanneer er geen echte persoonsgegevens
stromen. Verplicht bij gebruik: een alert-regel (Loki/SIEM) op `REDIS_UNPROTECTED`. Zonder die
regel scrolt de waarschuwing weg en blijft de klep onopgemerkt aan staan nadat het netwerk wél
TLS kreeg — dezelfde afweging als bij de LDV-klep in de magazijn-handleiding.

## Timeout-budgetten (er gelden twee harde invarianten)

De uitvraag heeft twee ketens waarin een binnenste en een buitenste timeout op elkaar moeten
aansluiten. Beide worden bij het opstarten gecontroleerd; klopt de verhouding niet, dan start de
service niet.

**Magazijn-keten** — `magazijn-client.read-timeout-ms` MOET groter zijn dan
`berichtensessiecache.magazijn-query-timeout-seconds` (×1000). De query-timeout slaat dan als
eerste aan en levert een TIMEOUT-event op; de read-timeout is het vangnet dat de socket alsnog
vrijgeeft. Draai je het om, dan blijft een hangend magazijn de worker-thread bezet houden.

**Profiel-keten** — `profiel.resolver.outer-await-seconds` MOET groter zijn dan
`profiel.resolver.inner-timeout-seconds`, anders slaat de buitenste als eerste aan en verliest de
aanroeper de timeout-classificatie.

| Property | Env var | Default | Wanneer aanpassen |
|---|---|---|---|
| `magazijn-client.connect-timeout-ms` | `MAGAZIJN_CONNECT_TIMEOUT_MS` | `2000` | Hoger bij magazijnen achter een trage outway |
| `magazijn-client.read-timeout-ms` | `MAGAZIJN_READ_TIMEOUT_MS` | `12000` | Altijd samen met de query-timeout aanpassen; de invariant blijft leidend |
| `berichtensessiecache.magazijn-query-timeout-seconds` | `MAGAZIJN_QUERY_TIMEOUT_SECONDS` | `10` | Verlaag om een traag magazijn eerder als gedegradeerd te melden |
| `profiel.resolver.inner-timeout-seconds` | `PROFIEL_INNER_TIMEOUT` | `18` | Herijken zodra je `PROFIEL_RETRY_JITTER` of de read-timeout verhoogt — de startup-validatie borgt alleen outer > inner, niet inner > retry-budget |
| `profiel.resolver.outer-await-seconds` | `PROFIEL_OUTER_AWAIT` | `25` | Zie de invariant hierboven |
| `berichtensessiecache.cache-await-timeout-seconds` | `CACHE_AWAIT_TIMEOUT_SECONDS` | `5` | Max wachttijd op één Redis-commando tijdens de ophaal-orkestratie |
| `berichtensessiecache.facade-await-timeout-seconds` | `FACADE_AWAIT_TIMEOUT_SECONDS` | `5` | Idem voor élk lees-/schrijfpad door de `Sessiecache`-facade; overschrijding geeft 503 |

## Cache-levensduur

| Property | Default | Wanneer aanpassen |
|---|---|---|
| `berichtensessiecache.ttl` | `PT12H` | Sliding TTL: elke succesvolle read verlengt hem. Dekt een halve werkdag zonder herhaalde ophaal-flow; verlagen bij geheugendruk op Redis |
| `berichtensessiecache.aggregation-lock-ttl` | `PT2M` | Vangnet-TTL voor de aggregatie-lock en de BEZIG-status. Bewust losgekoppeld van de cache-TTL: crasht een pod midden in een aggregatie, dan zelfheelt de status na deze tijd in plaats van de ontvanger 12 uur te blokkeren. Moet ruim boven de normale aggregatieduur liggen |
| `profiel.resolver.cache.ttl-seconds` | `30` | In-JVM Caffeine-cache die burst-load richting de Profiel-service absorbeert voor dezelfde ontvanger |
| `profiel.resolver.cache.max-size` | `10000` | Verhoog bij veel gelijktijdige ontvangers; het is een geheugen-plafond |

## Aandachtspunten in bedrijf

- **Een 404 van de Profiel-service** heeft een eigen runbook:
  [`operations/profiel-404-alert.md`](operations/profiel-404-alert.md).
- **Een RediSearch-schemawijziging** vraagt een gecontroleerde index-herbouw:
  [`operations/redisearch-schema-bump.md`](operations/redisearch-schema-bump.md).
- **Gedeeltelijke storing is normaal gedrag.** Valt één magazijn uit, dan levert de uitvraag de
  overige berichten mét een degradatie-melding; dat is geen fout die je moet wegconfigureren.
- **`quarkus.http.limits.max-body-size` staat op `2M`** als vangnet tegen geheugen-DoS op de
  aanmeld-webhook, de enige request met een noemenswaardige body. Eén CloudEvent draagt maximaal
  één bericht van 1 MiB; de rest is envelope-overhead.
- **Certificaat-validatie richting de Profiel-service** leunt nu op de JVM-default trust-store.
  Een expliciete trust-store voor PKIoverheid-validatie staat nog open als `TODO(#552)`.
- **Uitgaand verkeer door de eigen FSC-outway heeft een eigen trust anchor nodig.** De outway
  serveert zijn poort met een certificaat uit de interne PKI van de peer, en die CA staat niet in
  de JVM-default trust-store. Wijs `MAGAZIJN_A_URL` of `PROFIEL_SERVICE_URL` naar de
  cluster-interne outway, dan horen daar deze variabelen bij:

  | Variabele | Waarde | Effect |
  |-----------|--------|--------|
  | `QUARKUS_TLS_OUTWAY_TRUST_STORE_PEM_CERTS` | het mount-pad van de interne CA, bv. `/etc/fsc/internal/logius/ca/root.pem` | maakt de TLS-configuratie `outway`; de magazijn-clients pakken 'm automatisch op |
  | `QUARKUS_REST_CLIENT_PROFIEL_SERVICE_TLS_CONFIGURATION_NAME` | `outway` | koppelt de profiel-service-client aan datzelfde anchor |

  Zet de tweede variabele **niet** zonder de eerste: een `tls-configuration-name` die naar een
  niet-bestaande configuratie wijst laat de client falen. Zijn beide leeg, dan geldt de
  JVM-default trust-store — het juiste gedrag zolang de outway via een publiek vertrouwde
  ingress bereikt wordt. Een mislukt anchor herken je aan een TLS-handshake-fout richting het
  outway-adres, niet aan een FSC-foutcode: de outway komt er dan niet eens aan te pas.

  **Het anchor geldt alleen voor magazijnen die door de outway lopen** — dat wil zeggen: met een
  `grantHash` in de configuratie. Een magazijn zonder grant-hash wordt rechtstreeks aangeroepen,
  presenteert een publiek certificaat en houdt de JVM-default trust-store. Dat onderscheid zit in
  de code en hoeft dus niet per omgeving geregeld te worden; een named TLS-configuratie vervángt
  de default trust-store namelijk en vult die niet aan.

  **Bij elke boot staat in het log welke modus geldt**, met de gevonden configuratienamen erbij.
  Dat is de plek om te kijken als de handshake faalt: een verkeerd gespelde variabele levert een
  volledig geldige, maar ongebruikte TLS-configuratie op, en die zie je hier terug naast de naam
  die de applicatie zoekt.

  | Variabele | Waarde | Effect |
  |-----------|--------|--------|
  | `FBS_OUTWAY_UNSAFE_ALLOW_UNVERIFIED_TLS` | `false` (default) | Weigert bij boot een `outway`-configuratie met `trust-all` of hostnaam-verificatie `NONE`. Op `true` mag het, en logt elke boot `OUTWAY_TLS_UNVERIFIED` voor alert-routing. |
