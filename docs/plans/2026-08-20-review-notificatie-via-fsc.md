# Reviewbevindingen op de notificatie-push door FSC verwerken

**Status:** Uitgevoerd

## Context

PR #211 (`feature/784-notificatie-via-fsc`) laat het berichtenmagazijn zijn CloudEvents
afleveren door de eigen FSC-outway. Op de PR staan drie reviewcomments: een blokkerende
bevinding op de smoke, een ZAD-toetsbaarheidsverslag met één tegenstrijdige runbook-regel,
en een reviewrapport met drie hoge, elf medium en negen lage punten.

Dit plan verwerkt de blokkerende bevinding, de drie hoge punten, de medium punten, en de
lage punten die één regel kosten. De ZAD-runbookpunten uit het tweede comment (en L4/L5)
raken documentatie die op `feature/outway-tls-hardening` al in beweging is; die blijven
hier buiten beschouwing op het ene punt na dat aantoonbaar tegenstrijdig is.

## Kern van het probleem

De SSRF-blocklist wordt overgeslagen op basis van *"er staat een grant-hash"*, terwijl de
rechtvaardiging is *"de bestemming komt uit het FSC-contract, niet uit onze URL"*. Die twee
vallen alleen samen als de URL daadwerkelijk de eigen outway aanwijst, en niets dwingt dat
af. Eén env-var opent daarmee de proxy-primitive die het comment bij `blokkeerIntern` zelf
als dreiging benoemt.

De rest van de bevindingen hangt daaraan vast: de compenserende alert-regel beweert iets dat
hij niet kan weten (H2), de tests kunnen "kiest de juiste downstream" niet onderscheiden van
"pakt de enige" (H3), en de operator-handleiding beschrijft een blocklist die niet meer
onvoorwaardelijk geldt (M4).

## Stappen

### 1. Blokkerend: het aanleverpad in de smokes

`smoke-notificatie.sh:214` en `smoke-keten.sh:86` doen `POST .../berichten`; sinds #203 is
aanleveren `POST /api/v1/aanleveringen` en antwoordt het magazijn `405`. De keten-smoke is
daarmee al rood op `main`; beide worden hier rechtgezet.

### 2. H1 — de uitzondering aan de outway-host binden

- Nieuwe sleutel `magazijn.publicatie.outway.host` in `PublicatieConfig`.
- `valideerUrl` krijgt de outway-host mee in plaats van een kale `viaOutway`-vlag. De
  blocklist-uitzondering geldt alleen bij een exacte host-match (lowercase).
- Grant-hash gezet, maar host matcht niet of `outway.host` ontbreekt ⇒ `ConfiguratieFout`
  met de werkelijke reden (non-herstelbaar, dus terminal — geen retry-lus op een
  configuratiefout).
- `%dev` blijft ongewijzigd: dat profiel valt al vóór deze check terug.

Gedragswijziging op ZAD is nihil zolang `NOTIFICATIE_GRANT_HASH` daar leeg is (bevestigd in
het tweede reviewcomment).

### 3. H2 — de alert-regel waarmaken

- De boot-logging verhuist uit het `init`-blok naar een `@Observes StartupEvent`-methode,
  net als `PublicatieConfigValidator` en `PublicatieOutbox` dat doen. Daarmee klopt "bij
  boot" en verdwijnt het verschil tussen de drie plekken die er iets anders over beweren.
- De WARN-regel noemt naast de key ook de outway-host: dat is het onderscheid waarvoor de
  blocklist vervalt, en het is geen persoonsgegeven.
- `DOWNSTREAM_VIA_OUTWAY` krijgt een test die het token pint, zoals `REDIS_UNPROTECTED` dat
  heeft.

### 4. H3 — cardinaliteit en configbinding

- De FSC-tests krijgen een `downstreams`-map met méér dan één entry, waarbij het aangeroepen
  doel een andere hash draagt dan zijn buurman. Een `values.first()`-refactor valt daar dan
  door. Leeg/één/meerdere via `@ParameterizedTest`.
- Een test in de bestaande `SmallRyeConfigBuilder`-harnas pint dat de sleutel `grant-hash`
  aan `grantHash()` bindt. Nodig omdat de zusterconfig in `fbs-magazijnregister` juist
  `@WithName("grantHash")` gebruikt: twee conventies naast elkaar.
- L7 valt hiermee samen: de `DOWNSTREAM_VIA_OUTWAY`-tak draait nu in geen enkele test.

### 5. M1 + M2 — onbruikbare hash niet stil, en niet fataal voor de outbox

`header(...)` staat buiten het `try`, dus een hash met een control-teken of een teken boven
U+00FF gooit `IllegalArgumentException` langs `lever()` heen. `pogingen` wordt dan niet
opgehoogd en de claim struikelt elke ronde opnieuw: één config-waarde legt de outbox stil.

- Vormcontrole vóór gebruik: printable US-ASCII zonder witruimte binnenin. Dat is precies
  wat `HttpRequest.Builder.header` accepteert, en het pint geen hash-formaat vast dat later
  kan wijzigen.
- Afwezig of leeg blijft "geen outway, rechtstreeks verkeer" — dat is gedocumenteerd gedrag
  van de env-var. Whitespace-only is dat niet: dat is een typfout en wordt een
  `ConfiguratieFout` in plaats van stille terugval.

### 6. M3 — de smoke-assert een venster geven waarin hij iets kan zien

Assert 3 wacht `PUBLICATIE_INTERVAL` (5 s) op een tweede aflevering, terwijl een retry alleen
door de outbox-poller kan komen en die op 60 s staat. De assert is structureel groen. Wordt
op de bron geassert (de outbox-rij) of krijgt een venster ≥ interval + backoff; de keuze valt
tijdens uitvoering, met de txlog-assert als voorbeeld.

### 7. M4 — operator-handleiding gelijktrekken

Punt 3 ("hosts die naar interne ranges resolven worden geweigerd") wordt conditioneel, de
tabel krijgt `magazijn.publicatie.downstreams.<key>.grant-hash` en
`magazijn.publicatie.outway.host`, en `DOWNSTREAM_VIA_OUTWAY` komt bij de alert-tokens.

### 8. M5 + M6 — diagnose bij een mislukte aflevering

Een begrensd, gesaneerd fragment van de responsebody in `reden` (die body wordt nu opgehaald
en weggegooid, terwijl `UNKNOWN_GRANT_HASH_IN_HEADER` daar juist in staat), en de
transaction-id mee in het faalpad in plaats van alleen op het succespad bij DEBUG.

### 9. M7 — profielen in `ApplicationPropertiesTest`

`%prod` — het profiel dat op ZAD draait — is ongedekt terwijl de KDoc van die testklasse
precies dat scenario beschrijft. `%staging`/`%acceptatie` hebben geen FSC-config; die
terugval wordt vastgelegd of gedicht.

### 10. M8 — de pagineerlus dekken

De verwijderde jq-guard-testvector krijgt een vervanger in `test-fsc-contract.sh`: één pagina
mét cursor gevolgd door een lege, meerdere samengevoegde pagina's, nul rijen, en de rem op
100 rondes.

### 11. M9 — de demo-default geen slecht voorbeeld laten zijn

`compose.podman-hostnet.yaml` hangt `fsc-grants.env` onvoorwaardelijk aan
`berichtenmagazijn-a`, terwijl `NOTIFICATIE_URL` naar toxiproxy default. Grant-hash en URL
worden samen gezet, conform wat `federatie/README.md` zelf voorschrijft.

### 12. M10 + M11 — motivering naar de juiste bron

`Fsc-Grant-Hash` en de UUID-v7-eis zijn OpenFSC-implementatie-eisen, geen fsc-core: de KDoc
attribueert dat al correct maar noemt de consequentie niet (een spec-conforme outway routeert
op het pad). De HTTP/1.1-pin verwijst naar `PROTOCOL_TCP_HTTP_1.1`, dat de upstream áchter de
inway beschrijft; de werkelijke oorzaak — hop-by-hop-headers die ongewijzigd doorgeproxyd
worden naar een Go-http2-transport dat ze weigert — staat er al en wordt de motivering.

### 13. Lage punten die één regel kosten

- **L1** `.pollDelay(Duration.ZERO)` op de Awaitility-aanroepen die niets af te wachten
  hebben (~0,6 s idle per testrun).
- **L2** de gegenereerde test-BSN op `9` pinnen (RvIG-testbereik) en via `--data @-` in
  plaats van de commandoregel, zodat hij niet in `ps` staat.
- **L8** een test op de invariant "nooit de URL loggen", symmetrisch aan wat
  `FscOutwayHeadersTest` al vastlegt.

## Buiten scope

- De runbookpunten uit het tweede reviewcomment en L4/L5 (ZAD-topologie, outway-publicatie):
  die documentatie wordt op `feature/outway-tls-hardening` al herzien. Uitzondering: de
  aantoonbaar tegenstrijdige regel in `magazijn-a/deploy/zad/verify-zad.md` over "geen
  ingress-route nodig" wordt hier rechtgezet.
- **L3** (één lege pagina extra per contract) en **L6** (end-to-end-versleuteling in het NL
  GOV CloudEvents-profiel) — beide vragen een afweging die buiten deze reviewronde valt.
- **L9** (twee tests negeren het `lever`-resultaat) — meelopen met stap 4 als het uitkomt.

## Verificatie

Gedraaid en groen:

- `./mvnw clean verify -pl services/berichtenmagazijn -am` — 437 tests (was 412), detekt 0
  bevindingen, JaCoCo-gate gehaald.
- `./mvnw clean verify -pl libraries/fbs-common -am` — 367 tests, detekt 0.
- `demo/environment/lib/test-fsc-contract.sh` — alle fixture-tests, inclusief de zes nieuwe
  vectoren op `fsc_contracten_paginas`.
- `shellcheck -x -S warning` op de gewijzigde scripts en `fsc-harness.sh` — schoon.
- `docker compose config` op de root-merge: `berichtenmagazijn-a` krijgt lege
  `NOTIFICATIE_GRANT_HASH` en `OUTWAY_HOST`, en geen `env_file` meer.

Niet gedraaid: de federatie-smokes zelf. Die vragen een draaiende peer-stack met uitgegeven
PKI, wat in deze omgeving niet beschikbaar is. De gewijzigde regels zijn statisch nagelopen
(syntax, shellcheck) maar het aanleverpad is hier niet end-to-end bewezen.

## Twee bevindingen die anders lagen dan gemeld

**M1 gedeeltelijk.** Afwezig of leeg blijft stille terugval op rechtstreeks verkeer: dat is het
gedocumenteerde gedrag van een niet-gezette `*_GRANT_HASH` en de manier waarop previews en de
demo-stack draaien. Alleen witruimte is nu een fout — dat is een typfout en nooit een keuze.

**M7, tweede helft.** `%staging`/`%acceptatie` bestaan in `application.properties` alleen voor
een retry-jitter, niet als deployment-profiel: er zijn daar helemaal geen
`downstreams.*.url`-sleutels. Draait het magazijn ooit onder zo'n profiel, dan faalt het bij boot
op `PublicatieOutbox.valideerStartConfiguratie` ("geen downstreams geconfigureerd") — fail-fast,
geen stille terugval. Alleen de `%prod`-dekking is toegevoegd.
