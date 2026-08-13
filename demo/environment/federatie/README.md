# Lokale FSC-federatie

Zet meerdere peer-harnessen uit `demo/environment/` naast elkaar neer als **één federatie**:
één directory, één group-CA, één SNI-router. Dat is wat je nodig hebt zodra je iets wilt
beproeven dat *tussen* peers speelt — een contract, een data-pad, service-discovery — in plaats
van binnen één peer.

Elke peer blijft zelfstandig draaibaar; zie zijn eigen `deploy/local/README.md`. Deze map voegt
alleen de compositie toe en verandert niets aan het standalone gedrag.

Op dit moment doen `logius` (uitvraag-consumer) en `magazijn-a` (provider) mee.

## Waarom dit nodig is

Elke peer-harness draait standalone een complete mini-federatie: zijn eigen postgres, router én
directory. Twee daarvan tegelijk starten werkt niet — ze claimen dezelfde poorten en, erger,
elk zou zijn eigen directory en group-root hebben, dus ze zouden elkaar niet vertrouwen.

De oplossing berust op één eigenschap van de hostnet-modus: **twee compose-projecten die béide
`network_mode: host` draaien, delen al dezelfde netns.** Ze hoeven dus niet samengevoegd te
worden. Wat overblijft zijn drie dingen, en die doet deze map:

1. **poorten scheiden** — elke peer krijgt een eigen blok (zie hieronder);
2. **de infra delen** — één peer is *gastheer* en levert postgres, router en directory; de rest
   zijn *gasten* en zetten die vier services in een inactief profiel;
3. **de group-CA delen** — `deel-groep-ca.sh` geeft alle peers hetzelfde anker.

## Poortschema

Vaste federatie-infra, buiten de peer-blokken:

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

**Waarom vanaf 61000 en blokken van 100.** De ephemere poortrange is `32768–60999`; daarboven
kan een vaste listener nooit botsen met een uitgaande verbinding. Dat laat 4535 poorten over.
Bij blokken van 1000 passen er vier peers in — minder dan er gepland staan. Bij blokken van
100 passen er 45, terwijl een peer er hooguit dertien gebruikt.

## Draaiboek

Eenmalig, per omgeving:

```bash
# 1. Elke peer heeft zijn eigen .env (zie zijn deploy/local/README.md)
for p in logius magazijn-a; do
  cp -n "$p/deploy/local/.env.example" "$p/deploy/local/.env"
  printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" >> "$p/deploy/local/.env"
done

# 2. PKI per peer (vereist cfssl; zie <peer>/pki/README.md)
(cd logius/pki && ./init-ca.sh && ./issue.sh && ./verify.sh)
(cd magazijn-a/pki && ./init-ca.sh && ./issue.sh && ./verify.sh)

# 3. Eén group-CA over alle peers — DESTRUCTIEF op de doel-peers, zie het script
./federatie/deel-groep-ca.sh --check      # toont wat er zou gebeuren
./federatie/deel-groep-ca.sh              # voert het uit

# 4. De router moet op :443 kunnen binden
sudo sysctl net.ipv4.ip_unprivileged_port_start=0
```

Daarna:

```bash
./federatie/federatie.sh up        # gastheer, dan gasten; wacht tot iedereen aangemeld is
./federatie/smoke-federatie.sh     # bewijst de federatie
./federatie/federatie.sh status    # containers + luisteraars
./federatie/federatie.sh down      # afbreken, inclusief volumes
```

## Een peer toevoegen

Vier plaatsen, allemaal mechanisch:

1. **`federatie/compose/<peer>.yaml`** — kopieer `magazijn-a.yaml`, vervang de peer-naam en zet
   elke listener op het volgende vrije blok (offsets blijven identiek).
2. **`haproxy.federatie.cfg`** — twee `use_backend`-regels en twee `backend`-blokken:
   `<blok>+00` voor de manager, `<blok>+30` voor de inway.
3. **`postgres-init.federatie.sql`** — de drie `CREATE DATABASE`-regels uit de
   `postgres-init.sql` van die peer (niet `fsc_directory`, die is er al).
4. **`federatie.sh`** — de peer aan `GASTEN` toevoegen; en in beide compose-overlays plus
   `smoke-federatie.sh` de nieuwe hostnamen en poorten opnemen.

De `extra_hosts`-lijst in élke overlay moet álle federatie-namen dragen. Dat is geen
overdrijving: de directory haalt bij het tekenen van een publicatie-contract de JWKS op bij de
publicerende peer (`https://<peer>/.well-known/jwks.json`). Kan hij die naam niet resolven, dan
blijft dat contract op `CONTRACT_STATE_PROPOSED` staan, is de dienst niet vindbaar — en wordt
het **niet automatisch opnieuw geprobeerd**.

## Valkuilen

- **`depends_on: !override`, niet `!reset`.** `!reset` maakt de sleutel leeg ongeacht wat
  eronder staat. Op een manager betekent dat: gelijk starten met zijn eigen migratie, en
  sterven op `relation "peers.peers" does not exist`.
- **Podman faalt regelmatig op de eerste `up`** met `container ID 0 cannot be mapped to a host
  ID`: twee containers maken dan tegelijk een ID-mapped kopie van dezelfde image-laag. Dat is
  transiënt — `federatie.sh up` ruimt de achterblijvers op en probeert het tot driemaal
  opnieuw (`UP_POGINGEN` om dat te wijzigen). Je ziet de mislukte poging in de uitvoer staan;
  zolang het script `FEDERATIE OP` meldt, is er niets aan de hand.
- **Zie je `error during connect: ... EOF`**, dan is de podman-API-service zelf bezweken onder
  de gelijktijdige creates. Die herstart het script bewust níét voor je:

  ```bash
  podman system service --time=0 unix://${XDG_RUNTIME_DIR:-/tmp/podman-run-$(id -u)}/podman/podman.sock &
  ```
- **Paden in de overlays zijn relatief aan de project-directory** — de map van het eerste
  `-f`-bestand, dus `<peer>/deploy/local/` — niet aan het overlay-bestand zelf.
- **De peers kunnen niet tegelijk standalone én in federatie draaien.** Beide opstellingen
  claimen `:443` en `:5432`. Breek de een af voor je de ander start.
