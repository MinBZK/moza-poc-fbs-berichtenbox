# Lokaal Fundament-testcluster: bouwplan

**Status:** Uitgevoerd (de afwijkingen staan onder "Uitkomst" in het ontwerp)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `charts/fundament-testcluster.sh op|controleer|af` zet de plugin-sandbox van Fundament op als
fieldlab-cluster (Kubernetes 1.36, echte plug-ins), installeert de drie diensten met de chart in twee
rollen en controleert dat ze gezond en via de Gateway bereikbaar zijn.

**Architecture:** Een dun orkestratiescript (`charts/fundament-testcluster.sh`) dat zijn manifests,
values en parsers uit een bibliotheek zonder bijwerkingen haalt (`charts/fundament-testcluster/lib.sh`).
De bibliotheek is zonder cluster te testen met een bash-suite in CI; het orkestratiescript wordt
geverifieerd door het echt te draaien op de host. Fundament zelf draait uit een eigen worktree van de
clone op een gepinde commit, met hun `just`-recipes.

**Tech Stack:** bash, k3d 5.9.0, k3s `v1.36.6-k3s1`, kubectl, helm 4, just + mise (uit de
`mise.toml` van Fundament), `functl`, Fundament-plug-ins `gateway-api-envoy` v0.2.0,
`cloudnativepg` v0.2.0, `cert-manager` v1.21.3.

**Spec:** [`2026-11-11-lokaal-fundament-testcluster.md`](2026-11-11-lokaal-fundament-testcluster.md)

## Global Constraints

- Branch `feature/1535-fundament-testcluster` in worktree `../moza-poc-fbs-fundament-testcluster`,
  gestapeld op `feature/1535-helm-chart`. `charts/lokaal-testcluster.sh`, `charts/controleer.sh` en
  alles onder `charts/fbs-dienst/` en `charts/policy/` niet wijzigen: die zijn van PR 444. Wat je
  daar nodig hebt, kopieer je; wat daar fout is, gaat als bevinding naar PR 444.
- Elke `kubectl`-aanroep via `kc` (`kubectl --context k3d-fundament-plugin`) of `kcp`
  (`--context k3d-fundament`), elke `helm`-aanroep via `hm` (`helm --kube-context k3d-fundament-plugin`).
  Het script wisselt nooit de huidige context (`k3d cluster create … --kubeconfig-switch-context=false`,
  geen `just plugins cluster-create`, want die wisselt wel).
- Geheimen nooit op de commandoregel: als bestand (`--from-file`) of via stdin. `functl auth login`
  krijgt de key via stdin (het leest stdin als het argument ontbreekt).
- Poorten alleen op `127.0.0.1`. De https-poort van de sandbox: `HTTPS_POORT`, default `9443`
  (8443 is van het platform).
- Images op digest: `rancher/k3s:v1.36.6-k3s1@sha256:88d3fac10eb4a429d3c5275f4868fe4b13620f6bbca5bdb5dca0627ac76ae2a0`,
  `curlimages/curl:8.17.0@sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40`;
  Redis Stack met dezelfde digest als `lokaal-testcluster.sh`.
- Fundament: `FUNDAMENT_REPO` (default `~/dev/fundament`), `FUNDAMENT_REF` (default `b9c12531`),
  worktree onder `${XDG_CACHE_HOME:-~/.cache}/fbs-fundament-testcluster/fundament-<ref>`.
- Vaste namen: namespace `tnt-fbs--fbs`; ServiceAccount `projectlid`; Gateway `web`, listener `https`
  op 443 voor `*.fbs.localhost`; Issuer `lokale-ca`, certificaat-Secret `web-tls`; CNPG-Clusters `db`
  (magazijn, de naam die `charts/voorbeelden/berichtenmagazijn-cnpg.yaml` verwacht) en `ldv-uitvraag`;
  organisatie `system` = `019b4000-0000-7000-8000-000000000000`; installaties `system--<plugin>`.
- Taal en commentaar volgens CLAUDE.md: identifiers en meldingen Nederlands, technische idiomen
  Engels; commentaar legt het waarom vast. `shellcheck -x -S warning` schoon (CI lint alles onder
  `charts/`).
- Exitcodes: `0` groen, `1` controle(s) niet in orde, `2` ontbrekende voorwaarde (tool, variabele,
  bezette poort), `3` handmatige stap nodig (API-key).

## Review Focus

1. **`op` een tweede keer draaien** op een opstelling die al staat: moet doorlopen tot groen zonder
   iets dubbel te publiceren of te installeren (versies zijn create-only) — Task 1
   (`plugin_staat_klaar`) en Task 4.
2. **Het platform is opnieuw uitgerold** (databases leeg, API-key ongeldig): een duidelijke melding
   "maak een nieuwe API-key" met exit 3, geen kale functl-fout — Task 1 (`functl_fout_soort`).
3. **`HTTPS_POORT` is bezet**: weigeren vóór er iets wordt aangemaakt, met de poort in de melding —
   Task 1 (`poort_vrij`).
4. **Een sandbox die er al staat maar niet van ons is** (andere k3s-versie, of gemaakt met
   `just plugins cluster-create` zonder onze portmapping): weigeren met de opruimopdracht, niet
   stilletjes gebruiken — Task 1 (`sandbox_past`).
5. **Een aanroep die de context vergeet** (iemand voegt later een kale `kubectl` toe): de suite faalt
   op elke `kubectl`/`helm` buiten de wrappers — Task 1 (statische controle).

---

### Task 1: Bibliotheek, testsuite en CI-registratie

**Files:**
- Create: `charts/fundament-testcluster/lib.sh`
- Create: `charts/fundament-testcluster/test-lib.sh`
- Modify: `.github/workflows/ci-scripts.yml` (de `find` van de bash-unittests en `VERWACHTE_ASSERTIES`)

**Interfaces:**
- Produces (alle functies schrijven naar stdout, geen bijwerkingen behalve waar genoemd):
  - `publicatie_lees <uitvoer>` → `"<versie> <hash>"` uit een regel
    `published plugin=<p> version=<v> hash=sha256:<64 hex> …`; exit 1 als die regel ontbreekt.
  - `functl_fout_soort <uitvoer>` → `geen-listing` (bevat `no listing named`), `niet-ingelogd`
    (bevat `unauthenticated` of `401`), anders `onbekend`.
  - `plugin_staat_klaar <kubectl-json>` → exit 0 als `.status.phase == "Running"` en `.status.ready == true`.
  - `sandbox_past <server-versie> <poortmappings>` → exit 0 als de versie met `v1.36.` begint én
    de mappings `127.0.0.1:<HTTPS_POORT>->443` bevatten; anders een melding op stderr en exit 1.
  - `poort_vrij <poort>` → exit 0 als niemand op `127.0.0.1:<poort>` luistert (`ss -ltn`).
- Consumes: niets.

- [ ] **Step 1: Schrijf de suite met de harness en de eerste asserties.** Zelfde vorm als
  `.github/scripts/test-*.sh`: per assertie `OK: …` of `FOUT: …`, aan het eind `ASSERTIES=<n>`,
  exit 1 bij een fout. Tests:
  - `publicatie_lees` op `published plugin=cert-manager version=v1.21.3 hash=sha256:<64×a> id=1 version_id=2 status=SUBMISSION_STATUS_DRAFT` → `v1.21.3 sha256:<64×a>`; op uitvoer zonder `published`-regel → exit 1; op een hash van 63 tekens → exit 1.
  - `functl_fout_soort`: drie gevallen, één per uitkomst.
  - `plugin_staat_klaar`: `{"status":{"phase":"Running","ready":true}}` → 0; `phase` `Installing` → 1; `ready` ontbreekt → 1; lege invoer → 1.
  - `sandbox_past`: `v1.36.6+k3s1` + `127.0.0.1:9443->443` → 0; `v1.33.5+k3s1` → 1 met `1.36` in de melding; juiste versie zonder mapping → 1 met `HTTPS_POORT` in de melding.
  - `poort_vrij`: start `python3 -m http.server --bind 127.0.0.1 <vrije poort>` op de achtergrond → 1; na `kill` → 0.
  - Statisch: `grep -nE '(^|[^a-z_])(kubectl|helm) '` over de bestanden in een lijst `GECONTROLEERDE_SCRIPTS` (nu alleen `lib.sh`), minus de regels die `kc()`, `kcp()` en `hm()` definiëren, levert niets op. Een bestand uit de lijst dat ontbreekt is een `FOUT`, geen overslaan. Task 4 voegt `charts/fundament-testcluster.sh` aan de lijst toe.

- [ ] **Step 2: Draai de suite.** `bash charts/fundament-testcluster/test-lib.sh` → faalt (functies bestaan niet).

- [ ] **Step 3: Implementeer de vijf functies in `lib.sh`.** Geen `set -e` in de bibliotheek (die wordt gesourcet); `jq` voor de JSON, die staat al op de host en in `mise.toml` van Fundament.

- [ ] **Step 4: Draai de suite opnieuw.** Alle asserties `OK`. `shellcheck -x -S warning charts/fundament-testcluster/*.sh` schoon.

- [ ] **Step 5: Registreer de suite in CI.** In `ci-scripts.yml`: voeg `charts` toe aan beide `find`-aanroepen die `test-*.sh` zoeken, en `[charts/fundament-testcluster/test-lib.sh]=<n>` aan `VERWACHTE_ASSERTIES` met het aantal na Task 3 (zet het nu op het huidige aantal en werk het per task bij).

- [ ] **Step 6: Commit** `feat(charts): bibliotheek en testsuite voor het Fundament-testcluster`.

### Task 2: Manifests van de opstelling

**Files:**
- Modify: `charts/fundament-testcluster/lib.sh`
- Modify: `charts/fundament-testcluster/test-lib.sh`

**Interfaces:**
- Produces (elk print één YAML-document of een `---`-gescheiden set):
  - `manifest_plugininstallatie <plugin> <versie> <hash>` → `PluginInstallation` `system--<plugin>`, `spec.definitionRef` met `organizationName: system`.
  - `manifest_tenant_namespace <ns>` → Namespace met de labels van `cluster-worker` (`fundament.io/managed-by: cluster-worker`, `fundament.io/namespace-name: <deel na -->`, `fundament.io/namespace-id`, `fundament.io/project-id`, `fundament.io/organization-id`, `fundament.io/cluster-id` met de vaste sandbox-id's uit `plugins/sandbox/values.yaml`) plus `pod-security.kubernetes.io/enforce: restricted`; en een LimitRange `fundament-defaults` met alleen `default`/`defaultRequest` (cpu 500m/100m, geheugen 512Mi/128Mi).
  - `manifest_projectlid <ns>` → ServiceAccount `projectlid` + RoleBinding `projectlid-admin` naar ClusterRole `admin`.
  - `manifest_lokale_ca <ns>` → Issuer `zelfondertekend` (selfSigned), Certificate `lokale-ca` (`isCA: true`, Secret `lokale-ca`), Issuer `lokale-ca` (`ca.secretName: lokale-ca`).
  - `manifest_gateway <ns>` → Gateway `web`, class `eg`, annotatie `cert-manager.io/issuer: lokale-ca`, listener `https` (HTTPS, 443, `hostname: "*.fbs.localhost"`, `tls.certificateRefs: [{name: web-tls}]`, `allowedRoutes.namespaces.from: Same`).
  - `manifest_cnpg_cluster <naam> <ns>` → `postgresql.cnpg.io/v1` Cluster, `instances: 1`, `storage.size: 1Gi`, `imageCatalogRef: {apiGroup: postgresql.cnpg.io, kind: ClusterImageCatalog, name: fundament-postgresql, major: 18}`.
  - `manifest_proef_redis <ns> <image>` → Deployment + Service `proef-redis`, overgenomen uit `lokaal-testcluster.sh` (UID 999, drop ALL, seccomp RuntimeDefault, `REDIS_ARGS` met `--requirepass $(REDIS_PASSWORD)`), Secret-naam `proef-voorzieningen`.
  - `manifest_probe <ns> <dienst> <image>` → Pod `probe-<dienst>` met label `client.fbs.minbzk.nl/<dienst>: "true"`, `restartPolicy: Never`, restricted securityContext, commando `curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://<dienst>.<ns>.svc/q/health/ready`.
- Consumes: niets.

- [ ] **Step 1: Schrijf per functie de asserties.** Per manifest: de `kind` en `metadata.name`, en de waarden uit de Interfaces hierboven die de opstelling dragen. Toets met `grep -F` op exacte regels, niet met een YAML-parser (die staat niet in CI). Minstens: `major: 18`; `from: Same`; `hostname: "*.fbs.localhost"`; `cert-manager.io/issuer: lokale-ca`; `pod-security.kubernetes.io/enforce: restricted`; `client.fbs.minbzk.nl/berichtenmagazijn: "true"`; `runAsNonRoot: true` en `drop: [ALL]` in zowel Redis als probe; een LimitRange zónder `max:` en `min:`; `name: system--cloudnativepg`.

- [ ] **Step 2: Draai de suite** → de nieuwe asserties falen.

- [ ] **Step 3: Implementeer de manifestfuncties** met heredocs. Neem in een comment boven `manifest_tenant_namespace` op waar de labels vandaan komen (`cluster-worker/pkg/handler/namespace`), zodat iemand ze kan bijwerken als Fundament ze wijzigt.

- [ ] **Step 4: Draai de suite** → groen; shellcheck schoon; werk `VERWACHTE_ASSERTIES` bij.

- [ ] **Step 5: Commit** `feat(charts): manifests voor tenant-namespace, Gateway, CNPG en proefvoorzieningen`.

### Task 3: Values per dienst en per rol

**Files:**
- Modify: `charts/fundament-testcluster/lib.sh`
- Modify: `charts/fundament-testcluster/test-lib.sh`

**Interfaces:**
- Produces:
  - `values_dienst <dienst> <ns>` → het values-bestand bovenop `charts/fbs-dienst/diensten/<dienst>.yaml`:
    - **berichtenuitvraag:** `REDIS_HOSTS: redis://proef-redis.<ns>.svc:6379`, `FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT: "true"`, `allowUnsafeFlags: true`, de `.invalid`-adressen uit `lokaal-testcluster.sh`, en de LDV-verbinding uit Secret `ldv-uitvraag-app` volgens het patroon van `voorbeelden/berichtenmagazijn-cnpg.yaml` (`$(LDV_HOST)` enz., `?sslmode=require`). Géén `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT`.
    - **berichtenmagazijn:** alleen wat `voorbeelden/berichtenmagazijn-cnpg.yaml` niet zet (`MAGAZIJN_OIN`, `.invalid`-adressen); dat voorbeeld gaat als extra `-f` mee. Géén onveilige klep.
    - **stelselregister:** de env uit `lokaal-testcluster.sh`.
  - `helm_route_args <dienst>` → de `--set`-argumenten voor een route: `ingress.enabled=true`, `ingress.allowUnauthenticated=true`, `ingress.hostname=<dienst>.fbs.localhost`, `cluster.gateway.name=web`, `cluster.gateway.sectionName=https`.
- Consumes: niets.

- [ ] **Step 1: Schrijf de asserties.** Uitvraag bevat `sslmode=require` en `secretKeyRef: {name: ldv-uitvraag-app` en bevat niet `FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT`; magazijn bevat geen enkele `UNSAFE`; uitvraag bevat `FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT: "true"`; `helm_route_args stelselregister` bevat `ingress.hostname=stelselregister.fbs.localhost` en `cluster.gateway.sectionName=https`; een onbekende dienst → exit 1.

- [ ] **Step 2: Draai de suite** → faalt.

- [ ] **Step 3: Implementeer `values_dienst` en `helm_route_args`.**

- [ ] **Step 4: Toets de render zonder cluster.** Per dienst: `helm template t charts/fbs-dienst -n tnt-fbs--fbs -f charts/fbs-dienst/diensten/<d>.yaml [-f charts/voorbeelden/berichtenmagazijn-cnpg.yaml] -f <(values_dienst <d> tnt-fbs--fbs) --set dienst.image.tag=test --set dienst.tlsTermination=mesh $(helm_route_args <d>)` slaagt. Dat is een handmatige verificatie; de suite in CI heeft geen helm.

- [ ] **Step 5: Draai de suite** → groen; werk `VERWACHTE_ASSERTIES` bij.

- [ ] **Step 6: Commit** `feat(charts): values voor de diensten op het Fundament-testcluster`.

### Task 4: `op`, deel 1: Fundament-platform, sandbox en plug-ins

**Files:**
- Create: `charts/fundament-testcluster.sh`

**Interfaces:**
- Consumes: alles uit Task 1 en Task 2 (`manifest_plugininstallatie`).
- Produces: subcommando `op` (dit deel), en de functies `kc`, `kcp`, `hm`, `fundament_werkboom`, `platform_op`, `sandbox_op`, `plugins_op`, die Task 5-7 aanroepen.

- [ ] **Step 1: Kop en voorwaarden.** Gebruik (`op|controleer|af`), `set -euo pipefail`, source `lib.sh`, wrappers `kc`/`kcp`/`hm`. `op` eist `TAG` en de tools `docker k3d kubectl helm just mise git jq curl ss` (exit 2 met de ontbrekende naam). `just`, `k3d`, `skaffold` en `functl` komen uit `mise install` in de Fundament-worktree; draai Fundament-recipes daarom met `mise exec -C <werkboom> -- just …`. Zet `charts/fundament-testcluster.sh` in `GECONTROLEERDE_SCRIPTS` van de suite en draai hem: de statische controle is groen.

- [ ] **Step 2: `fundament_werkboom`.** `git -C "$FUNDAMENT_REPO" worktree add --detach <pad> "$FUNDAMENT_REF"` als het pad nog niet bestaat, anders controleren dat `HEAD` op `FUNDAMENT_REF` staat (exit 2 met melding als niet). Dan `mise trust` en `mise install` in de werkboom.

- [ ] **Step 3: `platform_op`.** Draait `k3d-fundament` al (`k3d cluster list fundament` + `docker inspect -f '{{.State.Running}}' k3d-fundament-server-0`), dan niets doen. Anders `just cluster-start` en `just deploy local` in de werkboom, en wachten tot `https://console.fundament.localhost:8443` antwoordt (max 15 min, met de mkcert-CA). Toon bij de eerste keer dat het bouwen van de platform-images lang duurt.

- [ ] **Step 4: API-key.** Ontbreekt `FUNCTL_API_KEY`: print de stappen (log in op de console als `platform-admin@fundament.io` / `password`, **API keys** → **New API key**, `export FUNCTL_API_KEY=…`, `op` opnieuw) en exit 3. Anders `printf '%s\n' "$FUNCTL_API_KEY" | just functl auth login` en `just functl org set 019b4000-0000-7000-8000-000000000000`. Bij een fout: `functl_fout_soort`; `niet-ingelogd` → dezelfde stappen plus "het platform is opnieuw uitgerold, de oude key is weg", exit 3.

- [ ] **Step 5: `sandbox_op`.** Bestaat `k3d-fundament-plugin`: `sandbox_past "$(kc version -o json | jq -r .serverVersion.gitVersion)" "$(docker port k3d-fundament-plugin-serverlb)"`, bij nee exit 2 met `just plugins cluster-delete` als uitweg. Bestaat hij niet: `poort_vrij "$HTTPS_POORT"` (anders exit 2), dan in `<werkboom>/plugins`: `k3d cluster create --config=sandbox/k3d-config.yaml --image <k3s-digest> -p "127.0.0.1:$HTTPS_POORT:443@loadbalancer" --kubeconfig-switch-context=false --wait`. Daarna `just plugins deploy`, `just plugin-sandbox-kubeconfig`, `just plugins sandbox-catalog`.

- [ ] **Step 6: `plugins_op`.** Voor `cert-manager`, `gateway-api/envoy-gateway`, `cloudnativepg` (installatienamen `system--cert-manager`, `system--gateway-api-envoy`, `system--cloudnativepg`): staat de installatie al klaar (`plugin_staat_klaar "$(kc get plugininstallation <naam> -o json)"`), sla over. Anders `PLUGIN_REGISTRY=localhost:5112 just plugins publish <pad>`; bij `geen-listing` opnieuw met `--create`; `publicatie_lees` → `kc apply` van `manifest_plugininstallatie`; wachten tot `plugin_staat_klaar` (max 10 min, elke 5 s). Daarna wachten op GatewayClass `eg` `Accepted` en op de CRD `clusters.postgresql.cnpg.io`.

- [ ] **Step 7: Live verifiëren.** `TAG=main-<sha7> charts/fundament-testcluster.sh op` op de host (zonder Task 5-7 stopt hij na de plug-ins). Verwacht: `kc version` → `v1.36.6+k3s1`; `kc get plugininstallations` → drie keer `Running true`; `kc get gatewayclass eg` → `Accepted True`; `kubectl config current-context` is onveranderd. Draai `op` meteen nog een keer: geen nieuwe publicatie, zelfde uitkomst (Review Focus 1).

- [ ] **Step 8: Commit** `feat(charts): Fundament-platform, sandbox op 1.36 en plug-ins voor het testcluster`.

### Task 5: `op`, deel 2: namespace, Gateway, databases en geheimen

**Files:**
- Modify: `charts/fundament-testcluster.sh`

**Interfaces:**
- Consumes: Task 2 (`manifest_*`), Task 4 (`kc`).
- Produces: functie `voorzieningen_op`; Secrets `proef-voorzieningen`, `stelselregister-keystore`, `berichtenuitvraag-geheimen`, `stelselregister-geheimen`; bestand `$WERK/lokale-ca.crt` via `lokale_ca_bestand <doel>`.

- [ ] **Step 1: Namespace, rechten en Gateway.** `kc apply` van `manifest_tenant_namespace`, `manifest_projectlid`, `manifest_lokale_ca`, `manifest_gateway`. Wacht: Certificate `lokale-ca` Ready, Gateway `web` `Programmed` (5 min), Secret `web-tls` bestaat (de cert-manager-annotatie maakt het certificaat).

- [ ] **Step 2: Databases.** `kc apply` van `manifest_cnpg_cluster db` en `… ldv-uitvraag`; wachten tot beide `kc get cluster.postgresql.cnpg.io <n> -o jsonpath='{.status.phase}'` = `Cluster in healthy state` (10 min), en de Secrets `db-app` en `ldv-uitvraag-app` bestaan. Faalt de imagecatalogus op major 18: niet terugvallen op 17 maar stoppen met de melding; dat is een bevinding (de console van het fieldlab biedt 16 en 17).

- [ ] **Step 3: Redis, keystore en geheimen.** Zoals `lokaal-testcluster.sh`: wachtwoord met `openssl rand -hex 24` naar een bestand onder `$WERK` (`umask 077`), Secret `proef-voorzieningen`, `manifest_proef_redis`, rollout afwachten; keystore met `demo/environment/stelselregister/pki/maak-keten.sh` (`STELSELDOCUMENT_OMGEVING=lokaal`); Secrets `berichtenuitvraag-geheimen` (`REDIS_PASSWORD`) en `stelselregister-geheimen` (`STELSELDOCUMENT_KEYSTORE_WACHTWOORD`). Het magazijn heeft geen eigen geheimen-Secret nodig: alles komt uit `db-app`. Bestaat een Secret al, laat hem staan (anders past Redis niet meer bij zijn wachtwoord bij een tweede `op`).

- [ ] **Step 4: Live verifiëren.** `op` opnieuw. Verwacht: `kc -n tnt-fbs--fbs get gateway web` → `PROGRAMMED True`; `get certificate` → beide `READY True`; `get cluster.postgresql.cnpg.io` → twee keer healthy; `kc -n tnt-fbs--fbs get pods` zonder PSA-weigering in de events.

- [ ] **Step 5: Commit** `feat(charts): tenant-namespace, Gateway met lokale CA en CNPG-databases`.

### Task 6: Installeren en controleren als organisatiebeheerder

**Files:**
- Modify: `charts/fundament-testcluster.sh`

**Interfaces:**
- Consumes: Task 3 (`values_dienst`, `helm_route_args`), Task 5.
- Produces: `installeer_beheerder`, subcommando `controleer` (functie `controleer_dienst <dienst>`), gedeeld door Task 7.

- [ ] **Step 1: `installeer_beheerder`.** Per dienst `hm upgrade --install <dienst> charts/fbs-dienst -n tnt-fbs--fbs -f diensten/<d>.yaml [-f voorbeelden/berichtenmagazijn-cnpg.yaml] -f <values_dienst> --set dienst.image.tag="$TAG" --set dienst.tlsTermination=mesh $(helm_route_args <d>)`, plus `--set dienst.extraEnvVarsSecret=<d>-geheimen` voor uitvraag en stelselregister. Geen `--create-namespace`.

- [ ] **Step 2: `controleer_dienst`.** Telt fouten op, stopt niet bij de eerste:
  - `kc rollout status deployment/<d>` (5 min); bij falen de laatste 40 logregels en de events van de pod.
  - Binnen het cluster: `manifest_probe` toepassen (eerst een oude probe weghalen), wachten op `Succeeded`, logregel = `200`. Een `000` met de probe zonder label zou de NetworkPolicy aantonen; dat is geen onderdeel van deze controle.
  - Via de Gateway: `curl --cacert "$WERK/lokale-ca.crt" --resolve "<d>.fbs.localhost:$HTTPS_POORT:127.0.0.1" -s -o /dev/null -w '%{http_code}' https://<d>.fbs.localhost:$HTTPS_POORT/openapi.json` → `200` (tot 3 min opnieuw proberen, net als `lokaal-testcluster.sh`); `/q/health/ready` → `404`. Een TLS-fout van curl telt als fout, er is geen `-k`.
  - Subcommando `controleer` draait `controleer_dienst` voor de drie diensten, exit 1 bij fouten, met het opruimcommando in de laatste regel.

- [ ] **Step 3: `op` sluit af met `installeer_beheerder` en `controleer`.**

- [ ] **Step 4: Live verifiëren.** `op` → drie keer rollout OK, binnen het cluster `200`, via de Gateway `200`/`404`. Negatieve proef met de hand: `kc -n tnt-fbs--fbs delete pod probe-…` en `kc -n tnt-fbs--fbs scale deployment/stelselregister --replicas=0`, `controleer` → exit 1 met stelselregister in de foutregels; terugzetten met `op`.

- [ ] **Step 5: Commit** `feat(charts): de diensten als organisatiebeheerder installeren en controleren`.

### Task 7: Installeren en controleren als projectlid

**Files:**
- Modify: `charts/fundament-testcluster.sh`

**Interfaces:**
- Consumes: Task 6 (`controleer_dienst`), Task 3, Task 5 (`projectlid`).
- Produces: `installeer_projectlid`; `op --rol beheerder|projectlid|beide` (default `beide`).

- [ ] **Step 1: Rechten vastleggen.** `kc auth can-i create httproutes.gateway.networking.k8s.io -n tnt-fbs--fbs --as system:serviceaccount:tnt-fbs--fbs:projectlid`; print `INFO: projectlid mag HTTPRoutes maken: ja|nee`. Geen fout bij `nee`; dat is de verwachting en de reden voor de uitweg hieronder.

- [ ] **Step 2: `installeer_projectlid`.** Haal de releases van de beheerder weg (`hm uninstall`, databases en Secrets blijven). Dan per dienst dezelfde `hm upgrade --install` als in Task 6, maar met `--kube-as-user system:serviceaccount:tnt-fbs--fbs:projectlid` en `--set ingress.enabled=false` (zonder `helm_route_args`). Daarna de routes apart als beheerder: `hm template … $(helm_route_args <d>) --show-only templates/httproute.yaml | kc apply -n tnt-fbs--fbs -f -`. Faalt de installatie als projectlid op iets anders dan een route: die soort en de foutmelding zijn een bevinding voor PR 444 (de README belooft dat de chart binnen de rol `admin` blijft). Stop dan met exit 1, werk er niet omheen.

- [ ] **Step 3: Controleren.** `controleer_dienst` voor de drie diensten, gelijk aan Task 6.

- [ ] **Step 4: Live verifiëren.** `op --rol projectlid` → groen, en `kc -n tnt-fbs--fbs get secret -l owner=helm -o jsonpath='{..metadata.name}'` toont de releases; `op` (beide rollen) → twee keer groen achter elkaar.

- [ ] **Step 5: Commit** `feat(charts): de diensten als projectlid installeren, met de route apart`.

### Task 8: `af`, documentatie en oplevering

**Files:**
- Modify: `charts/fundament-testcluster.sh`
- Modify: `charts/README.md` (nieuwe sectie "Proef op een Fundament-sandbox", ná de sectie van `lokaal-testcluster.sh`; die sectie zelf niet wijzigen)
- Modify: `docs/ontwikkelen.md` (de chartsectie: het commando en de voorwaarden)
- Modify: `docs/plans/2026-11-11-lokaal-fundament-testcluster.md` (status, uitkomsten)

- [ ] **Step 1: `af`.** `just plugins cluster-delete` in de werkboom (sandbox, registry, relays), dan `git -C "$FUNDAMENT_REPO" worktree remove <pad>`. Het platform laat hij staan; de laatste regel noemt `just cluster-delete` in de werkboom voor wie ook dat weg wil. Een tweede `af` meldt dat er niets op te ruimen is en eindigt met 0.

- [ ] **Step 2: Documentatie.** Voorwaarden (Docker met ≥16 GiB vrij, mise, `/dev/kvm` niet nodig), de API-key-stap, de drie subcommando's, de variabelen (`TAG`, `FUNCTL_API_KEY`, `FUNDAMENT_REPO`, `FUNDAMENT_REF`, `HTTPS_POORT`), en wat het afwijkt van het fieldlab (local-path i.p.v. Ceph, lokale CA i.p.v. Let's Encrypt, Redis als proefvoorziening).

- [ ] **Step 3: Volledige proef van nul.** `af`, dan `op` tot groen (beide rollen), dan `controleer`, dan `af`. Leg de duur en de uitkomsten vast.

- [ ] **Step 4: Plan bijwerken.** Status van het ontwerp en dit bouwplan op **Uitgevoerd**, met wat er gedraaid heeft, de uitkomst van `can-i create httproutes`, en de bevindingen voor PR 444 (als die er zijn).

- [ ] **Step 5: Controles.** `bash charts/fundament-testcluster/test-lib.sh` groen en het aantal gelijk aan `VERWACHTE_ASSERTIES`; `shellcheck -x -S warning` over alles onder `charts/`; reviewers `ci-workflow-reviewer` (voor `ci-scripts.yml`) en `taal-en-commentaar-reviewer` over de diff.

- [ ] **Step 6: Commit en draft-PR.** Commit `docs(charts): het Fundament-testcluster documenteren`; push; `gh pr create --draft --base feature/1535-helm-chart` met in de body de uitkomsten, de bevindingen voor PR 444 en `Refs MinBZK/MijnOverheidZakelijk#1535` (geen `Closes`: PR 444 sluit het issue).
