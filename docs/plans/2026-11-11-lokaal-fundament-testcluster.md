# Lokaal Fundament-testcluster voor de chart

**Status:** Uitgevoerd

## Aanleiding

Op het Fieldlab soevereine basis (23-25 november 2026) rollen we berichtenuitvraag, berichtenmagazijn
en stelselregister uit op een cluster van Fundament, met de chart `charts/fbs-dienst/` uit PR 444
(MinBZK/MijnOverheidZakelijk#1535). Op het fieldlab maak je zelf een cluster aan in de console van
Fundament, haal je er een kubeconfig van op (`functl cluster kubeconfig`), installeer je de
plug-ins die je nodig hebt en rol je uit met `kubectl`/`helm`.

De chart is tot nu toe alleen zonder cluster gecontroleerd (`charts/controleer.sh`), en onder
podman op losse containers. We willen vóór het fieldlab weten dat hij werkt op een cluster dat zich
gedraagt als een Fundament-cluster, met de plug-ins zoals Fundament ze installeert.

## Doel

Een herhaalbare opstelling op de ontwikkelmachine (Linux-host met Docker) die:

1. een cluster opzet dat op de punten waar de chart van afhangt gelijk is aan een Fundament-cluster
   van het fieldlab;
2. de Fundament-plug-ins `gateway-api/envoy-gateway`, `cloudnativepg` en `cert-manager`
   installeert via de échte plugin-controller van Fundament, niet nagebootst;
3. de drie diensten installeert met `helm install` op een kubeconfig, zoals op het fieldlab, in
   twee rollen (zie [Twee rollen](#twee-rollen));
4. per dienst controleert:
   - de rollout is klaar;
   - `/q/health/ready` geeft 200 binnen het cluster, vanaf een pod met het label
     `client.fbs.minbzk.nl/<fullname>: "true"` (de NetworkPolicy van de chart houdt andere pods
     tegen);
   - via de Gateway, over https: `/openapi.json` geeft 200 en `/q/health/ready` geeft 404. De chart
     routeert `/q` bewust niet naar buiten; een 200 daar is een route-lek en laat de controle falen.

## Niet-doelen

- **Geen `just test-env` (Gardener in een VM).** Die toetst Fundament zelf (cluster-lifecycle,
  nodepools, shoot-RBAC) en kost ~25 minuten en 24 GiB; voor een chart die met een kubeconfig
  wordt geïnstalleerd voegt dat niets toe. Terugvallen hierop alleen als het fieldlab-cluster
  zich anders blijkt te gedragen dan de sandbox.
- **Geen berichtenverkeer tussen de diensten.** Uitvraag en magazijn krijgen, zoals in
  `lokaal-testcluster.sh`, onbereikbare `https://….invalid`-adressen voor elkaar en voor de
  Profiel-service. Gezond starten en bereikbaar zijn is het doel; een bericht van aanleveren tot
  ophalen is een vervolgstap.
- **Geen plug-in "berichtenmagazijn"** (#1538). De opstelling moet die later wel kunnen dragen.
- **Geen FSC via de openfsc-plug-in.** Kan een vervolgstap zijn.
- **Geen Ceph.** Lokaal de local-path-provisioner van k3s; Ceph vraagt een VM met blokapparaten
  en voegt voor de chart niets toe.
- **Geen Let's Encrypt.** Lokaal een eigen CA via cert-manager (zie [Gateway](#gateway-en-tls)).
- **Geen Argo CD.** Fundament heeft er geen plug-in voor.
- **Geen CI.** Dit draait handmatig op de host.

## Anti-doelen

- De opstelling mag niet "groen" melden terwijl een dienst niet via de Gateway bereikbaar is, of
  terwijl `/q` wél naar buiten lekt.
- Geen proefvoorziening met onveilige kleppen die per ongeluk in een echt cluster belandt: elke
  `kubectl`/`helm`-aanroep noemt expliciet de context, en het script wisselt de huidige context
  niet (zoals `lokaal-testcluster.sh` al doet).
- Geen wijzigingen in de Fundament-repo nodig om het te laten draaien; afwijkingen van hun
  standaard-sandbox gaan via parameters of overrides vanuit onze kant.

## Wat een Fundament-cluster kenmerkt (voor de chart)

| Eigenschap | Op het fieldlab | Bron |
|---|---|---|
| Kubernetes | 1.36 | [aan-de-slag](https://fieldlab2026.projects.digilab.network/aan-de-slag): "Je cluster draait Kubernetes 1.36" |
| Plug-ins | deelnemers installeren zelf Ceph, Envoy Gateway en CloudNativePG; cert-manager is beschikbaar | aan-de-slag |
| Gateway | Envoy Gateway v1.9.3, Gateway API v1.6.1 (experimental), GatewayClass `eg`, controller en proxy's in `envoy-gateway-system`. De plug-in maakt géén Gateway: die maak je zelf in de eigen namespace (voorbeeld `web`); HTTPS via cert-manager (annotatie `cert-manager.io/issuer`) met een `https`-listener op 443 | aan-de-slag; `plugins/gateway-api/envoy-gateway/README.md` |
| Publiek adres | de Gateway krijgt via een LoadBalancer-Service een publiek IPv4-adres; geen DNS, A-record zelf zetten of `<ip-met-streepjes>.sslip.io` | docs/user/service-addresses; aan-de-slag |
| Namespaces | alleen via de console, naam `tnt-<project>--<naam>`; beheerde LimitRange `fundament-defaults` (alleen standaardwaarden) | aan-de-slag; `cluster-worker/pkg/handler/namespace` |
| Rechten | **organisatiebeheerder:** `cluster-admin` op het cluster. **Projectlid:** de ClusterRole `admin` (of `view`) in de namespaces van het project. HTTPRoute en Gateway horen niet bij `admin`: de Envoy-plug-in voegt ze daar niet aan toe, CloudNativePG doet dat voor zijn eigen resources wel | `cluster-worker/pkg/handler/usersync`, `…/projectrbac`; `plugins/cloudnativepg/roles.go` |
| Pod Security | namespaces van plug-ins krijgen `enforce: restricted`; tenant-namespaces krijgen geen label | `plugin-controller/pkg/controller/resources.go` |
| PostgreSQL | CloudNativePG: per `Cluster` een database `app`, Secret `<cluster>-app`, TLS aan met een eigen CA | aan-de-slag; `charts/voorbeelden/berichtenmagazijn-cnpg.yaml` |
| Redis | geen plug-in | — |
| Opslag | Ceph met BlockStorage als default StorageClass | aan-de-slag |
| Images | Forgejo-registry beschikbaar; ghcr.io niet uitgesloten | aan-de-slag |

## Gekozen aanpak: de plugin-sandbox van Fundament

Fundament heeft voor plug-in-ontwikkeling twee k3d-clusters:

```
 k3d-fundament (platform)              k3d-fundament-plugin (sandbox)
 console, marketplace-*,   ──catalog──► plugin-controller
 kube-api-proxy, plugin-proxy          draait plugin-<org>--<naam>
```

- `just cluster-start` in de Fundament-repo zet het platform op (Docker met ≥16 GiB).
- `just plugins cluster-create` + `just plugins deploy` + `just plugin-sandbox-kubeconfig` +
  `just plugins sandbox-catalog` zetten de sandbox op en koppelen hem.
- Plug-ins publiceren met `PLUGIN_REGISTRY=localhost:5112 just plugins publish <pad>` en
  installeren met een `PluginInstallation` op `k3d-fundament-plugin`.

De sandbox speelt het fieldlab-cluster. Onze chart gaat er met `helm --kube-context
k3d-fundament-plugin` op.

### Afwijkingen van de standaard-sandbox

- **Kubernetes 1.36.** `plugins/sandbox/k3d-config.yaml` pint geen k3s-image, dus k3d 5.9.0 kiest
  zijn eigen default. Wij geven een k3s-image voor 1.36 mee.
- **Een poort naar de host, alleen op loopback,** voor de https-listener van de Gateway.
- **Een namespace zoals de console hem maakt:** `tnt-fbs--fbs`, met de Fundament-labels en een
  LimitRange `fundament-defaults`. Daarnaast PSA `restricted` op die namespace: strenger dan een
  tenant-namespace, maar wat straks geldt in de namespace van de plug-in "berichtenmagazijn".

### Gateway en TLS

Zoals in de README van de chart ("Op Fundament"):

- een Gateway `web` in `tnt-fbs--fbs`, GatewayClass `eg`, met een listener `https` op 443 voor
  `*.fbs.localhost`;
- het certificaat via de cert-manager-plug-in, met een eigen lokale CA-Issuer in plaats van
  Let's Encrypt, en dezelfde annotatie `cert-manager.io/issuer` als op het fieldlab;
- de chart met `cluster.gateway.name=web`, `cluster.gateway.sectionName=https` en
  `ingress.hostname=<dienst>.fbs.localhost`;
- de controle via de Gateway toetst het certificaat tegen die lokale CA (geen `curl -k`).

### Twee rollen

Op het fieldlab hangt het af van je rol of je een HTTPRoute mag maken. De opstelling draait beide:

1. **Organisatiebeheerder** (`cluster-admin`), het pad van het fieldlab-voorbeeld: volledige
   installatie mét route. Moet volledig groen zijn.
2. **Projectlid** (ClusterRole `admin` in `tnt-fbs--fbs`, via een ServiceAccount of `--as`):
   - eerst `kubectl auth can-i create httproutes` als dat lid, met de uitkomst in het verslag. De
     README van de chart noemt die controle; de verwachting op deze Fundament-versie is "nee";
   - dan de installatie met `ingress.enabled=false`, de uitweg uit de README, en de route
     apart als beheerder. Moet ook volledig groen zijn.

Zo zijn de rechten van beide rollen vóór het fieldlab getoetst, ook de beperking die de README
noemt.

### Voorzieningen

- **PostgreSQL via CloudNativePG**, met TLS zoals CNPG het standaard levert. Voor PostgreSQL is
  dus geen onveilige klep nodig.
  - Het magazijn volgt `charts/voorbeelden/berichtenmagazijn-cnpg.yaml`: één `Cluster`, met het
    logboek in een eigen schema. Zo draait dat voorbeeld (en de `$(VAR)`-substitutie erin) voor
    het eerst echt.
  - De uitvraag krijgt een eigen `Cluster` voor zijn logboek, op dezelfde manier aangesloten.
- **Redis Stack** als proefvoorziening, zoals in `lokaal-testcluster.sh` (UID 999, zonder
  capabilities, door PSA `restricted` heen), met de onveilige klep
  `FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT`. Er is geen Redis-plug-in.
- **Stelselregister:** de ondertekensleutel als Secret, zoals in `lokaal-testcluster.sh`.

## Oplevering

- `charts/fundament-testcluster.sh` (naast `lokaal-testcluster.sh`) met de subcommando's `op`,
  `controleer` en `af`. Leunt op een clone van `fundament-oss/fundament` (pad via
  `FUNDAMENT_REPO`, default `~/dev/fundament`) en op de tooling uit hun `mise.toml`.
- Een korte sectie in `docs/ontwikkelen.md` en `charts/README.md`.
- Dit plan, bijgewerkt naar **Uitgevoerd** met wat er werkelijk gedraaid heeft, inclusief de
  uitkomst van `can-i create httproutes` voor een projectlid.
- Werk op branch `feature/1535-fundament-testcluster`, gestapeld op `feature/1535-helm-chart`
  (PR 444), in een eigen worktree zodat de sessie op PR 444 niet gestoord wordt. Een draft-PR met
  `feature/1535-helm-chart` als basis.

## Keuzes van de ontwerper (te controleren door de opdrachtgever)

- **Levenscyclus:** `op` start het platform (`just cluster-start`) alleen als `k3d-fundament` nog
  niet draait, en weigert als er al een `k3d-fundament-plugin` op een andere Kubernetes-versie staat
  (met de melding hoe je die opruimt). `af` ruimt alleen de sandbox en onze installatie op; het
  platform laat het staan, want dat kan voor ander Fundament-werk in gebruik zijn.
- **API-key voor `functl`:** `op` maakt hem zelf, zoals de e2e-suite van Fundament: inloggen met
  het lokale seed-account en `CreateAPIKey` aanroepen. `FUNCTL_API_KEY` gaat voor. (Eerst
  ontworpen als handmatige stap, omdat ik dacht dat alleen de console een key kon maken.)
- **Fundament-versie:** gepind op een commit van `fundament-oss/fundament` (startwaarde `b9c12531`,
  2026-11-11), overschrijfbaar via `FUNDAMENT_REF`. Het script checkt die uit in een eigen worktree
  van de clone, zodat de clone zelf niet verandert.
- **Twee rollen** in plaats van alleen cluster-admin, zodat de HTTPRoute-beperking van een
  projectlid niet pas op het fieldlab opvalt.
- **https op de Gateway met een lokale CA** in plaats van alleen HTTP, omdat de README van de
  chart een `https`-listener voorschrijft en de chart een route alleen aan een benoemde listener
  hangt.

## Aan de bouwer

- Hoe de k3s-versie van de sandbox het best te overschrijven is: een eigen k3d-config afgeleid van
  die van Fundament, of `k3d cluster create` met `--image`.
- Hoe de Gateway vanaf de host bereikbaar wordt: een portmapping op de k3d-loadbalancer, of
  `kubectl port-forward` naar de Envoy-service. Zolang het loopback blijft.
- Welke delen van `lokaal-testcluster.sh` hergebruikt worden: functies delen of kopiëren.

## Uitkomst (2026-11-12)

Gedraaid op de ontwikkelmachine, met images `main-8e9f467` en Fundament `b9c12531`:

- **Vanaf nul** (`af --met-platform`, dan `op`): groen in ruim twaalf minuten, met beide rollen.
  De sandbox draait `v1.36.6+k3s1`. Envoy Gateway, cert-manager en CloudNativePG draaien via de
  plugin-controller, gepind op hun hash. De databases draaien PostgreSQL 18.6 uit de catalogus
  van de plug-in.
- **Na `af`** (het platform blijft): `op` haalt de hash van de al goedgekeurde versies uit de
  catalogus en is weer groen.
- **Herhaald:** een tweede `op` slaat alles over wat er al staat. De huidige context van de
  gebruiker blijft staan (sinds de review maakt het script het platform zelf, zonder van context
  te wisselen).
- **Een projectlid mag geen HTTPRoute maken** (`kubectl auth can-i` geeft `no`). De uitweg uit de
  README werkt: installeren zonder route en de route apart maken. Dat lukt alleen als ook
  `cluster.gateway.name` gezet is.
- **Negatieve proef:** met het stelselregister op 0 replica's geeft `controleer` exit 1, met drie
  foutregels voor die dienst.

### Afwijkingen van dit ontwerp die het draaien opleverde

- **Plug-ins** worden gepubliceerd met `--submit` en goedgekeurd met `funops`, zoals test-env van
  Fundament: een concept ziet alleen de organisatie die het publiceerde.
- **Volgorde:** Envoy Gateway komt vóór cert-manager, anders draait cert-manager zonder
  Gateway-ondersteuning.
- **Namespace:** geen beheerlabels van Fundament. De cluster-worker van het lokale platform ziet
  de sandbox en verwijderde de namespace als "orphan".
- **Netwerk:** het Docker-netwerk van het platform staat op een instelbaar subnet. 172.19 was
  bezet.
- **Platform-uitrol:** via `skaffold run --default-repo=localhost:5111`, zoals hun `just dev`. Het
  recipe `just deploy local` pusht naar een registry-naam die de node niet kent.

### Na de review (2026-11-12)

- **Netwerk.** De recipes van Fundament zetten de registries, de database van het platform en de
  relays naar het platform op 0.0.0.0. Via de authn-relay kon iedereen op hetzelfde netwerk met het
  openbare seed-wachtwoord een beheerderstoken halen. Het script maakt platform en sandbox nu zelf
  aan, met die poorten op loopback. De relays bindt het aan het gateway-adres van het
  sandbox-netwerk. Dat adres is van de host zelf: lokale containers, en een buur die er een route
  naartoe legt, kunnen er nog bij. De README noemt dat, met wat je vóór het fieldlab moet doen. De
  Kubernetes-API's van k3d luisteren op alle adressen; anoniem geven ze 401.
- **Context.** Doordat het script het platform zelf aanmaakt, wisselt de huidige context niet
  meer. Een kale `kubectl` of `helm` in het script breekt het af.
- **Platform-herstart.** Een herstart van het platform leidt niet meer tot een nieuwe uitrol, en
  dus niet meer tot gewiste databases.
- **`af`** ruimt op wat er draait, ook zonder worktree, en laat een vreemde sandbox staan.
- **NetworkPolicy.** De controle toetst ook de weigering: een pod zonder clientlabel komt er niet
  in.

### Na de herbeoordeling van de lage punten (2026-11-12)

Een paar uitgestelde lage punten bleken bij nader inzien medium. Ze konden de belofte van de
opstelling ondermijnen: groen betekent dat het werkt.

- **NetworkPolicy-probe.** Een probe-pod die niet startte, telde als "de dienst weigert". De probe
  logt nu ook de exitcode van curl en het aantal verbindingen. Alleen een geweigerde verbinding
  (7, nul verbindingen) telt als weigering. Een timeout niet: die geeft ook een trage dienst of
  een DNS-timeout. De probe zonder label herhaalt nu ook, want tot de policy-controller een nieuwe
  pod kent, ziet een te ruime policy er net zo uit als een weigering. Een Service zonder gereede pods
  weigert met dezelfde foutcode; daarom telt de weigering alleen als naam, restarts en Ready van
  alle pods over de probe heen gelijk bleven.
- **Replica's.** De controle eiste één gereede pod. Nu moeten alle gewenste pods gereed zijn, en
  de diensten draaien standaard met twee (`REPLICAS`). Het stelselregister blijft op één: zijn
  chart-values zetten `singleReplica`, en de chart weigert dan een tweede (live gezien).
- **Uitrollen bij een API-fout.** Een mislukte `get` op het platform leidde tot een nieuwe uitrol
  over een bestaand platform heen. Nu wacht het script op de API, rolt alleen uit bij NotFound,
  en stopt bij elke andere fout.
- **Poorten op alle adressen.** Een platform dat met Fundaments eigen recipes was gemaakt, werd
  stil hergebruikt, en een gewijzigde k3d-config kon poorten op 0.0.0.0 zetten. De geconfigureerde
  poortbindingen van alle containers op beide netwerken worden nu getoetst, ook van gestopte
  (`docker inspect`, niet `docker ps`): vóór het begin en na het opzetten.
- **Beslislogica getest.** `controleer_dienst`, `voorcontrole` en `platform_op` hebben tests met
  stubs.
- **API-key de dag erna.** De key had een vaste naam. Een verlopen key houdt die naam bezet, dus
  `op` faalde na een uur altijd. Elke key krijgt nu een eigen naam, en een weigering toont het
  antwoord van het platform. Dit kwam pas aan het licht bij het live draaien van deze ronde.
- **DNS na een netwerkwissel.** Na een suspend op een ander netwerk vroegen de k3d-nodes hun DNS
  nog aan de server van het vorige netwerk. De nieuwe pods als projectlid bleven daardoor in
  ImagePullBackOff hangen, en de controle faalde na bijna een half uur. `op` toetst nu vooraf of de
  sandbox-node ghcr.io kan opzoeken, en noemt de herstart die het oplost: via `k3d cluster
  stop`/`start`. Een kale `docker restart` van de nodes herstelt de DNS wel, maar laat
  `host.k3d.internal` uit CoreDNS weg; daarna bereikt de plugin-controller het platform niet meer
  (live gezien).

### Bevindingen voor PR 444

Op 12 november doorgegeven aan het werk aan #444, samen met een vraag over `sslmode=require` (zie
hieronder).

- **Wachtwoorden met een newline.** `lokaal-testcluster.sh` maakt wachtwoorden met
  `openssl rand -hex 24 > bestand` en zet ze met `--from-file` in een Secret. Daarmee komt de
  newline in het Secret. Redis knipt die weg uit zijn argumenten, de uitvraag stuurt hem mee: de
  uitvraag wordt nooit ready (`WRONGPASS`).
- **NetworkPolicy zonder route.** Met `ingress.enabled=false` en zonder `cluster.gateway.name`
  rendert de NetworkPolicy `owning-gateway-name: ""`. Die blokkeert de proxy's van de Gateway, dus
  de route die de beheerder apart maakt geeft 503. De README-uitweg moet `cluster.gateway.name`
  noemen. Een render-fout voor deze combinatie is te overwegen.
- **`sslmode=require` controleert de server niet.** Het voorbeeld met CNPG beschrijft dit al als
  keuze. De vraag is of `verify-full` met `<cluster>-ca` op het fieldlab de standaard moet zijn: daar
  gaan BSN's via het logboek over deze verbinding.

### Pin-ophoging naar Fundament `e18ad710` (2026-11-18)

Fundament loste de meeste bevindingen van 12 november upstream op (fundament-oss/fundament#658,
#659, #660, #662, #663, #665, en #682 als opvolger van #657). De pin gaat daarom van `b9c12531` naar
`e18ad710`, zonder te wachten op fundament-oss/fundament#661 (`functl auth status`).

Wat eruit ging:
- `k3d_config_loopback` en het zelf aanmaken van netwerk en platform: hun `just cluster-create` zet
  alles op loopback, ook de Kubernetes-API, en leest `FUNDAMENT_K3D_SUBNET`. Onze variabele heet nu
  ook zo, en wordt geëxporteerd: hun Justfile leest hem uit de omgeving.
- De eigen `skaffold run`: `just deploy local` pusht nu naar `localhost:5111`, en zijn profiel
  `env-local` zet de kube-context zelf op `k3d-fundament`.
- De eigen socat-relays met een hostpoort: hun `just plugins sandbox-catalog` hangt de relays aan
  beide Docker-netwerken, zonder hostpoort. De hash van een al goedgekeurde versie komt nu van de
  route van de catalogus op het platform (`marketplace-catalog-api.fundament.localhost:8443`). Hun
  recipe start `alpine/socat` zonder tag of digest; ons eigen image was op digest gepind.
- De uitzondering voor `->6443` in de poortcontrole: een API op alle adressen is nu een cluster van
  een oudere versie.

Wat bleef, en waarom:
- **Het eigen aanmaken van de sandbox.** Hun `just plugins cluster-create` kent geen k3s-image en
  geen extra poort; wij hebben 1.36 en https op `127.0.0.1:$HTTPS_POORT` nodig. Het gebruikt nu hun
  config ongewijzigd.
- **`functl org set` als test op een sessie**, tot fundament-oss/fundament#661 er is.
- **Pinnen op de hash van de definitie.** Na #682 kan een installatie zonder hash, maar gepind ligt
  dichter bij het fieldlab.

Nieuw:
- De contexten staan in `$WORKTREE/.dev/kubeconfig`; `kc`, `kcp` en `hm` geven die mee, en de
  meldingen noemen hem ook. `~/.kube/config` blijft onaangeroerd (checksum voor en na de run gelijk).
  Hun recipes volgen in hun test-VM (`FUNDAMENT_TEST_ENV=1` of `/etc/fundament-test-env`) de
  kubeconfig van de shell; het script zet de variabele uit en weigert in die VM.
- `sandbox_van_ons` leest het k3s-image en de poortbindingen uit `docker inspect`, niet via de API.
  Daarmee is ook een gestopte sandbox te herkennen, en een die nog in de kubeconfig van de vorige
  pin staat. `op` start een gestopte sandbox met hun `just plugins cluster-start`. Zo blokkeert een
  gepauzeerde sandbox `op` en `af` niet meer.
- Een catalogus die niet of met een HTTP-fout antwoordt, meldt het script als zodanig, niet als een
  ontbrekende hash.

Review: een codereview, een review van de tests, van de foutafhandeling en van het commentaar. Er
waren twee hoge bevindingen: de omgang met `FUNDAMENT_TEST_ENV`, en tests die ook zonder de
bewaakte code slaagden (de export van het subnet, de poortcontrole na het aanmaken) of ontbraken
(`sandbox_op`, `plugin_hervatten`). Beide zijn opgelost; elke nieuwe test is met een mutatie van
het script rood gezien. De reden die eerst voor de eigen `skaffold run` stond, klopte niet.
In een tweede ronde (code en tests) geen hoge of middelzware bevindingen in de code. Wel ontbrak een
test die toetst dat `op` en `af` de weigering in de test-VM ook aanroepen; die is erbij, net als
tests voor de meldingen met de kubeconfig, de poortcontrole direct na het aanmaken van de sandbox
en een map van mkcert zonder CA. De suite zet de instellingen uit de shell van wie hem draait uit,
zodat bijvoorbeeld een eigen `HTTPS_POORT` geen verwachting verschuift. `--fail-with-body` vraagt
curl 7.76 of nieuwer; de README noemt dat.

Live, met images `main-7ab403f`:

| Situatie | Uitkomst |
|---|---|
| `af --met-platform` met het nieuwe script op een opstelling van `b9c12531` | gelukt; de oude contexten in `~/.kube/config` en de oude worktree blijven achter (README noemt de opruiming) |
| Vanaf nul, beide rollen | groen |
| Gepauzeerd met `k3d cluster stop`, dan `op` | groen; de sandbox wordt herkend en gestart, de API-poorten blijven gelijk |
| `af`, dan `op` | groen; de hashes komen uit de catalogus via de route op het platform |
| Poorten | alles op loopback; de relays zonder hostpoort |
| Huidige kube-context | blijft ongewijzigd |

### Pin-ophoging naar Fundament `b363604d` (2026-11-20)

fundament-oss/fundament#661 is gemerged (`ab868a3b`, 19 november). De pin gaat van `e18ad710` naar
`b363604d`, de nieuwste commit op `master` die `ab868a3b` bevat.

Wat er tussen de twee pins veranderde, en wat het voor het script betekent:
- **Recipes, `mise.toml`, de k3d-configs en de plek van de kubeconfig** (`.dev/`): niet gewijzigd.
  Alleen `e2e/mod.just` kreeg recipes voor belastingtests; die gebruikt het script niet.
- **Seed-accounts:** ongewijzigd. In `values-local.yaml` kwam alleen `cookieAllowedOrigins` voor de
  kube-api-proxy bij. Het secret `db-fun-operator`, dat `just funops` leest, heet nog zo.
- **`functl auth status`** (#661): eindigt met exit 0 als het de sessie kon controleren, en geeft met
  `-o json` `authenticated`, en bij `false` een `reason`: `not_logged_in` of `rejected`. Een exit
  die niet 0 is, betekent dat het niet te controleren was (platform onbereikbaar, config
  onleesbaar).
- **Plug-ins:** de definities kregen `metadata.categories`. `functl plugin publish --create` zoekt ze
  op in het register en weigert een onbekende. Dezelfde versienummers, dus na `af` gaat het verder via
  de catalogus zoals eerder.
- **Platform:** sessies eindigen een vaste tijd na het inloggen (#684); het wachtwoord-inloggen
  geeft nog steeds `access_token`. De chart vraagt Kubernetes 1.30 of nieuwer (het platform draait
  1.35), begrenst de databasepools en zet `max_connections` op 400. Migratie 063 voor API-keys.
  Geen van deze vraagt iets van het script.

Wat er in het script veranderde:
- **`functl_inloggen`** leest de sessie uit `functl auth status -o json`, alleen van stdout: mise zet
  `FUNCTL_DEBUG` aan, en de debuglog gaat naar stderr. Ingelogd: `org set` blijft, maar alleen om
  de organisatie te kiezen. `not_logged_in` of `rejected`: een nieuwe API-key en inloggen. Een exit
  die niet 0 is, of een antwoord dat het script niet kent (ook een nieuwe `reason`): stoppen met de
  melding van functl. Een nieuwe key lost een onbereikbaar platform niet op en verbergt de oorzaak.
- **`functl_fout_soort`** kent `niet-ingelogd` niet meer; die tak was alleen voor de omweg. De
  soorten `geen-listing` en `bestaat-al` blijven nodig voor het publiceren.
- **Instellingen van functl uit de shell** (`FUNDAMENT_API_KEY`, `FUNCTL_API_ENDPOINT`,
  `FUNCTL_AUTHN_URL`, `FUNCTL_REGISTRY_URL`) zet het script uit. functl leest ze vóór zijn
  configuratie in `FUNCTL_CONFIG_DIR`, dus een eigen key voor een echt platform zou
  anders de sessie van de opstelling vervangen, en een geweigerde key in de omgeving bleef ook na
  een nieuwe login gelden. Dat speelde al vóór deze ophoging, maar `auth status` maakte het
  zichtbaar: het controleert de key uit de omgeving.
- **Na een herstart van de machine.** Op 20 november bleven de pods na een herstart 25 uur in
  ImagePullBackOff (`lookup ghcr.io: Try again`), tot `k3d cluster stop`/`start` en een nieuwe
  `op`. Docker start de containers dan zelf (restart-policy `unless-stopped`), en de sandbox-node
  kon ghcr.io daarna niet opzoeken. Waarom precies, is niet vastgesteld; waarschijnlijk legde de
  node bij het booten een DNS-server vast die nog niet werkte, zoals na een wissel van netwerk. De
  herstart via k3d lost het op. Het slotbericht zei "na een herstart van de machine start hij
  vanzelf weer"; nu zegt het dat je dan beide clusters via k3d herstart en `op` draait. De README
  behandelt herstart en netwerkwissel nu in één alinea, met de map van de worktree in de blokken.
  `controleer` toetst ook of de sandbox-node ghcr.io kan opzoeken, met dezelfde remedie. Anders
  toont een kapotte DNS zich daar pas als een rollout die na vijf minuten niet klaar is, zonder
  oorzaak; of, na een netwerkwissel zonder herstart, helemaal niet zolang de pods blijven draaien.

Wat bleef: het eigen aanmaken van de sandbox (hun recipe kent nog geen k3s-image of extra poort),
en het pinnen op de hash van de definitie.
