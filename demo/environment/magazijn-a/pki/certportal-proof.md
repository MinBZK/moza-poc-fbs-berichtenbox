# Cert-portal-bewijs — group-cert voor magazijn-OIN via self-service

> Deze procedure vereist Docker (`ca-cfssl` + `ca-certportal`-containers) en `openssl`.
> Hieronder gedocumenteerd om handmatig te draaien.

## Context (Spec A, "Cert-portal-bewijs")

Fragment uit repo A's
`docs/superpowers/specs/2026-06-29-fsc-generiek-provider-onboarding-design.md`, opgehaald met:

```bash
gh api "repos/MinBZK/moza-fsc-testnet/contents/docs/superpowers/specs/2026-06-29-fsc-generiek-provider-onboarding-design.md" \
  --jq '.content' | base64 -d | sed -n '/Cert-portal-bewijs/,+8p'
```

> Aparte, kleine verificatie (script of gedocumenteerde stappen): start `ca-cfssl` +
> `ca-certportal`, vraag via de portal een cert aan voor een test-OIN, en toon dat het
> geldige cert tegen de test-trust-anchor verifieert (`pki/verify.sh`-stijl). Bewijst de
> self-service-onboarding zonder de hele tweede peer te hoeven draaien.

Uit dezelfde spec, de rol van beide componenten:

| Component | Rol | Image |
|-----------|-----|-------|
| `ca-cfssl` | draaiende test-CA (CFSSL, `:8888`) die certs signt tegen de test-trust-anchor | `open-fsc-ca-cfssl-unsafe` |
| `ca-certportal` | web/HTTP-portal waarmee een peer een group-cert aanvraagt; client van `ca-cfssl` (`--ca-host`) | `open-fsc-ca-certportal` |

`ca-cfssl` draait dezelfde group-CA-config (root + intermediate) als in `pki/` — dus eerst
`pki/init-ca.sh` draaien (zie `pki/README.md`) zodat `pki/ca/root.pem`

+ `ca/intermediate.pem` bestaan.

## Stap 1 — `ca-cfssl` starten met de group-CA uit `pki/`

Mount de group-CA (`pki/ca/`) en `pki/config.json` in de container en start CFSSL als
HTTP-service op `:8888`. Gebruik de projectbrede image-pin `v1.43.7` (zie
`docs/plans/2026-07-08-magazijn-provider-peer-fsc-design.md`, tabel "Bekende parameters"):

```bash
docker run --rm -d --name ca-cfssl \
  -p 8888:8888 \
  -v "$(pwd)/pki/ca:/ca:ro" \
  -v "$(pwd)/pki/config.json:/config.json:ro" \
  open-fsc-ca-cfssl-unsafe:v1.43.7 \
  serve -ca /ca/intermediate.pem -ca-key /ca/intermediate-key.pem -config /config.json -address 0.0.0.0
```

## Stap 2 — `ca-certportal` starten als client van `ca-cfssl`

```bash
docker run --rm -d --name ca-certportal \
  -p 8443:8443 \
  --link ca-cfssl \
  open-fsc-ca-certportal:v1.43.7 \
  --ca-host ca-cfssl --ca-port 8888
```

## Stap 3 — group-cert aanvragen voor de magazijn-OIN via de portal

Vraag via de portal-UI (of diens HTTP-API, afhankelijk van wat `ca-certportal` blootlegt) een
group-cert aan met dezelfde velden als `pki/peers/magazijn-a/manager/csr.json`:

+ `CN`: `manager.magazijn-a.fsc-test.local`
+ `serialnumber` (OIN): `00000000000000100000`
+ `O`: `magazijn-a`

Sla het teruggegeven certificaat lokaal op als `<portal-cert>.pem`.

## Stap 4 — verifiëren tegen de trust-anchor

```bash
openssl verify -CAfile pki/group/root.pem <portal-cert>.pem   # Expected: OK
openssl x509 -in <portal-cert>.pem -noout -subject | grep 00000000000000100000
```

`pki/group/root.pem` verwijst hier naar de group-trust-anchor die `init-ca.sh` produceert op
`pki/ca/root.pem` — pas het pad aan als de lokale checkout een andere structuur gebruikt.

Expected: `openssl verify` meldt `OK`; de `grep` op de OIN levert een treffer op (OIN zit in
`serialNumber` van het subject).

## Resultaat van deze bundel

Geen van bovenstaande stappen is uitgevoerd. Er is geen container gestart, geen cert
aangevraagd en geen verify-output vastgelegd. Dit document is de gedocumenteerde procedure;
het daadwerkelijke bewijs (geplakte `openssl verify: OK`-output) volgt zodra iemand dit met
Docker beschikbaar uitvoert.
