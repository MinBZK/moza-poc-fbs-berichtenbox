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

## Installeren

Geef per dienst het values-bestand uit `diensten/` mee, een image-tag (de keten publiceert
`main-<sha7>` en `pr-<n>-<sha7>`, geen `latest`) en wat per omgeving verschilt:

```bash
helm upgrade --install berichtenmagazijn charts/fbs-dienst --namespace fbs \
  -f charts/fbs-dienst/diensten/berichtenmagazijn.yaml \
  -f mijn-omgeving/berichtenmagazijn.yaml \
  --set dienst.image.tag=main-8e9f467
```

met in `mijn-omgeving/berichtenmagazijn.yaml` bijvoorbeeld:

```yaml
dienst:
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
  hostname: magazijn.organisatie.example.nl
```

Welke instellingen een dienst nodig heeft, staat bovenin zijn bestand onder `diensten/` en in de
operatorhandleiding van die dienst. Waarden in `dienst.env` mogen templates bevatten, zoals
`{{ .Release.Namespace }}`.

**Geheimen staan nooit in values.** Wachtwoorden komen uit een bestaand Secret
(`dienst.extraEnvVarsSecret`); het stelselregister leest zijn ondertekensleutel uit het Secret
`<fullname>-sleutel`, sleutel `keystore.p12`.

## Wat de chart standaard afdwingt

- UID 185, `runAsNonRoot`, read-only root-filesystem (met een `emptyDir` op `/tmp`), geen
  capabilities, seccomp `RuntimeDefault`, geen service-account-token.
- Liveness, readiness en startup op `/q/health/*`; requests en limits op cpu, geheugen en
  ephemeral-storage.
- Een NetworkPolicy: inkomend alleen vanaf de proxy's van Envoy Gateway (namespace
  `envoy-gateway-system`, instelbaar met `networkPolicy.ingressNSMatchLabels`) en vanaf pods met
  het label `<fullname>-client: "true"`; uitgaand alleen DNS en de poorten in
  `networkPolicy.allowedEgressPorts`.
- Een PodDisruptionBudget.
- Het stelselregister draait met precies één replica, en rolt uit met `Recreate`: twee instanties
  ondertekenen elk op hun eigen moment, en een afnemer weigert dan het oudere document.
- Geen HSTS- of andere security-headers in de route: die zet de dienst zelf.

Een render met een onmogelijke combinatie faalt met een melding: geen tag, een route zonder
hostnaam of Gateway, of het stelselregister met meer replica's of een HPA.

## Controleren zonder cluster

```bash
charts/controleer.sh
```

Per dienst en scenario: `helm lint --strict`, `helm template` met de uitvoer door `kubeconform`
(Kubernetes plus de Gateway-API-schema's) en door de beleidsregels in `policy/`. Daarnaast
`conftest verify` voor de eigen regels, en de renders die moeten falen. Vereist `helm`, `conftest`
en `kubeconform`; de versies die de CI gebruikt staan in `.github/workflows/ci-scripts.yml`. De
CI draait het script op elke PR.

## Proef op een lokaal cluster

```bash
TAG=main-<sha7> charts/lokaal-testcluster.sh
charts/lokaal-testcluster.sh opruimen
```

Zet een k3d-cluster op met Envoy Gateway, PostgreSQL en Redis Stack als proefvoorzieningen,
installeert de drie diensten en controleert dat elk gezond is en via de Gateway bereikbaar.
Vereist `k3d`, `kubectl`, `helm`, `openssl` en `curl`, en een host met Docker of podman: in een
container zonder `/dev/fuse` en `/lib/modules` start k3d niet.
