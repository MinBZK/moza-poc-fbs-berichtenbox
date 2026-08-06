# FSC PKI-scaffolding — peer `logius`

Test-PKI voor de FSC-peer `logius`: neemt via een outway `berichtenmagazijn` bij `magazijn-a`
af en biedt via een inway aan (nog zonder gepubliceerde dienst). Scripts en CA-configs zijn
1:1 overgenomen uit `MinBZK/moza-fsc-testnet` (`pki/`), zodat deze peer aansluit op dezelfde
testnet-conventies als de andere deelnemers (group `moza-fbs-test`, directory-OIN
`00000000000000000010`).

> **Niet voor productie.** Sleutels/certs horen **niet** in git: `ca/`, `out/`, `internal/` en
> `zad-upload/` zijn gitignored (repo-root `.gitignore`). Alleen scripts, CA-configs en
> `csr.json`-templates staan in git — zie ook repo A's eigen `pki/README.md` voor het
> achterliggende ontwerp (`docs/superpowers/specs/2026-06-24-test-pki-design.md`, repo A).

## Wat de scripts doen

| Script | Doet |
|--------|------|
| `init-ca.sh` | Genereert de **group** root- + intermediate-CA (`ca.json`/`intermediate.json` → `ca/root.pem`, `ca/intermediate.pem`). Trust-anchor voor het hele testnet. |
| `gen-csr.sh` | (Her)genereert de `peers/logius/<endpoint>/csr.json` (de ZAD-peer) uit de ZAD-topologie-env (`ZAD_PROJECT`/`ZAD_DEPLOYMENT`/`ZAD_BASE_DOMAIN`) — de peer-identiteit (OIN/O/endpoints) staat in het script, de omgeving-afhankelijke SAN's (mesh-host + cluster-Service-DNS) worden afgeleid. Maakt een projectwissel env-var-only. Wordt automatisch door `issue.sh` aangeroepen. De lokale-proof `directory`-peer blijft statisch. |
| `issue.sh [-f]` | Roept eerst `gen-csr.sh` aan (verse csr's voor het actieve project) en dan voor elke `peers/<peer>/<endpoint>/csr.json`: een **group**-cert (getekend door de group-intermediate) én een **internal**-cert (getekend door een per-peer self-signed internal-CA, automatisch aangemaakt). `-f` forceert her-uitgifte. |
| `gen-crl.sh` | Genereert een lege CRL getekend door de group-intermediate → `ca/intermediate.crl`. **Vereist vóór `verify.sh`** — die assert dat de CRL leesbaar is. |
| `verify.sh` | Acceptatie-asserts: ketengeldigheid group- en internal-certs, OIN in het subject, isolatie group↔internal en tussen peers onderling, CRL leesbaar, geen secrets zichtbaar voor git. Exit 0 = groen. |
| `combine-pem.sh` | Bouwt per group-endpoint één `combined.pem` (cert + key) voor de ZAD "Publicatie op het web"-passthrough-upload (modus 2). Gitignored (bevat de key). |
| `fix-permissions.sh` | Haalt world read/write van de `*-key.pem`/`key.pem`-bestanden af. |
| `zad-bundle.sh <peer>` | Verzamelt de upload-klare cert-set van één peer (group-trust-anchor + per-endpoint group/internal cert+key) in `zad-upload/<peer>/` met een `MANIFEST.md` (pod-pad + `TLS_*`-env-var per bestand). |
| `config.json` | cfssl signing-config: profiel `intermediate` (voor de group-intermediate) en profiel `peer` (voor endpoint-leaves). |
| `internal-ca.json`, `ca.json`, `intermediate.json` | cfssl CSR-specs voor resp. de per-peer internal-CA, de group-root en de group-intermediate. |

### GROUP versus INTERNAL keten

Elk endpoint (`manager`, `outway`, `inway`, `controller`, `txlog`) krijgt twee certs uit twee
losse ketens, zodat de manager zowel extern (mesh, group-trust) als intern
(component-tot-component) een geldig certificaat heeft:

| Keten | Issuer | Output | Doel |
|-------|--------|--------|------|
| **group** | group-intermediate (`ca/`) | `out/logius/<endpoint>/{cert,key}.pem` | Extern: mesh-mTLS, token- en contract-endpoints (hergebruikt dezelfde identity-cert). |
| **internal** | per-peer internal-CA (`internal/logius/ca/`) | `internal/logius/<endpoint>/{cert,key}.pem` | Intern: component-tot-component binnen de peer, los van de group-trust-anchor. |

## Peer `logius`

- Peer-OIN = Peer ID = `serialnumber` in elke `csr.json`: `00000000000000001000`.
- `names[].O`: `Logius` — de naam waaronder de peer in de directory verschijnt, als eigennaam met
  hoofdletter. De mapnaam, DNS-namen en ZAD-componenten blijven `logius` (identifiers, lowercase).
- Endpoints: `manager`, `outway`, `inway`, `controller`, `txlog`. De outway is de afnemende kant
  (roept `berichtenmagazijn` bij `magazijn-a` aan), de inway de aanbiedende (nog zonder
  gepubliceerde dienst). Elke csr draagt naast de lokale naam (`<endpoint>.logius.fsc-test.local`)
  de ZAD-SAN's: de externe mesh-host (`<short>-<deployment>-<project>.<base-domain>`) en de
  cluster-interne Service-DNS (`<deployment>-<short>` +
  `<deployment>-<short>.rig-prd-<project>.svc.cluster.local`). Die worden door `gen-csr.sh` uit de
  ZAD-topologie-env afgeleid — wijzig het project via `ZAD_PROJECT`, niet door de csr's met de hand
  te bewerken.

## Uitvoeren (vereist cfssl)

Genereer de certs met `cfssl`. Het CA-materiaal (`ca/root.pem`, `ca/intermediate.pem` + keys) staat
niet in git (`pki/ca/` is gitignored) — het is het resultaat van deze stappen:

```bash
cd pki
./init-ca.sh          # 1. group root + intermediate  -> ca/   (ALLEEN lokale proof, zie hieronder)
./issue.sh            # 2. gen-csr.sh (verse csr's) + per endpoint: group- + internal-cert
./gen-crl.sh          # 3. lege CRL getekend door de intermediate -> ca/intermediate.crl
./verify.sh           # 4. acceptatie-asserts (incl. CRL leesbaar), exit 0 = groen
```

> **Projectwissel is env-var-only.** Draait de peer in een ander ZAD-project/deployment, zet dan
> `ZAD_PROJECT` (evt. `ZAD_DEPLOYMENT`/`ZAD_BASE_DOMAIN`/`ZAD_NAMESPACE`) en her-genereer + geef
> opnieuw uit — geen handmatige csr-edits. `issue.sh` roept `gen-csr.sh` zelf aan, dus:
>
> ```bash
> export ZAD_PROJECT=ander-project        # dezelfde env als deploy/zad/upsert-peer.sh
> cd pki && ./issue.sh -f && ./gen-crl.sh && ./verify.sh && ./zad-bundle.sh logius
> ```
>
> De SAN's in de nieuwe csr's (git-diff) en de adressen die `upsert-peer.sh` deployt sporen dan per
> definitie, want beide lezen dezelfde `ZAD_*`-vars.

---

> **Lokale proof vs. echte directory — de group-CA verschilt.**
>
> - **Lokale compose-proof** (`deploy/local/`): geïsoleerde mesh, dus `init-ca.sh` genereert
>   een eigen group root+intermediate. Prima — alle deelnemers vertrouwen dezelfde lokale root.
> - **ZAD, aangesloten op de fsc-testnet-directory** (`deploy/zad/`): de group-leaf van
>   logius moet ketenen naar **fsc-testnet's** group-root (anders vertrouwt de directory de
>   peer niet). Draai `init-ca.sh` dan **niet**; zet fsc-testnet's `ca/root.pem` +
>   `ca/intermediate.pem` (+ keys) in `pki/ca/` en draai alleen `issue.sh` (stap 2-4). De
>   INTERNAL-CA blijft hoe dan ook lokaal/self-signed per-peer.

Controleer daarna dat de OIN in het certificaat zit:

```bash
openssl x509 -in out/logius/inway/cert.pem -noout -subject | grep 00000000000000001000
```

Expected: de OIN wordt geëchood (zit in `serialNumber` van het subject).

## Statische validatie (zonder cfssl/docker)

```bash
bash -n pki/init-ca.sh pki/gen-csr.sh pki/issue.sh pki/gen-crl.sh pki/verify.sh \
        pki/zad-bundle.sh pki/combine-pem.sh
sh -n   pki/fix-permissions.sh
for f in pki/peers/logius/*/csr.json; do
  jq -e '.serialnumber=="00000000000000001000"' "$f" >/dev/null && echo "$f OK"
done
jq . pki/config.json pki/internal-ca.json >/dev/null && echo "json OK"
```
