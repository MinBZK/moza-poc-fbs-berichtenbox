# Lokale testfederatie start in één keer

**Status:** Concept

Issue: MinBZK/MijnOverheidZakelijk#975. Bouwt voort op PR #386 (#933, `chore/933-testopstellingen-gelijk-starten`):
die levert `demo/lib/podman-modus.sh` (engine- en socketkeuze) en `demo/environment/lib/peer-stack.sh`
(de losse peers), en hernoemt de peer-compose-bestanden naar `compose*.yaml`.

## Context

`federatie.sh up` slaagt in een ontwikkelcontainer (rootless podman, `vfs`, Linux) vaak pas na
meerdere pogingen. De retry-lus (`UP_POGINGEN=3`) maskeert dat; de foutmeldingen staan wel in beeld.
Daarnaast blokkeert een verweesde `pause.pid` het opstarten volledig, en valt de melding over een
onbereikbare podman-API weg achter de compose-fout.

### Meting (2026-10-20, ontwikkelcontainer op Rancher Desktop, arm64, podman 5.4.2, crun 1.21, vfs)

`UP_POGINGEN=1`, telkens na `down -v`:

| Situatie | Runs | Mislukt | Duur |
|---|---|---|---|
| warm (images en ID-mapped lagen aanwezig), parallel | 3 | 0 | ~15 s |
| koud (images verwijderd), parallel | 6 | 4 — allemaal `container ID 0 cannot be mapped to a host ID` | 18–38 s |
| koud, `COMPOSE_PARALLEL_LIMIT=1` | 5 | 0 | ~56 s (eerste 157 s) |

De tweede fout uit het issue (`write file '/proc/<pid>/uid_map': Operation not permitted`) kwam in
deze tien koude runs niet voor.

### Oorzaak

De podman-overlays zetten elke FSC-service op `userns_mode: keep-id`. Met de `vfs`-driver kan podman
een laag niet ID-mapped mounten en maakt het bij de eerste container per laag een ID-mapped kopie.
Compose maakt de containers parallel aan; containers met een gedeelde basislaag maken die kopie dan
tegelijk aan, en de verliezer faalt. Is de kopie er eenmaal, dan gaat het goed — vandaar dat de
tweede of derde poging slaagt en een warme start nooit faalt.

## Ontwerpkeuzes

1. **Images parallel ophalen, containers serieel aanmaken.** `compose pull --policy missing` en
   daarna `compose --parallel 1 up -d`. Het ophalen raakt geen ID-mapping en hoeft dus niet serieel;
   `--policy missing` houdt een warme start offline en snel. Alleen op podman: Docker kent de race
   niet en houdt zijn parallelle start. Geldt voor `federatie.sh` én `peer-stack.sh`, want de losse
   peers dragen dezelfde `keep-id`-overlays.
   - *Verworpen: een voorverwarmstap* (`podman create --userns=keep-id` per image). Sneller, maar
     de mapping moet exact gelijk zijn aan wat compose aanvraagt; wijkt die af, dan is de warming
     zwijgend nutteloos.
   - *Verworpen: alleen de retry verbeteren.* Lost het acceptatiecriterium "in één poging, zonder
     tussentijdse foutmeldingen" niet op.
2. **Verweesde `pause.pid` opruimen** in `podman-modus.sh`, vóór de eerste podman-aanroep. Verweesd =
   het bestand bestaat en het proces erin bestaat niet, of heet niet `podman pause` (een
   hergebruikte pid wijst naar een vreemde user namespace). Dan het bestand verwijderen en melden.
   `federatie.sh` gaat `podman-modus.sh` sourcen en gebruikt `modus_engine`, zodat hij dezelfde
   socketafhandeling krijgt als de losse peers en de demo-stack.
3. **De socket echt bevragen** in `modus_engine` (`/_ping`) in plaats van alleen te kijken of het
   bestand bestaat. Een socketbestand van een gestopte service blijft staan (na een herstart van de
   container); dan de service opnieuw starten. Wijst `DOCKER_HOST` expliciet naar een socket die niet
   antwoordt, dan stoppen met het startcommando, vóór de eerste compose-aanroep.
4. **De retry blijft als vangnet**, met een melding die zegt dat een herhaling niet hoort voor te
   komen en de fout noemt. README: de valkuil herschrijven naar de oorzaak, de maatregel, en de
   resterende bekende fout (`uid_map`) met zijn melding.

## Stappen

1. `podman-modus.sh`: `modus_pause_pid_verweesd <bestand>` + opruimen in `modus_engine`;
   `modus_socket_antwoordt <sock>`; `modus_compose_up <compose-commando…>` (pull + serieel op podman).
2. `federatie.sh`: `podman-modus.sh` sourcen, `modus_engine` vóór `up`/`down`/…, `dc … up` via de
   nieuwe helper; retry-melding aanscherpen.
3. `peer-stack.sh`: `up -d` via dezelfde helper.
4. Tests in `demo/lib/test-podman-modus.sh`: verweesde/levende/hergebruikte pid, ontbrekend bestand;
   socket die wel/niet antwoordt; de helper roept op podman pull + `--parallel 1` aan en op docker
   alleen `up -d` (stub-compose).
5. README federatie + peer-READMEs bijwerken.

## Verificatie

- `demo/lib/test-podman-modus.sh`, `test-federatie-helpers.sh`, shellcheck.
- Vijf koude en drie warme runs `UP_POGINGEN=1 federatie.sh up` → alle in één poging, geen
  foutmeldingen; duur noteren. `smoke-federatie.sh` groen.
- Verweesde `pause.pid` nagespeeld (pid van een beëindigd proces) → `federatie.sh up` ruimt op en
  start. Gestopte API-service nagespeeld → melding vóór compose.
- Een losse peer via `up.sh` koud → in één keer gezond.
