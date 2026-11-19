# Verlopen magazijnsessie apart te zien in de log

**Status:** Uitgevoerd

## Context

Bij rechtstreeks ophalen (`libraries/fbs-rechtstreeks`) geven een onbekend token en een verlopen
sessie allebei `Afwijzing.SESSIE_ONBEKEND`. Voor de app is dat goed: allebei een `401` met
dezelfde body en `WWW-Authenticate: Bearer realm="<OIN>", error="invalid_token"`, zodat een app er
niets uit afleidt. Voor de operator niet: hij ziet niet of een app met een verzonnen token komt of
net over de grens van vijftien minuten liep. Het eerste is een signaal, het tweede dagelijkse kost.

Het onderscheid kan niet alleen in `Verifier.vereisLopend`. `GeheugenOpslag.ruimOp()` draait bij
élke aanroep van de opslag, ook bij een challenge of sessie van een andere app, en haalt verlopen
sessies weg. Komt de app met zijn verlopen token terug, dan is de sessie meestal al weg en geeft
`zoek` `null`, net als bij een onbekend token.

## Afweging

**A. `zoek` geeft een verlopen sessie eerst terug, en ruimt daarna pas op.** Dit werkt alleen als
er tussen het verlopen en de terugkeer van de app niets anders in de opslag gebeurt. In de
simulator, met honderd gesimuleerde magazijnen en meerdere apps, is dat zelden zo: elke
challenge-aanvraag van een andere app ruimt de sessie al op. Het onderscheid zou dan afhangen van
de drukte, en een log die op drukke momenten anders meldt dan op rustige is niet te vertrouwen.
Verworpen.

**B. De opslag onthoudt verlopen tokens kort.** Bij het opruimen gaat een verlopen sessie naar een
tweede, begrensde verzameling. Die bewaart per Verifier alleen een hash van het token en het moment
van verlopen, geen sleutel en geen identiteit. `zoek` blijft wat het is. Een nieuwe vraag
`verlopen(verifier, token)` zegt of een token dat `zoek` niet vond kort geleden nog een sessie had.

Gekozen: B.

- **Geheugen.** Begrensd op `maxSessies` (standaard 50.000) en op een bewaartermijn (standaard een
  uur). Per vermelding is dat een hash van 32 bytes plus de verwijzing naar de Verifier-OIN en een
  `Instant`, dus enkele megabytes in het slechtste geval. Bij een volle verzameling valt de oudste
  vermelding weg. Die verliest dan alleen het onderscheid, niet de afwijzing: zo'n token wordt
  `SESSIE_ONBEKEND`.
- **Geheim.** Het token zelf blijft niet bewaard, alleen de SHA-256-hash. Het token is na het
  verlopen waardeloos, maar zo staat er ook in een heap-dump niets dat op een geldig token lijkt.
- **Vangnet van de opslag.** Ongewijzigd. `ruimOp` blijft bij elke aanroep opruimen, de quota
  tellen alleen levende sessies, en de nieuwe verzameling telt nergens mee.
- **Bewaartermijn.** Een uur dekt een app die even op de achtergrond stond. Een token dat langer
  geleden verliep, heet weer `SESSIE_ONBEKEND`. De operator ziet het verschil dan niet meer, maar
  bij die afstand is het ook geen gewone "net verlopen"-situatie meer.

## Stappen

1. `Afwijzing.SESSIE_VERLOPEN` naast `SESSIE_ONBEKEND`.
2. `SessieOpslag.verlopen(verifier, token): Boolean`. `GeheugenOpslag` vult de verzameling in
   `ruimOp` en snoeit haar daar ook, op termijn en op omvang.
3. `Verifier.sessie` vraagt bij een gemiste `zoek` naar `verlopen` en wijst af met de juiste reden.
   `vereisLopend` (de sessie verloopt terwijl het verzoek loopt) wijst af met `SESSIE_VERLOPEN`.
4. Simulator: `VerifierFoutMapper` zet `error="invalid_token"` voor beide redenen.

## Verificatie

- Library: onbekend, verlopen, verlopen tijdens het verzoek (`versleutel` en `ontsleutel`), de grens
  op precies `verlooptOp` en een seconde ervoor, het token van een andere Verifier, en de opslag
  na de bewaartermijn en bij een volle verzameling.
- Simulator (`RechtstreeksVerloopTest`, verzetbare klok): een verlopen en een onbekend token geven
  exact hetzelfde antwoord (status, `WWW-Authenticate`, body op `instance` na), en de log noemt
  `SESSIE_VERLOPEN` respectievelijk `SESSIE_ONBEKEND`.
