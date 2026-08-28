**Status:** Concept

# Storingsknoppen en cluster-intern verkeer op ZAD — ontwerp

Vervolg op `2026-08-26-demo-op-zad-design.md`. Dat ontwerp bracht de demo-console naar ZAD; twee
knopgroepen bleven achter omdat de opzet die er stond niet werkt zodra previews meetellen. Dit
document beschrijft wat er precies in de weg zit en welke route het wél oplost.

**Issue:** volgt uit dit ontwerp. Hangt samen met MinBZK/MijnOverheidZakelijk#936 (uitgevoerd voor
de console) en #938 (magazijn-simulator).

## Wat er nu niet werkt

Op ZAD staat de console als `democonsole` in `mpfm-w3h/test` en in elke preview daarvan. Alles wat
hij doet is óf binnen zijn eigen deployment (de magazijnen, de database) óf over de publieke
ingress (de uitvraag). Twee knopgroepen vallen daarbuiten, en die zijn uitgezet:

| Knop | Wat hij nodig heeft | Waar dat staat |
|---|---|---|
| Cache verlopen | Redis, poort 6379 | `mpfb-8wh`, ander project |
| Storingen | vier Toxiproxy-admin-API's, poort 8474 | `mpfb-8wh` en `mpfpsm-lcl` |

De console laat ze zelf weg op grond van `GET /api/demo/omgeving`: lege `TOXIPROXY_*_URL`-waarden en
`SESSIECACHE_BEREIKBAAR=false`.

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

### Stap 1: netwerkregels per deployment

`PATCH /api/v2/projects/{p}/services/cross-domain-access/config/deployment/{d}/outbound` en
`.../inbound` bestaan. De deploy-workflow kan de regel voor een preview dus bijschrijven op het
moment dat hij de preview aanmaakt, en `cleanup-preview.yml` kan hem weer opruimen bij het sluiten
van de PR.

Elke hop vraagt twee regels — een outbound bij de bellende kant en een inbound bij de gebelde kant,
in twee verschillende projecten. De ontvanger beslist, dus geen van beide is alleen genoeg.

Aandachtspunten:

- **Opruimen is niet optioneel.** Een achtergebleven regel noemt een deployment die niet meer
  bestaat; de resolver slaat hem over en logt dat, dus hij faalt stil en stapelt op.
- **De volgorde ten opzichte van de deploy.** De regel kan pas geschreven worden als de deployment
  bestaat, en de pod heeft hem pas nodig zodra iemand op de knop drukt. Ná de deploy volstaat.
- **Twee API-keys per hop.** De regels staan in twee projecten, dus de stap heeft de sleutels van
  beide nodig — een job die vandaag maar één project aanraakt, raakt er dan twee.

### Stap 2: de console maakt zijn eigen proxies aan

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

## Overwogen en afgevallen

| Alternatief | Waarom niet |
|---|---|
| Eén attachment per deployment, bijgeschreven door de deploy-workflow | De attachment-API kan het, maar het is een tweede kopie van dezelfde informatie die de aliassen al dragen — en `command` blijft dan alsnog UI-handwerk. |
| De admin-poorten publiek publiceren, zodat de netwerkregels niet nodig zijn | Wie erbij kan, kan de demo stukmaken. Een authorization-wall ervoor sluit juist de console buiten, want die heeft geen SSO-sessie. |
| De storingsknoppen alleen in `test`, previews uitgezonderd | Previews klonen `test` integraal; wat in `test` staat, komt mee. |
| Wachten op #938 en de storingen uit de simulator halen | Dekt de magazijn-storingen, niet de vier stromen hier (profiel, notificatie, aanmeld, Redis). |
