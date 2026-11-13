# Helm-chart voor de berichtendiensten

`fbs-dienst` installeert één dienst van het Federatief Berichtenstelsel op Kubernetes:
berichtenuitvraag, berichtenmagazijn of stelselregister. De chart is gemaakt voor
[Fundament](https://docs.fundament-poc.nl/docs/), dat routeert met Envoy Gateway en HTTPRoutes,
maar werkt op elk cluster met Gateway API of een Ingress-controller. Het ontwerp staat in
[`docs/plans/2026-11-11-helm-chart-fbs-dienst.md`](../docs/plans/2026-11-11-helm-chart-fbs-dienst.md).

| Pad | Inhoud |
|---|---|
| `fbs-dienst/values.yaml` | standaarden voor elke dienst |
| `fbs-dienst/diensten/<dienst>.yaml` | image, poort, uitgaand verkeer en vaste instellingen per dienst |
| `fbs-dienst/ci/` | scenario's die `controleer.sh` doorloopt |
| `fbs-dienst/charts/common/` | de common-chart van mijn-bureau-infra, ongewijzigd (zie `HERKOMST.md`) |
| `policy/` | beleidsregels voor conftest: overgenomen van mijn-bureau-infra, plus eigen regels met tests |
| `controleer.sh` | controle zonder cluster |
| `lokaal-testcluster.sh` | de drie diensten op een k3d-cluster achter Envoy Gateway |
| `fundament-testcluster.sh` | de drie diensten op een sandbox van Fundament, met de echte plug-ins |
| `voorbeelden/` | values voor een opzet die niet bij elke installatie hoort, zoals een database uit CloudNativePG |

## Installeren

Geef per dienst het values-bestand uit `diensten/` mee, een image-tag (`main-<sha7>` of
`pr-<n>-<sha7>`, bij een herdraai of ready-for-review met `-r<n>`/`-k<n>` erachter, zoals de keten
ze publiceert; anders een digest), de plek waar TLS eindigt, en wat per omgeving verschilt:

```bash
helm upgrade --install berichtenmagazijn charts/fbs-dienst --namespace fbs \
  -f charts/fbs-dienst/diensten/berichtenmagazijn.yaml \
  -f mijn-omgeving/berichtenmagazijn.yaml \
  --set dienst.image.tag=main-8e9f467
```

met in `mijn-omgeving/berichtenmagazijn.yaml` bijvoorbeeld:

```yaml
dienst:
  tlsTermination: mesh       # zie hieronder
  env:
    DB_JDBC_URL: jdbc:postgresql://db.example.nl:5432/magazijn?sslmode=require
    DB_USERNAME: magazijn
    DB_SCHEMA: magazijn
    MAGAZIJN_OIN: "00000001003214345000"
    # ...
  extraEnvVarsSecret: berichtenmagazijn-geheimen   # DB_PASSWORD, LDV_POSTGRES_PASSWORD

cluster:
  gateway:
    name: fundament-gateway
    namespace: gateway-system

ingress:
  enabled: true
  allowUnauthenticated: true # de API authenticeert niet; zie hieronder
  hostname: magazijn.organisatie.example.nl
```

Welke instellingen een dienst nodig heeft, staat bovenin zijn bestand onder `diensten/` en in de
operatorhandleiding van die dienst. Waarden in `dienst.env` mogen templates bevatten, zoals
`{{ .Release.Namespace }}`.

**`dienst.tlsTermination` is een bewuste keuze, zonder standaard.** De chart ondersteunt alleen
`mesh`: TLS eindigt vóór de dienst, en het laatste stuk van de Gateway naar de pod loopt als
platte HTTP. Kies dat alleen als het pod-netwerk dat stuk afschermt: over die verbinding gaan het
BSN en de inhoud van berichten. TLS in de dienst zelf (`app`) vraagt een https-poort, probes op
HTTPS en TLS vanaf de Gateway; de render weigert die keuze.

**Geheimen staan nooit in values.**
- Wachtwoorden komen uit een bestaand Secret (`dienst.extraEnvVarsSecret`).
- Het stelselregister leest zijn ondertekensleutel uit het Secret `<fullname>-keystore`, data-key
  `keystore.p12`.

**Wat TLS kan uitzetten, weigert de render.** De namen in `dienst.env` en `dienst.extraEnvVars`
worden getoetst in elke schrijfwijze die Quarkus leest, ook met een profielvoorvoegsel als
`_PROD_`.
- De `FBS_*_UNSAFE_*`-kleppen alleen met `dienst.allowUnsafeFlags=true`.
- Altijd geweigerd, ook met `allowUnsafeFlags`:
  - een ander Quarkus-profiel of config-bestand: `QUARKUS_PROFILE` en alles wat begint met
    `QUARKUS_CONFIG_` of `SMALLRYE_CONFIG_`, zoals de profile-parents en `*_CONFIG_LOCATIONS`;
  - alles wat begint met `GC_`, `JAVA_`, `JDK_`, `_JAVA_` of `JBOSS_`: het image start via
    `run-java.sh`, dat die op de java-opdrachtregel zet of er scripts uit laadt, en daar past ook
    `-Dquarkus.profile` in;
  - een eigen `dienst.command` of `dienst.args`;
  - `HTTP_TLS_TERMINATION` en `QUARKUS_HTTP_PORT`: die zet de chart zelf.

  In het profiel `dev` of `test` vervalt elke TLS-eis, zonder alert-token.
- Deze controles vangen vergissingen, geen opzet. Wat in `extraEnvVarsCM`, `extraEnvVarsSecret`,
  een gemount config-bestand, een sidecar of `extraDeploy` staat, ziet de chart niet: wie de
  values beheert, kan de TLS-eisen altijd omzeilen.

**De API's authenticeren hun aanroepers niet.** Wie de route bereikt, bereikt de API. Een route
vraagt daarom `ingress.allowUnauthenticated=true`; de chart beperkt de toegang zelf niet. Beperk
de toegang dan in de Gateway of in het netwerk ervoor. Het
stelselregister zet de schakelaar zelf, want het stelseldocument is openbaar. Voor uitvraag en
magazijn is hij in de praktijk nodig zodra ze elkaar aanroepen: de uitvraag eist een https-adres
voor een magazijn, en binnen het cluster spreekt de pod alleen HTTP, dus dat verkeer loopt via de
Gateway. Aanroepers binnen het cluster komen binnen met het podlabel
`client.fbs.minbzk.nl/<fullname>: "true"`, één per dienst die ze aanroepen.

## Op Fundament

Zo ziet een cluster van het fieldlab eruit (Kubernetes 1.36, Envoy Gateway 1.9 met Gateway API
1.6), en wat dat van de installatie vraagt:

- **Namespace.** Maak de namespace in de console; hij heet dan `tnt-<project>--<naam>`. Installeer
  zonder `--create-namespace`: een namespace die je met kubectl of Helm maakt, geeft projectleden
  geen rechten.
- **Rechten.** Projectbeheerders hebben in hun namespace de ClusterRole `admin`. De chart maakt
  alleen objecten binnen de namespace, en een beleidsregel weigert andere soorten, ook via
  `extraDeploy`. HTTPRoute hoort niet bij de standaardrol `admin` van Kubernetes; het fieldlab
  maakt routes wel met `kubectl apply`, maar dat het met jouw rol mag, controleer je met
  `kubectl auth can-i create httproutes -n <namespace>`. Een projectlid met alleen `admin` krijgt
  daar `no`. Installeer dan met `ingress.enabled=false`, maar mét `cluster.gateway.name` (en
  `cluster.gateway.namespace` als de Gateway elders staat): daarop laat de NetworkPolicy de
  proxy's van de Gateway binnen. Zonder die naam geeft de Gateway een 503; NOTES.txt waarschuwt
  dan. Een beheerder maakt de route met dezelfde values, zodat de render hem eerst toetst:
  `helm template <release> charts/fbs-dienst -f ... --set ingress.enabled=true
  --show-only templates/httproute.yaml | kubectl apply -f -`.
- **Gateway.** Fundament installeert Envoy Gateway (GatewayClass `eg`) maar maakt geen Gateway.
  Maak die zelf in de eigen namespace, bijvoorbeeld `web` met een listener `https` op 443 en een
  certificaat via cert-manager, en zet `cluster.gateway.name=web` en
  `cluster.gateway.sectionName=https`. `cluster.gateway.namespace` mag leeg blijven. De proxy's
  draaien in `envoy-gateway-system`; daar staat de NetworkPolicy al op ingesteld.
- **Hostnaam.** De Gateway krijgt een publiek IPv4-adres, maar Fundament maakt geen DNS. Volgorde:
  maak eerst de Gateway, wacht op het adres (`kubectl get gateway web`), en installeer dan de chart
  met als `ingress.hostname` een eigen A-record of `<ip-met-streepjes>.sslip.io`. Die hostnaam moet
  passen bij de `hostname` van de https-listener. Een route zonder hostnaam weigert de chart: die
  zou al het verkeer op de Gateway vangen.
- **Pod Security.** Op de namespaces van plug-ins dwingt Fundament `restricted` af. De pod van de
  chart haalt dat, en de beleidsregels toetsen het: `allowPrivilegeEscalation: false`, geen
  `hostPort`, alleen de volumesoorten die `restricted` toelaat.
- **Resources.** Een beheerde LimitRange `fundament-defaults` zet alleen standaardwaarden voor
  containers zonder eigen requests en limits. De chart zet ze zelf, dus die botsen niet.
- **Database.** PostgreSQL komt uit CloudNativePG, met een Secret `<cluster>-app`.
  [`voorbeelden/berichtenmagazijn-cnpg.yaml`](voorbeelden/berichtenmagazijn-cnpg.yaml) laat zien
  hoe het magazijn en zijn logboek daaruit verbinden, met `sslmode=verify-full` tegen de CA uit
  `<cluster>-ca`: via het logboek gaan BSN's over die verbinding, en `require` controleert niet
  welke server antwoordt.

## Wat de chart standaard afdwingt

- **Pod en container:**
  - UID 185 en `runAsNonRoot`.
  - Een read-only root-filesystem, met een `emptyDir` op `/tmp`.
  - Geen capabilities, seccomp `RuntimeDefault`, geen service-account-token.
  - Dezelfde regels gelden voor initContainers.
- **Probes en resources:** liveness, readiness en startup op `/q/health/*`; requests en limits op
  cpu, geheugen en ephemeral-storage.
- **Een route die alleen `/api/v1`, `/openapi.json` en `/.well-known` doorlaat**, met padtype
  `PathPrefix` of `Exact`. `/q` (health, Swagger UI) blijft binnen het cluster; een health-antwoord
  noemt bij een storing interne hostnamen.
- **Timeouts in de HTTPRoute.** Envoy breekt een antwoord standaard na 15 seconden af. Uitvraag en
  magazijn zetten daarom `ingress.requestTimeout: 5m`, voor een bijlage tot 25 MiB. De SSE-paden van
  de uitvraag krijgen een eigen regel zonder request-timeout. Bij `routingMode=ingress` gelden deze
  instellingen niet: daar regelt de Ingress-controller de timeouts, en de render weigert
  `streamPaths` en `requestTimeout`.
- **Een route alleen aan een benoemde listener** (`cluster.gateway.sectionName`). Zonder hangt een
  HTTPRoute aan elke listener van de Gateway, ook aan een zonder TLS.
- **Een NetworkPolicy, inkomend.** Alleen vanaf de proxy's van de eigen Gateway (in
  `envoy-gateway-system`, instelbaar met `networkPolicy.ingressNSMatchLabels`) en vanaf pods met
  het label `client.fbs.minbzk.nl/<fullname>: "true"`.
- **Een NetworkPolicy, uitgaand.** Alleen:
  - DNS op 53 en 8053; op Gardener luistert CoreDNS op 8053;
  - de poorten in `networkPolicy.allowedEgressPorts`, naar elke bestemming;
  - bij uitvraag en magazijn de proxy's van Envoy Gateway op 10443, voor een https-adres van een
    dienst in hetzelfde cluster. Envoy laat een listener op 443 op 10443 luisteren.
- **Een PodDisruptionBudget.**
- **Het stelselregister draait met precies één replica, en rolt uit met `Recreate`.** Twee
  instanties ondertekenen elk op hun eigen moment, en een afnemer weigert dan het oudere document.
- **Geen HSTS- of andere security-headers in de route:** die zet de dienst zelf.

Een render met een onmogelijke combinatie faalt met een melding. Voorbeelden:
- geen tag, of een meebewegende tag als `latest` of `main`;
- geen keuze voor TLS, of `app`;
- een route zonder `allowUnauthenticated`, hostnaam, Gateway of listener, met een ander padtype,
  of met `/q`;
- een lege `networkPolicy.dnsPorts` of `gatewayEgress.ports`: een egress-regel zonder poorten laat
  elke poort toe;
- bij een route een lege labelwaarde in `networkPolicy.ingressNSPodMatchLabels`. Bij
  `routingMode=ingress` geldt dat ook voor de standaard, die naar de Gateway kijkt: zet daar de
  labels van de controller-pods, of null;
- een Ingress zonder TLS;
- een onveilige klep zonder `allowUnsafeFlags`, of een ander Quarkus-profiel, of een eigen
  `command`;
- het stelselregister met een ander aantal replica's dan één, of met een HPA.

## Controleren zonder cluster

```bash
charts/controleer.sh
```

Per dienst en scenario draait het script:
- `helm lint --strict`;
- `helm template`, met de uitvoer door `kubeconform` (Kubernetes plus de Gateway-API-schema's,
  beide gepind) en door de beleidsregels in `policy/`.

Daarnaast draaien `conftest verify` voor de eigen regels, de bijzondere values en de renders die
moeten falen. Het script vereist `helm`, `conftest` en `kubeconform`; de versies die de CI
gebruikt staan in `.github/workflows/ci-scripts.yml`. De CI draait het script op elke PR.

## Proef op een lokaal cluster

```bash
TAG=main-<sha7> charts/lokaal-testcluster.sh
charts/lokaal-testcluster.sh opruimen
```

Het script:
- zet een k3d-cluster op met Envoy Gateway, met PostgreSQL en Redis Stack als
  proefvoorzieningen;
- installeert de drie diensten;
- controleert per dienst dat de rollout slaagt, dat `/openapi.json` via de Gateway bereikbaar is
  en dat `/q` dat niet is.

Elke aanroep noemt de context van het proefcluster, en het script wisselt de huidige context niet.
Het vereist `k3d`, `kubectl`, `helm`, `openssl` en `curl`, en een host met Docker of podman: in een
container zonder `/dev/fuse` en `/lib/modules` start k3d niet.

## Proef op een Fundament-sandbox

Dichter bij het fieldlab dan de k3d-proef: het lokale platform van Fundament met zijn
plugin-sandbox als cluster, op Kubernetes 1.36. Envoy Gateway, cert-manager en CloudNativePG komen
via de echte plugin-controller, zoals op het fieldlab. Het ontwerp staat in
[`docs/plans/2026-11-11-lokaal-fundament-testcluster.md`](../docs/plans/2026-11-11-lokaal-fundament-testcluster.md).

```bash
TAG=main-<sha7> charts/fundament-testcluster.sh op       # opzetten, installeren, controleren
TAG=main-<sha7> charts/fundament-testcluster.sh op --rol projectlid
charts/fundament-testcluster.sh controleer                # alleen controleren
charts/fundament-testcluster.sh af                        # sandbox weg; het platform blijft
charts/fundament-testcluster.sh af --met-platform         # ook het platform
```

`op` doet:
- **Fundament** op een vaste commit (`FUNDAMENT_REF`, een volledige SHA) in een eigen worktree
  van een clone (`FUNDAMENT_REPO`, standaard `~/dev/fundament`), met de tools uit hun
  `mise.toml`. Het voert hun code en recipes uit, met de rechten van Docker;
- **het platform** (`k3d-fundament`), alleen uitgerold als Kubernetes meldt dat het er niet is:
  elke uitrol wist zijn databases. Antwoordt de API niet, dan stopt het script. De eerste keer
  bouwt dit alle images van Fundament;
- **een API-key** voor `functl`, met het lokale seed-account, zoals hun e2e-suite;
- **de sandbox** (`k3d-fundament-plugin`) op k3s 1.36, met https op `127.0.0.1:$HTTPS_POORT`, en
  de relays van de sandbox naar het platform;
- **de plug-ins**: publiceren, goedkeuren en installeren;
- **in `tnt-fbs--fbs`**: Gateway `web` met een https-listener en een certificaat van cert-manager
  uit een lokale CA, twee databases uit CloudNativePG (PostgreSQL 18, TLS) en Redis Stack;
- **de diensten** in twee rollen, elk gevolgd door de controle:
  - als **organisatiebeheerder** (cluster-admin), met route;
  - als **projectlid** (alleen de rol `admin` in de namespace). Die mag geen HTTPRoute maken, dus
    installeert het script zonder route en maakt de beheerder de route apart. Zet ook dan
    `cluster.gateway.name`: zonder laat de NetworkPolicy de proxy's van de Gateway niet binnen.

De controle eist per dienst:
- een rollout waarin alle pods gereed zijn, standaard twee (`REPLICAS`), voor het stelselregister één;
- binnen het cluster `/q/health/ready` 200 vanaf een pod mét het clientlabel, en geen verbinding
  vanaf een pod zonder. Beide probes herhalen ruim een halve minuut, want de policy-controller
  kent een nieuwe pod pas na een vertraging. De weigering telt alleen als curl de verbinding
  geweigerd kreeg (exitcode 7, zonder TCP-verbinding) en alle pods de hele probe gereed bleven:
  een Service zonder gereede pods weigert op dezelfde manier. Een timeout, een probe-pod die niet start,
  of een naam die niet gevonden wordt, is een fout. Een netwerklaag die verkeer stil laat vallen in
  plaats van te weigeren, geeft dus ook een fout;
- via de Gateway, over https met het certificaat getoetst, `/openapi.json` 200 en `/q` 404.

Waarin het afwijkt van het fieldlab:
- opslag via local-path in plaats van Ceph;
- een lokale CA in plaats van Let's Encrypt. Let's Encrypt met HTTP-01 vraagt op het fieldlab ook
  een `http`-listener op 80 voor de challenge; houd de route dan met `sectionName=https` aan de
  https-listener;
- PostgreSQL 18 via een eigen `Cluster`-manifest. De console van de plug-in biedt 16 en 17;
- de NetworkPolicy wordt hier afgedwongen door k3s, op het fieldlab door een andere netwerklaag.
  Toets de weigering daar opnieuw;
- de namespace draagt Pod Security `restricted`, zoals een namespace van een plug-in, en mist
  de beheerlabels van Fundament: de cluster-worker van het lokale platform ziet de sandbox, en
  verwijdert een namespace met die labels die hij niet kent.

Wat hier kan omdat de routes alleen op loopback staan, maar op het fieldlab een keuze vraagt:
- **`ingress.allowUnauthenticated=true`.** Op het fieldlab krijgt de Gateway een publiek adres.
  Beperk de toegang in de Gateway, of zet er bewust alleen proefgegevens achter;
- **`dienst.tlsTermination=mesh`.** Ga na of het pod-netwerk van het fieldlab het verkeer tussen
  Gateway en pod versleutelt voordat er echte berichten overheen gaan;
- **Redis zonder TLS**, met de klep `FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT`. Er is geen Redis-plug-in.
  Op het fieldlab hoort het `rediss://` met wachtwoord te zijn, of de klep bewust, met het alert
  `REDIS_UNPROTECTED`.

Wat er op de machine openstaat:
- **op loopback:** de https-poort, de console (8443), de registries (5111, 5112) en de database
  van het platform (54328);
- **op het host-adres van de sandbox-bridge** (bijvoorbeeld `172.25.0.1`): de relays naar de
  catalogus (38080) en de inlog van het platform (38081). Dat adres is van de host zelf. Elke
  container op de machine kan erbij, en een buur op hetzelfde netwerk die er een route naartoe
  legt ook. Via 38081 haalt hij met het openbare seed-wachtwoord een beheerderstoken voor het
  lokale platform. Fundament's eigen `just plugins sandbox-catalog` zet de relays zelfs op
  0.0.0.0; het script gebruikt die recipe daarom niet;
- **op alle adressen:** de Kubernetes-API's van beide k3d-clusters. Anoniem geven die 401, maar
  ze accepteren wel tokens.

Publiceert een andere container van de opstelling een poort op alle adressen, ook als hij gestopt
is, dan weigert `op`. Dat gebeurt vóórdat het iets aanmaakt of start, en nog een keer na het
opzetten van platform en sandbox. Zo valt een platform op dat met Fundaments eigen recipes is
gemaakt, of een k3d-config waarvan het script de poorten niet heeft kunnen herschrijven. Een
weigering na het opzetten laat staan wat er al draait. Ruim het dan meteen op met
`af --met-platform`.

De containers starten na een herstart van de machine vanzelf weer.

**Na een wissel van netwerk**, zoals op het fieldlab, vraagt een k3d-node zijn DNS nog aan de
server van het vorige netwerk. Docker legt die vast bij het starten van de container. Nieuwe pods
blijven dan in ImagePullBackOff hangen. `op` controleert dat vooraf. Herstel het door beide
clusters via k3d te herstarten, in de worktree van Fundament (de melding noemt het pad):

```bash
mise exec -- k3d cluster stop fundament-plugin fundament
mise exec -- k3d cluster start fundament fundament-plugin
```

Niet met `docker restart`: alleen `k3d cluster start` zet `host.k3d.internal` terug in CoreDNS, en
zonder die naam bereikt de plugin-controller het platform niet meer.

**Vóór je de laptop op een gedeeld netwerk zet**, zoals op het fieldlab: ruim de opstelling op
met `charts/fundament-testcluster.sh af --met-platform`. Wil je hem houden, stop dan op zijn minst
de clusters en de relays:

```bash
docker stop $(docker ps -q --filter name=k3d-fundament) fun-catalog-relay fun-authn-relay
```

Het lokale platform installeert bij het aanmaken de mkcert-CA in de trust-stores van de machine
en van de browsers (`mkcert -install`, met `certutil`). De private sleutel van die CA staat
daarna ook in een Secret in het platformcluster. Wie die sleutel heeft, kan elke https-verbinding
van deze machine onderscheppen. De pin op `FUNDAMENT_REF` dekt alleen de broncode van Fundament:
hun builds halen base-images en Helm-charts op tag op.

Het vereist Docker met minstens 16 GiB, `mise`, `git`, `kubectl`, `helm`, `jq`, `curl`, `ss`,
`openssl` en `mkcert` met een geïnstalleerde CA. Instelbaar:
- `HTTPS_POORT` (standaard 9443);
- `REPLICAS` (standaard 2): pods per dienst. Twee toetst ook wat één pod verbergt: een tweede pod
  die niet start, en twee pods die tegelijk migreren. Het stelselregister blijft op één: zijn
  chart-values zetten `singleReplica`. Spreiding over nodes en de PodDisruptionBudget toetst de
  proef niet; de sandbox heeft één node;
- `FUNDAMENT_SUBNET` (standaard `172.29.0.0/16`): Fundament neemt anders 172.19, dat op een
  machine met meer compose-projecten vaak bezet is;
- `FUNCTL_API_KEY`: een eigen key in plaats van een gemaakte. Zet hem met `read -rs
  FUNCTL_API_KEY && export FUNCTL_API_KEY`, niet inline: dan belandt hij niet in de
  shellgeschiedenis.

Ook dit script noemt bij elke aanroep de context, en het wisselt de huidige context niet. Een
kale `kubectl` of `helm` in het script breekt het af.
