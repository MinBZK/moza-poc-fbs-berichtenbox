# Lokale testopstellingen starten overal op dezelfde manier

**Status:** Uitgevoerd

Issue: MinBZK/MijnOverheidZakelijk#933

## Context

De demo-stack (#163) en de FSC-peer-harnessen (#178) zijn los van elkaar podman-geschikt gemaakt
en kwamen uit op dezelfde gelaagde opzet: basis → podman-overlay → hostnet-overlay. Beide PR's
staan sinds 13 augustus op `main`; de afhankelijkheid uit het issue is dus vervuld.

Stand op `main` (3f435914), per voorgestelde stap uit het issue:

| Stap | Demo-stack | FSC-peers (`logius`, `magazijn-a`) | Federatie |
|---|---|---|---|
| Starten | `demo/podman-up.sh`, kiest zelf bridge/hostnet | met de hand drie `-f`'s, bij élk vervolgcommando opnieuw | `federatie/federatie.sh up`, altijd hostnet (bewust: gedeelde netns op 127.20.x) |
| SELinux `:z` | op elke bind-mount in `compose.podman.yaml` | ontbreekt — `${PKI_DIR}:/pki`, `haproxy*.cfg`, `postgres-init.sql` | erft van de peers |
| CI-guard | sinds #239 meegenomen (`demo-stack-op-loopback` in `fsc-harness-overlays.yml`) | `harness-overlays`-job | via de peers |
| Naamgeving | `compose.yaml`, `compose.podman*.yaml` | `docker-compose.yaml`, `docker-compose.podman*.yaml` | verwijst naar de peer-namen |

Stap 3 van het issue (guard over beide) is daarmee al grotendeels gebeurd, alleen via een aparte
job in plaats van één glob. Wat overblijft: `:z`, de modus-detectie voor de peers, de naamgeving,
en de documentatie gelijktrekken.

## Ontwerpkeuzes

1. **Eén naamconventie: de peers gaan naar `compose.yaml` / `compose.podman.yaml` /
   `compose.podman-hostnet.yaml`.** Dat is de naam die de Compose-spec als voorkeur noemt en die
   de repo-root al draagt. Omgekeerd (root naar `docker-compose.*`) raakt `compose.yaml`, dat
   `proeftuin-image.sh`, de deploy en het ZAD-creatiescript lezen. Kosten: `git mv` van zes
   bestanden en ~160 verwijzingen in scripts, READMEs, de federatie-compose en de workflow.
   Afgesloten plannen onder `docs/plans/` laten we staan — die beschrijven de stand van toen.

2. **Detectie als gedeelde, gesourcete bibliotheek: `demo/lib/podman-modus.sh`.** Uit
   `podman-up.sh` gaan daarheen: socket bepalen/starten, `PODMAN_RUNTIME`, compose-implementatie
   kiezen, `kan_bridge` en de `!reset`-render-controle (gerenderde merge, geen `published:` naast
   `network_mode: host`). Zelfde patroon als `pin-pr-lib.sh`: het script dat sourcet houdt de
   eigen keuzes (welke bestanden, welke services, welke probes). Kopiëren per bundel is precies
   het uit-elkaar-groeien waar het issue over gaat.

3. **Eén startscript per peer: `deploy/local/up.sh` met `up | down | ps | compose <args…>`.**
   Het script bepaalt de modus één keer, legt hem vast in `deploy/local/.modus` (gitignored), en
   elk vervolgcommando leest die terug. Daarmee vervalt de README-waarschuwing dat élk
   compose-commando alle `-f`'s nodig heeft: wie via het script gaat, kan er niet één vergeten.
   `MODUS=docker|bridge|hostnet` forceert, zoals bij de demo-stack; `docker` bestaat alleen voor
   de peers, die Docker-first zijn. Werkt de geforceerde of gedetecteerde
   modus niet (render faalt, `ip_unprivileged_port_start` > 443 in hostnet, socket weg), dan stopt
   het met de oorzaak — geen terugval.
   De demo-stack krijgt dezelfde vier subcommando's in `podman-up.sh` (nu kent die alleen `up`;
   `down` moet de gebruiker met de hand samenstellen).

4. **Wachten: de peers gebruiken `smoke-services.sh` als readiness-check, geen nieuwe wachtlussen.**
   Die probeert al elk van de veertien services en noemt welke niet gezond is. `up.sh` draait hem
   in een begrensde lus (`WACHTTIJD`, default 300 s: de FSC-managers en hun migraties hebben meer
   tijd nodig dan de 180 s van `wacht_op` in de demo-stack). De begrensde
   `restart: on-failure:600` blijft; die vangt de volgordeproblemen, de smoke bewijst het resultaat.

5. **`:z`, niet `:Z`.** De federatie mount dezelfde group-CA in meerdere peers; een privé-label
   (`:Z`) zou de tweede peer de toegang ontnemen. `:z` komt in de podman-overlay, op elke bind-mount;
   compose voegt volumes samen op doelpad, dus dezelfde mount mét label vervangt die uit de basis
   (zoals `compose.podman.yaml` in de root al doet). Onder Docker en zonder
   SELinux is het een no-op.

6. **De federatie blijft hostnet-only.** Haar netwerkontwerp (eigen loopback-adres per peer) bestaat
   alleen in een gedeelde netns. `federatie.sh` gebruikt de nieuwe bestandsnamen en weigert
   expliciet een andere `MODUS` dan `hostnet`.

## Stappen

1. `git mv` van de zes peer-compose-bestanden; alle verwijzingen bijwerken (grep op
   `docker-compose\(\.podman\(-hostnet\)\?\)\?\.yaml` moet buiten `docs/plans/` leeg zijn).
2. `:z` op elke bind-mount in beide `compose.podman.yaml`'s van de peers.
3. `demo/lib/podman-modus.sh` uit `podman-up.sh` lichten; `podman-up.sh` sourcet hem en krijgt
   `up | down | ps | compose`. Gedrag van `up` ongewijzigd.
4. `deploy/local/up.sh` per peer (dun: bestanden + `smoke-services.sh` als readiness). Gedeelde
   peer-logica in `demo/environment/lib/fsc-harness.sh`, niet twee keer.
5. Bash-tests: `demo/lib/test-podman-modus.sh` (modus-keuze met gestubde `podman`/`uname`,
   geforceerde modus die niet rendert → exit ≠ 0 met melding, `.modus` lezen/ontbreken).
   `harness-scripts` in `fsc-harness-overlays.yml` neemt `demo/lib/` mee in shellcheck en tests.
6. Guard: de `harness-overlays`-job globt na de rename op `*/deploy/local/compose.podman-hostnet.yaml`
   en controleert daarnaast dat elke bind-mount in een podman-overlay `:z` draagt — voor peers én
   demo-stack, zodat een nieuwe mount zonder label niet stil terugkomt.
7. Documentatie: beide peer-READMEs, `demo/environment/README.md`, `docs/demo-runbook.md` en
   `docs/ontwikkelen.md` beschrijven dezelfde werkwijze ("start via het script; `MODUS=` forceert;
   het script meldt waarom een modus niet kan") met dezelfde termen *bridge* en *hostnet*.

## Verificatie

- `shellcheck` (koalaman-container) en alle `test-*.sh` groen.
- In deze omgeving (podman-in-een-container): `deploy/local/up.sh up` per peer kiest hostnet,
  `smoke-services.sh` groen, `up.sh down` ruimt op; `MODUS=bridge` faalt met de netavark-oorzaak.
  `demo/podman-up.sh up` en `down` idem. `federatie.sh up` + `smoke-federatie.sh` groen.
- `docker compose … config` van elke overlay-combinatie rendert; de guard-job lokaal nagespeeld.
- **Niet verifieerbaar hier:** SELinux enforcing en het bridge-pad op een echte rootless host
  (buiten scope volgens het issue). De `:z`-guard borgt alleen dat het label er staat.

## Uitvoering — afwijkingen en bevindingen

- **Healthcheck-probe toegevoegd aan de modus-keuze.** In deze ontwikkelomgeving werkte de
  proef-bridge wél, maar voert podman zonder systemd geen enkele healthcheck uit. Elke container
  blijft dan op `starting`, en `up -d` wacht eindeloos op de eerste `service_healthy`. De keuze
  vereist nu voor `bridge` (op Linux) ook dat een proefcontainer met `--health-cmd true` binnen
  10 s `healthy` wordt; anders `hostnet`, waar de overlays de healthchecks weghalen. Een
  geforceerde `MODUS=bridge` zonder lopende healthchecks wordt geweigerd met die oorzaak.
- **De SELinux-controle is een eigen guard** (`.github/scripts/selinux-label-guard.sh`) en hoort
  niet bij `merge-guard.sh`. Die laatste toetst netns-eisen en draait alleen op hostnet-merges,
  terwijl het label ook in bridge nodig is. De nieuwe guard draait op de bridge-, hostnet- en
  federatie-merges van de peers en op beide podman-merges van de demo-stack. Hij vond meteen een
  echt gat: de later toegevoegde `demo-proxy` mountte zijn nginx-template zonder `:z`.
- **De peer-logica staat in `demo/environment/lib/peer-stack.sh`**, niet in `fsc-harness.sh`: het
  is een uitvoerbaar script met eigen argumenten, geen helper. `deploy/local/up.sh` per peer is
  een wrapper van drie regels.
- **`up.sh` weigert een project dat al containers heeft zonder vastgelegde modus.** Draait de
  federatie, dan zou `up` de peer zonder haar vierde laag hercreëren.
- **`docs/ontwikkelen.md` bleef ongewijzigd**: het noemt de lokale testopstellingen niet. Wel
  bijgewerkt: de README van `demo/`, de skill `demo-starten` en de tabel in `CLAUDE.md`.

### Verificatie (2026-10-16, podman-in-een-container, arm64)

- Shellcheck over alle harness-globs plus de nieuwe scripts, actionlint op de twee gewijzigde
  workflows, alle `test-*.sh` onder `.github/scripts/`, `demo/environment/lib/` en `demo/lib/`:
  groen. De drie overlay-jobs lokaal nagespeeld: groen.
- `magazijn-a` en `logius` via `deploy/local/up.sh`: automatisch `hostnet` (met de
  healthcheck-oorzaak), `smoke-services.sh` groen, `run-smokes.sh` groen, `down -v` ruimt op en
  wist `.modus`. Geweigerd met de oorzaak: `MODUS=bridge` (healthchecks), `MODUS=docker` (podman-
  socket), een onbekende modus, `ps` met een afwijkende `MODUS`, en een tweede `up` naast een
  draaiend project.
- Federatie: `deel-groep-ca.sh` → `federatie.sh up` → `smoke-federatie.sh` groen; tijdens de
  federatie weigert een losse `up.sh`; `MODUS=bridge federatie.sh` weigert.
- Demo-stack: `demo/podman-up.sh` start volledig in `hostnet`, `ps` en `down -v` via het
  vastgelegde `.modus`. `demo/smoke.sh` faalde op stap 2 (403, geen profiel). Dat komt niet door
  deze wijziging: het lokale `fbs-berichtenmagazijn:demo`-image dateert van 4 september, van vóór
  #304, en vraagt het profiel nog op via `GET …/BSN/<nr>` in plaats van `POST …/partij`.
  Met alle zes demo-images opnieuw gebouwd vanaf deze branch is `demo/smoke.sh` groen (5/5,
  fan-out 3/15/45/100 organisaties).
- Niet verifieerbaar hier, zoals verwacht: SELinux enforcing en het bridge-pad op een echte
  rootless-host.

### Review (ci-workflow-reviewer)

Geen hoge bevindingen. Verwerkt: buiten Linux gebruikt de modus-keuze de socket van podman machine
in plaats van er zelf een te starten (anders vielen de peers op macOS om, die eerst een kale
`docker compose` gebruikten); `source-path=SCRIPTDIR`, zodat shellcheck de bibliotheek echt volgt;
de suite onder `demo/lib/` wordt in CI op naam en aantal (≥ 42) geëist; de label-suite noemt een
render-fout bij naam; `demo/lib/` valt buiten de uitrolpoort (`wijzigingsfilter.sh`, met een
fixture erbij); de demo-stack-stappen draaien ook na een eerdere fout in hun job.
