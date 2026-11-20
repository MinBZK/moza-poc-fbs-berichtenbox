# De demo op Fundament

`fbs-demo` installeert de demo van het Federatief Berichtenstelsel op een cluster met Gateway API,
zoals Fundament: de berichtenbox van de proeftuin, het bedieningspaneel, de demo-identiteiten, de
magazijn-simulator, de stubs, Toxiproxy en Redis. Samen met vier releases van
[`fbs-dienst`](../README.md) (uitvraag, magazijn A en B, stelselregister) is dat dezelfde demo als
op de testomgeving. **Alleen voor demo's, met demo-gegevens. Het draait nooit in productie.**

Het ontwerp met de afwegingen staat in
[`docs/plans/2026-11-20-demo-op-fundament.md`](../../docs/plans/2026-11-20-demo-op-fundament.md).

| Pad | Inhoud |
|---|---|
| `values.yaml` | wat per omgeving verschilt: basisdomein, Gateway, image-tag, geheimen, database |
| `templates/_componenten.tpl` | de onderdelen en hun bedrading op één plek: images, poorten, env, wie wie aanroept |
| `stelsel/` | de values waarmee de vier releases van `fbs-dienst` in de demo passen |
| `ci/` | het scenario dat `charts/controleer.sh` doorloopt |

## Wat er draait

De browser kent één adres, `demo.<basisdomein>`. Daarachter zet een nginx (de demo-proxy) alles
onder één origin, zoals lokaal in `compose.yaml`:

| Pad op `demo.<basisdomein>` | Naar | Wachtwoord |
|---|---|---|
| `/moza/berichtenbox/` en de rest van `/` | de berichtenbox van de proeftuin | nee |
| `/bediening/` | het bedieningspaneel | ja |
| `/api/demo/personas` | de demo-identiteiten (de berichtenbox kiest er een testaccount mee) | nee |
| de rest van `/api/demo/` | het gereedschap van het paneel: storingen, vullen, legen | ja |
| `/api/v1/` | de uitvraag | nee |

De andere routes zijn er omdat de diensten elkaar over https aanroepen. Buiten het profiel `dev`
eisen ze dat, en het magazijn eist daarnaast een publiek adres:

| Hostnaam | Naar | Voor |
|---|---|---|
| `berichtenmagazijn-a.<basisdomein>`, `berichtenmagazijn-b.<basisdomein>` | magazijn A (RVO) en B (Belastingdienst) | de uitvraag |
| `simulator.<basisdomein>` | de 98 gesimuleerde magazijnen, alleen `/magazijn/` | de uitvraag |
| `profiel.<basisdomein>` | de profiel-stub, via Toxiproxy | uitvraag en magazijnen |
| `notificatie.<basisdomein>` | de notificatie-stub, via Toxiproxy | de magazijnen |
| `aanmelding.<basisdomein>` | de uitvraag (`/api/v1/aanmeldingen`), via Toxiproxy | de magazijnen |
| `stelselregister.<basisdomein>` | het stelseldocument | afnemers |

Profiel, notificatie en aanmelding lopen via Toxiproxy. Zo zet een storingsknop van het paneel ook
dat verkeer uit. Redis zit voor de uitvraag ook achter Toxiproxy. Het paneel spreekt Redis
rechtstreeks aan, zodat het de sessiecache ook tijdens een Redis-storing kan wissen.

## Wat het cluster vooraf nodig heeft

De chart maakt geen geheimen, databases of Gateway. Maak ze vooraf, in deze volgorde. Op het
fieldlab is iedereen in de organisatie beheerder van elk project: gebruik alleen geheimen die voor
dit cluster bestaan, en zet er alleen demo-gegevens achter.

1. **Namespace.** Maak project en namespace in de console van Fundament (prefix `fbs-`). De
   namespace heet `tnt-<project>--<naam>`. Zie ook "Op Fundament" in [`../README.md`](../README.md).

2. **Gateway, adres en certificaat.** Eén Gateway (`web`) met een https-listener `https` op 443,
   hostnaam `*.<basisdomein>`. Wacht op het publieke adres (`kubectl get gateway web`) en kies dan
   het basisdomein, bijvoorbeeld `fbs.1-2-3-4.sslip.io` voor adres 1.2.3.4. Het certificaat noemt
   alle acht hostnamen. Let's Encrypt geeft met HTTP-01 geen wildcard. Maak daarom een
   `Certificate` met de namen expliciet, en zet geen `cert-manager.io/issuer` op de Gateway: die
   annotatie vraagt een certificaat voor de wildcard aan. HTTP-01 heeft ook een `http`-listener op
   80 nodig. De routes hangen alleen aan `https`.

   ```yaml
   apiVersion: cert-manager.io/v1
   kind: Certificate
   metadata:
     name: web
   spec:
     secretName: web-tls
     issuerRef: {name: letsencrypt, kind: Issuer}   # een Issuer in de eigen namespace
     dnsNames:
       - demo.<basisdomein>
       - simulator.<basisdomein>
       - profiel.<basisdomein>
       - notificatie.<basisdomein>
       - aanmelding.<basisdomein>
       - berichtenmagazijn-a.<basisdomein>
       - berichtenmagazijn-b.<basisdomein>
       - stelselregister.<basisdomein>
   ```

3. **Databases** uit CloudNativePG: `db` voor beide magazijnen en de simulator (elk een eigen
   schema), en `ldv-uitvraag` voor het logboek van de uitvraag. `db` krijgt meer verbindingen dan
   de standaard 100. Daar komen magazijn A en B met hun logboek, de simulator (20) en het paneel
   (4) samen.

   ```yaml
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata:
     name: db
   spec:
     instances: 1
     imageCatalogRef: {apiGroup: postgresql.cnpg.io, kind: ClusterImageCatalog, name: fundament-postgresql, major: 18}
     postgresql:
       parameters:
         max_connections: "200"
     storage: {size: 2Gi}
   ```

   `ldv-uitvraag` op dezelfde manier, zonder de parameters. CloudNativePG maakt per Cluster de
   Secrets `<cluster>-app` en `<cluster>-ca`. De chart en de values onder `stelsel/` lezen die.

4. **Geheimen**, als bestand en niet op de opdrachtregel. Een opdrachtregel is via `ps` zichtbaar
   voor andere gebruikers.

   ```bash
   umask 077
   openssl rand -hex 32 >redis-wachtwoord
   openssl rand -hex 32 >beheer-token
   kubectl -n $NS create secret generic fbs-demo-geheimen \
     --from-file=REDIS_PASSWORD=redis-wachtwoord --from-file=BEHEER_TOKEN=beheer-token

   # Het bedieningspaneel: één regel per gebruiker, het wachtwoord in een bestand.
   printf 'demo:%s\n' "$(openssl passwd -6 -stdin <paneel-wachtwoord)" >htpasswd
   kubectl -n $NS create secret generic fbs-demo-bediening --from-file=htpasswd=htpasswd

   # Een eigen ondertekensleutel voor het stelselregister, alleen voor dit cluster.
   (cd demo/environment/stelselregister/pki && STELSELDOCUMENT_OMGEVING=demo ./maak-keten.sh)
   kubectl -n $NS create secret generic stelselregister-keystore \
     --from-file=keystore.p12=demo/environment/stelselregister/pki/out/keystore.p12
   kubectl -n $NS create secret generic stelselregister-geheimen \
     --from-file=STELSELDOCUMENT_KEYSTORE_WACHTWOORD=demo/environment/stelselregister/pki/out/wachtwoord
   ```

## Installeren

Eerst de demo, dan het stelsel. De uitvraag en het stelselregister lezen het magazijnregister uit
een ConfigMap van `fbs-demo`. Installeer de demo onder de releasenaam `fbs-demo`: de values onder
`stelsel/` noemen de onderdelen bij die naam.

```bash
NS=tnt-fbs--demo                     # de namespace uit de console
BASIS=fbs.1-2-3-4.sslip.io           # het basisdomein uit stap 2
TAG=main-<sha7>                      # zie hieronder
PROEFTUIN=$(.github/scripts/proeftuin-image.sh)

# Het register en de set van de simulator. Het aantal (98) past bij de profiel-stubs in het image.
SIMULATOR_URL='${MAGAZIJN_SIMULATOR_URL}' python3 demo/genereer-magazijnen.py

helm upgrade --install fbs-demo charts/fbs-demo --namespace $NS \
  --set basisdomein=$BASIS --set cluster.gateway.name=web \
  --set images.tag=$TAG --set images.proeftuin=$PROEFTUIN \
  --set-file magazijnenregister=demo/generated/magazijnen-register.properties \
  --set-file simulatorSet=demo/generated/magazijn-simulator.properties

STELSEL=(--namespace $NS --set demo.basisdomein=$BASIS --set dienst.image.tag=$TAG
  --set dienst.tlsTermination=mesh --set cluster.gateway.name=web)

helm upgrade --install berichtenuitvraag charts/fbs-dienst "${STELSEL[@]}" \
  -f charts/fbs-dienst/diensten/berichtenuitvraag.yaml -f charts/fbs-demo/stelsel/berichtenuitvraag.yaml

for m in a b; do
  helm upgrade --install berichtenmagazijn-$m charts/fbs-dienst "${STELSEL[@]}" \
    -f charts/fbs-dienst/diensten/berichtenmagazijn.yaml -f charts/voorbeelden/berichtenmagazijn-cnpg.yaml \
    -f charts/fbs-demo/stelsel/berichtenmagazijn.yaml -f charts/fbs-demo/stelsel/berichtenmagazijn-$m.yaml \
    --set ingress.hostname=berichtenmagazijn-$m.$BASIS
done

helm upgrade --install stelselregister charts/fbs-dienst "${STELSEL[@]}" \
  -f charts/fbs-dienst/diensten/stelselregister.yaml -f charts/fbs-demo/stelsel/stelselregister.yaml \
  --set ingress.hostname=stelselregister.$BASIS
```

**De tag.** Alle acht images hebben dezelfde tag: de drie stelseldiensten en `fbs-demo-console`,
`fbs-demo-personas`, `fbs-magazijn-simulator`, `fbs-demo-profiel` en `fbs-externe-stubs`. Niet elke
commit op main publiceert ze alle acht. De keten bouwt alleen wat een wijziging raakt. Kies een
`main-<sha7>` waarvoor ze allemaal bestaan:

```bash
for i in berichtenuitvraag berichtenmagazijn stelselregister demo-console demo-personas \
    magazijn-simulator demo-profiel externe-stubs; do
  docker manifest inspect ghcr.io/minbzk/fbs-$i:$TAG >/dev/null && echo "ok   $i" || echo "MIST $i"
done
```

**De berichtenbox** draait de versie die in `compose.yaml` is gepind, dezelfde als de rest van de
demo. `.github/scripts/proeftuin-image.sh` leest die regel. De chart weigert een referentie zonder
digest.

Na de installatie:
- de berichtenbox op `https://demo.<basisdomein>/moza/berichtenbox/`;
- het paneel op `https://demo.<basisdomein>/bediening/`, met de gebruiker uit het htpasswd.

Het paneel vult bij het opstarten elk leeg magazijn met de basisvulling, en de simulator vult
zichzelf. Een magazijn weigert aanleveringen zolang de profiel-stub niet bereikbaar is; het paneel
probeert het tot dertig minuten opnieuw.

## De scenario's op Fundament

Dezelfde scenario's als in [`docs/demo-runbook.md`](../../docs/demo-runbook.md#9-de-scenarios-spelen)
§9, met de beperkingen van de testomgeving op ZAD:

| # | Scenario | Op Fundament |
|---|---|---|
| 1 | Berichten succesvol opgehaald | werkt |
| 2 | Trager dan normaal | **niet** met de echte magazijnen. Hun storingsknoppen ontbreken: de uitvraag spreekt ze via hun eigen route aan, niet via Toxiproxy. Zo is het ook op ZAD. De gesimuleerde magazijnen hebben trage organisaties |
| 3 | Magazijnen onbereikbaar | met de gesimuleerde magazijnen (*Actief aantal*); de echte niet, zie 2 |
| 4 | Enkele magazijnen antwoorden laat | met de gesimuleerde magazijnen; de echte niet, zie 2 |
| 5 | Nieuwe berichten tijdens de sessie | werkt |
| 6 | Cache-tijd verloopt | werkt met de knop *Cache verlopen*. Wachten duurt de standaardinactiviteit van de uitvraag, niet de twee minuten van de lokale demo |
| 7 | Bijlage wordt niet opgehaald | **niet**: vraagt *Magazijn A uit*, zie 2 |
| 8 | Foutieve aanlevering | werkt |
| 9 | Profielservice weg | werkt |
| 10 | Notificatieservice weg | werkt |
| 11 | Uitvraagsysteem eruit | werkt |
| 12 | Redis weg | werkt |
| 13 | Ontdubbeling | werkt |
| 15 | Niet alles opgehaald | werkt |
| M1-M3 | Mappen | werken |

De Redis-knop kondigt zich niet aan in Mattermost: dat hoort bij de bewaakte testomgeving.

## Wat er publiek staat

De Gateway krijgt op het fieldlab een publiek adres. Wat erachter staat:
- **zonder wachtwoord:** de berichtenbox, de personalijst, de uitvraag, de magazijnen, de
  simulator (alleen `/magazijn/`), de stubs en het stelseldocument. Zoals op ZAD: de API's
  authenticeren hun aanroepers niet. Wie de uitvraag bereikt, leest met elk demo-nummer in
  `X-Ontvanger` de berichten. Zet er daarom alleen demo-gegevens achter;
- **met wachtwoord:** het paneel en zijn gereedschap. De knop *legen* wist de magazijnen. De
  proxy geeft het wachtwoord niet door aan de diensten erachter;
- **alleen binnen het cluster:** `/q` van elke dienst, het beheer van de simulator (met
  `BEHEER_TOKEN`) en de admin-API van Toxiproxy. Die laatste heeft geen authenticatie, en alleen
  het paneel mag erbij.

Elk onderdeel draait als vaste non-root UID, met een read-only root-filesystem, zonder
capabilities en service-account-token, en achter een NetworkPolicy die alleen het verkeer uit
`templates/_componenten.tpl` doorlaat.

## Controleren zonder cluster

`charts/controleer.sh` neemt de demo mee: lint, kubeconform en de beleidsregels van `../policy/`
per release. Daarnaast toetst het de vijf releases samen (`../policy/demo/`). Dat vangt een
clientlabel zonder NetworkPolicy, een poort die de aanroeper niet uit mag, en een ConfigMap die geen
release maakt. Zulke fouten geven in het cluster een stille timeout.
