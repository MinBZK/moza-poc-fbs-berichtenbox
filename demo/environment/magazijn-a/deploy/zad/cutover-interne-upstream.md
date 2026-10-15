# Cutover — de inway van magazijn-a naar het cluster-interne adres van de app

> Draaiboek voor het omzetten van de dienst `berichtenmagazijn` van het publieke adres van
> `magazijna` naar zijn cluster-interne Service-adres. Spiegelbeeld van
> `../../../logius/deploy/zad/cutover-interne-outway.md`; achtergrond in
> `docs/plans/2026-10-15-magazijn-intern-achter-inway.md`.

Doeladres:

```
http://test-magazijna.rig-prd-mpfm-w3h.svc.cluster.local:8090
```

De Service heet `<deployment>-<component>` en leeft in namespace `rig-prd-<project>`. Het is plain
HTTP: de app luistert niet met TLS, de ingress termineerde dat.

## Wat dit níet doet

De publicatie op het web van `magazijna` blijft staan. Die hangt aan de component en geldt dus ook
voor de previews, en die roepen hun eigen `magazijna` nog op dat adres aan. Intrekken volgt zodra
de previews via de federatie lopen.

## Voorwaarden

- `ZAD_API_KEY` voor project `mpfm-w3h` staat in de omgeving; `zadctl -p mpfm-w3h deployment list`
  werkt.
- Toegang tot de beheer-UI van `magazijna-fscctl` (achter de autorisatiemuur).

## Stap 0 — de huidige dienst vastleggen

De geregistreerde `endpoint_url` staat in de controller, niet in de projectspec. Noteer hem in de
beheer-UI (Diensten → `berichtenmagazijn`) vóór je iets wijzigt: dat is het terugrol-adres. Wijkt
hij af van `https://magazijna-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl`, zoek dan eerst uit
waarom.

## Stap 1 — netwerktoegang tussen de twee deployments

Twee regels: een `outbound` bij de aanroepende kant (de inway) en een `inbound` bij de ontvanger
(de app). Eén van de twee is niet genoeg — de ontvanger geeft de toestemming. Beide noemen hun
peer-deployment: zonder `deployment` rendert er geen NetworkPolicy, zonder waarschuwing.

`zadctl service config set` schrijft het hele document; een veld dat je niet noemt wordt
verwijderd. Lees dus eerst wat er staat en voeg deze twee toe aan de bestaande lijsten — de
`democonsole-naar-*`-regels moeten blijven:

```bash
zadctl -p mpfm-w3h --json service config get cross-domain-access > cross-domain-access.json
```

```yaml
inbound:
  - name: fscinway-naar-magazijna
    from: { project: mpfm-w3h, deployment: fsc-magazijna, component: magazijna-fscinway }
    to:   { component: magazijna, port: 8090 }
outbound:
  # ... de bestaande democonsole-naar-*-regels ...
  - name: fscinway-naar-magazijna
    from: { component: magazijna-fscinway }
    to:   { project: mpfm-w3h, deployment: test, component: magazijna, port: 8090 }
```

```bash
zadctl -p mpfm-w3h service config set cross-domain-access --target project -f cross-domain-access.yaml --dry-run
zadctl -p mpfm-w3h service config set cross-domain-access --target project -f cross-domain-access.yaml
```

Controleer de uitkomst aan de gerenderde manifests, niet aan de API-respons. Onder
`rig-cluster-application-test/odcn-production/mpfm-w3h/` horen een Egress-policy in
`fsc-magazijna/` en een Ingress-policy in `test/` te staan (`*-cross-domain-access-*-network-policy.yaml`),
elkaars spiegelbeeld op poort 8090.

De inbound-regel noemt geen `deployment` aan de `to`-kant en geldt dus ook voor de `magazijna` van
een preview. Dat opent niets zolang er geen outbound-regel naar die preview is.

## Stap 2 — de dienst naar het interne adres

In de beheer-UI van `magazijna-fscctl`: zet de `endpoint_url` van `berichtenmagazijn` op het
doeladres. Kent de UI geen bewerken, verwijder de dienst dan en maak hem opnieuw aan met dezelfde
naam, hetzelfde `inway_address` en het doeladres; het contract van logius verwijst naar de
dienstnaam en blijft geldig. Controleer na afloop in de directory dat de dienst nog vindbaar is
(`verify-zad.md`, stap c).

## Stap 3 — verifiëren

Een geslaagde respons zegt niets over de route: de oude route werkte ook. Het bewijs zit in de
logboeken.

1. Een ophaalronde door de keten `berichtenuitvraag → logius-fscoutway → magazijna-fscinway →
   berichtenmagazijn`, met een verse BSN zodat de sessiecache de keten niet maskeert.
2. Een nieuwe transactie in de txlog van logius én in die van magazijn-a, met de dienst
   `berichtenmagazijn`.
3. Geen fouten van de inway richting de upstream:

   ```bash
   zadctl -p mpfm-w3h logs fsc-magazijna -c magazijna-fscinway | grep -iE "error|refused|timeout"
   ```

   `connection refused` of een time-out op het doeladres wijst op een ontbrekende of niet-gerenderde
   netwerkregel (stap 1).

## Terugrollen

Zet de `endpoint_url` terug op het adres uit stap 0. Dat werkt zolang de publicatie van `magazijna`
staat, en dat doet hij na dit draaiboek nog. De netwerkregels uit stap 1 mogen blijven: zonder een
dienst die het interne adres noemt, doen ze niets.
