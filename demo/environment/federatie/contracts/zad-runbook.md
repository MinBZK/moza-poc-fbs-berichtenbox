# Contract-bootstrap op ZAD — runbook

Draaiboek voor de operator: de contract-bootstrap als component per peer neerzetten. Eén keer per
peer; daarna houdt `deploy.yml` alleen de image-tag bij.

## Waarom een component en geen CI-stap

Elk ZAD-deployment krijgt een `<deployment>-tenant-baseline-network-policy` met
`podSelector: {deployment: <naam>}`. Ingress komt alleen van pods met hetzelfde `deployment`-label,
de `rig`-ingresscontroller, `rig-prd-operations` en `rig-prd-backup`; egress gaat alleen naar
kube-dns, hetzelfde deployment, die twee namespaces, `<project>-infrastructure`, en `0.0.0.0/0`
beperkt tot TCP 443/80. De isolatie loopt dus per **deployment**, niet per project.

Daar komt bij dat de manager-internal-API (`:9443` authenticated, `:9444` unauthenticated) geen
route heeft — de ingress publiceert alleen backend-poort `:8443`, de mesh-poort. Alleen een pod
binnen hetzelfde deployment kan de contract-API dus bereiken, en een CI-runner of een pod uit een
ander project nooit.

Vandaar: elke peer bootstrapt zijn eigen kant tegen zijn eigen manager, en het contract kruist via
de FSC-mesh. Dat is geen omweg — het is dezelfde weg die de managers onderling toch al gebruiken,
en het houdt de contract-API van elke peer onbereikbaar van buiten.

## De twee componenten

| Deployment | Component | `FSC_ROL` | Doet |
|------------|-----------|-----------|------|
| `fsc-logius` (`mpfb-8wh`) | `logius-fscbootstrap` | `consumer` | dient het contract in bij de eigen manager en stelt vast dat het geldig wordt |
| `fsc-magazijna` (`mpfm-w3h`) | `magazijna-fscbootstrap` | `provider` | tekent binnengekomen contracten die de autorisatietoets halen |

Beide draaien hetzelfde image (`ghcr.io/minbzk/fbs-fsc-contract-bootstrap`). ZAD staat geen
component-args toe, dus de rol komt uit env.

De componenten hebben **geen ingress en geen inbound poort**: ze bellen alleen uit, naar de manager
van hun eigen deployment. Geef ze dus geen `ports.inbound` — dat zou een Service opleveren die
niemand gebruikt.

## Env per component

Gemeenschappelijk (beide componenten):

| Variabele | Waarde |
|-----------|--------|
| `FSC_ROL` | `consumer` respectievelijk `provider` |
| `FSC_LUS_WACHT` | optioneel, standaard `15` — interval zolang er nog iets moet gebeuren |
| `FSC_LUS_HERHAAL` | optioneel, standaard `300` — interval als alles staat |

`logius-fscbootstrap` (consumer):

| Variabele | Waarde |
|-----------|--------|
| `FSC_CONSUMER_OIN` | `00000000000000001000` |
| `FSC_PROVIDER_OIN` | `00000000000000100000` |
| `FSC_SERVICE_NAME` | `berichtenmagazijn` |
| `FSC_OUTWAY_THUMBPRINT` | zie hieronder |
| `FSC_CONSUMER_MANAGER` | `https://fsc-logius-logius-fscmgr:9443` |
| `FSC_CONSUMER_CERT` | `/etc/fsc/internal/logius/bootstrap/cert.pem` |
| `FSC_CONSUMER_KEY` | `/etc/fsc/internal/logius/bootstrap/key.pem` |
| `FSC_CONSUMER_CA` | `/etc/fsc/internal/logius/ca/root.pem` |

`magazijna-fscbootstrap` (provider):

| Variabele | Waarde |
|-----------|--------|
| `FSC_PROVIDER_OIN` | `00000000000000100000` |
| `FSC_DIENSTEN` | `berichtenmagazijn` — spaties-gescheiden lijst |
| `FSC_CONSUMERS` | `00000000000000001000` — spaties-gescheiden lijst |
| `FSC_PROVIDER_MANAGER` | `https://fsc-magazijna-magazijna-fscmgr:9443` |
| `FSC_PROVIDER_CERT` | `/etc/fsc/internal/magazijn-a/bootstrap/cert.pem` |
| `FSC_PROVIDER_KEY` | `/etc/fsc/internal/magazijn-a/bootstrap/key.pem` |
| `FSC_PROVIDER_CA` | `/etc/fsc/internal/magazijn-a/ca/root.pem` |

> `FSC_DIENSTEN` en `FSC_CONSUMERS` samen zijn de autorisatiegrens van de provider: alles wat er
> niet in staat, wordt niet getekend. Een contract dat de toets niet haalt blijft ongetekend en
> werkt daarmee niet — dat is de bedoelde uitkomst, geen storing. Zet er dus niet ruimhartig extra
> waarden in "voor later"; een OIN erbij is een peer die van ons mag afnemen.

### De outway-thumbprint bepalen

De consumer-helft heeft de SPKI-SHA256-thumbprint van het **group**-cert van de eigen outway nodig.
Op ZAD komt die uit env in plaats van uit een bestand: het group-cert hangt aan het
outway-component, en het aan een tweede component koppelen zou die identiteit verspreiden.

```bash
openssl x509 -in demo/environment/logius/pki/out/logius/outway/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -r | cut -d' ' -f1
```

64 hex-tekens. De waarde is stabiel zolang het sleutelpaar dat is — een cert-rotatie binnen
hetzelfde sleutelpaar verandert hem niet. Rouleer je het sleutelpaar wél, dan moet deze env mee en
komt er een nieuw contract; het oude wordt door de consumer-helft opgeruimd zodra het overtollig is.

## Cert-attachments

Het component gebruikt het **internal**-cert van het `bootstrap`-endpoint als client naar de
manager-API. Dat is een eigen cert en niet dat van een andere component: wie een cert deelt, deelt
een identiteit, en dan is in het txlog niet meer te zien wie wat deed.

`pki/issue.sh` geeft dit endpoint bewust **geen group-cert** — het bedient zelf geen TLS en hoort
zich niet in de mesh als de peer te kunnen voordoen.

Attachments per bootstrap-component (UI-only; de v2-API kloont ze niet):

| Bestand uit `pki/zad-upload/<peer>/` | Pod-pad |
|--------------------------------------|---------|
| `internal/<peer>/bootstrap/cert.pem` | `/etc/fsc/internal/<peer>/bootstrap/cert.pem` |
| `internal/<peer>/bootstrap/key.pem` | `/etc/fsc/internal/<peer>/bootstrap/key.pem` |
| `internal/<peer>/ca/root.pem` | `/etc/fsc/internal/<peer>/ca/root.pem` |

Zie `deploy/zad/cert-manifest.md` bij de peer voor de algemene valkuilen (internal-pad krijgt het
internal-cert, group-pad het group-cert inclusief intermediate).

## Volgorde

1. `pki/issue.sh -f` bij beide peers — geeft het nieuwe `bootstrap`-endpoint uit.
2. `pki/zad-bundle.sh <peer>` — zet de upload-set klaar.
3. Component éénmalig aanmaken in de ZAD-UI, in de deployment van díé peer, met de env hierboven
   en zonder inbound poort.
4. De drie attachments koppelen.
5. Component starten en de log volgen.

Daarna houdt de `deploy-test-*`-job de image-tag bij, zoals bij de andere peer-componenten.

## Verifiëren

De consumer-log meldt `CONSUMER OK`, de provider-log `PROVIDER OK (1 contract(en) getekend)` en
daarna elke ronde `PROVIDER OK (niets te tekenen)`. Dat laatste is het idempotentie-signaal: draait
het component opnieuw of herstart de pod, dan komt er geen tweede contract bij.

Blijft de consumer `CONSUMER WACHT` melden terwijl de provider niets meer te tekenen heeft, dan is
de accept-handtekening onderweg blijven steken (zie hieronder).

## Bekende beperking: een gestrande accept-push

De manager pusht de accept-handtekening na het tekenen naar de consumer, maar best-effort: met
begrensde backoff en zonder cron-retry. Strandt die push, dan blijft het contract bij de consumer
`proposed` en ziet de outway de grant nooit, terwijl het bij de provider geldig heet.

De provider-helft stuurt daarom na elke accept één keer na. Strandt de push daarná alsnog, dan kan
geen van beide helften dat waarnemen: de consumer ziet het probleem maar kan de her-distributie niet
aanroepen (dat is een provider-endpoint), en de provider kan de her-distributie aanroepen maar het
probleem niet zien. Lokaal loste één script dat op door bij beide managers te kijken; op ZAD kan dat
niet.

De uitweg is een handmatige duw: zet op het provider-component tijdelijk
`FSC_FORCEER_DISTRIBUTIE=1`, laat één ronde lopen, en zet hem weer uit. Aan laten staan kost een
extra API-call per contract per ronde zonder dat er iets mis is.
