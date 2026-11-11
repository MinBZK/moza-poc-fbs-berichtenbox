# Helm-chart voor de berichtendiensten (fieldlab Fundament)

**Status:** Concept: de chart en de controle zonder cluster zijn klaar, de clusterproef
(`charts/lokaal-testcluster.sh`) heeft nog niet gedraaid.

Issue: MinBZK/MijnOverheidZakelijk#1535, onder feature #1468 (Fieldlab Soevereine basis).

## Context

Op het fieldlab (23-25 november 2026) gaan de berichtendiensten op een cluster van Fundament.
Fundament installeert software met Helm (`helm upgrade --install` vanuit de Go-SDK van een
plug-in), en routeert naar buiten met Envoy Gateway en HTTPRoutes. Onze diensten draaien nu op ZAD;
die inrichting (Operations Manager, componenten met env) is op Fundament niet bruikbaar.

De vraag is één chart voor berichtenuitvraag, berichtenmagazijn en stelselregister, elk met eigen
instellingen, die zonder cluster te controleren is en die de plug-in "berichtenmagazijn" (#1538)
kan hergebruiken.

## Structuur

```
charts/
  fbs-dienst/                 de generieke chart
    Chart.yaml
    values.yaml               veilige standaarden, geen dienst-specifieke waarden
    diensten/<dienst>.yaml    image, poort, egress en vaste env per dienst
    ci/<scenario>-values.yaml scenario's voor de controle (en voor chart-testing)
    charts/common/            gevendorde common-chart 2.31.1 van mijn-bureau-infra
    templates/
  policy/                     rego-regels voor conftest: overgenomen van mijn-bureau + eigen
  controleer.sh               controle zonder cluster
  lokaal-testcluster.sh       live proef op een k3d-cluster op de host
```

## Ontwerpkeuzes

- **Eén generieke chart, values per dienst.** De drie diensten zijn gelijk van vorm: één
  Quarkus-container, health op `/q/health/*`, geen eigen opslag. Wat verschilt (image, poort,
  uitgaand verkeer, het aantal replica's) staat in `diensten/<dienst>.yaml`. Die bestanden zitten
  in de chart, zodat een plug-in ze met de chart meekrijgt.
- **Startpunt het template van mijn-bureau-infra**, afgeslankt: daemonset, statefulset, pvc, role,
  clusterrolebinding, vpa, servicemonitor (de diensten hebben geen metrics-endpoint),
  tls-secret, configmap, secret en de volume-permissions-initcontainer zijn weg. Erbij:
  `httproute.yaml`, naar het voorbeeld van de app-charts van mijn-bureau.
- **De `common`-chart gevendord, uitgepakt onder `charts/fbs-dienst/charts/common/`.** De
  mijn-bureau-versie is een fork van Bitnami common met eigen helpers (HTTPRoute, DNS-egress) en
  staat niet in een OCI-registry. Uitgepakt in `charts/` werkt de chart zonder
  `helm dependency build`, ook als een plug-in hem vanaf een pad installeert.
- **Geen geheimen in values.** Wachtwoorden en de ondertekensleutel komen uit een bestaand Secret
  (`extraEnvVarsSecret`, `dienst.keystore`). Een chart die geheimen uit values in een Secret zet,
  nodigt uit tot wachtwoorden in een values-bestand in git. De keystore heeft een eigen value en
  hangt niet aan `extraVolumes`: Helm vervangt lijsten, dus een eigen `extraVolumes` zou hem stil
  laten wegvallen.
- **Image-tag verplicht en vast.** De keten publiceert `main-<sha7>` en `pr-<n>-<sha7>`, bij een
  herdraai of ready-for-review met `-r<n>`/`-k<n>` erachter. Een standaardtag zou stil verouderen,
  en een meebewegende tag laat een herinstallatie een ander image draaien. De render weigert daarom
  elke andere tag; een digest mag altijd.
- **TLS-terminatie verplicht te kiezen, en alleen `mesh`.** De diensten weigeren te starten zonder
  TLS, tenzij `HTTP_TLS_TERMINATION=mesh`. Fundament heeft geen mesh: met `mesh` gaat het stuk van
  de Gateway naar de pod onversleuteld, met het BSN erin. Een standaard zou die keuze voor de
  operator maken; `dienst.tlsTermination` heeft er daarom geen. TLS in de dienst zelf (`app`)
  vraagt een https-poort, probes op HTTPS en TLS vanaf de Gateway; die levert de chart niet, en
  de render weigert `app` in plaats van een pod op te leveren die nooit gereed wordt.
- **Vergissingen die TLS uitzetten, weigert de render.** De `FBS_*_UNSAFE_*`-kleppen alleen met
  `dienst.allowUnsafeFlags`. Altijd geweigerd: een ander Quarkus-profiel of config-bestand
  (`QUARKUS_PROFILE`, `SMALLRYE_CONFIG_PROFILE`, de profile-parents, `*_CONFIG_LOCATIONS`), de
  JVM-opties die dat kunnen zetten, en een eigen `command`/`args`. In het profiel `dev` of `test`
  vervalt elke TLS-eis, zonder alert-token. De namen worden genormaliseerd zoals SmallRye ze leest,
  en `extraEnvVars` telt mee na de render.
- **De chart vangt vergissingen, geen opzet.** Wie de values beheert, kan een config-bestand mounten
  of via `extraEnvVarsCM` een profiel zetten; dat ziet de chart niet. Sluitend wordt het pas als de
  diensten de TLS-eis aan de launch mode koppelen in plaats van aan de profielnaam, zoals het
  stelselregister dat al doet voor zijn wegwerpketen. Dat raakt `fbs-common`, niet de chart.
- **Een route vraagt een expliciete schakelaar.** De API's authenticeren hun aanroepers niet; wie
  de route bereikt, leest met een willekeurig BSN in `X-Ontvanger` berichten. `ingress.enabled`
  vraagt daarom `ingress.allowUnauthenticated=true`. Alleen het stelselregister zet die zelf: het
  stelseldocument is openbaar en ondertekend. Voor uitvraag en magazijn is de schakelaar in de
  praktijk altijd nodig zodra ze elkaar aanroepen, want dat verkeer loopt over https via de
  Gateway. Een toegangsbeperking in de chart zelf (een `SecurityPolicy` van Envoy Gateway) is een
  open ontwerpkeuze.
- **Alleen de API naar buiten.** De route laat `/api/v1`, `/openapi.json` en `/.well-known` door,
  niet `/q`, en alleen met padtype `PathPrefix` of `Exact`: een reguliere expressie zou die
  uitsluiting omzeilen. De health-checks van Quarkus zetten bij een storing de foutmelding van de
  database of Redis in het antwoord, met interne hostnamen en poorten.
- **Timeouts in de HTTPRoute.** Envoy breekt een antwoord standaard na 15 seconden af, tot het hele
  antwoord binnen is. Uitvraag en magazijn zetten `ingress.requestTimeout: 5m` voor bijlagen tot
  25 MiB. De stream-paden van de uitvraag (`_ophalen`, `_volgen`) krijgen een eigen regel met
  `timeouts.request: 0s`; een Exact-match gaat in Gateway API vóór de PathPrefix. Bij een Ingress
  regelt de controller de timeouts, en weigert de render `streamPaths`.
- **Beveiliging volgens de mijn-bureau-standaard:** UID 185 (de gebruiker van het
  UBI-openjdk-image), `runAsNonRoot`, read-only root-filesystem met een `emptyDir` op `/tmp`,
  alle capabilities weg, `RuntimeDefault`-seccomp, geen service-account-token, eigen
  service-account, `imagePullPolicy: Always`, requests en limits op cpu, geheugen en
  ephemeral-storage.
- **Netwerkafscherming standaard dicht.**
  - Inkomend alleen vanaf de proxy's van de eigen Gateway en vanaf pods met het label
    `client.fbs.minbzk.nl/<fullname>: "true"`. Eén sleutel per doel, zodat een pod meerdere
    diensten kan aanroepen; de fullname is ten hoogste 63 tekens, de grens voor het naamdeel van
    een labelsleutel.
  - Uitgaand alleen DNS plus de poorten die de dienst nodig heeft (443, 5432, 6379), per dienst. Het
    stelselregister maakt geen uitgaande verbindingen en krijgt alleen DNS.
  - Een NetworkPolicy ziet de poort ná de vertaling door de Service. Daarom staat DNS ook op 8053
    (CoreDNS op Gardener), en mogen uitvraag en magazijn naar de proxy's van Envoy Gateway op 10443
    (Envoy Gateway telt 10000 op bij een listener onder 1024). Die egress heeft eigen selectors: de
    bestemming kan achter een andere Gateway staan dan de eigen route.
  - Bestemmingen per IP zijn op Fundament nog niet bekend (MinBZK/MijnOverheidZakelijk#1536). De
    poortbeperking is de afscherming die nu kan; wie de bestemmingen kent, beschrijft ze met `to`
    in `extraEgress`.
- **Precies één replica als invariant van de chart.** Het stelselregister ondertekent per instantie
  op een eigen moment; twee instanties leveren afnemers afwisselend een ouder document, dat ze
  weigeren. `dienst.singleReplica: true` laat de render falen bij een ander aantal replica's dan
  één of een HPA, en zet de uitrolstrategie op `Recreate`, zodat er ook tijdens een uitrol geen
  twee draaien.
- **Afwijkingen van Haven.** Haven vraagt een metrics-endpoint en schaalbaarheid naar meerdere
  replica's. De diensten hebben geen metrics-endpoint (daarom geen ServiceMonitor), en het
  stelselregister draait bewust met één replica.
- **HTTPRoute met instelbare `sectionName`.** De helper van mijn-bureau zet die vast op `https`;
  de listener-naam van de Gateway op Fundament is nog niet bekend.
- **Afhankelijkheden buiten de chart.** PostgreSQL, Redis met RediSearch en het logboek horen bij
  MinBZK/MijnOverheidZakelijk#1536. De chart verwijst er alleen naar via env.

## Controle zonder cluster

`charts/controleer.sh` draait per scenario in `ci/`:

1. `helm lint --strict`;
2. `helm template`, met de uitvoer door `kubeconform -strict` (Kubernetes-schema's en de
   Gateway-API-schema's uit de CRDs-catalog, beide gepind op een commit);
3. `conftest test` met de regels uit `charts/policy/`: die van mijn-bureau (containers, image,
   labels, namespace), eigen regels per manifest (probes, afscherming, route, één replica) en
   regels over de samenhang van een release (Service naar pods, route naar Service, HPA naar
   Deployment, NetworkPolicy en PDB per Deployment). Een run waarin geen enkele regel draait,
   telt als fout;
4. `conftest verify` voor de eigen regels, met een ondergrens op het aantal tests;
5. bijzondere values (HPA, PDB-grenzen, geen inkomende bronnen, twee magazijnen naast elkaar,
   een eigen `extraVolumes` bij het stelselregister) en de renders die moeten falen.

Het totaal heeft een ondergrens, zodat een geschrapte controle opvalt.

`ci-scripts.yml` draait het script (helm, conftest en kubeconform gepind op versie en sha256).
`charts/` gaat in `NIET_CODE` van de wijzigingsfilter: een chartwijziging raakt geen Maven-build en
geen image, en hoort dus geen testrun en geen preview te kopen.

## Verificatie

- `charts/controleer.sh` lokaal groen; elke eigen regel gemuteerd en de vangst bevestigd.
- Live: de images met dezelfde securityContext (read-only, UID 185, geen capabilities) onder
  podman tegen PostgreSQL en Redis Stack. Een k3d-cluster draait niet in de ontwikkelcontainer
  (geen `/dev/fuse`, geen `/lib/modules`); `charts/lokaal-testcluster.sh` doet de clusterproef op
  de host: rollout per dienst, `/openapi.json` via de Gateway bereikbaar, `/q` niet.
