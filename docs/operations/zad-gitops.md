# ZAD deploy & GitOps (debug)

Debug-gids voor de ZAD-omgeving: waar de grond-waarheid staat, wat een component wel en niet kan,
en welke valkuilen een deploy-onderzoek op een dwaalspoor zetten. CLAUDE.md vat hiervan alleen de
kern samen en verwijst hierheen.

ZAD draait op **Argo CD GitOps**: de bron van waarheid is Git, niet de cluster of de
Operations-Manager-API. Argo-Applications hebben `selfHeal: true` + `prune: true`, dus
een directe `kubectl`- of live-OM-wijziging aan een draaiende deployment wordt
teruggedraaid naar wat in Git staat. Reactiveren/schalen moet dus via OM (dat commit
naar de Git-repo die Argo volgt), niet handmatig in de cluster of in de gerenderde repo.

**Projecten (project-id = OM-project; staat in de env van `.github/workflows/deploy.yml` én in de
matrix van `.github/workflows/cleanup-preview.yml` — wijzig een id op beide plekken, anders
ruimt de opruiming een ánder project op en verifieert ze daar: zolang dat project bestaat is de
run groen en blijft de preview staan):**
`berichtenuitvraag` = `mpfb-8wh` (`redis`, `uitvraag`, `toxiproxy-aanmeld`, `toxiproxy-redis`),
`magazijnen` = `mpfm-w3h` (`magazijna`, `magazijnb`, `democonsole`, `demopersonas`,
`magazijnsimulator`, `proeftuin`), `externe-stubs` = `mpfpsm-lcl` (`profiel`, `notificatie`,
`toxiproxy-profiel`, `toxiproxy-notificatie`).
Deployment-namen: `test` (baseline, push→main) en `pr-<n>` (previews, clone-from `test`).
Previews worden opgeruimd door `cleanup-preview.yml` bij het sluiten van de PR; een gemiste
opruiming haal je in met `gh workflow run cleanup-preview.yml -f pr=<n>`.

`democonsole` is het bedieningspaneel van de demo. Het staat in `mpfm-w3h` en niet in een eigen
deployment omdat `postgresql-database` deployment-gebonden is: alleen een component ín dezelfde
deployment als de magazijnen erft hun database-secret, en dat secret is wat de legen-knop mogelijk
maakt. De eenmalige creatie staat in `demo/environment/zad-demo/README.md`.

Vijf ZAD-eigenschappen die bepalen wat een component wél en niet kan, alle vijf geverifieerd in
`RijksICTGilde/RIG-Cluster`:

- De inhoud van een **attachment** wordt ongewijzigd gemount (geen `$DEPLOYMENT_NAME`-substitutie,
  anders dan bij aliassen).
- **`command`** staat niet in `AddComponentRequest`/`UpdateComponentRequest` en kent `zadctl` niet,
  dus een startcommando is UI-handwerk.
- Een **`cross-domain-access`-regel** noemt altijd één concrete peer-deployment — een regel waarvan
  die open blijft, wordt bij het genereren overgeslagen. Eén regel opent bovendien één poort op één
  component bij één peer (`cross_domain_access/merge.py`), dus elke hop is een eigen regel. Gevolg:
  cluster-intern verkeer naar een ánder project volgt geen preview, tenzij de regel per deployment
  wordt bijgeschreven
  (`PATCH /api/v2/projects/{p}/services/cross-domain-access/config/deployment/{d}/{inbound,outbound}`).
- Een component **draagt meer dan één poort** (`ports: [...]`), maar publiceert er één: elke poort
  ná de eerste wordt een extra Service-poort en de Ingress pakt alleen `ports[0]`
  (`service.yaml.jinja`, `project_manager.py`). Zo blijft een beheerpoort cluster-intern terwijl de
  eerste poort publiek gaat.
- Zonder de **`health-check`**-dienst probeert Kubernetes een TCP-socket op `ports[0]`, met
  `livenessProbe` op 30s × 3. Sluit de applicatie die poort bewust (een proxy die je uitzet), dan
  herstart de pod anderhalve minuut later. Richt de probe dan op een poort die altijd staat.

**Drie GitOps-lagen (allemaal `RijksICTGilde`-repos, `gh api` leest ze — deels private):**

| Repo / pad | Wat |
|------------|-----|
| `rig-cluster-projects` → `projects/<project-id>.yaml` | OM-projectspec: componenten, resources (auto-tune-history), aliassen (cross-project-URL's op `$DEPLOYMENT_NAME`), SOPS-versleutelde env. `redis` pint hier zijn `image:`; app-componenten krijgen hun tag uit de deploy. |
| `argo-applications` → `odcn-production/<project-id>/` | Eén `*-<deployment>-argocd-application.yaml` per deployment. Toont `spec.source.repoURL`/`path`/`targetRevision` + `syncPolicy` (bevestigt `selfHeal`/`prune`). |
| `rig-cluster-application-test` → `odcn-production/<project-id>/<deployment>/` | **Gerenderde k8s-manifests die Argo daadwerkelijk synct.** Hier staat de échte image-tag én `replicas` per component (bv. `test/uitvraag-deployment.yaml`). Dit is de grond-waarheid bij elk pull-/schaal-probleem. |

**ZAD CLI (`zadctl`) — eerste ingang, boven handmatige OM-API-calls.**
[RijksICTGilde/zad-cli](https://github.com/RijksICTGilde/zad-cli) (EUPL-1.2) dekt hetzelfde
OM-API-oppervlak plus ontdekbaarheid, JSON-output en exitcodes; `zadctl logs` is het
belangrijkste dat we met de hand nooit gebruikten. Val terug op rauwe OM-calls voor wat de
CLI niet aanbiedt.

```bash
# Linux (Intel/AMD; ARM: linux_arm64)
mkdir -p ~/.local/bin && curl -fsSL -o /tmp/zadctl.tgz \
  https://github.com/RijksICTGilde/zad-cli/releases/latest/download/zadctl_linux_amd64.tar.gz
tar -xzf /tmp/zadctl.tgz -C ~/.local/bin zadctl && zadctl --version   # `zad` = tweede naam

# macOS (Apple Silicon; Intel-Mac: darwin_amd64)
mkdir -p ~/.local/bin && curl -fsSL -o /tmp/zadctl.tgz \
  https://github.com/RijksICTGilde/zad-cli/releases/latest/download/zadctl_darwin_arm64.tar.gz
tar -xzf /tmp/zadctl.tgz -C ~/.local/bin zadctl
xattr -d com.apple.quarantine ~/.local/bin/zadctl 2>/dev/null || true   # zonder dit weigert Gatekeeper de ongesigneerde binary
zadctl --version

zadctl login                    # SSO (Keycloak); de ZAD_API_KEY_*-secrets zijn niet lokaal leesbaar
zadctl project use mpfb-8wh     # of mpfm-w3h / mpfpsm-lcl; schrijft .env.zadctl (0600, gitignored)
```

**Inloggen vanuit een container** (onze dev-omgeving): `zadctl login` zet een loopback-listener
op in de container, terwijl de browser op de host draait — `http://127.0.0.1:<poort>/callback`
komt daar nooit aan, en de device-flow is op de Keycloak-client `zad-cli` uitgeschakeld
(*"The flow is disabled for the client"*). Werkende route: start `zadctl login --browser
--no-open` op de achtergrond, open de geprinte URL in de browser, en stuur na het inloggen de
volledige — in de browser falende — callback-URL uit de adresbalk vanuit de container naar de
wachtende listener met `curl -s "<callback-url>"`. Dat moet hetzelfde login-proces zijn (het
houdt `state` en de PKCE-verifier vast) en de `code` verloopt binnen ~1 minuut. Het
geschreven `.env.zadctl` hoort bij de directory waar je de login draaide en wordt nergens
anders gelezen: kopieer hem mee naar de werkmap van waaruit je de CLI gebruikt.

| Commando | Waarvoor |
|----------|----------|
| `zadctl logs <deployment> -c <component> -n 200 --since 1h` | Pod-logs (API-equivalent: `GET /api/logs/{project}?deployment=&component=&lines=`, max 1000) |
| `zadctl deployment list` / `describe <d>` / `url <d> -c <c>` | Deployments, component-images, publieke adressen |
| `zadctl deployment update-image` / `refresh <d>` | Image zetten / reconcilen — **reactiveert géén uitgeschakeld component** (zie deadlock hieronder) |
| `zadctl deployment delete <d>` → `create <d> …` | De delete+upsert-herstelroute; **destructief**, lees eerst de waarschuwing onderaan |
| `zadctl resource tune` / `sanitize` | Auto-tune CPU/geheugen op werkelijk gebruik; kapotte deployments detecteren |
| `zadctl project pending` / `refresh` | Wat is opgeslagen maar nog niet uitgerold, en alles alsnog uitrollen |
| `zadctl guide [--section <naam>]` | Volledige uitleg, zonder credentials; `--output json` voor agent-gebruik |

Voor scripts en agents: `-o json` op elk commando (data naar stdout, diagnostiek naar
stderr), `--dry-run` toont de request zonder te sturen, `--yes` beantwoordt de
bevestigingsprompts (alleen `delete`/`remove`/`clear`/`unset`/`restore` vragen), `--strict`
maakt "gelukt maar degraded" non-zero. Exitcodes: `1` = eigen input/config/app, `2` =
platform/netwerk (retry zinvol), `3` = niet te attribueren. CI blijft `zad-actions`
gebruiken; de CLI is voor handwerk en debuggen.

**OM-API rechtstreeks** — vanuit CI, of waar de CLI niets voor heeft (per-project
`X-API-Key`, secrets `ZAD_API_KEY_UITVRAAG`/`_MAGAZIJNEN`/`_PROFIEL`): basis
`https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api`, spec op `/openapi.json`.
Handig (v2, read-only tenzij anders): `GET /projects/{p}/deployments` (lijst),
`GET …/deployments/{d}` (detail incl. component-images), `PUT …/deployments/{d}/image`
(zet image per component), `POST …/deployments/{d}/:refresh` (reconcile — **reactiveert
géén uitgeschakeld component**).

**OM vergrendelt op project, niet op deployment.** Draait er een tweede taak in hetzelfde project,
dan wordt de wachtstap van een lopende deploy overruled: `zadctl` eindigt met 0 en `zad-actions`
meldt "Deployment successful", maar het `superseded`-resultaat draagt geen `urls` en de job faalt
alsnog op `Could not extract URLs from result` — een melding die de oorzaak niet noemt. De uitrol
zelf is dan geslaagd; opnieuw draaien volstaat. Doe daarom **geen handmatig OM-werk terwijl er een
deploy loopt** (`gh run list --workflow "Deploy ZAD"` toont dat), en verwacht hetzelfde wanneer twee
PR's tegelijk naar hetzelfde project uitrollen — de concurrency-groepen in `deploy.yml` staan per
project **en** PR, dus die race sluiten ze niet uit.

**Valkuilen bij debuggen (geleerd uit een ImagePullBackOff-melding):**
- De UI-melding **"uitgeschakeld: image ontbreekt"** + logs **"No resources found in
  namespace"** = `replicas: 0` in het gerenderde `*-deployment.yaml`. Er draait niets;
  het is een schaal-/enable-probleem, geen image-probleem.
- De **"Technische details"-ImagePullBackOff kan een bevroren, verouderd event zijn**
  (bv. een oude `:main`-tag) terwijl de gesyncte manifest allang een geldige tag heeft.
  **Verifieer altijd eerst de tag/`replicas` in het gerenderde `*-deployment.yaml`** vóór
  je de UI-fouttekst gelooft.
- De workflow pusht tags `main-<sha7>` (push→main) en `pr-<n>-<sha7>` (PR) — **nooit** een
  kale `:main` of `:pr-<n>`. Zie de `meta`-job in `deploy.yml`. Een deployment die `:main`
  verwacht, is handmatig/verouderd geconfigureerd.
- **Image-tags moeten uniek zijn per commit.** Argo synct op verschil in het gerenderde
  manifest; een herbruikte tag laat dat manifest ongewijzigd, dus rolt er niets uit en
  blijft de preview op de eerste build hangen terwijl de deploy-check groen is
  (`imagePullPolicy: Always` werkt pas bij een herstart). Bij PR-sluiten ruimt
  `cleanup-preview-images` alle `pr-<n>-*`-versies in ghcr op.
- De ghcr-images (`ghcr.io/minbzk/fbs-*`) zijn **public**; ZAD trekt ze via de
  pull-through-mirror `rcr.rijksapps.nl/ghcr-rig/minbzk/*`. Een 404 op een bestaande,
  publieke tag wijst op de mirror/registry-config aan ZAD-zijde, niet op onze push.
- `:refresh`/UI-"herverwerken"/`gh run rerun`/`PUT …/image` reconcilen wel, maar
  **reactiveren een door OM uitgeschakeld component NIET** (ze verhogen `replicas` niet).
  Een disabled/replicas-0-deployment zit in een deadlock: replicas 0 → geen pod → geen
  verse pull → controller herverifieert de image nooit → blijft uit. **De enige werkende
  fix = deployment HERSCHEPPEN via de API:** `DELETE /api/v2/projects/{p}/{d}` (let op:
  korte pad, zónder `/deployments/`) → `POST /api/v2/projects/{p}/:upsert-deployment`
  (body: `deploymentName` + `components:[{reference,image}]`). Upsert-na-delete = create
  → start **enabled**, synct gezond, trekt de geldige tag prima. Env overleeft (staat in
  de project-spec, niet in de deployment). Dit is precies wat onze zad-actions **cleanup**
  (delete) + **deploy** (upsert) doen; de workflow verwijdert `test` nooit, dus doe het
  met de hand tegen de baseline. **DESTRUCTIEF:** `DELETE` draait Argo `prune`+`Delete`
  en, voor projecten met de `postgresql-database`-service, `database_cleanup` →
  DB-data weg — geverifieerd 2026-07-02 voor magazijnen `mpfm-w3h`. Hetzelfde geldt
  voor uitvraag `mpfb-8wh` zodra de LDV-PostgreSQL-migratie die service daar aan het
  `uitvraag`-component koppelt (handmatige OM-stap — voor uitvraag niet apart
  geverifieerd). Projecten zónder DB (externe-stubs `mpfpsm-lcl`: enkel wiremock-stubs,
  ephemeral) verliezen niets.

