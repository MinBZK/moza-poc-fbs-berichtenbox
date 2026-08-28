# Twee bevindingen: mirror-fout op ghcr.io/shopify, en een overruled deploy die verkeerd faalt

## 1. Pull-through-mirror geeft HTTP 500 op `ghcr.io/shopify/toxiproxy`

**Cluster:** odcn-production
**Projecten:** `mpfb-8wh` en `mpfpsm-lcl` (MOZa PoC Federatief Berichtenstelsel)
**Waargenomen:** 28 augustus 2026, ongeveer 08:00–08:30 UTC

## Wat er gebeurt

Pods die `ghcr.io/shopify/toxiproxy:2.12.0` gebruiken komen niet verder dan `ErrImagePull`. De
mirror antwoordt met een HTTP 500 bij het lezen van de manifest:

```
Back-off pulling image "rcr.rijksapps.nl/ghcr-rig/shopify/toxiproxy:2.12.0": ErrImagePull:
unable to pull image or OCI artifact: pull image err: initializing source
docker://rcr.rijksapps.nl/ghcr-rig/shopify/toxiproxy:2.12.0: reading manifest 2.12.0 in
rcr.rijksapps.nl/ghcr-rig/shopify/toxiproxy: received unexpected HTTP status:
500 Internal Server Error
```

Dit hield aan over meerdere pod-generaties en ongeveer een half uur, in twee verschillende
projecten. Het lijkt dus geen incidentele hapering.

## Waarom het waarschijnlijk aan de mirror ligt

De image is publiek en zonder inloggen op te vragen bij de bron. Met een anoniem pull-token geeft
ghcr.io voor exact dezelfde tag gewoon een geldige manifest terug:

```bash
TOKEN=$(curl -s "https://ghcr.io/token?scope=repository:shopify/toxiproxy:pull" | jq -r .token)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.oci.image.index.v1+json" \
  "https://ghcr.io/v2/shopify/toxiproxy/manifests/2.12.0"
# -> 200, digest sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e
```

Een 500 (en niet een 404 of 401) wijst eerder op een fout aan de kant van de mirror dan op een
ontbrekende of afgeschermde image.

Wat wij niet konden nagaan: of de `ghcr-rig`-mirror per upstream-namespace geconfigureerd is.
`rcr.rijksapps.nl` is van buiten het cluster niet bereikbaar, dus we konden de mirror niet
rechtstreeks bevragen. In de projectspecs op het cluster komen naast `ghcr.io/minbzk` ook
`ghcr.io/rijksictgilde`, `ghcr.io/anneschuth`, `ghcr.io/nederlandsedigitaledienst` en enkele andere
namespaces voor, dus een harde beperking tot één namespace lijkt het niet te zijn.

## Reproduceren

Een component aanmaken met `--image ghcr.io/shopify/toxiproxy:2.12.0` in een willekeurige
deployment is genoeg; de pod blijft in `ImagePullBackOff` staan met bovenstaande melding.

## Wat wij intussen doen

Wij publiceren de image ongewijzigd door onder onze eigen namespace
(`ghcr.io/minbzk/fbs-toxiproxy`, gepind op tag én digest van de upstream), omdat `ghcr.io/minbzk/*`
wél door de mirror komt. Dat werkt voor ons, maar het is een omweg: er staat nu een kopie die met de
upstream mee moet bewegen. Zodra de mirror deze image kan bedienen, halen we die kopie weer weg.

### Vraag

Kunnen jullie kijken wat de mirror op deze repository doet struikelen? En is er iets dat wij aan
onze kant hadden kunnen zien of instellen — een namespace die aangemeld moet worden bijvoorbeeld —
zodat we hier de volgende keer zelf uitkomen?

## 2. Een overruled taak laat `zad-actions/deploy` falen op een misleidende melding

**Waargenomen:** 28 augustus 2026, in `mpfb-8wh`, met `zad-actions` v4.

Draait er in hetzelfde project een tweede taak, dan wordt de wachtstap van een lopende deploy
overruled:

```
Deployment successful
##[error]Could not extract URLs from result
{
  "status": "superseded",
  "message": "Superseded while waiting for ArgoCD application 'mpfb-8wh-pr-248' to sync:
              task adf57a8d-... (delete_component) for project 'mpfb-8wh' covers this task's scope"
}
```

De deployment zelf is dan gewoon aangemaakt — `zadctl` eindigt met exitcode 0 en de action meldt
"Deployment successful". Maar een `superseded` resultaat draagt geen `urls`, en daar loopt
`deploy/action.yml` op stuk:

```bash
ALL_URLS=$(echo "$RESULT" | jq -c ".urls.\"${DEPLOYMENT_NAME}\".urls // {}")
if [ -z "$ALL_URLS" ] || [ "$ALL_URLS" = "{}" ] || [ "$ALL_URLS" = "null" ]; then
  echo "::error::Could not extract URLs from result"
  exit 1
fi
```

De job wordt rood met een melding die niets zegt over de werkelijke oorzaak, terwijl de uitrol
geslaagd is. Opnieuw draaien lost het op, dus het is geen blokkade — wel iedere keer een
zoektocht.

**Zou het kloppender zijn** als een `superseded` wachtstap opnieuw gaat pollen in plaats van als
fout te eindigen? En als dat niet kan: zou de action dit geval kunnen herkennen en er een melding
bij kunnen geven die de superseding-taak noemt?

**Wat wij eraan bijdroegen:** in dit geval kwam de tweede taak uit handmatig OM-werk van ons, naast
een lopende CI-run. Dat is aan onze kant te vermijden. Maar hetzelfde kan gebeuren tussen twee
pull requests die naar hetzelfde project uitrollen: OM vergrendelt op project, terwijl de
concurrency-groep van een workflow doorgaans per deployment of per PR staat.
