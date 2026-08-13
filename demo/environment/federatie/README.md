# Lokale FSC-federatie

Zet meerdere peer-harnessen uit `demo/environment/` naast elkaar neer als **één federatie**: één
directory, één group-CA, één SNI-router. Dat is wat je nodig hebt zodra je iets wilt beproeven dat
*tussen* peers speelt — een contract, een data-pad, service-discovery — in plaats van binnen één
peer.

Elke peer blijft zelfstandig draaibaar; zie zijn eigen `deploy/local/README.md`. Deze map voegt
alleen de compositie toe en verandert niets aan het standalone gedrag.

Op dit moment doen `logius` (uitvraag-consumer) en `magazijn-a` (provider) mee.

**Linux + podman.** De scripts gebruiken `ss` (iproute2) en `podman`; op macOS draaien ze niet.

## Waarom dit nodig is

Elke peer-harness draait standalone een complete mini-federatie: zijn eigen postgres, router én
directory. Twee daarvan tegelijk starten werkt niet — ze claimen dezelfde poorten en, erger, elk
zou zijn eigen directory en group-root hebben, dus ze zouden elkaar niet vertrouwen.

De oplossing berust op één eigenschap van de hostnet-modus: **twee compose-projecten die béide
`network_mode: host` draaien, delen al dezelfde netns.** Ze hoeven dus niet samengevoegd te worden.
Wat overblijft zijn drie dingen, en die doet deze map:

1. **poorten scheiden** — elke peer krijgt een eigen blok (zie hieronder);
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

## Poortschema

Vaste federatie-infra, gedraaid door de gastheer namens iedereen, buiten de peer-blokken:

| Poort | Rol |
|-------|-----|
| `443` | SNI-router (alle peers) |
| `5432` | postgres (alle databases) |
| `18443` / `19443` / `19444` / `18080` | directory-manager extern / intern / intern-unauth / monitoring |
| `8081` / `8082` | directory-UI / monitoring |

Peer-blokken van **100** poorten, vanaf `61000`:

| Peer | Blok |
|------|------|
| `logius` | `61000` |
| `magazijn-a` | `61100` |
| *(volgende)* | `61200`, `61300`, … |

Binnen een blok liggen de offsets vast, voor élke peer gelijk:

| Offset | Listener | Offset | Listener |
|--------|----------|--------|----------|
| `+00` | manager extern | `+10` | controller UI |
| `+01` | manager intern | `+11` | controller registratie-API |
| `+02` | manager intern-unauth | `+12` | controller administratie-API |
| `+03` | manager monitoring | `+13` | controller monitoring |
| `+20` | txlog | `+30` | inway |
| `+21` | txlog monitoring | `+31` | inway monitoring |
| `+40` | outway | `+50` | stub-upstream |
| `+41` | outway monitoring | | |

**Waarom vanaf 61000, en waarom blokken van 100.** Op Linux is de ephemere poortrange
`32768–60999`; daarboven kan een vaste listener nooit botsen met een uitgaande verbinding. Dat laat
4536 poorten over (`61000–65535`). Een peer reikt tot `+50`, dus hij beslaat 51 slots — bij blokken
van 1000 passen er vier peers in het bereik, minder dan er gepland staan; bij blokken van 100 passen
er 45.

`peers.env` is de enige plek waar staat wie meedoet, met welke OIN en welk blok. `federatie.sh`,
`smoke-federatie.sh` en de CI-guard lezen alle drie dat bestand.

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

## Een peer toevoegen

1. **`peers.env`** — de peer aan `GASTEN` toevoegen, met zijn `OIN_<peer>` en het volgende vrije
   `BLOK_<peer>`.
2. **`compose/<peer>.yaml`** — kopieer `magazijn-a.yaml`, vervang de peer-naam en zet elke listener
   op het nieuwe blok (de offsets blijven identiek).
3. **`haproxy.federatie.cfg`** — twee `use_backend`-regels en twee `backend`-blokken: `<blok>+00`
   voor de manager, `<blok>+30` voor de inway.
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
