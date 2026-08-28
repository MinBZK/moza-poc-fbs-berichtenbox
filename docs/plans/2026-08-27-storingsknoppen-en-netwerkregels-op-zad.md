**Status:** Uitgevoerd; de keten op `test` gaat door de proxies zodra de nieuwe console daar staat. Zie "Wat er staat" onderaan.

# Storingsknoppen en cluster-intern verkeer op ZAD — ontwerp

Vervolg op `2026-08-26-demo-op-zad-design.md`. Dat ontwerp bracht de demo-console naar ZAD; twee
knopgroepen bleven achter omdat de opzet die er stond niet werkt zodra previews meetellen. Dit
document beschrijft wat er precies in de weg zit en welke route het wél oplost.

**Issue:** volgt uit dit ontwerp. Hangt samen met MinBZK/MijnOverheidZakelijk#936 (uitgevoerd voor
de console) en #938 (magazijn-simulator).

## De aanleiding

> Stap 1 hieronder is uitgevoerd en stap 2 is gebouwd. De beschrijving blijft staan omdat ze
> vastlegt waaróm de opzet werd zoals hij is.

Op ZAD staat de console als `democonsole` in `mpfm-w3h/test` en in elke preview daarvan. Alles wat
hij doet is óf binnen zijn eigen deployment (de magazijnen, de database) óf over de publieke
ingress (de uitvraag). Twee knopgroepen vallen daarbuiten:

| Knop | Wat hij nodig heeft | Waar dat staat | Nu |
|---|---|---|---|
| Cache verlopen | Redis, poort 6379 | `mpfb-8wh`, ander project | werkt (stap 1) |
| Storingen | vier Toxiproxy-admin-API's, poort 8474 | `mpfb-8wh` en `mpfpsm-lcl` | stap 2 |

De console laat weg wat een omgeving niet kan bedienen, op grond van `GET /api/demo/omgeving`: lege
`TOXIPROXY_*_URL`-waarden halen de storingsknoppen uit het paneel. `SESSIECACHE_BEREIKBAAR` deed
hetzelfde voor de cache-verval-knop en staat op ZAD inmiddels op `true`.

## Drie eigenschappen van ZAD die de oude opzet blokkeren

Alle drie geverifieerd in `RijksICTGilde/RIG-Cluster` en op de OM-API.

**1. De inhoud van een attachment wordt ongewijzigd gemount.** `$DEPLOYMENT_NAME`-substitutie
bestaat alleen voor aliassen, niet voor bestanden. Een `proxies.json` met
`upstream: test-profiel:8080` blijft in `pr-244` naar `test-profiel` wijzen.

**2. `command` is niet via de API te zetten.** Het veld bestaat wel op een component in
projectschema v2, maar niet in `AddComponentRequest` of `UpdateComponentRequest`, en `zadctl` kent
het niet. Het startcommando dat Toxiproxy naar `proxies.json` wijst, is dus UI-handwerk per
component, dat een hercreatie niet overleeft.

**3. Een `cross-domain-access`-regel noemt één concrete peer-deployment.** `merge.py` en
`resolve.py`: blijft die open, dan wordt de regel bij het genereren overgeslagen. De gerenderde
NetworkPolicy pint de tegenpartij op `app: <deployment>-<component>`. Er is geen vorm die "dezelfde
deployment als de mijne" betekent.

Samen leveren die drie de slechtste uitkomst op. De keten-aliassen gebruiken `$DEPLOYMENT_NAME`, dus
`pr-244`'s uitvraag praat tegen `pr-244`'s eigen Toxiproxy-pod — maar diens `proxies.json` wijst naar
`test-profiel` en `test-uitvraag`. Een preview zou zijn verkeer stilzwijgend het `test`-magazijn en
de `test`-uitvraag in sturen, en dan bewijst hij niet meer wat hij lijkt te bewijzen.

## De route die het wel oplost

Twee stappen, in deze volgorde. De eerste staat op zichzelf en maakt de cache-verval-knop al
bruikbaar; de tweede leunt erop.

### Stap 1: netwerkregels per deployment — uitgevoerd

`PATCH /api/v2/projects/{p}/services/cross-domain-access/config/deployment/{d}/outbound` en
`.../inbound` bestaan. De deploy-workflow kan de regel voor een preview dus bijschrijven op het
moment dat hij de preview aanmaakt, en `cleanup-preview.yml` kan hem weer opruimen bij het sluiten
van de PR.

Elke hop vraagt twee regels — een outbound bij de aanroepende kant en een inbound bij de aangeroepen
kant, in twee verschillende projecten. De ontvanger beslist, dus geen van beide is alleen genoeg.

Aandachtspunten:

- **Opruimen is niet optioneel.** Een achtergebleven regel noemt een deployment die niet meer
  bestaat; de resolver slaat hem over en logt dat, dus hij faalt stil en stapelt op.
- **De volgorde ten opzichte van de deploy.** De regel kan pas geschreven worden als de deployment
  bestaat, en de pod heeft hem pas nodig zodra iemand op de knop drukt. Ná de deploy volstaat.
- **Twee API-keys per hop.** De regels staan in twee projecten, dus de stap heeft de sleutels van
  beide nodig — een job die vandaag maar één project aanraakt, raakt er dan twee.

Zo is het geworden: `.github/scripts/cross-domain-preview.sh` zet of verwijdert de per-deployment
invulling; `deploy.yml` roept hem aan in `deploy-preview-uitvraag` (inbound, want Redis staat daar)
en `deploy-preview-magazijnen` (outbound, want de console staat daar), en `cleanup-preview.yml` doet
de tegenhanger per matrix-leg. De regel zelf staat één keer op projectniveau zonder peer-deployment;
`demo/environment/zad-demo/README.md` beschrijft die eenmalige stap.

Twee dingen die bij de uitvoering bleken. `zadctl service config set cross-domain-access` is een PUT
over de héle configuratie en zou de bestaande regels overschrijven — de `PATCH …/inbound` en
`…/outbound` zijn add/remove per regelnaam en zijn daarom wat het script gebruikt. En de
preview-deploy-jobs hadden geen `actions/checkout`: de deploy-stap is een action en heeft de repo
niet nodig, een script wel.

### Stap 2: de console maakt zijn eigen proxies aan — uitgevoerd

Toxiproxy start met zijn eigen default-`CMD` (`-host=0.0.0.0`, geverifieerd op de image-config van
2.12.0) prima op, met nul proxies en zijn admin-API omhoog. Dat schrapt eigenschap 1 en 2 in één
klap: geen attachment, geen `command`.

De console maakt elke proxy dan zelf aan via `POST /proxies` op de admin-API, met listen en upstream
uit zijn eigen configuratie. Die configuratie komt uit aliassen, en aliassen kennen
`$DEPLOYMENT_NAME` wél — dus elke deployment krijgt de juiste upstream.

Wat dat raakt in `demo/demo-console`:

- `ToxiproxyConfig.Instantie` krijgt naast `url()` een `listen()` en een `upstream()`.
- `ToxiproxyClient` krijgt een `maakProxy(...)`.
- Een idempotente bootstrap: bestaat de proxy al, laat hem staan; de demo mag niet afhangen van de
  vraag of de console eerder opstartte dan de Toxiproxy.
- De bestaande `reset()`-guard op "kent geen enkele proxy" houdt zijn betekenis, en wint er zelfs
  aan: nul proxies ná de bootstrap betekent dat de bootstrap faalde, en dat is precies het moment
  waarop de keten dood is.

Lokaal verandert er niets: compose houdt zijn `proxies.json`, en een bootstrap die een bestaande
proxy laat staan is daar een no-op.

### Wat er op ZAD bij komt

Vier `toxiproxy-*`-componenten, elk in dezelfde deployment als zijn upstream: `profiel` en
`notificatie` in `mpfpsm-lcl`, `aanmeld` en `redis` in `mpfb-8wh`. Drie krijgen een
`publish-on-web`-ingress zodat de TLS-eis in de code overeind blijft — Toxiproxy termineert zelf
geen TLS, en de router doet dat vandaag al voor de andere componenten. De vierde (`redis`) heeft er
geen nodig.

Kosten: vier containers van ~32 Mi plus drie ingressen, per deployment inclusief previews. En een
permanente extra hop in het verkeerspad van de keten, ook wanneer er niemand demonstreert. Dat is
de afweging die bij dit werk hoort en die eerder al als open stond aangemerkt.

Drie dingen bleken bij de uitvoering, alle drie in `RijksICTGilde/RIG-Cluster` na te lezen.

**Een component draagt méér dan één poort.** `ports: [...]` bestaat in `AddComponentRequest`, elke
poort ná de eerste wordt een extra Service-poort en de Ingress pakt alleen `ports[0]`. Dit ontwerp
ging nog uit van één poort per component; in werkelijkheid geldt die beperking voor de ingress, niet
voor de Service. Elke Toxiproxy draagt daarom zijn stroom op de eerste poort en zijn admin-API op
8474, cluster-intern achter de netwerkregel.

**De standaard-probe zou de uit-knop terugdraaien.** Zonder de `health-check`-dienst probeert
Kubernetes een TCP-socket op `ports[0]` — precies de poort die Toxiproxy sluit als je een proxy
uitzet. Anderhalve minuut na een druk op "uit" zou de pod herstarten en álle proxies meenemen. De
probe wijst daarom naar 8474.

**De ingressen zijn een eis, geen voorkeur.** `DownstreamClient.valideerUrl` weigert buiten dev een
downstream zonder `https` of op een intern adres (BIO 13.2.1 plus de SSRF-blocklist). `aanmeld` en
`notificatie` móéten dus over de publieke ingress lopen.

**En het image komt niet van upstream.** De ZAD-mirror geeft op `ghcr.io/shopify/toxiproxy` een
HTTP 500, terwijl diezelfde tag rechtstreeks bij ghcr.io anoniem 200 geeft en Docker Hub sinds 2019
stilstaat op 2.1.4. `toxiproxy/Dockerfile` publiceert het image daarom door als
`ghcr.io/minbzk/fbs-toxiproxy`, gepind op tag én digest — hetzelfde patroon als
`wiremock/demo-profiel`. Verdwijnt de storing bij RIG, dan kan dat bestand weg.

**Twee ordeningen die niet vrij zijn.** Een regel waarvan het peer-component nog niet bestaat, wordt
bij het renderen overgeslagen — met een waarschuwing, terwijl de deployment `Healthy` meldt en de
NetworkPolicy die egress-regel mist. En de keten mag pas door de proxies wanneer overal een console
draait die ze aanmaakt; eerder omhangen wijst de uitvraag naar een proxy die niemand maakt.

En één ding dat het ontwerp helemaal niet had: **de proxies staan alleen in het geheugen van
Toxiproxy.** Zonder `proxies.json` laat een herstart van die pod de keten dood achter, want al het
profiel-, notificatie-, aanmeld- en Redis-verkeer loopt erdoorheen. De console herhaalt zijn
bootstrap daarom elke dertig seconden; een bestaande proxy blijft staan, ook een bewust uitgezette,
dus alleen een leeggeraakte instantie wordt opnieuw gevuld.

## Overwogen en afgevallen

| Alternatief | Waarom niet |
|---|---|
| Eén attachment per deployment, bijgeschreven door de deploy-workflow | De attachment-API kan het, maar het is een tweede kopie van dezelfde informatie die de aliassen al dragen — en `command` blijft dan alsnog UI-handwerk. |
| De admin-poorten publiek publiceren, zodat de netwerkregels niet nodig zijn | Wie erbij kan, kan de demo stukmaken. Een authorization-wall ervoor sluit juist de console buiten, want die heeft geen SSO-sessie. |
| De storingsknoppen alleen in `test`, previews uitgezonderd | Previews klonen `test` integraal; wat in `test` staat, komt mee. |
| Wachten op #938 en de storingen uit de simulator halen | Dekt de magazijn-storingen, niet de vier stromen hier (profiel, notificatie, aanmeld, Redis). |

## Wat er staat

| | Status |
|---|---|
| Script + tests, `deploy.yml`, `cleanup-preview.yml`, runbook | Uitgevoerd |
| De regel op projectniveau in `mpfb-8wh` (inbound) | Gezet |
| De regel op projectniveau in `mpfm-w3h` (outbound) | Gezet |
| `REDIS_HOSTS`, `REDIS_PASSWORD` en `SESSIECACHE_BEREIKBAAR=true` op de console | Gezet |
| Console maakt zijn eigen proxies aan, met reconcile + tests | Uitgevoerd |
| Netwerkregels per preview in `deploy.yml`/`cleanup-preview.yml`, runbook | Uitgevoerd |
| De vier Toxiproxy-componenten op `test`, met netwerkregels | Uitgevoerd |
| Geverifieerd op de preview: eigen proxies, eigen upstreams, eigen NetworkPolicy | Uitgevoerd |
| De keten op `test` door de proxies leiden | Na de merge, zie het runbook |

De cache-verval-knop werkt, op `test` en op een preview. Eén ding kwam er bij de verificatie
bovenop dat hier niet stond: de Redis op ZAD eist een wachtwoord, en de console kende de property
niet. Dat de verbinding er dóórheen kwam en met `NOAUTH` antwoordde, was meteen het bewijs dat de
netwerkregel deed wat hij moest.

Dat wachtwoord is de enige waarde die met de hand gelijk gehouden moet worden: hij staat in de
`user-env-vars` van zowel `uitvraag` als `democonsole`, en de API geeft hem niet terug. Loopt hij
uiteen, dan faalt alleen deze knop, en pas op het moment dat iemand hem gebruikt.
