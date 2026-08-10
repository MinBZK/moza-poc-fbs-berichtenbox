# Preview rolt uit per commit (unieke image-tag)

**Status:** Uitgevoerd
**Issue:** #171

## Context

Een PR-preview op ZAD bevroor bij de eerste build. Elke volgende commit werd wel gebouwd en
gepusht en de `deploy-preview-*`-check werd groen, maar er rolde niets uit: de pods bleven op
de image-laag van de eerste build draaien.

Oorzaak: `deploy.yml` gaf op een PR altijd dezelfde image-tag mee (`pr-<n>`). Die tag komt via
`zad-actions/deploy` in Operations Manager en belandt in het gerenderde k8s-manifest in
`rig-cluster-application-test`. Bij een tweede commit is dat manifest byte-identiek — dezelfde
`image:`-regel stond er al. Argo CD synct op verschil in het manifest, ziet er geen, en doet
niets. `imagePullPolicy: Always` verandert dat niet: dat werkt alleen bij een herstart van de
pod, en die komt niet vanzelf. Op `main` speelde het niet, want `main-<sha7>` wijzigt per commit.

Gemeten bewijs (PR #168): `odcn-production/mpfm-w3h/pr-168/magazijna-deployment.yaml` was sinds
2026-08-07 09:56 onveranderd, terwijl de deploy van 2026-08-10 07:40 groen stond. Die deploy
raakte in de PR-map alleen `decrypt-sops.yaml`, `kustomization.yaml` en de twee
`*-user-secret.sops.yaml` — geen enkel `*-deployment.yaml`.

Waarom dit erger is dan een vertraging: een groene deploy-check zegt alleen dat het image
bouwde en dat OM de aanroep accepteerde. Wie op de preview verifieert kijkt vanaf de tweede
commit naar oude code, zonder signaal dat dat zo is.

## Afweging

### 1. Deployen op digest (`image: …@sha256:…`)

De digest wijzigt zodra de inhoud wijzigt, dus het manifest ook. De tag `pr-<n>` kan blijven
bestaan, waarmee de bestaande cleanup ongewijzigd werkt.

Onderzocht in `RijksICTGilde/zad-actions@13434cd` (v4): de `deploy`-action valideert alleen de
component-*naam* (`^[a-zA-Z0-9._-]+$`) en geeft het image-veld ongewijzigd door aan
`zad-cli@v0.8.0`. Die valideert in `api/models.py` eveneens alleen `Component.name`; de
OM-OpenAPI (`api/upstream-openapi.json`) legt geen patroon op `image`/`newImageUrl` op. Een
digest-referentie wordt dus waarschijnlijk geaccepteerd — maar dat is een afwezigheid van
validatie, geen bewijs dat OM's manifest-rendering en de pull-through-mirror er goed mee
omgaan. Dat pad is niet zonder een echte deploy vast te stellen.

Zwaarder weegt de opruimkant. Jib bouwt reproduceerbaar: identieke sources geven een identieke
digest. Voor de tag betekent dat weinig, maar het maakt de digest ongeschikt als *label* van
een PR: een ghcr-versie draagt geen PR-nummer, en na het verplaatsen van de tag `pr-<n>` is de
vorige versie ongetagd. Ongetagde versies zijn niet meer aan een PR toe te wijzen en dus niet
gericht op te ruimen.

Dat is geen theorie: `fbs-berichtenmagazijn` telde bij het schrijven van dit plan ~237
ghcr-versies waarvan ~157 ongetagd — precies de tussenbuilds die het verplaatsen van `pr-<n>`
achterliet. De huidige cleanup ruimt per package één exacte tag op en laat die berg staan.

### 2. Unieke tag per commit (`pr-<n>-<sha7>`) — gekozen

Elke build krijgt een eigen tag, dus elk manifest wijzigt, dus Argo rolt uit. Geen aannames
over wat OM met een referentievorm doet: de vorm blijft `repo:tag`, precies zoals nu op `main`
(waar de per-commit-tag al jaren werkt — dat is meteen het bewijs dat dit pad werkt).

De cleanup moet dan wél alle tags van de PR opruimen in plaats van één. Dat kan, en het is
strikt beter dan vandaag: elke build houdt een tag, dus elke build blijft toewijsbaar aan zijn
PR en gaat bij het sluiten mee. Het ongetagde-versies-lek stopt daarmee voor nieuwe PR's.

Kosten: bij twee builds van identieke sources rolt Argo een identiek image opnieuw uit (jib
levert dan dezelfde digest, dus ghcr hangt beide tags aan dezelfde versie). Dat is één
overbodige herstart in een randgeval, tegenover een preview die anders stil verkeerd staat.

### 3. Iets aan ZAD-zijde dat een rollout forceert

Bestaat niet. `:refresh` reconcilet zonder revisiewijziging, en een deployment herscheppen
(`DELETE` + `:upsert-deployment`) draait op projecten met de `postgresql-database`-service een
`database_cleanup` — dat vernietigt data en is geen routine-oplossing.

### Stand aan ZAD-zijde

Niet uitrollen bij een ongewijzigde image-referentie is aan ZAD-zijde opzet, geen fout
(bevestigd door het ZAD-team, 2026-08-10): bij een deployment met tien componenten mag één
gewijzigde image niet alle tien vervangen. De oplossing moet daar dus binnen passen, niet
omheen — en dat doet een per-commit wijzigende referentie ook: ZAD rolt uit omdat de referentie
écht anders is.

Wel de keerzijde benoemen: een tag die de commit-sha draagt wijzigt voor álle componenten bij
elke commit, ook als alleen `berichtenuitvraag` veranderde. Op deze drie projecten (vijf
app-pods, plus redis/clickhouse die hun eigen vaste pin houden) is dat goedkoop, maar het is
precies de granulariteit die ZAD bewaakt. Wie die wil behouden, deployt op digest en pusht
daarnaast de unieke tag voor de opruimkant: een ongewijzigd component levert dan dezelfde
digest en rolt niet, een gewijzigd component wel. Zie "Openstaand".

### Conclusie

Richting 2. Richting 1 lost het bevriezen even goed op, maar laat het opruimprobleem staan en
vraagt bovendien een niet-verifieerbare aanname over de OM-keten. Richting 2 heeft één nadeel
(een zeldzame overbodige rollout) en één extra stuk workflow-code, en lost het ghcr-lek meteen
mee op.

## Wijzigingen

`.github/workflows/deploy.yml`

- `meta`: PR-tag wordt `pr-<n>-<sha7>` op basis van `github.event.pull_request.head.sha` — de
  sha die in de PR-tijdlijn staat, niet de efemere merge-commit uit `GITHUB_SHA`. De
  interpolaties gaan via `env:` (defense-in-depth injectie), zoals de rest van de workflow.
  De `main`-tak blijft `main-<sha7>`.
- `meta`: bij een herdraai (`GITHUB_RUN_ATTEMPT > 1`) krijgt de tag een `-r<n>`-achtervoegsel.
  Een herdraai bouwt uit `refs/pull/<n>/merge` met de main van dát moment; is main inmiddels
  verder, dan levert dezelfde head-sha andere inhoud onder dezelfde tag op — de bevriezing die
  deze tagvorm juist moet uitsluiten, plus een ongetagde weesversie. Poging 1, het normale
  geval, houdt de kale leesbare tag.
- `build` en `build-externe-stubs` slaan een docs-only PR over (`needs: changes`), net als de
  `gate` en de deploys dat al deden.
- `cleanup-preview-*`: `delete-container: 'false'`; de `containers`-lijst en de daarvoor
  benodigde `packages: write` + `needs: meta` zijn weg.
- Nieuw: `cleanup-preview-images` ruimt bij PR-sluiten alle ghcr-versies op waarvan minstens
  één tag begint met `pr-<n>-`. Het afsluitende koppelteken is essentieel: zonder dat zou het
  sluiten van PR 16 de images van PR 168 meenemen. De job faalt zichtbaar (rood) als een
  verwijdering mislukt, zodat een groeiende ghcr-berg niet ongemerkt ontstaat.

Bewust géén package-brede fallback bij "cannot delete the last tagged version" (die de
`zad-actions`-cleanup wél heeft): dat zou een preview-cleanup het hele package laten
verwijderen, main-images incluis. De `main-<sha7>`-tags zorgen ervoor dat die situatie zich
niet voordoet.

`CLAUDE.md`: de tag-conventie in "ZAD deploy & GitOps (debug)" bijgewerkt, plus de reden dat
image-tags uniek per commit moeten zijn.

## Digitale verspilling

Een review met de duurzaamheidsbril (NeRDS 13, green coding) leverde één harde vondst op, met
bewijs uit deze PR zelf. De `build`-jobs hingen niet aan de `changes`-filter, terwijl de `gate`
en de deploys dat wel deden. Een docs-only commit bouwde dus twee volledige Maven/jib-images
plus de WireMock-image en pushte die naar ghcr, waarna er niets mee gebeurde: de commits
`3e2866c` en `941933a` van deze PR raakten alleen `docs/plans/*.md` en hebben desondanks
`pr-172-3e2866c` en `pr-172-941933a` in beide service-packages staan.

Dat kostte per docs-commit een volledige buildcyclus (Maven-augmentatie, jib-push, docker
build/push) zonder enige afnemer. Met de unieke tags weegt het bovendien zwaarder dan voorheen:
elke overbodige build laat nu een eigen ghcr-versie achter in plaats van een tag te verplaatsen.
De jobs hangen nu aan dezelfde filter als de rest.

Tweede vondst, uit dezelfde review: **we bouwden drie images per bot-PR die nooit gedeployd
worden.** `zad-actions/deploy` weigert PR's van bots (`skip-bot-prs`, default `true`) — in de
log van de Dependabot-PR #167 staat letterlijk `Skipping: PR author 'dependabot[bot]' is a bot`.
Onze build-jobs kenden die regel niet en pushten er wél images voor. De cleanup-action slaat
bot-PR's óók over, dus die images bleven staan: `pr-143` en `pr-167` staan er vandaag nog,
terwijl beide PR's op 2026-08-07 gesloten zijn. Per Dependabot-PR kostte dat twee Maven/jib-
builds plus een docker-build, en een permanente ghcr-versie. De `changes`-job zet `run=false`
voor bot-PR's, waarmee ook de `gate` (die minutenlang op checks polt) vervalt.

Blijft staan als bewuste afweging: elke commit vernieuwt de tag van alle app-componenten, dus
rollen ze alle mee. Zie "Openstaand" — zonder reproduceerbare build valt daar niets aan te
winnen, ook niet met een digest.

## Randvoorwaarden getoetst

- **Alle drie projecten.** De tag komt uit één `meta`-job; de jib-services en de
  docker-gebouwde externe-stubs gebruiken dezelfde output. Geen van beide bouwpaden kent de
  tagvorm.
- **Geen weesversies.** Elke gepushte PR-versie houdt een unieke tag en valt onder de prefix
  die de cleanup opruimt.
- **Actions SHA-gepind.** Er komt geen nieuwe action bij; `cleanup-preview-images` draait op
  `gh`/`jq` van de runner.
- **`pin-consistency.yml`.** Bewaakt alleen `redis/redis-stack-server` en
  `clickhouse/clickhouse-server`; die pins zijn niet geraakt.

## Verificatie

Een groene CI-run is hier geen bewijs — dat was juist het probleem. Aangetoond op PR #172 zelf,
met `magazijna` in project `mpfm-w3h`.

De tweede commit zette tijdelijk een `%prod`-only responseheader `X-Preview-Verificatie:
commit-2` op berichtenmagazijn; die is in de commit erna weer verwijderd. Alleen `%prod`, zodat
tests en dev-mode er niets van merkten.

1. Ná commit 1 (`6e8616f`): manifest
   `odcn-production/mpfm-w3h/pr-172/magazijna-deployment.yaml` op commit `b016a5a`
   (08:24:07Z), `image: …/fbs-berichtenmagazijn:pr-172-6e8616f`. Preview bereikbaar, header
   afwezig.
2. Ná commit 2 (`95f59fb`): nieuwe manifest-commit `7e69327` (08:37:10Z) met
   `image: …/fbs-berichtenmagazijn:pr-172-95f59fb` — de regel die bij PR #168 drie dagen
   onveranderd bleef.
3. Argo rolde uit: `curl` op de preview gaf `x-preview-verificatie: commit-2`. Het draaiende
   component vertoonde dus het gedrag van de tweede commit.

De opruimkant is drooggetest tegen de echte ghcr-data: het filter van
`cleanup-preview-images` (`tags | any(startswith("pr-172-"))`) selecteerde beide versies
(`pr-172-6e8616f` én `pr-172-95f59fb`), niet alleen de laatste. De prefix `pr-17-` selecteert
ze niet — het afsluitende koppelteken doet zijn werk.

## Openstaand

**Per-component granulariteit.** Elke commit vernieuwt nu de tag van alle app-componenten, dus
rollen ze alle mee terwijl er vaak één service wijzigde.

Deployen op digest lost dat níét op, ondanks wat je zou verwachten. Gemeten op de vijf builds
van deze PR: `berichtenuitvraag` werd geen byte gewijzigd en commit `3fb1f96` draaide de
voorgaande commit exact terug, en tóch heeft elke build een eigen digest (magazijn:
`341796cb…`, `bbba6900…`, `5fc3d971…`, `93715c51…`, `ed7804c5…`). Onze jib-build is dus niet
reproduceerbaar: de digest volgt de build, niet de inhoud. Een digest-deploy zou hetzelfde
rolgedrag geven als de unieke tag, plus een resolve-stap en een onbewezen aanname over OM en de
pull-through-mirror.

Wie de granulariteit écht wil, moet eerst de build reproduceerbaar maken (build-timestamps
pinnen, `SOURCE_DATE_EPOCH`). Dat is een eigen traject; pas daarna wordt een digest-deploy
zinvol.

**Achtergebleven bot-PR-images.** De images van eerdere Dependabot-PR's (`pr-143`, `pr-167` en
soortgenoten) zijn al gepusht en worden door geen enkele cleanup meer opgehaald: die PR's zijn
gesloten en hun cleanup-run heeft ze overgeslagen. Ze zijn wél getagd en dus herkenbaar; een
eenmalige opruimactie kan, maar verwijdert onherstelbaar en hoort niet in deze wijziging.

De ~157 bestaande ongetagde ghcr-versies van `fbs-berichtenmagazijn` (en de overeenkomstige
van de andere twee packages) blijven staan: ze zijn niet aan een PR toe te wijzen. Ze
opruimen vraagt een eigen afweging (een ongetagde versie kan ook een index-/attestatie-kind
zijn) en valt buiten deze wijziging.
