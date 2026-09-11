# Preview-uitrol: de derde deploy weer parallel

**Status:** Uitgevoerd

## Aanleiding

De doorlooptijd van "Deploy ZAD" is sinds eind augustus verdubbeld. Gemiddelde duur van de
geslaagde runs, per dag:

| dag | gemiddelde |
|-----|-----------|
| 20-08 | 4,8 min |
| 25-08 | 5,1 min |
| 27-08 | 10,2 min |
| 04-09 | 12,2 min |
| 09-09 | 11,5 min |

`Test`, `detekt` en `CodeQL` bleven in diezelfde periode gelijk (Test zelfs iets sneller dan in
juli), en Deploy is een required check — de doorlooptijd van een PR is dus die van Deploy.

## Meting

Kritiek pad van run 34365432460 (12:55 in totaal) naast een run van 25 augustus (6:41):

| onderdeel | 25-08 | 09-09 |
|-----------|-------|-------|
| checks tot `gate` | 5:26 | 5:35 |
| deploy-preview-uitvraag | 63 s | 212 s |
| deploy-preview-externe-stubs | 50 s | 137 s |
| deploy-preview-magazijnen | 48 s, parallel | 212 s, serieel erná |

Drie oorzaken, alle drie uit eigen keuzes:

1. `deploy-preview-magazijnen` wachtte op de twee andere preview-deploys. Kostte 3:32 aan
   doorlooptijd, tegen ~50 s toen die twee jobs nog kort waren.
2. De projecten dragen meer componenten: magazijnen ging van 2 naar 6 (console, simulator,
   personadienst, proeftuin). De deploy-stap zelf duurt daardoor 155 s in plaats van ~50 s.
3. De netwerkregel-stappen erbij (96 s bij de uitvraag, 49 s bij de magazijnen).

Punt 2 en 3 zijn de prijs van de demo; punt 1 was gratis te herstellen.

## Wat er níét mis is

Componenten worden al in één keer aangeboden: de actie draait één
`zad deployment create <naam> --components '[…]'`, wat één taak in Operations Manager wordt. Dat
opknippen in een job per component kan ook niet — OM vergrendelt op project, dus gelijktijdige
taken in hetzelfde project eindigen als `superseded`: exitcode 0, geen `urls`, en een job die
faalt op een melding die de oorzaak niet noemt.

Hetzelfde geldt voor de netwerkregels: `patch_body` zet alle regels in één `{"add":[…]}` en wacht
op één taak. Daar viel alleen de pollgranulariteit te halen.

## Wijziging

`deploy-preview-magazijnen` draait weer parallel aan de twee andere deploys. Wat wél op alle drie
de deployments moet wachten — de outbound-netwerkregels en de preview-comment — verhuist naar een
eigen korte job `preview-afronding`.

De reden van de oude volgorde blijft gelden en staat nu bij die job: een cross-domain-regel noemt
een peer-deployment in een ánder project, en bestaat die peer nog niet bij het genereren, dan
slaat de resolver de regel over — een groene preview met dode storingsknoppen. Die eis geldt voor
het zetten van de regels, niet voor de deploy.

Verder:

- Beide deploy-jobs publiceren hun `urls` nu als job-output; de comment leest ze via `needs`.
- `preview-afronding` deelt de concurrency-groep `zad-magazijnen-pr-<n>` met de magazijnen-deploy
  en met de cleanup van dat project, zodat er nooit twee OM-taken tegelijk in `mpfm-w3h` lopen.
- `cross-domain-preview.sh` pollt elke seconde in plaats van elke twee; de time-out blijft twee
  minuten.

## Poort

`preview-afronding` draagt bewust geen `deploy-preview-`-voorvoegsel: `uitrol-poort` telt op dat
voorvoegsel de uitrol-jobs per as, en dit is er geen. De poort beoordeelt de job apart — success
bij een verwachte preview, skipped op een push en bij `deploy=false`. Zonder dat oordeel zou een
mislukte netwerkregel groen doorgaan: de job is zelf geen required check.

## Verwacht effect

De seriestaart gaat van 3:32 naar ~0:50; ongeveer 2,5 minuut op een run van bijna 13.

## Verificatie

- `bash .github/scripts/test-uitrol-poort.sh` (79 asserties, waarvan 7 nieuw voor de afronding)
- `bash .github/scripts/test-preview-comment.sh` (121 asserties)
- `bash .github/scripts/test-cross-domain-preview.sh`, `actionlint`, `shellcheck -x -S warning`
- Op de preview van deze PR: de storingsknoppen in de console indrukken. De magazijnen-deploy
  draait nu vóór de peers bestaan, dus dat de regels ná afloop alsnog goed gegenereerd worden is
  het enige dat een run moet aantonen.
