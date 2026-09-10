---
name: zad-debug
description: Onderzoek een ZAD-deployment of preview die niet draait, in de volgorde die de gids voorschrijft — eerst het gerenderde manifest, dan pas de UI-melding
---

# ZAD-deployment debuggen

De volgorde hieronder is het punt van deze skill. Ga je op de foutmelding af, dan begin je precies
verkeerd om: de "Technische details"-ImagePullBackOff in de UI kan een bevroren, verouderd event
zijn terwijl de gesyncte manifest allang een geldige tag heeft. En de melding "uitgeschakeld: image
ontbreekt" gaat vaak helemaal niet over een image.

De volledige gids staat in `docs/operations/zad-gitops.md`; dit is de looproute.

## 0. Loopt er een deploy?

```bash
gh run list --workflow "Deploy ZAD" --limit 5
```

Doe geen handmatig OM-werk terwijl er een deploy loopt. OM vergrendelt op project, niet op
deployment: een tweede taak overruled de wachtstap, het resultaat wordt `superseded`, draagt geen
`urls`, en de job faalt op `Could not extract URLs from result` — terwijl de uitrol geslaagd is.
Opnieuw draaien volstaat dan.

## 1. Lees het gerenderde manifest — dit is de grond-waarheid

Argo synct `rig-cluster-application-test`. Daar staat de échte image-tag én `replicas`:

```bash
gh api repos/RijksICTGilde/rig-cluster-application-test/contents/odcn-production/<project-id>/<deployment>/<component>-deployment.yaml \
  --jq '.content' | base64 -d | grep -E 'image:|replicas:'
```

Project-ids: `berichtenuitvraag` = `mpfb-8wh`, `magazijnen` = `mpfm-w3h`, `externe-stubs` =
`mpfpsm-lcl`. Deployments: `test` (baseline) en `pr-<n>` (previews).

Wat je hier ziet, bepaalt de rest:

| Bevinding | Wat het is |
|-----------|------------|
| `replicas: 0` | Schaal-/enable-probleem, géén image-probleem. Ga naar stap 2. |
| Een kale `:main` of `:pr-<n>` | Handmatig of verouderd geconfigureerd. De workflow pusht `main-<sha7>` en `pr-<n>-<sha7>`, nooit een kale tag. |
| De tag van een oudere commit | De deploy landde niet, of de tag was niet uniek — een herbruikte tag laat het manifest ongewijzigd, dus synct Argo niets. |
| Tag klopt, `replicas: 1` | Het draait; ga naar stap 3 en kijk naar de applicatie zelf. |

Ook nuttig: `resources.limits.memory` in hetzelfde manifest. Een deployment-override van een paar
tientallen Mi uit een auto-tune-meting geeft een component dat degraded is zonder ook maar één
logregel.

## 2. `replicas: 0` — zet het component weer aan met een rollout

Eén `update-image` volstaat, **ook met exact dezelfde tag**:

```bash
zadctl -p <project> deployment update-image <deployment> -c <component> --image <zelfde image>
```

Noem het component expliciet met `-c`: de hook raakt alleen wat in de image-update genoemd wordt.
Dit heft élke uitschakeling op, ongeacht de reden. Een `refresh` of UI-"herverwerken" doet dat
alleen bij een image-pull-uitschakeling.

Blijf je daarna op `replicas: 0`, kijk dan naar `disabled-reason` in de projectspec
(`rig-cluster-projects` → `projects/<project-id>.yaml`): OM heeft het component dan opnieuw
uitgezet, met een verse reden.

Herscheppen (`DELETE` + `:upsert-deployment`) is het zware alternatief en **destructief**: voor
projecten met de `postgresql-database`-service ruimt de delete de databasedata op. Doe dat niet
zonder expliciete instemming.

## 3. Draait het, maar klopt het gedrag niet — lees de logs

```bash
zadctl logs <deployment> -c <component> -n 200 --since 1h
```

"No resources found in namespace" is géén logfout: dat is `replicas: 0`, dus terug naar stap 1.

**Check de tijdzone van de log.** Onze eigen diensten loggen in `Europe/Amsterdam`, met de offset in
elke regel (`+01:00`/`+02:00`). Componenten uit een extern image (`redis`, `proeftuin`, de
FSC-componenten) loggen in UTC zonder offset, en lopen dus een of twee uur achter op je klok. Werk
met een relatief venster (`--since`), en reken een tijd die iemand je in lokale tijd noemt om
voordat je hem in zo'n log opzoekt.

## 4. Bereikbaarheid

```bash
zadctl deployment url <deployment> -c <component>
```

Twee eigenschappen die hier verrassen:

- **Een component publiceert alleen `ports[0]`.** Elke poort daarna wordt een extra Service-poort
  zonder Ingress; die blijft cluster-intern.
- **De netpol isoleert per deployment.** Cross-deployment verkeer loopt alleen over de publieke
  route op 443; een cluster-interne poort zonder route is van buiten die deployment niet te
  bereiken.

Een 404 op een bestaande, publieke ghcr-tag wijst op de pull-through-mirror aan ZAD-zijde
(`rcr.rijksapps.nl/ghcr-rig/minbzk/*`), niet op onze push.

## 5. Preview blijft staan of is verdwenen

Previews ruimt `cleanup-preview.yml` op bij het sluiten van de PR. Een gemiste opruiming haal je
in:

```bash
gh workflow run cleanup-preview.yml -f pr=<n>
```

Let op: een mislukte delete geeft een `::warning::` en een gróéne job. Verifieer zelf via OM
(`GET /api/v2/projects/{p}/deployments/{d}`, met `X-API-Key`) en vraag de statuscode expliciet op —
de exitcode van `gh` zegt hier niets.

## 6. Rapporteer

Zeg wat het manifest toonde, wat de oorzaak was, en welke stap hem verhielp. Ging je uit van de
UI-melding en klopte die niet, meld dat ook: die vergissing herhaalt zich anders.
