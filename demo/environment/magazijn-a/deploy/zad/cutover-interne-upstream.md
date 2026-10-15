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
de previews via de federatie lopen (MinBZK/MijnOverheidZakelijk#1328).

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
verwijderd. Haal het huidige document op en voeg deze twee regels toe aan de bestaande lijsten —
de `democonsole-naar-*`-regels moeten blijven:

```bash
zadctl -p mpfm-w3h --yaml service config get cross-domain-access > cross-domain-access.yaml
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
`fsc-magazijna/` en een Ingress-policy in `test/` te staan
(`*-cross-domain-access-*-network-policy.yaml`), elkaars spiegelbeeld op poort 8090.

**De inbound-regel geldt ook voor de `magazijna` van elke preview**, want de `to`-kant van een
inbound-regel noemt geen deployment. Dat opent in de praktijk niets: de baseline-policy van
`fsc-magazijna` laat naar buiten alleen TCP 443/80 toe, en de enige outbound-regel noemt `test`.

**Controleer bij de eerstvolgende preview de log van Operations Manager.** Een preview neemt de
regels over, maar heeft geen `magazijna-fscinway`. Slaat OM de outbound-regel daar met een
waarschuwing over, dan telt die waarschuwing mee in de uitrol-controle van de deploy.

## Stap 2 — de dienst naar het interne adres

In de beheer-UI van `magazijna-fscctl`: werk de dienst `berichtenmagazijn` bij en zet de
`endpoint_url` op het doeladres; naam en `inway_address` blijven gelijk. De controller kent
bijwerken (`PUT /v1/services/{name}`, zoals `../local/publish-service.sh` doet). Verwijder de dienst
niet om hem opnieuw aan te maken: dat kan de publicatie in de directory meenemen, en dan ligt de
federatie stil tot die opnieuw getekend is.

Lees de dienst daarna terug en noteer het tijdstip: de `endpoint_url` moet het doeladres tonen.
Controleer in de directory dat de dienst nog vindbaar is (`verify-zad.md`, stap c).

## Stap 3 — verifiëren

Een geslaagde respons zegt niets over de route, en het transactielogboek ook niet op zichzelf: dat
legt de FSC-transactie vast, niet het adres waar de inway naartoe stuurt, en ziet er voor de oude
en de nieuwe route hetzelfde uit. Het bewijs is de combinatie: de inway kent alleen de
`endpoint_url` uit stap 2, dus een geslaagde transactie ná dat tijdstip ging over het interne adres.

1. Een ophaalronde door de keten `berichtenuitvraag → logius-fscoutway → magazijna-fscinway →
   berichtenmagazijn`, met een verse BSN zodat de sessiecache de keten niet maskeert.
2. Een nieuwe transactie in de txlog van logius én in die van magazijn-a, met de dienst
   `berichtenmagazijn`, en een tijdstip ná het teruglezen in stap 2.
3. Geen fouten van de inway richting de upstream:

   ```bash
   zadctl -p mpfm-w3h logs fsc-magazijna -c magazijna-fscinway | grep -iE "error|refused|timeout"
   ```

   `connection refused` of een time-out op het doeladres wijst op een ontbrekende of niet-gerenderde
   netwerkregel (stap 1).

## Terugrollen

Werk de `endpoint_url` terug bij naar het adres uit stap 0. Dat werkt zolang de publicatie van
`magazijna` staat, en dat doet hij na dit draaiboek nog. De netwerkregels uit stap 1 mogen blijven:
zonder een dienst die het interne adres noemt, doen ze niets.
