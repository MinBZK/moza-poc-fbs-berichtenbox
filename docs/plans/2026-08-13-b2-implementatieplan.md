# B2 implementatieplan: de FBS-keten lokaal door FSC

**Status:** Uitgevoerd

**Doel:** `berichtenuitvraag` haalt bij magazijn-a op via outway → router → inway →
`berichtenmagazijn-a`, lokaal, met bewijs dat de data uit dat magazijn komt en dat de transactie in
beide txlogs staat.

**Spec:** `docs/plans/2026-08-13-b2-lokale-fsc-keten-uitvraag-magazijn.md`

**Aanpak:** Bedrading, geen applicatiecode. De FSC-laag komt uit #197 (adres per component) en #200
(contract + grant-hash); de FBS-laag uit `compose.yaml`. Wat ontbreekt is dat ze op één machine
naast elkaar kunnen draaien en dat de drie koppelpunten (inway-upstream, outway-URL, grant-hash)
gezet worden.

**Stack:** bash, docker/podman compose, OpenFSC v2.5.2, Quarkus.

## Globale randvoorwaarden

- Federatie op `127.20.0.0/16`, één adres per component, standaardpoorten. Demo-stack op
  `127.0.0.1`.
- De outway spreekt **plain HTTP** op zijn listener — vastgesteld uit `smoke-contract.sh` van #200
  (`OUTWAY="http://127.0.0.1:$((CONS_BLOK + 40))"`). Lokaal wordt dat `http://127.20.1.5:8443`.
- Elke shell-wijziging blijft door `shellcheck -x -S warning`.
- Geen enkele listener buiten loopback, in beide stacks.

## Volgorde en afhankelijkheden

Taak 1 is een enabler op de branch van #200; taak 2 t/m 5 staan op `feature/b2-lokale-fsc-keten`,
die daarna op #200 gerebased wordt.

---

### Taak 1: #200 naar het adresmodel

**Waarom:** #200 staat op de oude #197 en rekent op tien plekken met `BLOK`. Zonder deze stap kan
B2 niet draaien, want de contract-bootstrap levert het grant-hash.

**Bestanden:**
- Rebase: `feature/782-fsc-contract-magazijn-uitvraag` op `feature/fsc-lokale-federatie-harness`
- Wijzig: `demo/environment/federatie/contracts/fbs-contracten.sh` (regels 31–72)
- Wijzig: `demo/environment/federatie/smoke-contract.sh` (regels 41–186)

**Stappen:**

- [ ] 1.1 Rebase de #200-branch op de nieuwe #197-kop. Conflicten in `peers.env`, de twee
      `compose/*.yaml`, `haproxy.federatie.cfg`, `smoke-federatie.sh`, `merge-guard.sh` en
      `fsc-harness-overlays.yml` lost je op ten gunste van **onze** kant (het adresmodel); de
      bestanden die alleen #200 toevoegt (`contracts/*`, `smoke-contract.sh`) neem je ongewijzigd
      over.
- [ ] 1.2 In `fbs-contracten.sh`: `fsc_peer_waarde BLOK` → `fsc_peer_waarde NET`, en de
      manager-URL's `…:$((BLOK + 1))` → `…:9443`. De variabelen heten dan `CONS_NET`/`PROV_NET` en
      worden alleen nog gebruikt voor de foutmelding bij een ontbrekende peer.
- [ ] 1.3 In `smoke-contract.sh`: `OUTWAY="http://127.0.0.1:$((CONS_BLOK + 40))"` →
      `OUTWAY="http://$(fsc_component_adres "$CONS_NET" outway):8443"`. De helper `manager_json`
      krijgt het net in plaats van het blok: `poort=9443`, en
      `--resolve "${naam}:9443:$(fsc_component_adres "$net" manager)"`.
- [ ] 1.4 De `--resolve "${INWAY_NAAM}:443:127.0.0.1"` op regel 137 → `${ADRES_ROUTER}`.
- [ ] 1.5 Verifiëren: `shellcheck -x -S warning` schoon; `federatie.sh up`;
      `contracts/fbs-contracten.sh`; `smoke-contract.sh` groen; `smoke-federatie.sh` groen.
- [ ] 1.6 Committen en pushen naar de #200-branch.

---

### Taak 2: loopback-discipline voor de demo-stack

**Waarom:** de blokkade. De demo-stack bindt wildcard; dat botst met élke specifieke bind van de
federatie op dezelfde poort (`Failed to bind to /0.0.0.0:8081`). En het zet Redis en PostgreSQL op
alle interfaces van de machine.

**Bestanden:**
- Wijzig: `compose.yaml` — elke `ports:`-publicatie krijgt het `127.0.0.1:`-voorvoegsel
- Wijzig: `compose.podman-hostnet.yaml` — expliciet bind-adres per service
- Wijzig: `.github/workflows/fsc-harness-overlays.yml` — derde job op de demo-merge

**Stappen:**

- [ ] 2.1 In `compose.yaml` elke publicatie van `"<poort>:<poort>"` naar
      `"127.0.0.1:<poort>:<poort>"`. Dat zijn: redis, magazijn-a, magazijn-b, profiel-service,
      aanmeld-stub, notificatie-stub, postgres-uitvraag, postgres-a, postgres-b,
      berichtenmagazijn-a, berichtenmagazijn-b, toxiproxy, magazijn-stubs, berichtenuitvraag.
      `demo-console` staat al goed.
- [ ] 2.2 In `compose.podman-hostnet.yaml` per service een expliciet bind-adres:
      - wiremocks (`magazijn-a`, `magazijn-b`, `profiel-service`, `aanmeld-stub`,
        `notificatie-stub`, `magazijn-stubs`): `--bind-address 127.0.0.1` aan `command` toevoegen;
      - Quarkus (`berichtenmagazijn-a/-b`, `berichtenuitvraag`, `demo-console`):
        `QUARKUS_HTTP_HOST: 127.0.0.1`;
      - `redis`: `command: ["redis-server", "--bind", "127.0.0.1", "--port", "<poort>"]`;
      - `postgres-a/-b/-uitvraag`: `-c listen_addresses=127.0.0.1` aan `command`;
      - `toxiproxy`: luisteradres in `demo/generated/proxies.json` (gegenereerd door
        `demo/podman-prepare.sh`) op `127.0.0.1`.
- [ ] 2.3 Verifiëren dat de merge schoon is:
      `docker compose -f compose.yaml -f compose.podman.yaml -f compose.podman-hostnet.yaml config
      --format json | .github/scripts/merge-guard.sh "demo (hostnet-merge)"`. De guard eist
      `network_mode: host` op élke service; klopt dat niet voor de demo-stack, dan draai je alleen
      de listener-checks — zie stap 2.4.
- [ ] 2.4 Als `merge-guard.sh` niet één-op-één past op de demo-merge (andere eisen: geen `/pki`,
      geen `!reset`-verplichting), splits dan de listener-checks (`env_lek`, `bind_lek`, `pg_lek`,
      `pg_mist`) af naar een aanroepbare modus `--alleen-listeners`, en gebruik die voor de
      demo-merge. Niet dupliceren: één script blijft de bron.
- [ ] 2.5 Job toevoegen aan `.github/workflows/fsc-harness-overlays.yml` die die controle op de
      demo-merge draait. Pad-filter uitbreiden met `compose*.yaml`.
- [ ] 2.6 Runtime-bewijs: demo-stack omhoog, dan van een niet-loopback-adres van de host
      controleren dat niets meer bereikbaar is (vóór deze taak waren `:6379` en `:8082` dat wél).
- [ ] 2.7 Committen.

---

### Taak 3: de inway wijst naar het echte magazijn

**Bestanden:**
- Wijzig: `demo/environment/magazijn-a/deploy/local/publish-service.sh` (regel ~22)

**Stappen:**

- [ ] 3.1 `STUB_URL="${FSC_STUB_URL:-http://stub-upstream:8080}"` hernoemen naar
      `UPSTREAM_URL="${FSC_UPSTREAM_URL:-${FSC_STUB_URL:-http://stub-upstream:8080}}"`. De oude
      variabele blijft als fallback werken, zodat `smoke-federatie.sh` (die hem zet) niet stilvalt.
- [ ] 3.2 Dezelfde hernoeming in `logius/deploy/local/publish-service.sh`, zodat de twee peers niet
      uit elkaar lopen.
- [ ] 3.3 `shellcheck` schoon; `smoke-federatie.sh` nog steeds groen (die gebruikt de oude naam).
- [ ] 3.4 Committen.

---

### Taak 4: de uitvraag door de outway

**Bestanden:**
- Wijzig: `demo/environment/federatie/contracts/fbs-contracten.sh` — grant-hash wegschrijven
- Wijzig: `compose.podman-hostnet.yaml` — `MAGAZIJN_A_URL` + `MAGAZIJN_A_GRANT_HASH`
- Wijzig: `demo/generated/`-generatie in `demo/podman-prepare.sh`

**Stappen:**

- [ ] 4.1 `fbs-contracten.sh` schrijft per magazijn een regel naar
      `demo/generated/fsc-grants.env`: `MAGAZIJN_A_GRANT_HASH=<hash>`. Bestaand bestand wordt
      overschreven, niet aangevuld — anders stapelen hashes uit eerdere runs zich op.
- [ ] 4.2 `compose.podman-hostnet.yaml`: `berichtenuitvraag` krijgt
      `env_file: [demo/generated/fsc-grants.env]` en `MAGAZIJN_A_URL: http://127.20.1.5:8443`.
      Ontbreekt het bestand, dan moet compose niet fataal falen: `required: false` op de `env_file`.
- [ ] 4.3 Toxiproxy-upstream voor magazijn-a naar de outway laten wijzen in
      `demo/generated/proxies.json`, zodat de storing-knop blijft werken en de FSC-keten erachter
      zit.
- [ ] 4.4 Verifiëren: beide stacks omhoog, `curl` op de uitvraag levert berichten van magazijn-a.
- [ ] 4.5 Committen.

---

### Taak 5: ketensmoke

**Bestanden:**
- Maak: `demo/environment/federatie/smoke-keten.sh`

**Stappen:**

- [ ] 5.1 Assert 1 — ophalen levert data uit de database van magazijn-a: een bericht via `psql` in
      `postgres-a` zetten met een herkenbare inhoud, dat ophalen via de uitvraag, en op die inhoud
      matchen. Een 200 van de stub kan die inhoud niet hebben.
- [ ] 5.2 Assert 2 — dezelfde `Fsc-Transaction-Id` in beide txlogs: uitgaand bij logius, inkomend
      bij magazijn-a, via de txlog-API op `9443` per peer.
- [ ] 5.3 Assert 3 — grant-hash leegmaken en herstarten laat de call niet meer door de outway
      (verwacht `400 UNKNOWN_GRANT_HASH_IN_HEADER` of een lege grant → geen FSC-headers → de outway
      weigert).
- [ ] 5.4 Assert 4 — magazijn-b blijft rechtstreeks werken.
- [ ] 5.5 `shellcheck` schoon, smoke groen, opnemen in de `harness-scripts`-job.
- [ ] 5.6 Committen en de branch pushen.

---

## Verificatie van het geheel

1. `federatie.sh up` + `smoke-federatie.sh` groen.
2. `contracts/fbs-contracten.sh` + `smoke-contract.sh` groen.
3. Demo-stack omhoog naast de federatie, `smoke-keten.sh` groen.
4. `merge-guard.sh` groen op alle merges: twee standalone, twee federatie, één demo.
5. `shellcheck` + de bash-unittests groen.
6. Van buiten loopback is niets van beide stacks bereikbaar.

## Reviewrondes

Na de implementatie: reviewrondes tot er geen hoge bevindingen meer zijn, met de
`pr-review-toolkit`-agents en `/code-review`. Hoge bevindingen worden direct opgelost, medium in
overleg, laag genoteerd.
