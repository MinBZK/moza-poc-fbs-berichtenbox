# Contract-bootstrap op ZAD: gesplitst per peer

**Status:** Uitgevoerd

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
| Wachten tot het contract bij de provider staat | provider | vervalt — de provider-helft kijkt bij elke aanroep één keer in zijn eigen lijst; het herhalen zit in de aanroeper |
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

Wel verandert de betekenis van een randgeval. Bij de provider zag de check alleen contracten die
de mesh gehaald hadden; bij de consumer telt ook een contract dat net is ingediend en nog nergens
anders bestaat. De matcher geeft die bewust wél terug, met hun state erbij. Alleen op "geldig"
filteren zou hier averechts werken: in de gesplitste opzet zit er tijd tussen indienen en tekenen,
en in dat gat zou elke ronde er nóg een contract bij posten. De consumer-helft leest de state dus
zelf: geldig én door de provider getekend is klaar, alles daartussen is wachten.

## De provider tekent niet blind

Nu is de accept een gerichte handeling: het script weet welk hash het zelf net heeft laten
indienen. Na de splitsing kent de provider dat hash niet — hij vindt een contract in zijn
lijst en moet zelf besluiten of hij tekent. Dat is een autorisatiebesluit, en de peer die het
contract aanbiedt bepaalt de inhoud ervan.

Een contract draagt een **lijst** grants. Alleen kijken of er een grant in zit die ons bevalt
is daarom niet genoeg: een tegenpartij kan een tweede grant meesturen die we per ongeluk
mee-ondertekenen. De regel is dus dat het contract als geheel moet kloppen.

De provider tekent een contract alleen als **alle** volgende dingen gelden:

1. het contract hoort bij onze eigen group;
2. het gebruikt het afgesproken hash-algoritme — dat bindt de content aan de handtekening;
3. het draagt een geldigheidsduur, en die blijft onder een bovengrens;
4. het draagt **precies één** grant;
5. die grant is van type `GRANT_TYPE_SERVICE_CONNECTION`;
6. `grant.service.peer_id` is de **eigen** OIN;
7. `grant.service.name` staat in de lijst diensten die deze peer aanbiedt;
8. de outway wordt geïdentificeerd op public-key-thumbprint en niet op domeinnaam;
9. `grant.outway.peer_id` staat in de lijst toegestane consumer-OIN's;
10. het contract is nog niet door ons getekend en niet ingetrokken.

Faalt er één, dan laat de provider het contract met rust en meldt dat. Niet-tekenen is de
veilige uitkomst: zonder handtekening werkt het contract niet.

De punten 1 tot en met 3 gaan niet over de grant maar over het contract eromheen. Zonder die
drie bepaalt de allowlist wel *wie* er mag afnemen, maar bepaalt de tegenpartij in zijn eentje
*hoe lang* en onder welke voorwaarden.

De **waarde** van de thumbprint wordt niet getoetst, het **type** wel (punt 8). De waarde kan de
provider niet onafhankelijk verifiëren — hij heeft het outway-cert van de consumer niet — en dat
hoeft ook niet: die wordt aan zijn eigen kant cryptografisch afgedwongen op een later moment. De
outway haalt zijn token bij de manager van de provider, die de presentatie tegen de thumbprint in
de grant houdt, en de inway verifieert `cnf.x5t#S256` tegen het verbindingscertificaat.

Twee dingen zijn geen eigenschap van het contract maar van de verwerking, en horen er toch bij.
Elke waarde uit het contract komt van de tegenpartij en gaat een regelgebaseerde stroom in; een
newline in een dienstnaam zou daar een tweede record schrijven dat de aanroeper als eigen regel
leest, en zo de hele toets omzeilen. Stuurtekens worden daarom vervangen. En een hash of OIN uit
die stroom gaat een URL-pad in, dus de vorm ervan wordt gecontroleerd voordat er een call op volgt.

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

De scheiding wordt afgedwongen en niet alleen afgesproken: elke helft start met `env -u` op de
adres- en certificaatvariabelen van de overkant. Een aanroep naar de overkant kan daardoor niet
per ongeluk blijven staan — er is geen adres, cert of CA voor. Het is een denylist, dus een
nieuwe variabele van de overkant moet er expliciet bij; de fixture-test bewaakt dat geen van
beide helften de namen van de overkant noemt.

## ZAD-bedrading

**Vorm.** ZAD kent alleen langlopende componenten en staat geen component-args toe. De
bootstrap wordt dus een component met een eigen image, waarvan het entrypoint de rol uit env
leest, de lus draait en daarna blijft draaien. Een herstart is onschadelijk: de eerste ronde
constateert dan dat het werk al gedaan is.

**Image.** `fbs-fsc-contract-bootstrap`: een kleine basis plus `bash`, `curl`, `jq` en `openssl` —
dezelfde afhankelijkheden die de scripts lokaal ook hebben. De build-context is
`demo/environment/` en niet de contracts-map zelf, want de scripts leunen op `../../lib`. Gebouwd
en gepusht door dezelfde job-vorm als `fbs-externe-stubs`.

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
