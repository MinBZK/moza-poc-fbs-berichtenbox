# B2: de FBS-keten lokaal door FSC

**Status:** Concept

## Context

Na #197 (federatie-harness) en #200 (contract uitvraag↔magazijn) staat de FSC-laag lokaal
compleet: twee peers, één directory, een wederzijds ondertekend contract, en een bewezen data-pad
outway → router → inway. Achter die inway zit alleen nog een `stub-upstream` (http-echo).

De FBS-applicatie draait er los naast, in `compose.yaml`: `berichtenuitvraag` haalt rechtstreeks op
bij `berichtenmagazijn-a` en `-b`. Die twee werelden raken elkaar nergens.

Deze stap verbindt ze: de uitvraag haalt bij magazijn-a op **door de FSC-keten**, met de echte
`berichtenmagazijn` als upstream van de inway.

## Wat er níét voor nodig is

Applicatiecode. `MagazijnRouter` hangt al een `FscOutwayHeadersFilter` aan een magazijn zodra dat
een `grantHash` heeft:

```
magazijnen."00000000000000100000".url=${MAGAZIJN_A_URL}
magazijnen."00000000000000100000".grantHash=${MAGAZIJN_A_GRANT_HASH:}
```

Leeg = rechtstreeks; gevuld = `Fsc-Grant-Hash` + `Fsc-Transaction-Id` op elke call. Hetzelfde
patroon bestaat al voor `berichtenmagazijn → profiel-service`. Dit is dus bedrading, geen bouwwerk.

## Ontwerp

### 1. Loopback-discipline voor de demo-stack

**Dit is de blokkade, en hij is empirisch vastgesteld.** De demo-stack bindt in hostnet-modus
wildcard, niet op een adres:

```
FatalStartupException: java.io.IOException: Failed to bind to /0.0.0.0:8081
```

Een wildcard-bind botst met élke specifieke bind op dezelfde poort. De federatie heeft `:8081` op
zeven component-adressen (monitoring), dus `0.0.0.0:8081` kan er niet bij. Dat de rest wél opkwam
was toeval: `8082` gebruikt de federatie niet meer, en de demo-postgres pakte `[::]:5432` naast de
federatie op IPv4.

Elke demo-service krijgt daarom een expliciet bind-adres op `127.0.0.1`:

| Service | Waar |
|---|---|
| wiremocks (`magazijn-a/-b`, stubs) | `--bind-address 127.0.0.1` |
| Quarkus-services | `QUARKUS_HTTP_HOST=127.0.0.1` (staat nu op `0.0.0.0`) |
| redis | `--bind 127.0.0.1` |
| postgres | `-c listen_addresses=127.0.0.1` |
| toxiproxy | listen-adres in `demo/generated/proxies.json` |

Dat lost niet alleen de botsing op. Zonder deze stap staat de demo-stack op álle interfaces van de
machine — geverifieerd: `172.20.0.2:6379` (Redis, zonder auth) en `:8082` waren van buiten loopback
bereikbaar. Op een ontwikkelmachine aan een kantoornetwerk is dat Redis zonder wachtwoord en
PostgreSQL met demo-credentials voor het hele subnet. De FSC-harnessen hebben hier een CI-guard
tegen (`.github/scripts/merge-guard.sh`); de demo-stack heeft er geen.

Merk op dat dit breder is dan hostnet: `compose.yaml` publiceert `"6379:6379"`, `"5432:5432"` enz.,
en dat is bij zowel Docker als podman `0.0.0.0`. Alleen `demo-console` staat op `127.0.0.1:8095`.
Het voorstel is dus om élke publicatie in `compose.yaml` op `127.0.0.1:` te zetten, en de
hostnet-overlay expliciet te laten binden.

**Guard erachter,** anders rot het terug: de bestaande `merge-guard.sh` accepteert al elk `127.x`
en toetst precies dit. Hij hoeft alleen op de demo-merge losgelaten te worden — een derde job in
`fsc-harness-overlays.yml`, of een eigen workflow als die naam te misleidend wordt.

### 2. De inway wijst naar het echte magazijn

`magazijn-a/deploy/local/publish-service.sh` registreert de dienst met een `endpoint_url`. Die
wijst nu naar `stub-upstream`; hij moet naar `berichtenmagazijn-a` wijzen — in de demo-stack onder
hostnet is dat `http://127.0.0.1:8090`.

Het adres komt uit `FSC_STUB_URL`, dat al overrulebaar is. De naam dekt de lading dan niet meer;
hernoemen naar `FSC_UPSTREAM_URL` met de oude als fallback, of accepteren dat de variabele breder is
dan zijn naam suggereert. Voorkeur: hernoemen, het is één plek per peer.

### 3. De uitvraag door de outway

Twee env-vars op `berichtenuitvraag` in de demo-stack:

- `MAGAZIJN_A_URL` → de outway van logius. Lokaal `127.20.1.5:8443`.
- `MAGAZIJN_A_GRANT_HASH` → het grant-hash uit de bootstrap van #200.

`MAGAZIJN_B_URL` blijft ongemoeid. Dat is opzet: één magazijn door FSC, één rechtstreeks, naast
elkaar in dezelfde demo. Dat toont het verschil en houdt een werkend vergelijkingspad over als de
FSC-keten stuk is.

**Toxiproxy blijft waar hij staat.** De uitvraag praat nu via `127.0.0.1:18090` naar toxiproxy, die
doorzet naar het magazijn; dat is de storing-knop van de demo. Voor magazijn-a wordt de *upstream*
van die proxy de outway, zodat de storing-knop blijft werken en de FSC-keten er achter zit.

**Het koppelpunt is het grant-hash.** Compose kan dat niet uit een draaiende manager halen, dus de
bootstrap van #200 schrijft het naar een gegenereerd env-bestand (`demo/generated/`, waar de
toxiproxy-definities ook al staan) dat de demo-stack inleest. Zonder dat bestand blijft de var leeg
en valt de uitvraag terug op rechtstreeks — dezelfde degradatie die de config al kent.

### 4. Bewijs

Eén smoke die de keten aantoont en kán falen:

| Assert | Wat het uitsluit |
|---|---|
| een bericht ophalen via `berichtenuitvraag` levert data die aantoonbaar uit de database van `berichtenmagazijn-a` komt | een 200 die net zo goed van de stub had kunnen komen |
| dezelfde `Fsc-Transaction-Id` in beide txlogs, uitgaand bij logius en inkomend bij magazijn-a | dat de call buiten FSC om ging |
| grant-hash leegmaken → de call komt niet door de outway | dat de FSC-headers decoratief zijn |
| magazijn-b blijft rechtstreeks werken | dat de omzetting het niet-FSC-pad sloopt |

## Verificatie

- Beide stacks tegelijk omhoog onder rootless podman, `smoke-federatie.sh` én de nieuwe ketensmoke
  groen.
- `merge-guard.sh` op de demo-merge: alle listeners op loopback.
- Negatieve controle: van buiten loopback is niets van de demo-stack meer bereikbaar.

## Scope-grens

Eén magazijn (magazijn-a) door FSC. Magazijn-b, de profiel-stub en de notificatie-stub blijven
rechtstreeks; die hebben eigen issues (#730, #784). De isolatie-kanttekening uit #197 blijft
onverkort gelden: onder FSC bestaat hier geen grens tussen de peers, dus hier valt geen
autorisatie-eigenschap mee te bewijzen.

## Open punten

- **Spreekt de outway plain HTTP of TLS op zijn listener?** Dat bepaalt of `MAGAZIJN_A_URL`
  `http://` of `https://` wordt, en of er een CA-bundel bij moet. Op ZAD staat er een https-ingress
  voor; lokaal is de listener kaal. Eén call tegen de draaiende outway beslist dit — eerste stap
  van de uitvoering.
- **Volgorde ten opzichte van #200.** Deze stap heeft het grant-hash uit die bootstrap nodig. Hij
  stapelt dus op #200, niet ernaast.
