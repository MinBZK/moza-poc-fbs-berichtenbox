# Vergelijking ERDS en European Business Wallet vs. Federatief Berichtenstelsel (AI-gegenereerd)

> **AI-disclaimer:** Dit document is gegenereerd met behulp van Claude Code. De inhoud kan feitelijke onjuistheden bevatten en is nog niet inhoudelijk gereviewd. Feedback is welkom.

## Aanleiding

Het ECP-symposium [*De European Business Wallet: op naar eenvoudigere overheidsdienstverlening aan bedrijven*](https://ecp.nl/agenda/symposiumde-european-business-wallet-op-naar-eenvoudigere-overheidsdienstverlening-aan-bedrijven/) (22 september 2026) noemt in zijn begrippenlijst de **elektronische geregistreerde bezorgdienst (ERDS)**: de digitale tegenhanger van aangetekende post, met bewijs van verzending en ontvangst. Het bijbehorende document geeft als voorbeeld de verzending van gerechtelijke stukken binnen de rechtspraak, waar volgens dat document gekwalificeerde bezorgdiensten inmiddels verplicht zijn.

Dit document beantwoordt drie vragen:

1. Kan ERDS de basis zijn voor FBS, gegeven de huidige opzet?
2. Als je de huidige opzet loslaat: is een model met ERDS, de European Business Wallet (EBW) en een vault voor het bedrijf een betere oplossing?
3. Kan de EUDI-wallet, met een bevoegdheid uit de business wallet, dienen om namens een bedrijf in te loggen en als identiteit in de keten?

## Begrippen

| Begrip | Betekenis |
|--------|-----------|
| **ERDS** | *Electronic registered delivery service* (eIDAS art. 3(36)): een dienst die gegevens tussen partijen overbrengt met bewijs van verzending en ontvangst, en die het verzonden bericht beschermt tegen verlies, diefstal, beschadiging en ongeautoriseerde wijziging. |
| **QERDS** | Gekwalificeerde ERDS, geleverd door een gekwalificeerde vertrouwensdienstverlener (QTSP). Krijgt het rechtsvermoeden van eIDAS art. 43(2): integriteit van het bericht, verzending door de geïdentificeerde afzender, ontvangst door de geïdentificeerde geadresseerde en juistheid van datum en tijd. |
| **REM** | *Registered electronic mail*: een ERDS in de vorm van e-mail (ETSI EN 319 532). |
| **EBW** | European Business Wallet: een wallet voor bedrijven en andere economische actoren, voorgesteld door de Europese Commissie op 19 november 2025 (COM(2025) 838). |
| **Vault** | Opslag waarin berichten en documenten bij (of namens) de ontvanger staan, in plaats van bij de afzender. |

## Deel 1 — ERDS als basis voor FBS, vanuit de huidige opzet

### Waarom ERDS geen basis is

| Aspect | FBS nu | ERDS (eIDAS art. 43-44) |
|--------|--------|-------------------------|
| **Model** | Het bericht blijft in het magazijn van de afzender; de ontvanger haalt het op via de uitvraag | Een dienst bezorgt het bericht bij de ontvanger |
| **Doel** | Alle berichten van de overheid op één plek; in de praktijk vooral korte notificaties en mededelingen | Juridisch bewijs dat een bericht is verzonden en ontvangen |
| **Bewijs** | De leesstatus (`gelezen`) is een vlag die de ontvanger zelf zet en terugzet (`BerichtBeheerService`); daarnaast een regel in het Logboek Dataverwerkingen. Geen van beide is bewijs in de zin van eIDAS | Ondertekend en getijdstempeld bewijs, bij de gekwalificeerde variant afgegeven door een QTSP |
| **Wie geeft het bewijs af** | Het magazijn, dus de afzender zelf, geen neutrale partij | Een vertrouwde derde partij |

ERDS als basis zou elk bericht de zwaarte van een aangetekende brief geven. Dat botst met wat FBS grotendeels vervoert.

### Waar het wel past: "aangetekend" als berichttype

Voor berichten met rechtsgevolg (besluiten, aanmaningen, termijnen voor bezwaar) is een aangetekende variant bovenop FBS denkbaar. Wat daarvoor ontbreekt:

1. **Bewijs van verzending bij het aanleveren**: een hash van het bericht, voorzien van een e-zegel van de afzender en een gekwalificeerd tijdstempel.
2. **Bewijs van ontvangst**: een ondertekend bewijs op het moment dat het bericht beschikbaar komt of voor het eerst wordt opgehaald. Welk van de twee momenten telt, is een juridische keuze.
3. **Identificatie van de ontvanger op hoog niveau**, via eHerkenning EH3+ of later de EBW.
4. **Bewijs dat vastligt en niet te wijzigen is**, los van de leesstatus. Die leesstatus blijft een voorziening voor de gebruiker.
5. **Een QTSP** voor het rechtsvermoeden van art. 43(2). Zonder QTSP is het een niet-gekwalificeerde ERDS: toegestaan als bewijs (art. 43(1)), maar zonder dat rechtsvermoeden.

De federatieve opzet blijft zo intact: het bewijs is metadata bij het bericht in het magazijn. Voor interoperabiliteit met andere EU-landen zijn ETSI EN 319 522 (ERDS) en EN 319 532 (REM) de relevante standaarden.

## Deel 2 — Los van de huidige opzet: ERDS, EBW en een vault

### Wat het EBW-voorstel regelt

Stand van het voorstel zoals de Commissie het indiende, aangevuld met de posities in het wetgevingsproces:

- **Kernfuncties** zijn identificatie, elektronisch ondertekenen en zegelen, en het versturen en ontvangen van documenten en gegevens **via een gekwalificeerde elektronische geregistreerde bezorgdienst (QERDS)**. ERDS is dus geen los alternatief naast de EBW, maar het bezorgkanaal ervan.
- **Publieke organisaties zijn verplicht** de EBW te accepteren voor die kernfuncties. De Commissie stelt daarvoor 24 maanden na inwerkingtreding; de Raad (positie van 9 juni 2026) verschuift dat naar twee jaar na de laatste uitvoeringshandelingen.
- **Voor bedrijven is gebruik vrijwillig.** De Commissie evalueert binnen drie jaar of verplicht gebruik nodig is.
- **Eenmanszaken en zelfstandigen** gebruiken hun persoonlijke EUDI-wallet voor de bedrijfsfuncties.
- **Wallet-providers en QERDS-providers** moeten in de EU gevestigd zijn; het Europees Parlement wil ook dat gegevens uitsluitend binnen de Unie worden verwerkt en opgeslagen.
- **Handelingen via de wallet** hebben hetzelfde rechtsgevolg als op papier of in persoon.

### Drie modellen naast elkaar

| Aspect | **A. Federatief** (bericht bij de bron, ophalen) | **B. Vault** (QERDS-push naar de EBW of vault van het bedrijf) | **C. Centrale bus** (klassieke berichtenbox) |
|--------|-----------------|----------------|----------------|
| **Waar staat het bericht** | Bij de afzender | Bij het bedrijf, in een vault bij een wallet-provider | Bij de centrale voorziening |
| **Eén inbox voor de ondernemer** | Alleen door samenvoegen over alle magazijnen, met de bijbehorende last (verbindingen, latency, gedeeltelijke uitval) | Vanzelf: alles komt op één plek binnen | Vanzelf |
| **Juridisch bewijs** | Moet er apart bij gebouwd worden (deel 1) | Zit erin (QERDS) | Moet er apart bij gebouwd worden |
| **Wie heeft de controle** | De overheid | De ondernemer | De beheerder |
| **Delen met boekhouder of gemachtigde** | Vergt autorisatie over alle magazijnen heen | Machtigingen zijn een kernfunctie van de EBW | Centraal te regelen |
| **Correctie of intrekking** | Mogelijk, want de bron is gezaghebbend | Na aflevering staat er een kopie buiten bereik; herstel alleen met een nieuw bericht | Mogelijk |
| **Archiefwet en bewaartermijnen** | Bij de bron | De overheid bewaart toch een eigen kopie, dus dubbele opslag | Centraal |
| **Grensoverschrijdend (EU)** | Zwak | Sterk: EU-wallets en ETSI-ERDS | Zwak |
| **Bereik** | Iedereen met eHerkenning | Alleen wie een wallet heeft | Iedereen |
| **Afhankelijkheid** | Eigen stelsel | Markt van wallet- en QERDS-providers | Eén voorziening, één punt van uitval |
| **Volwassenheid** | Nu te bouwen | Voorstel nog in onderhandeling; acceptatieplicht op zijn vroegst twee jaar na inwerkingtreding; interoperabiliteit tussen QERDS-providers is in de praktijk beperkt | Bestaand |

### Waar het vault-model beter is

- **Het samenvoegprobleem verdwijnt.** Bij pushen hoeft niemand bij het openen van de inbox alle magazijnen tegelijk te bevragen. Dat is een groot deel van de complexiteit van model A.
- **Bewijs en identiteit zitten in het model** in plaats van in een laag erbovenop.
- **Machtigingen en meerdere gebruikers per bedrijf** zijn een kernfunctie van de EBW.
- **Het werkt tussen EU-landen.** Een Duits bedrijf ontvangt een Nederlands besluit in zijn eigen wallet.

### Waar het vault-model zwak is

- **Niet elk bedrijf heeft een wallet.** Gebruik is vrijwillig, dus de overheid houdt een tweede kanaal nodig. Dat betekent twee kanalen bouwen en onderhouden.
- **Een kopie buiten de overheid.** Een foutief besluit is na aflevering niet meer terug te halen.
- **Een eenmanszaak valt samen met de persoon.** Het voorstel laat die de persoonlijke EUDI-wallet gebruiken; een bericht aan een eenmanszaak komt dan bij een persoonsgebonden wallet terecht, met de AVG- en BIO-vragen die daarbij horen.
- **Timing.** Wie op de EBW wacht, heeft tot die tijd geen kanaal.
- **Een ontvangstbewijs bewijst geen kennisname.** Het bewijst dat het bericht in de vault is aangekomen; welk rechtsgevolg dat heeft, hangt af van de regels voor ontvangst in het bestuursrecht (Awb, Wet modernisering elektronisch bestuurlijk verkeer).

### Vermoedelijk optimum: een hybride

**Bron gezaghebbend, bezorging via QERDS, de EBW als voordeur.**

- Het bericht blijft gezaghebbend bij de bron. Dat dekt archivering, correctie en controle door de overheid.
- Bij verzending gaat er een QERDS-bezorging naar de EBW: een verzegelde kopie of een verzegelde verwijzing, met bewijs van verzending en ontvangst.
- Een bedrijf zonder wallet valt terug op ophalen uit de bron — het federatieve model.

FBS wordt in dit beeld niet vervangen maar wordt de bronlaag en het vangnet; de EBW wordt het bezorgkanaal zodra die er is. Omdat publieke organisaties de EBW hoe dan ook moeten accepteren, is de vraag niet óf FBS er een koppeling mee krijgt, maar hoe.

De kernkeuze daarbij is **kopie of verwijzing**:

- **Kopie**: het bericht staat echt in de vault van het bedrijf, maar het is een duplicaat met alle gevolgen voor correctie en bewaring.
- **Verwijzing**: één bron, maar de ondernemer blijft afhankelijk van de beschikbaarheid van die bron, en het bewijs van ontvangst dekt dan alleen de verwijzing.

## Deel 3 — Inloggen namens een bedrijf met de EUDI-wallet

Op het symposium werd gedemonstreerd hoe iemand met zijn **persoonlijke** wallet namens een bedrijf zaken doet, met een attestatie die gekoppeld is aan de **business wallet**. De vraag voor FBS: kan dat dienen om in te loggen op het portaal en als identiteit in de keten, zodat een afnemer weet wie er ingelogd is?

### Hoe het werkt

1. De business wallet bevat de **EBWOID** (*European Business Wallet Owner Identification Data*, voorheen LPID): de geverifieerde identiteit van het bedrijf, met onder meer de EUID of het KVK-nummer.
2. Het bedrijf geeft een medewerker of vertegenwoordiger een **bevoegdheidsattestatie** (EAA). Die komt in diens persoonlijke EUDI-wallet en is aan die persoon gebonden, zodat alleen de houder hem kan tonen.
3. Bij een dienst toont de persoon zijn persoonsgegevens (PID) én die attestatie. De dienst ziet dan: *persoon X handelt namens bedrijf Y, met bevoegdheid Z*.

Het voorstel kent daarnaast een tweede route: gebruikers die de business wallet zelf bedienen. De route via de persoonlijke wallet past beter bij inloggen.

Nog open is **wie de bevoegdheid mag uitgeven**. Wettelijke vertegenwoordiging hoort bij het handelsregister, maar bevoegdheden aan medewerkers geeft het bedrijf zelf. Een dienst moet per handeling bepalen welke uitgever hij vertrouwt. Ketenmachtigingen, waarbij een intermediair namens een klant handelt, zijn in het wallet-model nog het minst uitgewerkt.

### Toepassing in FBS

Dit sluit aan op de wallet als derde inlogkanaal naast DigiD en eHerkenning, zoals beschreven in de OpenID4VP-appendix van [de VoRijk-vergelijking](vergelijking-fbs-vorijk.md), en op het token met KvK/RSIN en machtigingsclaims dat `workspace.dsl` de Interactielaag voor ondernemers laat uitgeven.

```
Persoonlijke EUDI-wallet
  └─ OpenID4VP: PID + bevoegdheidsattestatie ──► Interactielaag (verifier)
                                                   ├─ controleert handtekening, intrekking, bevoegdheid
                                                   └─ geeft eigen signed JWT uit
                                                        ├─ onderneming (KVK/RSIN/EUID)
                                                        ├─ handelende persoon (pseudoniem)
                                                        └─ bevoegdheid (scope)
                                                             ▼
                                               uitvraag → magazijnen (afnemers)
```

De Interactielaag vertaalt de wallet-presentatie naar hetzelfde soort token als bij eHerkenning, dus uitvraag en magazijnen veranderen nauwelijks. Een afnemer weet dan welk bedrijf en welke persoon er handelen; dat laatste is met eHerkenning nu lastig door de keten te krijgen.

### Wat geregeld moet worden

1. **De wallet-presentatie gaat niet door de keten.** Die bevat persoonsgegevens en is bedoeld voor één ontvanger. De keten krijgt een eigen token met alleen wat nodig is, en een pseudoniem voor de persoon in plaats van een BSN.
2. **Identificatienummers vertalen.** FBS kent BSN, RSIN, KVK en OIN (`Identificatienummer` in `fbs-common`). De EBWOID levert vooral de EUID, voor Nederland `NLNHR.<kvk-nummer>`. Een buitenlands bedrijf zonder RSIN of KVK-nummer vraagt een eigen type; [het plan voor getypeerde identificatienummers](plans/2026-04-22-identificatienummer-type-tagging.md) heeft `EidasId` om die reden uitgesteld.
3. **Intrekking controleren bij het inloggen**, via de statuslijst van de attestatie, zodat een ingetrokken bevoegdheid (een vertrokken medewerker) direct geen toegang meer geeft.
4. **Betrouwbaarheidsniveau vastleggen**: welk niveau van PID plus attestatie staat gelijk aan welk eHerkenning-niveau? Dat bepaalt welke berichten zichtbaar zijn.
5. **Eenmanszaken** hebben geen business wallet, alleen de persoonlijke EUDI-wallet. Het portaal moet een persoon dus ook als ondernemer kunnen herkennen.
6. **eHerkenning blijft nodig.** Het gebruik van de EBW is voor bedrijven vrijwillig; de wallet komt ernaast.

Dit hoort bij de keten-brede authenticatie en autorisatie die nog op een oplossingsrichting wacht (MinBZK/MijnOverheidZakelijk#552).

## Open punten

- Of de EBW via QERDS alleen volledige documenten bezorgt, of ook een verzegelde verwijzing naar een bron toestaat. Dat bepaalt of de hybride met verwijzingen juridisch houdbaar is.
- Of de QERDS-functie in de wallet zelf zit of bij losse QTSP's blijft, en hoe een overheidsorganisatie dan als afzender aansluit: per organisatie een eigen QTSP-contract, of één gedeelde voorziening.
- Welk moment als ontvangst geldt — beschikbaar stellen in de vault of openen — en hoe eIDAS en de Awb zich daarin tot elkaar verhouden.
- Hoe de uitzondering voor kleine gemeenten die het Parlement voorstelt uitpakt, en wat dat betekent voor een stelsel waarin elke afzender een magazijn heeft.
- Welke uitgever een afnemer vertrouwt voor een bevoegdheidsattestatie (handelsregister of het bedrijf zelf), en hoe ketenmachtigingen in het wallet-model worden uitgedrukt.
- De bewering over verplicht gekwalificeerd bezorgen binnen de rechtspraak komt uit het symposiumdocument en is hier niet zelfstandig nagegaan.

## Bronnen

- [Symposium De European Business Wallet — ECP](https://ecp.nl/agenda/symposiumde-european-business-wallet-op-naar-eenvoudigere-overheidsdienstverlening-aan-bedrijven/)
- [Voorstel voor een verordening over European business wallets, COM(2025) 838 — EUR-Lex](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=celex:52025PC0838)
- [European business wallets — Legislative Train Schedule, Europees Parlement](https://www.europarl.europa.eu/legislative-train/theme-a-new-plan-for-europe-s-sustainable-prosperity-and-competitiveness/file-european-business-wallet)
- [European business wallets — EPRS-briefing](https://www.europarl.europa.eu/RegData/etudes/BRIE/2025/774703/EPRS_BRI(2025)774703_EN.pdf)
- [Verordening (EU) 910/2014 (eIDAS), art. 3(36), 43 en 44 — EUR-Lex](https://eur-lex.europa.eu/legal-content/NL/TXT/?uri=CELEX:32014R0910)
- [European Business Wallet Owner ID (EBWOID) — iGrant.io](https://docs.igrant.io/docs/european-business-wallet-owner-id/)
- [European Business Wallet Glossary 2026 — Spherity](https://www.spherity.com/post/european-business-wallet-glossary-2026-terminology-regulation-and-architecture)
- [The European Business Wallet: eIDAS for companies, not just citizens — Yivi](https://yivi.app/en/blog/european-business-wallet/)
- ETSI EN 319 522 (Electronic Registered Delivery Services) en ETSI EN 319 532 (Registered Electronic Mail)
