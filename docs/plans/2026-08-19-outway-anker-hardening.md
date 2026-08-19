# Het trust anchor voor de outway: gericht toepassen en zichtbaar maken

**Status:** Concept

Gestapeld op `feature/outway-https-clusterip` (PR #219). Dat werk gaf `berichtenuitvraag` een
trust anchor voor de interne PKI van de eigen FSC-outway. Een review daarop leverde drie
bevindingen op die allemaal dezelfde vorm hebben: **het risico is netjes opgeschreven waar deze
codebase het elders afdwingt.**

## De drie bevindingen

**Het anchor gaat naar élke magazijn-client.** De regel erboven discrimineert wel:
`fscFilterVoor(inschrijving)` schakelt op `inschrijving.grantHash`, en dat veld ís de "loopt via de
outway"-vlag. Een magazijn zonder grant-hash wordt rechtstreeks met een publiek certificaat
gebeld en krijgt met de huidige code toch de interne CA als enig anchor. Omdat een named
TLS-configuratie de JVM-default trust-store vervángt, klapt zo'n magazijn op `PKIX path building
failed`. Die valkuil staat nu als waarschuwing op vier plekken; de conditie staat één regel hoger.

**Een typefout in de configuratie-naam degradeert geruisloos.** `quarkus.tls.<naam>.*` is
map-shaped, dus elke naam is een geldige sleutel — geen "unrecognized configuration key". De
bucket wordt bij boot zelfs eager gevalideerd, dus het PEM-bestand wordt geopend en gelezen. Maar
`registry.get("outway")` vindt hem niet, levert `null`, en het verkeer valt terug op de
JVM-default trust-store. Een fout *pad* faalt de boot dus hard; een fout *naam* niet. De operator
ziet vervolgens een handshake-fout die als netwerkstoring geclassificeerd wordt en zoekt op de
verkeerde plek.

**De schakelaar staat ook aan bij configuratie die vertrouwen juist ópheft.**
`quarkus.tls.outway.trust-all=true` of hostname-verificatie `NONE` maakt de bucket net zo goed
"aanwezig". Wie dat tijdens een incident even aanzet om een route te bewijzen, schakelt
certificaatvalidatie uit voor al het magazijn-verkeer — zonder boot-check, zonder waarschuwing,
zonder alert-token, en de env-var overleeft een herschepping. `RedisVerbindingValidator` bewaakt
exact deze twee knoppen voor de berichtenopslag; voor uitgaande REST-clients bestaat die check
niet.

## Ontwerpkeuzes

**Gate op de inschrijving, niet op de deployment.** `grantHash != null` betekent al "loopt via de
outway". Daarmee wordt het anchor per magazijn correct in plaats van per omgeving, en vervalt de
operationele afspraak ("scope het per deployment") die nu in twee documenten staat en een
herschepping niet overleeft.

Eén gevolg hoort expliciet genoemd: het huidige, ongegate gedrag is per ongeluk een **tripwire**
voor de configuratiedrift uit MinBZK/MijnOverheidZakelijk#953. Valt de deployment-env weg, dan
verdwijnt óók de grant-hash en klapt het verkeer op PKIX in plaats van stil terug te vallen op het
directe pad. Met de gate wordt die terugval weer stil. Dat is geen reden om de gate te laten
zitten — op een toevallig neveneffect leunen is geen ontwerp — maar wel de reden dat de
boot-validatie hieronder in dezelfde wijziging hoort.

**Valideer de config-keys, niet de registry-uitkomst.** Alleen zo is het verschil zichtbaar tussen
"geen anchor geconfigureerd" en "anchor geconfigureerd onder een andere naam". `registry.get()` kan
die twee niet onderscheiden; `config.propertyNames` wel — dezelfde aanpak als
`RedisVerbindingValidator.valideerAlleClients`.

**Het anchor verhuist naar `fbs-common`.** De constante staat nu in `fbs-magazijnregister` omdat dat
de laagste module was die beide magazijn-consumers zien. Maar de profiel-service-client woont in
`fbs-common`, en `fbs-common` kan `fbs-magazijnregister` niet importeren — de afhankelijkheid
loopt andersom. Daardoor kan juist de derde consument de constante niet bereiken. In
`common/fsc`, naast het bestaande `FscOutwayHeaders`, is hij voor alle drie bereikbaar en staat
hij thematisch goed: dit is FSC-transportkennis, geen registerkennis.

**De registry wordt niet-nullable.** De nullable parameter bestond zodat unit-tests de bean zonder
CDI konden bouwen, maar de lege `Optional` uit `get()` modelleert "geen anchor" al volledig. Dat
haalt tegelijk de `@Inject constructor`-workaround weg, en de twee identieke commentaarblokken die
'm uitleggen.

## Stappen

1. `OutwayTls` naar `fbs-common`, package `nl.rijksoverheid.moz.fbs.common.fsc`.
2. `OutwayTlsValidator` in `fbs-common`: leest de `quarkus.tls.*`-config-keys, logt bij boot in
   beide takken welke modus geldt, en weigert buiten dev/test een `outway`-bucket met `trust-all`
   of hostname-verificatie `NONE` — tenzij een expliciete klep aan staat, en dan luid, met een
   stabiel alert-token.
3. `MagazijnRouter` en `MagazijnClientFactory`: anchor alleen bij `grantHash != null`, registry
   niet-nullable, `@Inject constructor` en de bijbehorende comments weg.
4. Tests: een `@QuarkusTest` met echte TLS in `fbs-berichtensessiecache` (het eager boot-pad van de
   factory, dat nu alleen een mock-registry kent), een tweede magazijn zonder grant-hash dat
   bewijst dat het anchor hem niet raakt, de letterlijke waarde van de configuratie-naam pinnen, en
   de validator zelf.
5. De testfixture harden: timeout op `keytool`, opruimen bij een falende `start()`, en de
   ongebruikte plaintext-listener uit.
6. Documentatie: de "vervangt de default trust-store"-waarschuwingen kunnen smaller, want ze
   gelden nu alleen nog binnen het outway-verkeer. Achtergebleven `RestClientBuilder`-comments in
   drie testbestanden bijwerken.

## Verificatie

- `./mvnw clean verify` groen voor de drie geraakte modules.
- De roundtrip-test blijft falen als de koppeling wordt uitgezet (negatieve controle uit #219).
- Een tweede magazijn zonder grant-hash blijft bereikbaar terwijl het anchor geconfigureerd is —
  dat is de bevinding die deze PR structureel wegneemt.

## Buiten scope

- De fout-taxonomie (`classifyMagazijnFault`) een eigen TLS/trust-klasse geven, zodat een
  permanente PKI-fout niet als transiënte netwerkstoring de circuit breaker opent.
- De cross-check tussen de trust-store-env-var en `tls-configuration-name` op de
  profiel-service-client.
- `berichtenmagazijn` heeft dezelfde outway-constructie richting de profiel-service en zal
  hetzelfde anchor nodig hebben zodra magazijn-a's outway TLS spreekt.
