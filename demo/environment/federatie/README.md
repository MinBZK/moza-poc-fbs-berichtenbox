# Lokale FSC-federatie

Zet meerdere peer-harnessen uit `demo/environment/` naast elkaar neer als **één federatie**: één
directory, één group-CA, één SNI-router. Dat is wat je nodig hebt zodra je iets wilt beproeven dat
*tussen* peers speelt — een contract, een data-pad, service-discovery — in plaats van binnen één
peer.

Elke peer blijft zelfstandig draaibaar; zie zijn eigen `deploy/local/README.md`. Deze map voegt
alleen de compositie toe en verandert niets aan het standalone gedrag.

Op dit moment doen `logius` en `magazijn-a` mee. Beide dragen twee rollen: `logius` biedt
`profiel-service` en `notificatieservice` aan en neemt `berichtenmagazijn` af, `magazijn-a` biedt
`berichtenmagazijn` aan en pusht notificatie-events door zijn eigen outway.

**Linux + podman.** De scripts gebruiken `ss` (iproute2) en `podman`; op macOS draaien ze niet.

## Waarom dit nodig is

Elke peer-harness draait standalone een complete mini-federatie: zijn eigen postgres, router én
directory. Twee daarvan tegelijk starten werkt niet — ze claimen dezelfde poorten en, erger, elk
zou zijn eigen directory en group-root hebben, dus ze zouden elkaar niet vertrouwen.

De oplossing berust op één eigenschap van de hostnet-modus: **twee compose-projecten die béide
`network_mode: host` draaien, delen al dezelfde netns.** Ze hoeven dus niet samengevoegd te worden.
Wat overblijft zijn drie dingen, en die doet deze map:

1. **de componenten scheiden** — elke component krijgt een eigen loopback-adres en houdt zijn
   standaardpoort (zie hieronder);
2. **de infra delen** — één peer is *gastheer* en levert postgres, router, directory en
   directory-UI; de rest zijn *gasten* en zetten die vier services in een inactief profiel;
3. **de group-CA delen** — `deel-groep-ca.sh` geeft alle peers hetzelfde anker.

### Wat je hiermee níét kunt aantonen

Onder de FSC-laag bestaat er in deze opstelling **geen enkele grens tussen de peers**. Ze delen één
postgres met één superuser (`postgres/postgres`, `sslmode=disable`), dus elke peer-container kan bij
de directory-state en het transactielogboek van alle andere. De controllers draaien met
`AUTHN_TYPE=none` en luisteren gelijktijdig in dezelfde netns, dus een container van de ene peer kan
diensten publiceren of intrekken op de controller van de andere.

Ook de FSC-identiteitslaag zelf is hier zwakker dan hij lijkt: `deel-groep-ca.sh` kopieert de
**signing-key van de group-CA** (`intermediate-key.pem`) naar elke peer, want zonder die sleutel kan
een peer zijn eigen certs niet uitgeven. Elke peer kan daarmee een geldig group-certificaat voor
wíllekeurig welke FSC-identiteit maken. Dat is inherent aan één gedeelde CA — het testnet van
`moza-fsc-testnet` doet het net zo — maar het betekent dat een peer-identiteit hier niet
onvervalsbaar is.

Voor een testfederatie op één ontwikkelmachine, loopback-only, met testdata is dat allemaal prima.
Maar het betekent wel dat je hier **geen isolatie- of autorisatie-eigenschap mee kunt bewijzen** en
al helemaal geen negatieve authz-test: elk cross-peer controlepad is onder FSC om te zeilen. Wie dat
wil toetsen, heeft echte netwerkscheiding en een CA per organisatie nodig.

## Adresschema

Elke **component** krijgt een eigen loopback-adres binnen `127.20.0.0/16` en houdt zijn
**standaardpoort**. Poorten schuiven dus niet; het adres onderscheidt de componenten.

Vaste federatie-infra, gedraaid door de gastheer namens iedereen, op `127.20.0.x`:

| Adres | Rol | Poort(en) |
|-------|-----|-----------|
| `127.20.0.1` | SNI-router (alle peers) | `443` |
| `127.20.0.2` | postgres (alle databases) | `5432` |
| `127.20.0.3` | directory-manager | `8443` / `9443` / `9444` / `8080` |
| `127.20.0.4` | directory-UI | `8080` / `8081` |

Elke peer krijgt een eigen `/24`:

| Peer | Net |
|------|-----|
| `logius` | `127.20.1.0/24` |
| `magazijn-a` | `127.20.2.0/24` |
| *(volgende)* | `127.20.3.0/24`, `127.20.4.0/24`, … |

Binnen een `/24` liggen de laatste octetten vast, voor élke peer gelijk:

| Octet | Component | Poorten |
|-------|-----------|---------|
| `.1` | manager | `8443` extern, `9443` intern, `9444` intern-unauth, `8080` monitoring |
| `.2` | controller | `8080` UI, `9443` registratie-API, `9444` administratie-API, `8081` monitoring |
| `.3` | txlog | `9443`, `8081` monitoring |
| `.4` | inway | `8443`, `8081` monitoring |
| `.5` | outway | `8443`, `8081` monitoring |
| `.6` | stub-upstream | `8080` |

**Waarom een adres per component en niet per peer.** Binnen één peer botsen de componenten
onderling ook: manager en inway willen beide `8443`, en manager-intern, controller-registratie en
txlog willen alle drie `9443`. In bridge-modus botst dat niet omdat elke container een eigen IP
heeft — dat is precies wat hier met de hand wordt nagebouwd.

**Waarom `127.20` en niet `127.0`.** Loopback is de hele `127.0.0.0/8`, dus adressen zijn er in
overvloed. Een eigen prefix maakt bovendien zichtbaar wat van de federatie is: alles binnen
`127.20.` is van ons, en een andere stack in dezelfde netns — de demo-stack uit `compose.yaml`, of
een standalone peer-harness op `127.0.0.1` — kan de asserts in `smoke-federatie.sh` daardoor niet
vertroebelen. Een component die zijn federatie-overlay mist, bindt buiten het prefix en valt juist
op.

`peers.env` is de enige plek waar staat wie meedoet, met welke OIN en welk net. `federatie.sh`,
`smoke-federatie.sh` en de CI-guard lezen alle drie dat bestand; de octet-toewijzing zelf staat in
`fsc_component_adres()` in `../lib/fsc-harness.sh`.

## Draaiboek

Eenmalig, per omgeving:

```bash
# 1. Elke peer heeft zijn eigen .env (zie zijn deploy/local/README.md)
for p in logius magazijn-a; do
  f="$p/deploy/local/.env"
  cp -n "$p/deploy/local/.env.example" "$f"
  grep -q '^HOST_UID=' "$f" || printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> "$f"
done

# 2. PKI per peer (vereist cfssl; zie <peer>/pki/README.md)
(cd logius/pki && ./init-ca.sh && ./issue.sh && ./verify.sh)
(cd magazijn-a/pki && ./init-ca.sh && ./issue.sh && ./verify.sh)

# 3. Eén group-CA over alle peers — DESTRUCTIEF op de doel-peers, zie de kop van het script
./federatie/deel-groep-ca.sh --check      # toont wat er zou gebeuren
./federatie/deel-groep-ca.sh              # voert het uit
```

Stap 4 is een **host-brede verlaging** en verdient een bewuste keuze. De router moet op `:443`
binden; rootless podman mag dat niet zonder:

```bash
sysctl -n net.ipv4.ip_unprivileged_port_start     # noteer de huidige waarde (meestal 1024)
sudo sysctl net.ipv4.ip_unprivileged_port_start=0
```

Vanaf dat moment mag **elke** onprivilegieerde gebruiker en elk gecompromitteerd proces op die
machine binden onder poort 1024 — dus ook op 53, 80 en 443, met lokale MITM-posities tegen andere
clients op die host tot gevolg. De instelling is niet aan podman of aan deze harness gebonden en
verdwijnt bij een reboot. Zet 'm na afloop terug op de genoteerde waarde.

Op een gedeelde machine is dat te grof. De sysctl is **per netwerk-namespace**, dus daar draai je de
hele harness in een eigen netns (`unshare -Un …`) en zet je hem alleen daarbinnen.

Daarna:

```bash
./federatie/federatie.sh up             # gastheer, dan gasten; wacht tot iedereen aangemeld is
./federatie/smoke-federatie.sh          # bewijst de federatie
./federatie/federatie.sh status         # containers + álle listeners in de netns
./federatie/federatie.sh restart router # goedkoop itereren op één service
./federatie/federatie.sh down           # afbreken, inclusief volumes
```

`down` wist bewust de volumes: de directory-DB houdt peers vast die aan een group-CA hangen, dus na
`deel-groep-ca.sh` moet die state weg. Voor het gewone itereren is dat verspilling — gebruik dan
`restart <service>`, of `stop`/`start`. Een kale `restart` bestaat niet: `docker compose restart`
respecteert `depends_on` niet, dus dat gooit postgres tegelijk met zijn afnemers om en laat de
managers achter op `Exited`. Voor een volledige cyclus is `down` + `up` de weg.

## Contracten

Een peer mag pas iets afnemen als er een wederzijds ondertekend `ServiceConnectionGrant`-contract
ligt. `contracts/fbs-contracten.sh` zet die op voor de FBS-rollen uit `peers.env`: één contract per
magazijn, zodat de uitvraag-outway `berichtenmagazijn` bij elk van hen mag ophalen.

```bash
./federatie/contracts/fbs-contracten.sh   # één contract per magazijn uit MAGAZIJNEN
./federatie/smoke-contract.sh             # bewijst contract, data-pad, afdwinging en verantwoording
./federatie/smoke-contract-split.sh       # bewijst dat de twee helften los werken en convergeren
```

Een magazijn toevoegen is één naam in `MAGAZIJNEN`.

`fbs-contracten.sh` zet twee soorten contract op: één per magazijn zodat de uitvraag-outway
`berichtenmagazijn` mag ophalen, en één per pusher zodat het magazijn zijn CloudEvents kwijt kan
bij `notificatieservice`. Die tweede loopt de andere kant op — het magazijn is daar de afnemer, en
`logius` de aanbieder. Beide grant-hashes komen in hetzelfde `demo/generated/fsc-grants.env`.

```bash
./federatie/smoke-notificatie.sh   # bewijst de push: outway magazijn -> inway aanbieder -> stub
```

Die smoke vereist dat het magazijn zijn events door de outway stuurt:

```bash
MODUS=hostnet MAGAZIJN_A_URL=http://127.20.1.5:8443 \
  NOTIFICATIE_URL=http://127.20.2.5:8443/events demo/podman-up.sh
```

**Zet URL en grant-hash altijd samen.** Het grant-hash komt uit `fsc-grants.env` en de URL uit de
omgeving; staat de hash wél en de URL niet op de outway, dan stuurt het magazijn FSC-headers naar
een bestemming die er niets mee doet — en vervalt bovendien de SSRF-controle op die URL. Het
magazijn logt bij de eerste aflevering welke downstreams zo lopen (`DOWNSTREAM_VIA_OUTWAY`).

### Twee helften

De bootstrap bestaat uit twee losse scripts: `contracts/bootstrap-consumer.sh` dient het contract in
bij de manager van de consumer, `contracts/bootstrap-provider.sh` tekent het bij die van de
provider. Elk praat met precies één manager.

Dat is geen stijlkeuze. Op ZAD isoleert de tenant-baseline-NetworkPolicy per deployment en heeft de
manager-internal-API geen route, dus één proces dat beide managers aanspreekt bestaat daar niet. Het
contract kruist in plaats daarvan via de FSC-mesh. Zie `contracts/zad-runbook.md`.

`contracts/bootstrap.sh` is de lokale aanroeper van diezelfde twee helften — niet een aparte,
eenvoudigere variant. Wat hier lokaal getoetst wordt, is dus de code die op ZAD draait. De scheiding
wordt daarbij afgedwongen en niet alleen afgesproken: elke helft start met `env -u` op de adres- en
certificaat-variabelen van de overkant.

De provider-helft krijgt geen hash mee maar besluit zelf of hij tekent, en dat is een
autorisatiebesluit: hij tekent alleen een contract met **precies één** grant, van type
`GRANT_TYPE_SERVICE_CONNECTION`, voor een eigen dienst uit `FSC_DIENSTEN` en een consumer uit
`FSC_CONSUMERS`. De eis "precies één" staat er omdat een contract een lijst grants draagt: wie
alleen toetst of er één passende grant in zit, tekent een meegestuurde tweede mee.

### Idempotentie

De bootstrap is **idempotent zonder lokale state**. De generieke variant in `moza-fsc-testnet`
onthoudt de content-hash in een bestand; dat werkt op een ontwikkelmachine, maar niet in een deploy
waar elke job met een lege schijf start: daar maakt elke run er nóg een geldig contract bij. Deze
variant leidt het bestaan af uit de contracten zelf — service, provider, consumer-outway en
thumbprint samen vormen de identiteit — zodat een herhaalde run overal een no-op is. Op ZAD is
herhaling geen randgeval maar de normale werking: beide componenten draaien in een lus.

De provider tekent niet vanzelf: `AUTO_SIGN_GRANTS` dekt alleen (delegated)servicePublication, dus
de accept is een expliciete `PUT`. Landt de accept-handtekening daarna niet bij de consumer (die
push is best-effort, met begrensde backoff en zonder cron-retry), dan blijft het contract daar
`proposed` en ziet de outway de grant nooit; de provider-helft stuurt daarom na elke accept één keer
na, en `bootstrap.sh` forceert de her-distributie als het contract alsnog niet geldig wordt.

## De FBS-applicatie door de keten

Standaard front de inway een `stub-upstream` (http-echo) en praat de demo-stack rechtstreeks met de
magazijnen. Met drie ingrepen loopt `berichtenuitvraag` bij magazijn-a écht door FSC:

```bash
# 1. de inway naar het echte magazijn laten wijzen (in plaats van de echo-stub)
FSC_UPSTREAM_URL=http://127.0.0.1:8090 \
  ../magazijn-a/deploy/local/publish-service.sh

# 2. contract + grant-hash; schrijft demo/generated/fsc-grants.env
./contracts/fbs-contracten.sh

# 3. de demo-stack met de uitvraag door de outway van logius
MODUS=hostnet MAGAZIJN_A_URL=http://127.20.1.5:8443 ../../podman-up.sh

./smoke-keten.sh
```

`smoke-keten.sh` bewijst het in drie asserts: een bericht dat alleen in magazijn-a bestaat komt via
de uitvraag terug, er staat een **nieuwe** transactie in beide txlogs, en magazijn-b blijft
rechtstreeks werken. Die tweede assert is de eigenlijke: zonder nulmeting zou een transactie uit een
eerdere run de smoke groen houden terwijl het verkeer buiten de outway om ging.

Drie dingen om te weten:

- **`MAGAZIJN_A_URL` moet ook in `%dev` doorwerken.** De demo draait met `QUARKUS_PROFILE=dev`, en
  een kale `%dev.magazijnen."…".url=http://localhost:8090` overrulet de omgeving. Het ophalen slaagt
  dan gewoon — alleen langs de verkeerde weg, en het transactielogboek blijft leeg.
- **De sessiecache maskeert de keten.** Een tweede ophaling voor dezelfde ontvanger komt uit Redis
  en raakt het magazijn niet; `smoke-keten.sh` gebruikt daarom per run een verse, elfproef-geldige
  BSN.
- **`publish-service.sh` werkt een gewijzigde upstream bij**, maar het `servicePublication`-contract
  blijft staan. Wisselt de dienst van betekenis, dan hoort daar een nieuwe publicatie bij.

## Een peer toevoegen

1. **`peers.env`** — de peer aan `GASTEN` toevoegen, met zijn `OIN_<peer>` en het volgende vrije
   `NET_<peer>`.
2. **`compose/<peer>.yaml`** — kopieer `magazijn-a.yaml`, vervang de peer-naam en zet elk adres op
   het nieuwe `/24` (de octetten en de poorten blijven identiek).
3. **`haproxy.federatie.cfg`** — twee `use_backend`-regels en twee `backend`-blokken: `<net>.1:8443`
   voor de manager, `<net>.4:8443` voor de inway.
4. **`postgres-init.federatie.sql`** — de drie `CREATE DATABASE`-regels uit de `postgres-init.sql`
   van die peer (niet `fsc_directory`, die is er al).
5. **De `extra_hosts`-lijst in álle `compose/*.yaml`** — de nieuwe hostnamen erbij, ook in de
   overlays van de bestaande peers.

`smoke-federatie.sh` en de CI-guard hoeven niet aangepast te worden: die lezen de peers uit
`peers.env` en de poorten uit de overlays.

## Valkuilen

- **`depends_on: !override`, niet `!reset`** — zie de toelichting in `compose/magazijn-a.yaml`.
- **Podman faalt regelmatig op de eerste `up`** met een ID-mapping-fout; `federatie.sh up` ruimt op
  en probeert het in totaal drie keer (`UP_POGINGEN`). De mislukte poging staat in de uitvoer;
  zolang het script `FEDERATIE OP` meldt, is er niets aan de hand.
- **Bezwijkt de podman-API-service** (`error during connect`), dan herstart `federatie.sh` die
  bewust niet voor je — het meldt het commando.
- **Paden in de overlays zijn relatief aan de project-directory** — zie de toelichting in
  `compose/logius.yaml`.
- **Standalone en federatie kunnen niet tegelijk.** Beide claimen `:443` en `:5432`.
- **Een afgebroken `deel-groep-ca.sh` laat een peer half over** (nieuwe root, oude leaves). Het
  script herkent dat bij de volgende run aan de certificaatketen en maakt het af.
