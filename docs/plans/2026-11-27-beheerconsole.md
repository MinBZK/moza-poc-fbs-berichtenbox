# Beheerconsole voor het berichtenstelsel

**Status:** Concept

Issues: MinBZK/MijnOverheidZakelijk#1483 (deel), onder epic #1469 (beheermogelijkheden).

## Context

Epic #1469 vraagt beheerinzicht in de berichtenuitvraag en de magazijnen. De meetwaarden en
meldingen landen in een monitoringvoorziening (Grafana of wat #1473 kiest). Daarnaast zijn er
beheertaken die geen grafiek zijn: welke magazijnen zijn aangesloten (#1483), magazijnen in- en
uitschrijven (#1485), verwerkingen van één ondernemer opvragen (#1494). Die krijgen een eigen
beheerconsole, gebouwd met het NLDD-designsysteem (`@nldd/design-system`).

Het meeste beheerwerk wacht op uitzoekwerk (#1472, #1473, #1484, #1493). Wat nu al kan, is een
console die alleen openbare gegevens toont: het stelseldocument is publiek en ondertekend, dus
"welke magazijnen zijn aangesloten en tot wanneer geldt het document" vraagt geen
autorisatiebeslissing.

## Verdeling: dashboard of console

- **Monitoringvoorziening (Grafana of opvolger):** alles wat een tijdreeks is: verkeer, duur,
  fouten, achterstand, meldingen. Bron: `/q/metrics` (#1474) en de logs (#1475).
- **Beheerconsole (NLDD):** de toestand van het stelsel en beheerhandelingen: aangesloten
  magazijnen, geldigheid van het stelseldocument, later inschrijven en uitschrijven, en
  inzageverzoeken.

## Stappen

1. **Skelet** (deze PR): module `services/beheerconsole` met Quarkus, Quinoa en een React-app
   met NLDD; de shell (navigatie, kop, voet) en een startpagina; security-headers met een
   CSP voor een webapp; CI (test-shard, detekt, Dependabot).
2. **Stelseloverzicht** (volgende PR): een backend-endpoint dat het stelseldocument ophaalt en
   verifieert volgens de zes stappen uit de spec van het stelselregister, met OpenAPI-spec en
   Bruno-collectie; een scherm met de magazijnen, de geldigheid en het verlopen van het
   ondertekencertificaat.
3. **Uitrol op ZAD** (volgende PR): de console als component, net als de andere stelseldiensten
   (besluit van de opdrachtgever, 2026-11-27).
4. **Later, na het uitzoekwerk:** het register van de uitvraag ernaast (#1483, tweede criterium),
   beheren zonder uitrol (#1485) en inzage (#1494).

## Ontwerpkeuzes

1. **Stelseldienst, geen demo.** De console hoort bij het product: een organisatie die het stelsel
   beheert, gebruikt hem. Hij staat onder `services/` en valt onder de demo-grens.
2. **Een eigen backend (BFF) in plaats van rechtstreeks vanuit de browser.** Het stelseldocument
   is in de browser op te halen (CORS staat open), maar de verificatie van de keten en de
   handtekening hoort bij een vastgelegde root en gaat in Kotlin met de JDK-PKIX-validatie
   betrouwbaarder dan met een JavaScript-ASN.1-parser. Latere schermen (inschrijven, inzage)
   hebben de backend toch nodig.
3. **Quinoa.** Quarkus bouwt de React-app mee in `mvn package` en serveert hem; `quarkus:dev`
   draait de Vite-devserver erachter. Geen aparte Node-build in CI: de Maven-shard bouwt alles.
   Quinoa installeert zelf een vastgepinde Node (`24.22.2`, LTS).
4. **Vaste versies.** `@nldd/design-system` staat exact op `0.23.0` (13 november 2026). Het pakket
   zit nog onder 1.0 en brengt bijna dagelijks een minor uit met brekende wijzigingen; een
   bereik (`^`) zou per installatie iets anders opleveren. Alle npm-afhankelijkheden staan exact
   gepind, de lockfile is ingecheckt en `npm ci` installeert. Dependabot bumpt.
5. **CSP voor een webapp.** De API-paden houden de strenge CSP (`default-src 'none'`). De app krijgt
   `default-src 'self'` met `script-src 'self'`, `style-src 'self'`, `img-src 'self' data:`,
   `object-src 'none'`, `base-uri 'none'`, `form-action 'self'`, `frame-ancestors 'none'`. Geen
   `unsafe-inline`: Vite levert losse script- en stylebestanden, en NLDD (Lit) gebruikt
   constructable stylesheets, die niet onder `style-src` vallen. De waarde staat bij de andere
   in `fbs-common/SecurityHeaders`. Alleen de ontwikkelserver (`quarkus:dev`) krijgt `'unsafe-inline'`
   voor script en style: Vite zet daar een inline preamble en laadt CSS als `<style>`.
6. **Geen inlog in stap 1 en 2.** De console toont alleen wat al openbaar is. Een scherm met
   niet-openbare gegevens of een beheerhandeling komt pas na het besluit over afscherming uit
   #1484. Tot die tijd hoort de console op ZAD achter de inlogmuur van het platform.

## Samengaan met het NMC-portaal

`MinBZK/moza-nmc-portaal` is een beheerportaal voor de notificatiecomponent, met dezelfde basis:
React, Vite, TypeScript, `react-router-dom` en `@nldd/design-system`. De opzet hier houdt een
latere samenvoeging klein:

- **Zelfde stack en versielijn.** React 19, react-router-dom 7, Vite, NLDD als web components. Het
  NMC-portaal staat nog op NLDD 0.8.63; samenvoegen begint met beide op dezelfde NLDD-versie.
- **Shell los van de inhoud.** `src/shell/` bevat de opmaak die elk beheerportaal deelt:
  navigatiebalk, paginaraamwerk, voet. Hij kent geen FBS-begrippen en krijgt zijn titel en
  menu-items als invoer. Een samengevoegd portaal neemt deze map over, of maakt er een gedeeld
  pakket van.
- **Inhoud per onderdeel.** Elk scherm hoort bij een onderdeel onder `src/onderdelen/<naam>/`,
  dat zijn routes en menu-items exporteert (`onderdeel.tsx`). De app voegt de onderdelen samen.
  De NMC-pagina's (`Applicaties`, `ApiTokens`, `Overzicht`) passen als een tweede onderdeel
  `nmc/`, zonder iets aan de shell te veranderen.
- **Een backend per onderdeel.** Een onderdeel praat alleen met zijn eigen API (hier
  `/api/v1/...` van deze console). In een samengevoegd portaal blijft dat zo: de FBS-schermen
  roepen de FBS-backend aan, de NMC-schermen die van de NMC.
- **JSX-typen uit het pakket.** NLDD levert ze zelf (`@nldd/design-system/react`); het NMC-portaal
  schrijft ze nog met de hand (`custom-elements.d.ts`). Bij samenvoegen vervalt dat bestand.
- **Basispad instelbaar.** Het NMC-portaal draait onder `/moza-nmc-portaal/`, deze console onder
  `/`. Vite's `base` en de `basename` van de router komen uit één instelling.

## Verificatie

- `@QuarkusTest`: de app wordt geserveerd op `/` en op een app-route (SPA-routing), met de
  webapp-CSP; de API-paden en `/q` houden hun eigen CSP; `/q/health` antwoordt.
- `npm run build` typecheckt (`tsc -b`) en faalt de Maven-build bij een typefout.
- Live: `quarkus:dev`, de pagina in een headless browser, zonder CSP-meldingen in de console.
