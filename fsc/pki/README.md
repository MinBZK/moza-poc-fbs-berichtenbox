# FSC PKI-scaffolding — provider-peer `magazijn-a`

Test-PKI voor de FSC provider-peer die later `berichtenmagazijn` als dienst publiceert.
Scripts en CA-configs zijn 1:1 overgenomen uit `MinBZK/moza-fsc-testnet` (`pki/`), zodat deze
peer aansluit op dezelfde testnet-conventies als de andere deelnemers (group `moza-fbs-test`,
directory-OIN `00000000000000000010`).

> **Niet voor productie.** Sleutels/certs horen **niet** in git: `ca/`, `out/`, `internal/` en
> `zad-upload/` zijn gitignored (repo-root `.gitignore`). Alleen scripts, CA-configs en
> `csr.json`-templates staan in git — zie ook repo A's eigen `pki/README.md` voor het
> achterliggende ontwerp (`docs/superpowers/specs/2026-06-24-test-pki-design.md`, repo A).

## Wat de scripts doen

| Script | Doet |
|--------|------|
| `init-ca.sh` | Genereert de **group** root- + intermediate-CA (`ca.json`/`intermediate.json` → `ca/root.pem`, `ca/intermediate.pem`). Trust-anchor voor het hele testnet. |
| `issue.sh [-f]` | Voor elke `peers/<peer>/<endpoint>/csr.json`: een **group**-cert (getekend door de group-intermediate) én een **internal**-cert (getekend door een per-peer self-signed internal-CA, automatisch aangemaakt). `-f` forceert her-uitgifte. |
| `gen-crl.sh` | Genereert een lege CRL getekend door de group-intermediate → `ca/intermediate.crl`. **Vereist vóór `verify.sh`** — die assert dat de CRL leesbaar is. |
| `verify.sh` | Acceptatie-asserts: ketengeldigheid group- en internal-certs, OIN in het subject, isolatie group↔internal en tussen peers onderling, CRL leesbaar, geen secrets zichtbaar voor git. Exit 0 = groen. |
| `combine-pem.sh` | Bouwt per group-endpoint één `combined.pem` (cert + key) voor de ZAD "Publicatie op het web"-passthrough-upload (modus 2). Gitignored (bevat de key). |
| `fix-permissions.sh` | Haalt world read/write van de `*-key.pem`/`key.pem`-bestanden af. |
| `zad-bundle.sh <peer>` | Verzamelt de upload-klare cert-set van één peer (group-trust-anchor + per-endpoint group/internal cert+key) in `zad-upload/<peer>/` met een `MANIFEST.md` (pod-pad + `TLS_*`-env-var per bestand). |
| `config.json` | cfssl signing-config: profiel `intermediate` (voor de group-intermediate) en profiel `peer` (voor endpoint-leaves). |
| `internal-ca.json`, `ca.json`, `intermediate.json` | cfssl CSR-specs voor resp. de per-peer internal-CA, de group-root en de group-intermediate. |

### GROUP versus INTERNAL keten

Elk endpoint (`manager`, `controller`, `inway`, `txlog`) krijgt twee certs uit twee losse ketens,
zodat de manager zowel extern (mesh, group-trust) als intern (component-tot-component) een
geldig certificaat heeft:

| Keten | Issuer | Output | Doel |
|-------|--------|--------|------|
| **group** | group-intermediate (`ca/`) | `out/magazijn-a/<endpoint>/{cert,key}.pem` | Extern: mesh-mTLS, token- en contract-endpoints (hergebruikt dezelfde identity-cert). |
| **internal** | per-peer internal-CA (`internal/magazijn-a/ca/`) | `internal/magazijn-a/<endpoint>/{cert,key}.pem` | Intern: component-tot-component binnen de peer, los van de group-trust-anchor. |

## Peer `magazijn-a`

- Peer-OIN = Peer ID = `serialnumber` in elke `csr.json`: `00000001003214345000`.
- `names[].O`: `magazijn-a`.
- Endpoints: `manager`, `controller`, `inway`, `txlog` — hostnamen zijn placeholder
  (`<endpoint>.magazijn-a.fsc-test.local`), spiegelt repo A's `TODO(#723)` (echte ZAD-adressen
  volgen later).

## UITGESTELD — te draaien door de mens in een omgeving met cfssl

Deze omgeving heeft geen `cfssl`/`cfssljson` en geen `docker`. Er zijn dus **geen certificaten
gegenereerd of geverifieerd** — alleen scripts en configs zijn neergezet. Het group-CA-materiaal
zelf (`ca/root.pem`, `ca/intermediate.pem`) staat ook in repo A niet in git (`pki/ca/` is daar
gitignored); dit is dus geen ontbrekende download maar het verwachte resultaat van `init-ca.sh`,
door de mens uit te voeren:

```bash
cd fsc/pki
./init-ca.sh          # 1. group root + intermediate  -> ca/   (ALLEEN lokale proof, zie hieronder)
./issue.sh            # 2. per endpoint: group- + internal-cert
./gen-crl.sh          # 3. lege CRL getekend door de intermediate -> ca/intermediate.crl
./verify.sh           # 4. acceptatie-asserts (incl. CRL leesbaar), exit 0 = groen
```

> **Lokale proof vs. echte directory — de group-CA verschilt.**
> - **Lokale compose-proof** (`fsc/deploy/local/`): geïsoleerde mesh, dus `init-ca.sh` genereert
>   een eigen group root+intermediate. Prima — alle deelnemers vertrouwen dezelfde lokale root.
> - **ZAD, aangesloten op de fsc-testnet-directory** (`fsc/deploy/zad/`): de group-leaf van
>   magazijn-a moet ketenen naar **fsc-testnet's** group-root (anders vertrouwt de directory de
>   peer niet). Draai `init-ca.sh` dan **niet**; zet fsc-testnet's `ca/root.pem` +
>   `ca/intermediate.pem` (+ keys) in `fsc/pki/ca/` en draai alleen `issue.sh` (stap 2-4). De
>   INTERNAL-CA blijft hoe dan ook lokaal/self-signed per-peer.

Controleer daarna dat de OIN in het certificaat zit:

```bash
openssl x509 -in out/magazijn-a/inway/cert.pem -noout -subject | grep 00000001003214345000
```

Expected: de OIN wordt geëchood (zit in `serialNumber` van het subject).

Zie ook `fsc/pki/certportal-proof.md` voor het (eveneens UITGESTELDE) alternatief via
`ca-cfssl`/`ca-certportal`.

## Statische validatie (wél al gedraaid, zonder cfssl/docker)

```bash
bash -n fsc/pki/init-ca.sh fsc/pki/issue.sh fsc/pki/gen-crl.sh fsc/pki/verify.sh \
        fsc/pki/zad-bundle.sh fsc/pki/combine-pem.sh
sh -n   fsc/pki/fix-permissions.sh
for f in fsc/pki/peers/magazijn-a/*/csr.json; do
  jq -e '.serialnumber=="00000001003214345000"' "$f" >/dev/null && echo "$f OK"
done
jq . fsc/pki/config.json fsc/pki/internal-ca.json >/dev/null && echo "json OK"
```
