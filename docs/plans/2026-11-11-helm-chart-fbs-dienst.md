# Helm-chart voor de berichtendiensten (fieldlab Fundament)

**Status:** Uitgevoerd

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
  (`extraEnvVarsSecret`, `extraVolumes`). Een chart die geheimen uit values in een Secret zet,
  nodigt uit tot wachtwoorden in een values-bestand in git.
- **Image-tag verplicht.** De keten publiceert alleen `main-<sha7>` en `pr-<n>-<sha7>`, geen
  `latest`; een standaardtag in de chart zou stil verouderen. Zonder tag of digest faalt de render.
- **Beveiliging volgens de mijn-bureau-standaard:** UID 185 (de gebruiker van het
  UBI-openjdk-image), `runAsNonRoot`, read-only root-filesystem met een `emptyDir` op `/tmp`,
  alle capabilities weg, `RuntimeDefault`-seccomp, geen service-account-token, eigen
  service-account, `imagePullPolicy: Always`, requests en limits op cpu, geheugen en
  ephemeral-storage.
- **Netwerkafscherming standaard dicht.** Inkomend alleen vanaf de namespace van de
  Envoy-proxy's (instelbaar) en vanaf pods met het client-label; uitgaand alleen DNS plus de
  poorten die de dienst nodig heeft (443, 5432, 6379), per dienst. Het stelselregister maakt geen
  uitgaande verbindingen en krijgt alleen DNS. Bestemmingen per IP zijn op Fundament nog niet
  bekend (#1536); de poortbeperking is de afscherming die nu kan.
- **Precies één replica als invariant van de chart.** Het stelselregister ondertekent per instantie
  op een eigen moment; twee instanties leveren afnemers afwisselend een ouder document, dat ze
  weigeren. `dienst.preciesEenReplica: true` laat de render falen bij meer replica's of een HPA,
  en zet de uitrolstrategie op `Recreate`, zodat er ook tijdens een uitrol geen twee draaien.
- **HTTPRoute met instelbare `sectionName`.** De helper van mijn-bureau zet die vast op `https`;
  de listener-naam van de Gateway op Fundament is nog niet bekend.
- **Afhankelijkheden buiten de chart.** PostgreSQL, Redis met RediSearch en het logboek zijn #1536.
  De chart verwijst er alleen naar via env.

## Controle zonder cluster

`charts/controleer.sh` draait per scenario in `ci/`:

1. `helm lint --strict`;
2. `helm template`, met de uitvoer door `kubeconform -strict` (Kubernetes-schema's plus de
   Gateway-API-schema's uit de CRDs-catalog, gepind op een commit);
3. `conftest test` met de regels uit `charts/policy/`: die van mijn-bureau (containers, image,
   labels, namespace) en eigen regels (probes, afscherming, één replica);
4. `conftest verify` voor de eigen regels;
5. negatieve gevallen die moeten falen: geen tag, route zonder hostnaam, meer dan één replica bij
   het stelselregister.

`ci-scripts.yml` draait het script (helm, conftest en kubeconform gepind op versie en sha256).
`charts/` gaat in `NIET_CODE` van de wijzigingsfilter: een chartwijziging raakt geen Maven-build en
geen image, en hoort dus geen testrun en geen preview te kopen.

## Verificatie

- `charts/controleer.sh` lokaal groen; elke eigen regel gemuteerd en de vangst bevestigd.
- Live: de images met dezelfde securityContext (read-only, UID 185, geen capabilities) onder
  podman tegen PostgreSQL en Redis Stack. Een k3d-cluster draait niet in de ontwikkelcontainer
  (geen `/dev/fuse`, geen `/lib/modules`); `charts/lokaal-testcluster.sh` doet de clusterproef op
  de host.
