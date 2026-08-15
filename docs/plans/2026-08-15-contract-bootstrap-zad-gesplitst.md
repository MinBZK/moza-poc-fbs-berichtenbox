# Contract-bootstrap op ZAD: gesplitst per peer

**Status:** Concept

## Context

De contract-bootstrap uit #200 zet lokaal één wederzijds ondertekend
`ServiceConnectionGrant`-contract op tussen de uitvraag-peer (consumer) en elk magazijn
(provider). Eén script praat daarbij met **twee** managers: het POST bij de consumer en
accepteert bij de provider.

Op ZAD kan dat niet. Elk deployment krijgt een gerenderde
`<deployment>-tenant-baseline-network-policy` met `podSelector: {deployment: <naam>}`:

- **ingress** alleen van pods met hetzelfde `deployment`-label, de `rig`-ingresscontroller,
  `rig-prd-operations` en `rig-prd-backup`;
- **egress** alleen naar kube-dns, hetzelfde deployment, die twee namespaces,
  `<project>-infrastructure`, en `0.0.0.0/0` beperkt tot TCP 443/80.

De isolatie loopt dus per **deployment**, niet per project. Daar komt bij dat de
manager-internal-API (`:9443` authenticated, `:9444` unauthenticated) géén route heeft — de
ingress publiceert alleen backend-poort `:8443`, de mesh-poort met `ssl-passthrough`. De twee
peers zitten bovendien in aparte namespaces (`rig-prd-mpfb-8wh` / `rig-prd-mpfm-w3h`).

Eén proces dat beide managers aanspreekt bestaat op ZAD dus niet, en er is geen configuratie
die dat oplost: netwerkpolicies zijn op ZAD niet aanpasbaar.

## Gewenst resultaat

Elke peer bootstrapt zijn eigen kant tegen zijn **eigen** manager. Het contract kruist via de
FSC-mesh, precies de weg die de managers al gebruiken. De contract-API van geen enkele peer
wordt daarvoor van buiten bereikbaar gemaakt.

## Rolverdeling

De bestaande stroom valt uiteen langs de manager-grens:

| Stap | Manager | Rol na de splitsing |
|------|---------|---------------------|
| Outway-thumbprint berekenen | — (lokaal bestand) | consumer |
| Bestaat er al een geldig contract? | provider | **verhuist naar consumer** (zie hieronder) |
| `POST /v1/contracts` | consumer | consumer |
| Wachten tot het contract bij de provider staat | provider | provider (pollt zijn eigen lijst) |
| `PUT /v1/contracts/{hash}/accept` | provider | provider |
| Her-distributie van de accept-handtekening forceren | provider | provider |
| Wachten tot de consumer het contract geldig ziet | consumer | consumer |

De twee helften coördineren niet met elkaar; ze convergeren. De consumer dient in en wacht;
de provider ziet het contract via de mesh binnenkomen en tekent. Beide draaien herhaald, dus
de volgorde waarin ze starten doet er niet toe.

**De existence-check verhuist naar de consumer-kant.** Nu draait hij tegen de contractenlijst
van de provider. Dat kan straks niet meer, en het hoeft ook niet: hetzelfde contract staat na
de mesh-sync op beide managers. De consumer is bovendien de juiste kant om het aan af te
lezen — daar haalt de outway zijn grant vandaan.

Wel verandert de betekenis van een randgeval. Bij de provider zag de check alleen contracten
die de mesh gehaald hadden; bij de consumer telt ook een contract dat net is ingediend en nog
nergens anders bestaat. De matcher filtert daarom op geldige, niet-ingetrokken contracten —
een net ingediend, nog niet geaccepteerd contract is niet `valid` en telt dus niet mee als
"bestaat al". Dat is precies het gedrag dat we willen: anders zou een gestrande indiening
elke volgende run laten denken dat het werk gedaan is.

## De provider tekent niet blind

Nu is de accept een gerichte handeling: het script weet welk hash het zelf net heeft laten
indienen. Na de splitsing kent de provider dat hash niet — hij vindt een contract in zijn
lijst en moet zelf besluiten of hij tekent. Dat is een autorisatiebesluit, en de peer die het
contract aanbiedt bepaalt de inhoud ervan.

Een contract draagt een **lijst** grants. Alleen kijken of er een grant in zit die ons bevalt
is daarom niet genoeg: een tegenpartij kan een tweede grant meesturen die we per ongeluk
mee-ondertekenen. De regel is dus dat het contract als geheel moet kloppen.

De provider tekent een contract alleen als **alle** volgende dingen gelden:

1. het contract draagt **precies één** grant;
2. die grant is van type `GRANT_TYPE_SERVICE_CONNECTION`;
3. `grant.service.peer_id` is de **eigen** OIN;
4. `grant.service.name` staat in de lijst diensten die deze peer aanbiedt;
5. `grant.outway.peer_id` staat in de lijst toegestane consumer-OIN's;
6. het contract is nog niet door ons getekend en niet ingetrokken.

Faalt er één, dan laat de provider het contract met rust en meldt dat. Niet-tekenen is de
veilige uitkomst: zonder handtekening werkt het contract niet.

De thumbprint in de grant wordt **niet** getoetst. De provider kan hem niet onafhankelijk
verifiëren — hij heeft het outway-cert van de consumer niet — en hij hoeft dat ook niet: de
thumbprint zegt welke outway van díé consumer de dienst mag afnemen, en welke dat zijn is aan
de consumer. Wat de provider bewaakt is dat het om zijn eigen dienst gaat en om een consumer
die hij kent.

## Idempotentie

Blijft state-loos, op dezelfde grond als in #200: het bestaan van een contract wordt afgeleid
uit de contracten zelf.

- **Consumer:** service + provider-OIN + eigen OIN + thumbprint samen zijn de identiteit.
  Bestaat er een geldig contract voor die combinatie, dan is er niets te doen. Bij meer dan
  één trekt hij de overtollige in en houdt het gesorteerd-eerste hash aan — stabiel over runs.
- **Provider:** tekent alleen wat nog niet getekend is. Een tweede ronde vindt niets meer.

Beide helften draaien op ZAD in een lus. Herhaling is dus geen randgeval maar de normale
werking, en dat is precies waarom de idempotentie niet op een state-bestand mag leunen.

## Lokaal hetzelfde pad

`bootstrap.sh` wordt de lokale orkestrator: hij draait de consumer-helft en daarna de
provider-helft, elk met **alleen** de env van zijn eigen kant. De gedeelde stappen verhuizen
naar `lib/fsc-contract.sh`.

Dat is niet alleen ontdubbeling. Het lokale pad draait daarmee exact de code die op ZAD
draait, dus de bestaande federatie-smoke toetst de gesplitste implementatie in plaats van een
variant ervan. Zonder dat zou de ZAD-code alleen op ZAD bewezen kunnen worden, en daar is de
terugkoppeling traag.

De scheiding wordt afgedwongen door constructie: elke helft krijgt alleen de variabelen van
zijn eigen kant. Een aanroep naar de overkant kan niet per ongeluk blijven staan — er is geen
adres, cert of CA voor.

## ZAD-bedrading

**Vorm.** ZAD kent alleen langlopende componenten en staat geen component-args toe. De
bootstrap wordt dus een component met een eigen image, waarvan het entrypoint de rol uit env
leest, de lus draait en daarna blijft draaien. Een herstart is onschadelijk: de eerste ronde
constateert dan dat het werk al gedaan is.

**Image.** `fbs-fsc-contract-bootstrap`, gebouwd uit de contracts-directory: een kleine basis
plus `bash`, `curl`, `jq` en `openssl` — dezelfde afhankelijkheden die de scripts lokaal ook
hebben. Gebouwd en gepusht door dezelfde job-vorm als `fbs-externe-stubs`.

**Componenten.** Eén per peer, in de deployment van die peer, zodat de
NetworkPolicy-voorwaarde "zelfde `deployment`-label" geldt:

| Deployment | Component | Rol |
|------------|-----------|-----|
| `fsc-logius` (`mpfb-8wh`) | `logius-fscbootstrap` | consumer |
| `fsc-magazijna` (`mpfm-w3h`) | `magazijna-fscbootstrap` | provider |

**Client-cert.** De internal-API op `:9443` is mTLS. De component krijgt een eigen
internal-cert (`bootstrap`) uit `pki/issue.sh`, niet het cert van een andere component: wie
een cert deelt, deelt een identiteit, en dan is in het txlog niet meer te zien wie wat deed.

**Thumbprint.** De consumer-helft heeft de thumbprint van zijn outway nodig. Op ZAD komt die
uit env in plaats van uit een bestand, zodat het outway-group-cert niet aan een tweede
component hoeft te hangen. De waarde is stabiel zolang het sleutelpaar dat is; bij rotatie
binnen hetzelfde sleutelpaar verandert hij niet. Het runbook zegt hoe je hem berekent.

**Handwerk dat blijft.** Cert-attachments zijn UI-only — de v2-API kloont ze niet — dus de
eenmalige creatie van beide componenten en het koppelen van de certs gaat via het runbook,
zoals bij de bestaande peer-componenten. `deploy.yml` doet daarna alleen tag-updates.

## Wat hier niet in zit

Het datapad van de app naar de outway. Dat loopt op ZAD tegen dezelfde deployment-isolatie
aan, maar heeft de mesh niet als uitweg: de uitvraag moet er buitenlangs, via de publieke
outway-route, en die is als enige van de drie niet `ssl-passthrough` maar edge-terminated.
Dat vraagt een eigen afweging en een eigen issue.

## Verificatie

- De federatie-smoke blijft groen: één contract, wederzijds geldig, herhaalde run levert geen
  tweede op.
- Een smoke op de splitsing: elke helft raakt alleen zijn eigen manager aan, en de twee samen
  convergeren ongeacht de volgorde waarin ze draaien.
- Weiger-gevallen op de autorisatie-invariant: een contract met twee grants, met een vreemde
  dienst, met een onbekende consumer-OIN en met een vreemde provider-OIN wordt niet getekend.
- De ZAD-kant is pas na een deploy-ronde bewezen; tot dan draagt het runbook de stappen.
