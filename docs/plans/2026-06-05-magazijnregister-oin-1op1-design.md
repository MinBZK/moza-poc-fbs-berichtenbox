# Magazijnregister — 1:1 OIN↔magazijn

**Status:** Concept

## Context

Vandaag koppelt de configuratie afzenders aan magazijnen via een M:N-model:

- `libraries/fbs-berichtensessiecache` — `MagazijnenConfig.instances.<id>.{url, naam, afzenders[]}`.
  `afzenders` is een **lijst** OIN's per magazijn; één OIN kan in meerdere magazijnen
  voorkomen en één magazijn kan meerdere afzenders serveren. `MagazijnClientFactory`
  bouwt hieruit een reverse-index `OIN → magazijn-id's`. `ProfielMagazijnResolver`
  kruist opted-in afzender-OIN's met die index.
- `services/berichtenuitvraag` — een **tweede** `@ConfigMapping(prefix="magazijnen")`
  met `urls.<id>` (+ `client`-timeouts) voor bijlage-/patch-/delete-routing via
  `MagazijnRouter`. De `<id>`-key moet exact overeenkomen met de sessiecache-config; de
  base-URL staat daardoor op twee plekken.

Het `magazijnId` (vandaag een vrije string als `magazijn-a`) stroomt vanuit de
sessiecache mee op elk `Bericht` → `MagazijnResult`/SSE → naar de client.

### Probleem

In het Federatief Berichtenstelsel hoort bij één deelnemende organisatie precies
één berichtenmagazijn. De architectuur veronderstelt dit al: organisaties worden
geïdentificeerd via hun OIN (`architectuur.organisatie-identificatie`), elk magazijn
is een eigen BSNk-dienst met uniek EP (`security.bsnk-dienstregistratie`), en
CloudEvents gebruiken `source: urn:nld:fbs:magazijn:{oin}`. Dat een organisatie intern
meerdere opslag-instanties heeft (bv. Belastingdienst: burgers vs. ondernemers) is voor
het stelsel niet relevant — naar buiten is er één magazijn per OIN.

Het M:N-config-model wijkt af van die intentie: het laat dubbele OIN's en gedeelde
magazijnen toe die niet kunnen bestaan, en dwingt verdedigende machinerie af
(tie-break bij meerdere matches, drift-warnings, reverse-index-opbouw) voor gevallen
die per definitie niet optreden.

### Doel

Eén 1:1-koppeling OIN ↔ magazijn, structureel afgedwongen doordat het `magazijnId`
**de OIN zelf is**. De koppeling leeft in een eigen registratie-component, los van de
sessiecache (die magazijnen *bevraagt*) en de uitvraag-service (die *routeert*), zodat
het productie-pad — beheer-interface + database-opslag — die twee niet raakt.

## Besluiten

| Onderwerp | Besluit |
|-----------|---------|
| Magazijn-identiteit | `magazijnId == OIN`. Geen aparte `afzenders`-lijst meer; de register-key *is* de afzender-OIN. |
| OIN in SSE-output | OIN gaat voluit mee als `magazijnId` (OIN is publiek, geen PII). |
| Hosting | Nieuwe gedeelde library `libraries/fbs-magazijnregister`, config-backed nu, DB + beheer-UI later. |
| Publiek contract | Getypeerd `Oin` overal (`voorOin(oin: Oin)`, keys als `Oin`). `magazijnId` blijft `String` (= `oin.waarde`) waar het door bestaande DTO's/SSE stroomt. |
| Client-timeouts | Blijven per consument (read-aggregatie vs. bijlage-proxy hebben los timeout-beleid). Register = enkel identiteit + endpoint + naam. |

## Architectuur

### Nieuwe library: `fbs-magazijnregister`

Maven-module naast `fbs-common` en `fbs-berichtensessiecache`. Nieuwe C4-container in
`berichtenUitvraagSysteem`: **"Magazijnregister"** — "Houdt per deelnemende organisatie
(OIN) bij welk berichtenmagazijn erbij hoort. Nu config-backed; later database-opslag met
beheer-interface." Beide consumenten (sessiecache-library, uitvraag-service) hangen aan dit
register; geen van beide bezit de koppeling.

### Publiek contract

```kotlin
package nl.rijksoverheid.moz.fbs.magazijnregister

data class MagazijnInschrijving(
    val oin: Oin,
    val url: URI,
    val naam: String?,
)

interface Magazijnregister {
    /** Alle ingeschreven magazijnen — sessiecache bouwt hieruit zijn REST-clients. */
    fun alle(): Collection<MagazijnInschrijving>

    /** Inschrijving voor [oin], of null als die OIN geen magazijn heeft (drift/onbekend). */
    fun voorOin(oin: Oin): MagazijnInschrijving?
}
```

Config-backed implementatie: `@ConfigMapping(prefix="magazijnen")`, **key = OIN**.
OIN-key wordt bij opstart gevalideerd (elfproef via `Oin`) en de URL op geldigheid +
`https` in prod-achtige profielen (hergebruik `OutboundTlsValidator`); fail-fast op een
ongeldige key of URL. De `afzenders`-lijst vervalt volledig.

```properties
# was: magazijnen.instances.magazijn-a.{url,naam,afzenders} + magazijnen.urls.magazijn-a
magazijnen."00000001003214345000".url=${MAGAZIJN_BD_URL}
magazijnen."00000001003214345000".naam=Belastingdienst
```

### Consumers verschuiven

| Plek | Nu | Na |
|------|-----|-----|
| `fbs-berichtensessiecache` `MagazijnenConfig` + `afzenders` | eigen `instances.*.afzenders[]` | **verwijderd**; `MagazijnClientFactory` bouwt clients uit `register.alle()` |
| `MagazijnClientFactory.magazijnenVoorAfzender(oin)` | reverse-index O(N×M) | `if (register.voorOin(oin) != null) setOf(oin.waarde) else emptySet()` |
| `ProfielMagazijnResolver.bepaalMagazijnen` | match opted-in OIN tegen afzender-index | drift-check = OIN onbekend in register |
| `berichtenuitvraag` `MagazijnenConfig.urls` + `MagazijnRouter` | `magazijnen.urls.<id>` | `register.voorOin(oin).url`; eigen `urls()`-map weg |

`MagazijnClientFactory` blijft de REST-client-fabriek (met zijn eigen read-/connect-timeouts);
het haalt alleen de magazijn-set niet meer uit eigen config maar uit het register.
`MagazijnRouter` houdt zijn `client.*`-timeouts en client-cache; alleen de URL-bron
verschuift naar het register.

### Gevolg voor de 1:1-invariant

Map-key = OIN ⇒ uniciteit structureel afgedwongen (geen dubbele OIN mogelijk in config).
De M:N-machinerie valt weg: tie-break bij "afzender bij meerdere magazijnen", de
bijbehorende warnings, en de reverse-index-`buildMap`. Geen verdediging meer voor een
geval dat niet kan bestaan.

## Datastroom (ongewijzigd, andere sleutelwaarde)

1. Ontvanger vraagt berichten op → `ProfielMagazijnResolver` bepaalt te bevragen magazijnen.
   Voor een OIN-ontvanger (B2B): alle magazijnen. Voor BSN/RSIN/KVK: Profiel-voorkeuren →
   opted-in afzender-OIN's → register-lookup → magazijn-OIN's.
2. `MagazijnClientFactory` levert per magazijn-OIN een REST-client; `BerichtensessiecacheService`
   aggregeert. Elk `Bericht` krijgt `magazijnId = oin.waarde`.
3. Uitvraag-bijlage/patch/delete: `MagazijnRouter.forMagazijn(oin)` → `register.voorOin(oin).url`.

Onbekend `magazijnId` in de router blijft 502 (topologie-mismatch), gelijk aan vandaag.

## Foutafhandeling

- **Opstart:** ongeldige OIN-key, ongeldige/niet-`https` URL, of leeg register → boot faalt
  (fail-fast, zelfde lijn als de huidige `MagazijnClientFactory.init`).
- **Runtime drift:** Profiel noemt een opted-in afzender-OIN die niet in het register staat
  → die OIN levert geen magazijn op (`voorOin` = null), warn-log met de (publieke) OIN.
  Bij 100% drift → bestaande `ProfielServiceFoutException.configDrift()`-pad blijft.
- **Router:** onbekende `magazijnId` → 502 Bad Gateway (ongewijzigd).

## Teststrategie

- **`fbs-magazijnregister`:** `@ConfigMapping`-roundtrip; OIN-key-validatie (ongeldige key
  faalt boot); URL/TLS-validatie per profiel; `voorOin`/`alle`. Pure-JVM library → eigen
  `jacoco-maven-plugin` (zoals `fbs-common`), 90%-gate.
- **`fbs-berichtensessiecache`:** `MagazijnClientFactoryInitTest` en
  `ProfielMagazijnResolverTest`/`-IntegrationTest` herschrijven naar het register
  (register-stub i.p.v. `instances.*.afzenders`-config).
- **`berichtenuitvraag`:** `MagazijnRouter`-tests tegen een register-stub.
- Unhappy paths: ongeldige OIN-key, onbekende OIN in router (502), drift in resolver.

## Documentatie

- `docs/architecture/workspace.dsl`: nieuwe container "Magazijnregister" in
  `berichtenUitvraagSysteem` + relaties (`sessiecacheService` → register voor magazijn-set,
  `uitvraagOphaalService`/`uitvraagBeheerService` → register voor routing). `workspace.json`
  regenereren.
- `CLAUDE.md`: module-lijst (`libraries/fbs-magazijnregister`), de caching/config-sectie en
  de "Actieve modules"-opsomming bijwerken; `magazijnen.<OIN>`-config-conventie documenteren.

## Niet in scope

- Database-opslag en beheer-interface voor het register (productie-pad; later).
- Aanmeld-service (zit niet op `main`; verwerkt deze wijziging later op zijn eigen branch).
- Wijziging aan client-timeout-beleid of magazijn-REST-contract.
