# Analyse: Architectuur voor Uniforme Bronontsluiting (UBO) (AI-gegenereerd)

> **AI-disclaimer:** Dit document is gegenereerd met behulp van Claude Code. De inhoud kan feitelijke onjuistheden bevatten en is nog niet inhoudelijk gereviewd. Feedback is welkom.

**Bron:** [Architectuur Uniforme bronontsluiting (PDF)](https://digilab.overheid.nl/uploads/uniforme-bronontsluiting/2025-11-11%20Architectuur%20Uniforme%20bronontsluiting.pdf)
**Auteur:** VNG Realisatie | **Versie:** 11-11-2025 (concept) | **Omvang:** 42 pagina's

## 1. Doel en scope

Uniforme Bronontsluiting (UBO) biedt gemeenten **een enkele manier** om hun databronnen te ontsluiten voor meerdere distributiekanalen, met name:

- **OOTS** (Once Only Technical System) - grensoverschrijdende gegevensuitwisseling binnen de EU
- **EUDI-wallet** - de European Digital Identity Wallet voor attestaties aan burgers

Het ontzorgt gemeenten door:

- Ondersteunende services te bieden (Basisinrichting OOTS, verificatieservice voor EUDI-wallet)
- Veilige en betrouwbare gegevenslevering in te richten uit gemeentelijke bronnen
- Europese verplichtingen (SDG-verordening, eIDAS) centraal op te lossen ("ontkokering")

De architectuur is opgezet in afstemming met het **Programma Federatief Datastelsel (FDS)** en het project **Gemeenschappelijke Bronontsluiting (GBO)**, en maakt gebruik van **Common Ground**-standaarden.

## 2. Strategie en context

Het project opereert in een context van **onzekerheid**: de EUDI-wallet is nog niet volledig gespecificeerd, FSC-profielen zijn nog in ontwikkeling. De architectuur is daarom bewust opgesteld met "veronderstellingen" die met stakeholders zijn afgestemd.

**Kernprincipe:** Door het gemeentelijke gegevensaanbod te ontkoppelen van het gebruik ervan, ontstaat een stabiele kern - de **gemeentelijke integratielaag** - waarmee gegevens via verschillende kanalen op een manier geleverd kunnen worden.

### Relevante Europese ontwikkelingen

- **Europe's Digital Decade** - 2030-doelen voor digitale identiteit en overheidsdiensten
- **Single Digital Gateway-verordening** - introduceert het OOTS
- **eIDAS-verordening (herzien)** - introduceert de EUDI-wallet
- **European Interoperability Act** - maatregelen voor interoperabiliteit overheidssector

### Relevante nationale ontwikkelingen

- **Gemeenschappelijke Bronontsluiting (GBO)** - samenhangend set van afspraken, standaarden en stelselfuncties voor uniforme bronontsluiting
- **Interbestuurlijke Data Strategie / Federatief Data Stelsel (FDS)** - federatief stelsel voor datadeling en integratie (BZK); afspraken en standaarden van FDS zijn van toepassing, waaronder Digikoppeling en FSC
- **Programma EDI stelsel NL** - ontwikkeling van een stelsel voor publieke en private EUDI-wallets (BZK)
- **Large Scale Pilots** - Europese proefprojecten voor EUDI-wallet; gemeenten Amsterdam en Rotterdam nemen deel aan LSP APTITUDE
- **Trusted Information Partners (TIP)** - publiek-private samenwerking voor afsprakenstelsel en technische specificaties voor gegevensuitwisseling; werkt samen met ETSI
- **Common Ground** - transitie van de gemeentelijke informatievoorziening; UBO maakt gebruik van de FSC-standaard die door Common Ground is ontwikkeld
- **Nederlandse Digitaliseringsstrategie (NDS)** - centrale regie voor digitalisering

## 3. Use cases

Uniforme Bronontsluiting is ontwikkeld voor drie use cases:

| # | Use case | Kanaal | Fase |
|---|----------|--------|------|
| 1 | **OOTS-1: verstrekken van evidence** | OOTS | Fase 1 |
| 2 | **EUDI-wallet 1: verstrekken van een QEAA** | EUDI-wallet | Fase 2 |
| 3 | **EUDI-wallet 2: attribuutverificatie** | EUDI-wallet | Fase 2 |

### Use case OOTS-1: verstrekken van evidence

Een burger vraagt in een ander EU-lidstaat een publieke dienst aan waarvoor geboortegegevens van een Nederlandse gemeente nodig zijn. De gebruiker kiest ervoor om die gegevens door de buitenlandse dienstverlener digitaal via het OOTS op te laten halen bij de Nederlandse gemeente.

### Use case EUDI-wallet 1: verstrekken van een QEAA

Een burger wil zijn geboortegegevens als een hoog-betrouwbare attestatie in zijn EUDI-wallet krijgen. De burger gaat naar een loket van de daarvoor door de gemeente aangewezen QTSP en vraagt de attestatie op. De QTSP haalt de gegevens bij de gemeente op en verstrekt ze aan de EUDI-wallet van de gebruiker.

### Use case EUDI-wallet 2: attribuutverificatie

De burger verstrekt een kopie van zijn geboortegegevens aan een QTSP met het verzoek de informatie als hoog-betrouwbare attestatie in zijn wallet te zetten. De QTSP vraagt de gemeente om de authenticiteit van de geboortegegevens te controleren. Als de gemeente constateert dat de informatie klopt, kan de QTSP de geboortegegevens als QEAA aan de wallet verstrekken.

## 4. Rollen

Rollen conform de domeinarchitectuur gegevensuitwisseling:

| Rol | Beschrijving |
|-----|-------------|
| **Bronhouder** | De gemeente die de databron beheert die het digitaal ontsluit |
| **Aanbieder** | De partij die de gegevens namens de gemeente op uniforme manier aanbiedt voor het gebruik via meerdere kanalen. Levert in UBO de integratielaag |
| **Intermediair** | De partij die de uniform aangeboden gegevens geschikt maakt voor het gebruik in een specifiek kanaal (OOTS, EUDI-wallet). QTSP wordt ook als intermediair beschouwd |
| **Afnemer** | De (publieke of private) instantie die de informatie gebruikt in dienstverleningsprocessen |
| **Gebruiker** | De natuurlijke persoon die de dienst heeft aangevraagd en voor wie de gegevens uit een databron opgevraagd moeten worden |

## 5. Bronontsluiting (architectuuronderdeel 1)

Het architectuuronderdeel "bronontsluiting" biedt overheidsorganisaties, waaronder gemeenten, een uniforme manier voor het beschikbaar stellen van gegevens uit verschillende bronnen. Dat gebeurt op een moderne API-georienteerde manier. De architectuur ontkoppelt het ontsluiten van de bron en het gebruik van de brongegevens in de verschillende kanalen.

### 5.1 Ontwerpbeslissingen

| Onderwerp | Beslissing | Onderbouwing |
|-----------|-----------|--------------|
| **Digikoppeling REST API profiel** | Uniforme bronontsluiting gebruikt het Digikoppeling REST API profiel voor gestandaardiseerde bronbevraging | Staat op de Pas-toe-of-leg-uit lijst; technologie-onafhankelijk; bredere ondersteuning in gangbare ontwikkeltalen (Go, Rust, Python, Java, etc.); wordt verwacht meer gebruikt te gaan worden in overheid-naar-overheid communicatieprofielen |
| **FSC voor transport** | Uniforme bronontsluiting benut FSC-functionaliteit voor authenticatie, autorisatie en logging | FSC is sinds 2024 onderdeel van de Digikoppeling standaard; gemeenten zijn grondlegger; schaalbaar zonder centrale componenten; bevat afspraken over mTLS en PKI-certificaten |

### 5.2 Processtappen

Het uniform beschikbaar stellen van het gegevensaanbod verloopt langs:

1. **Vastleggen** - het registreren van gegevens in een (authentieke) bron
2. **Ontsluiten** - het ophalen van gegevens uit de gegevensbron voor vragende partijen
3. **Uniformeren** - het omvormen van het formaat en de structuur van de gegevens tot een consistent aanbod. API conform Digikoppeling REST API profiel en API design rules. Datamodellen gebaseerd op het Gemeentelijk Gegevensmodel (GGM)
4. **Aanbieden** - het geuniformeerde gegevensaanbod bekend maken aan afnemers conform FSC-standaard (publiceren in FSC-catalogus)

Bij een gegevensvraag:

5. **Ontvang gegevensvraag** - controleren en accepteren van de vraag binnen een FSC-contract
6. **Authenticeer gegevensvrager** - vaststellen identiteit via FSC met PKI-certificaten
7. **Autoriseer gegevensvraag** - controleren of de vragende partij bevoegd is via FSC-contracten
8. **Lokaliseer gegevensbron** - bepalen welke bron bevraagd moet worden
9. **Vraag gegevens op** - de integratielaag verzoekt de bron om de benodigde gegevens
10. **Ontvang, ontsleutel en valideer gegevens** - decryptie en syntactische/semantische controle
11. **Lever gegevens** - omvormen naar Data API formaat en verstrekken aan vragende partij
12. **Log gegevenslevering** - vastleggen in logbestanden voor audit trail

### 5.3 Componenten en interfaces

De UBO integratielaag bestaat uit:

- **Databronnen** - de gemeentelijke bronnen die digitaal ontsloten worden
- **Middleware** - de component die ervoor zorgt dat de gegevens uit de verschillende gemeentelijke databronnen ontsloten en gecombineerd kunnen worden, zodat ze beschikbaar zijn voor de Data API
- **Data API** - de centrale service van de Uniforme bronontsluiting en het "contactpunt" voor gegevensvragen die afnemers - al dan niet via tussenliggende distributiekanalen - aan de bronhouders stellen
- **OpenFSC Inway** - open source implementatie van de FSC inway: een reverse proxy die toegangstokens valideert, het verzoek naar de juiste service routeert en respons terugstuurt
- **OpenFSC Outway** - open source implementatie van de FSC Outway: een forward proxy die gegevensvragen via het netwerk naar de Inway stuurt die behoort bij de service die aangeroepen wordt

Interfaces:

| Interface | Tussen | Standaard |
|-----------|--------|-----------|
| Interface 1 | Databron - Middleware | Niet gestandaardiseerd, specifiek per gemeente |
| Interface 2 | OpenFSC Inway - OpenFSC Outway | FSC-standaard voor authenticatie, autorisatie en routering; Digikoppeling REST API profiel; API design rules |

### 5.4 Data API

De Data API is de centrale service die de gegevens op geuniformeerde manier beschikbaar stelt. De API voldoet aan het Digikoppeling REST API-profiel, is gespecificeerd conform de OpenAPI-specificatie en volgt de API design rules.

Het request bevat altijd:

1. De identificatie van de natuurlijke of niet-natuurlijke persoon (persoonsidentificatiegegevens conform eIDAS-verordening, indien van toepassing aangevuld met BSN)
2. De identificatie van het gevraagde dataset (bewijstype of attestatietype)

De Data API biedt gegevens **kanaalonafhankelijk** aan. De structuur en het formaat van de te leveren gegevens kan echter wel kanaalspecifiek zijn.

### 5.5 FSC configuratie

#### Rollen

| FSC-rol | Partij |
|---------|--------|
| FSC provider | Bronhouder (gemeente), kan gedelegeerd worden aan ICT-leverancier (Enable-U). Bronhouder treedt op als Delegator service provider, dienstverlener als Service provider |
| FSC consumer | Afhangt van use case: intermediair platform (OOTS), QTSP (EUDI-wallet 1), of via Stichting RINIS als Delegatee |

#### Contracten

FSC-contracten zijn nodig tussen alle bij de FSC-interactie betrokken partijen. Drie opties:

1. **Elke peer eigen FSC-manager** - volledig in control, maar impact kan groot zijn voor kleinere gemeenten en QTSP's
2. **Leveranciers beheren contracten namens klanten** - verwijzing naar papieren overeenkomst; aanwezigheid en geldigheid kan niet geautomatiseerd gecontroleerd worden
3. **Centrale signing service** - webapplicatie voor contractbeheer; nog in ontwikkeling

Optie 1 en 3 zijn toegestaan voor Uniforme bronontsluiting. **Optie 2 biedt onvoldoende waarborgen.**

#### FSC-properties op contracten

Drie properties voor autorisatie en auditing:

1. **AVG-grondslag** - toestemming, overeenkomst, wettelijke verplichting, vitaal belang, taak van algemeen belang of uitoefening van openbaar gezag, gerechtvaardigd belang
2. **Verwerkrelatie** - verwerkersovereenkomst of wettelijke taak
3. **Kanaal** - OOTS of QEAA

#### Statements

Twee typen aanvullende statements die de bronhouder kan verlangen:

1. **Verklaring dat de partij gegevens mag verwerken** - bewijs van toestemming onder de AVG (consent)
2. **Verklaring dat de partij gegevens mag opvragen**:
   - Verzoek: wens van de gebruiker (bijv. digitale handtekening via EUDI-wallet = "bewijs van verzoek")
   - Uitdrukkelijk verzoek: verklaring dat de gebruiker via het OOTS wil ophalen (specifiek voor OOTS, voorgeschreven in Uitvoeringsverordening)

#### Authenticatie

Authenticatie gebeurt conform FSC-standaard met **mutual TLS en X.509 certificaten**. Elke peer in het netwerk heeft een certificaat nodig: de Inway, Outway en de Managers.

#### Autorisatie

FSC maakt gebruik van **access tokens** die de FSC manager van de FSC provider op verzoek van de FSC consumer uitgeeft. Binnen een contract kunnen in FSC verschillende policies van toepassing zijn die bepalen welke bevragingen binnen dat contract toegestaan zijn. In UBO is dit opgevat als "de scope van het contract": de gegevenssets die opgevraagd mogen worden (bewijstypen, attestatietypen, individuele attributen).

#### Logging

FSC schrijft een standaard werkwijze voor logging voor, waarbij alle peers logging-attributen vastleggen. Door de unieke transactie-ID kunnen de peers samen een volledige audit trail samenstellen. Gegevensvragen en -antwoorden zelf worden **niet gelogd** vanwege het (mogelijk) privacygevoelige karakter van de gegevens.

## 6. Verstrekking via het OOTS (architectuuronderdeel 2)

Het OOTS is een Europees voorgeschreven uitwisselingssysteem voor implementatie van het once only principle bij grensoverschrijdende dienstverlening. Het is sinds eind 2023 (technisch) operationeel.

### 6.1 Kaders en uitgangspunten

De werking van het OOTS is gespecificeerd in:

- SDG-verordening, artikel 14
- Uitvoeringsverordening OOTS (2022/1463)
- Europese OOTS-specificaties (Technical Design Documents via OOTSHUB)
- Architectuur NL OOTS - deel 1 introductie en deel 2 procesmodel

De Basisinrichting OOTS is een nationale voorziening van BZK met twee onderdelen:

- **OOTS-V** - voor bewijsverstrekkers (bronhouders)
- **OOTS-A** - voor bewijsaanvragers (dienstverleners)

### 6.2 Ontwerpbeslissing

| Onderwerp | Beslissing | Onderbouwing |
|-----------|-----------|--------------|
| **Aansluiten op de Basisinrichting OOTS** | Uniforme bronontsluiting maakt gebruik van de Basisinrichting OOTS voor het aansluiten op het Europese OOTS-netwerk | Voorkomt nieuwe inspanningen voor bouw en beheer; voldoet aan Europese specificaties; beheerorganisatie is ingericht op volgen van Europese ontwikkelingen; voorgedragen voor opname in de GDI; krijgt wettelijke status via Besluit Digitale Overheid |

### 6.3 Componenten en interfaces

Om aan te sluiten op de UBO integratielaag is de OOTS-V uitgebreid met een Digikoppeling- en FSC-compliant aansluitvariant (OpenFSC outway).

**Componenten van het intermediaire platform OOTS-V:**

- **OpenFSC Outway** - bevraagt de integratielaag conform FSC-voorschriften; geeft bij de aanroep mee welke gegevens gevraagd worden, welke persoon het betreft, onder welk FSC-contract de bevraging plaatsvindt en bewijs van instemming
- **OOTS-V backend** - omvorming van het UBO-datamodel naar het OOTS Exchange Data Model (EDM); bewaakt het uitwisselingsproces (state) en zorgt voor logging en audit trail
- **eDelivery toegangspunt** - implementeert de Europese AS4-standaard (Domibus implementatie); gekoppeld aan eDelivery toegangspunten in andere lidstaten via S-CIRCABC
- **Authenticatieservice** - herauthenticatie van de gebruiker via DigiD, eHerkenning of eIDAS-genotificeerd inlogmiddel (via identity broker TVS); zorgt voor beschermde omgeving tegen man-in-the-browser attacks en achterhaalt BSN
- **Preview en toestemmingservice** - toont gegevens aan gebruiker (indien gewenst) en verwerkt instemming met het verzenden naar de andere lidstaat

**Interfaces:**

| Interface | Tussen | Standaard |
|-----------|--------|-----------|
| Interface 2 | UBO integratielaag - Intermediair platform | Digikoppeling REST API profiel + FSC |
| Interface 3 | NL intermediair platform - EU intermediair platform | eDelivery AS4 (v1.15), OOTS EDM, RegRep (v4.0) |

### 6.4 OOTS-V aansluiting

De voorkeursaansluiting op de OOTS-V verloopt via **Digikoppeling REST API profiel en FSC**. De OOTS-V functioneert als de "consumer zijde" van de FSC-relatie en is het de tegenhanger van de Data API die namens de "provider zijde" optreedt.

Het sequence diagram beschrijft drie fasen:

1. **FSC peer registratie** - announce met managerURL, peerOIN, peerName; persisteer peer metadata
2. **FSC contract management** - ServicePublicationGrant en ServiceConnectionGrant; valideer en onderteken contracten
3. **API Usage** - data API request met serviceConnectionGrant hash; access token uitwisseling; bevraging via Inway; native access naar bron

### 6.5 Vertrouwensmodel OOTS

Drie FSC peers met contracten:

- **Gemeente** = delegator service publication
- **ICT-leverancier / integrator** = FSC provider
- **Intermediair platform** = FSC consumer

De contracteringsflow kent 10 stappen: Create Contract, Sign, Submit, Accept tussen de drie peers.

Het OOTS is een netwerk van vertrouwde partijen. Nationale goedkeuring van de nationaal coordinator SDG (BZK) is vereist voor elke aansluiting. Partijen worden als "vertrouwd" geclassificeerd. Er vindt geen run-time controle op rechtmatigheid plaats; partijen moeten informatie verstrekken waarmee rechtmatigheid achteraf te controleren is.

### 6.6 Use case OOTS-1: verstrekken van evidence (processtappen)

1. **Browse naar website EU-dienstverlener** - de gebruiker wil een dienst (SDG-procedure) afnemen in een andere lidstaat
2. **Presenteer diensten** - de EU-dienstverlener toont beschikbare diensten
3. **Start SDG-procedure** - de gebruiker kiest de dienst
4. **Initieer authenticatie** - de EU-dienstverlener biedt inlogmogelijkheid met o.a. DigiD en eHerkenning
5. **Log in met eIDAS genotificeerd inlogmiddel** - de gebruiker maakt zijn identiteit bekend
6. **Geef optie om bewijs via OOTS op te halen** - de EU-dienstverlener biedt de mogelijkheid; de gebruiker weet dat inschakelen niet verplicht is
7. **Verzoek om inzet van OOTS** - de gebruiker vraagt de EU-dienstverlener om gegevens via het OOTS op te halen
8. **Verzoek om bewijs via het OOTS** - de EU-dienstverlener stuurt het "uitdrukkelijk verzoek" naar de Basisinrichting OOTS-V
9. **Ontvang bewijsverzoek** - de OOTS-V controleert en accepteert het bewijsverzoek
10. **Initieer herauthenticatie** - de OOTS-V laat de gebruiker inloggen met DigiD, eHerkenning of EU-genotificeerd inlogmiddel in een beschermde omgeving; achterhaalt BSN
11. **Log in op de Basisinrichting OOTS** - de gebruiker maakt zijn identiteit bekend via gekozen authenticatiemiddel
12. **Vraag gegevens op** - de Basisinrichting OOTS benadert de UBO integratielaag (Data API) voor het leveren van de benodigde gegevens
13. **Beantwoord gegevensvraag** - de UBO integratielaag zorgt voor het ophalen en verstrekken conform de principes uit hoofdstuk 2
14. **Ontvang gegevens** - de OOTS-V ontvangt de gegevens van de UBO integratielaag
15. **Toon voorbeeldweergave** - de OOTS-V toont de gegevens aan de gebruiker (previewfunctie)
16. **Stem in met verstrekking** - de gebruiker stemt in met het verzenden van de gegevens
17. **Verstrek bewijs** - de OOTS-V verstrekt de gegevens aan de EU-dienstverlener via eDelivery
18. **Vervolg procedure** - de EU-dienstverlener ontvangt de gegevens en zet het dienstverleningsproces door

## 7. Verstrekking naar de EUDI-wallet (architectuuronderdeel 3)

De specificaties voor de verstrekking van attestaties aan EUDI-wallets en het verifieren van attributen door een QTSP zijn nog volop in ontwikkeling. De lidstaten, de Europese Commissie, het EDI-programma, TIP en ETSI werken aan de specificaties.

### 7.1 Kaders en uitgangspunten

- **eIDAS uitvoeringsverordening 45def** - verplicht de Europese Commissie om inzetbaarheid van OOTS Common services als catalogi voor de EUDI-wallet te onderzoeken; geeft lidstaten mogelijkheid om nationale OOTS-componenten in te zetten voor attribuutverificatie
- **OOTS-EUDI-wallet Synergies (contact group)** - brengt samenhang tussen OOTS en EUDI-wallet in kaart; UBO beproeft investment synergies 4, 5 en 6
- **ETSI TS 119 478** - standaard voor attribuutverstrekking en attribuutverificatie (draft)
- **Architecture Reference Framework (ARF)** - Annex 2 (topic 42) beschrijft requirements voor QTSP's om authentieke bronnen te benaderen
- **EUDI Wallet standards and Technical specifications** - TS11 voor interfaces, formaten, catalogus van attributen en catalogus van schema's
- **Ministerie van EZ Q&A over artikel 45 eIDAS** - geen initiatieven voor Pub-EAA's; geen nationale publieke trust service provider; geen nationaal verificatiepunt; bronhouders mogen samenwerken aan oplossingen

### 7.2 Ontwerpbeslissingen

| Onderwerp | Beslissing | Onderbouwing |
|-----------|-----------|--------------|
| **Attribuutverstrekking** | Via QTSP (niet via nationale Pub-TSP) | EZ en BZK hebben geen initiatieven voor uitgifte van Pub-EAA's en geen voornemen om een nationale publiek trust service provider in te richten |
| **Pull-model** | QTSP haalt gegevens op op verzoek van de gebruiker (pull) | Pull-model biedt meer flexibiliteit voor gemeenten en QTSP's; sluit aan bij attribuutverificatie; draagt bij aan gegevensbescherming (geen onnodige uitgifte als QTSP onbeschikbaar is) |
| **Attribuutverificatie** | Gemeenschappelijke verificatieoplossing voor alle gemeenten | EZ en BZK hebben geen voornemen voor nationale voorziening; gemeenschappelijke service ontzorgt gemeenten bij artikel 45-verplichting |
| **Gelijke afspraken** | Dezelfde afspraken en standaarden voor QTSP in beide use cases | Eenduidig voor QTSP's; voorkomt prijsopdrijving door meerdere implementaties; beperkt lock-in |
| **Europese standaarden** | Europese (niet nationale) standaarden en protocollen voor QTSP-interactie | QTSP's zijn Europees gekwalificeerd; beperkt lock-in; grotere markt van beschikbare QTSP's; eenvoudiger overstappen |
| **Berichtstandaard** | eDelivery voor interactie met QTSP's | TIP hanteert eDelivery ook; ontwikkeld voor many-to-many interacties; mogelijkheid voor QERDS; waarborgen voor vertrouwelijkheid en onweerlegbaarheid |
| **Data-model** | OOTS EDM als basis voor request-response met QTSP | DG DIGIT heeft dit datamodel al beproefd; wordt zoveel mogelijk overgenomen van DIGIT; alleen waar nodig aangepast |

### 7.3 BSN-verwerking

QTSP's zijn private organisaties die in beginsel niet gerechtigd zijn het BSN te verwerken. Het BSN is echter nodig voor het ophalen van gegevens bij de gemeentelijke bron.

**Verschillen per use case:**

- **Use case 1 (QEAA verstrekking):** De gemeente en QTSP sluiten verwerkersovereenkomst; QTSP krijgt BSN bij authenticatie van de gebruiker en verstrekt dit aan het intermediaire platform
- **Use case 2 (attribuutverificatie):** Geen contractrelatie nodig (wettelijke bevoegdheid art. 45); het intermediaire platform achterhaalt BSN zelf

**Drie opties voor BSN-achterhaling bij use case 2:**

1. **Herauthenticatie in het intermediair platform** - gebruiker logt opnieuw in via DigiD/eHerkenning; voordeel: sluit aan bij OOTS-werkwijze; nadeel: extra gebruikersinteractie nodig
2. **Identity matching via BRPk-wallet** - PID-attributen vergelijken met BRP; hoog-betrouwbaar voor Nederlandse wallets; BRPk-wallet is nog niet ontwikkeld; minder eenduidig bij buitenlandse wallets
3. **Versleutelde identiteiten op de EUDI-wallet** - polymorfe versleuteling via BSNk; voordeel: geen herauthenticatie of identity matching nodig; nadeel: gebruiker moet versleutelde identiteiten vooraf op wallet laden

Combinaties van de opties zijn ook mogelijk.

### 7.4 Componenten en interfaces

**UBO integratielaag** - Data API en OpenFSC Inway (zoals beschreven in hoofdstuk 2)

**Intermediair platform voor OOTS en EUDI-wallet:**

- **OpenFSC Outway** - bevraagt de gemeentelijke integratielaag via FSC (hergebruik van OOTS-V)
- **OOTS-V Backend** - transformatie van DATA API responses naar OOTS EDM-berichten; bewaakt status en logt interacties (hergebruik van OOTS-V)
- **eDelivery toegangspunt** - AS4-standaard voor communicatie met QTSP's (hergebruik van OOTS-V)
- **Verificatieservice** - vergelijkt ontvangen attributen met authentieke bron op individueel attribuutniveau
- **BSN-service** - achterhaalt BSN van de natuurlijke persoon indien nodig

**QTSP platform:**

- **eDelivery toegangspunt** - tegenhanger van het gemeentelijk toegangspunt
- **QEAA Provider** - handelt de gebruikersinteractie en interactie met de EUDI-wallet af; genereert QEAA conform ARF (MDOC en/of SD-JWT)

**Aanvullende voorzieningen:**

- **BRPk-wallet** (werknaam, BZK) - BSN opzoeken via identity matching met BRP; nog in ontwikkeling
- **TVS** (ToegangVerleningService) - publieke identity broker voor authenticatie met DigiD, eHerkenning of eIDAS-genotificeerd middel
- **BSNk** (BZK via Logius) - polymorfe versleuteling van identiteiten; inschakeling gebeurt niet run-time
- **Catalogus authentieke bronnen en attributen** - door de Europese Commissie te leveren; nog in ontwikkeling
- **Dienstverlenertoepassing** - accepteert attestaties van de EUDI-wallet (buiten scope)

**Interfaces:**

| Interface | Tussen | Standaard | Status |
|-----------|--------|-----------|--------|
| Int 2 | UBO integratielaag - Intermediair platform | Digikoppeling REST API + FSC | Uitgewerkt |
| Int 2a | Intermediair platform - QTSP platform | eDelivery AS4 (v1.15), OOTS EDM, RegRep (v4.0) | Uitgewerkt |
| Int 2b | QTSP platform - EUDI-wallet | OID4VCI protocol, MDOC of SD-JWT (conform ARF) | Conform ARF |
| Int 2c | Intermediair platform - BRPk-wallet | Nog niet ontworpen | RvIG stelt specificaties op |
| Int 2d | Intermediair platform - TVS | SAML (DICTU) | Met OIDC-aansluitmogelijkheid via Stelsel |
| Int 2e | Intermediair platform - BSNk | Voorgeschreven door Logius | Beschikbaar |
| Int 2f | QTSP platform - Catalogus authentieke bronnen | Nog door EC te ontwerpen | In ontwikkeling |
| Int 3 | EUDI-wallet - Dienstverlener | Conform ARF | Buiten scope |

### 7.5 Vertrouwensmodel EUDI-wallet

Drie vertrouwensmodellen van toepassing:

1. **FSC-vertrouwensmodel** - bevoegdheid om te vragen vastgesteld door contractrelaties en properties
2. **eDelivery-vertrouwensmodel** - four corner typology; bilateraal vertrouwen (QEAA-verstrekking) of configuratie op kwalificatie (attribuutverificatie)
3. **EUDI-wallet vertrouwensmodel** - trust lists conform Architecture Reference Framework (ARF) voor partijen die wallets mogen uitgeven, attestaties mogen verstrekken (QTSP's) en wallet-vertrouwende partijen

**Use case EUDI-wallet 1 - vier FSC peers:**

- QTSP = FSC-delegator
- Intermediair platform = delegatee (FSC consumer)
- Gemeente = delegator service publication
- ICT-leverancier/integrator = FSC provider

Contracteringsflow: 17 stappen (Create, Sign, Submit, Accept tussen vier peers)

**Use case EUDI-wallet 2 - drie FSC peers:**

QTSP wordt niet betrokken in FSC-contractering (wettelijke bevoegdheid art. 45 eIDAS). Alleen:

- Intermediair platform = FSC consumer
- Gemeente = delegator service publication
- ICT-leverancier/integrator = FSC provider

Voor de interactie tussen het intermediair platform en het QTSP-platform past UBO ook in use case 2 de eDelivery standaard toe. UBO verwacht dat er een Europees eDelivery netwerk van gekwalificeerde QTSP's en authentieke bronnen (met hun verificatieservices) wordt opgezet.

### 7.6 Use case EUDI-wallet 1: verstrekken van een QEAA (processtappen)

1. **Verzoek om attestatie in wallet te laden** - de gebruiker maakt zijn verzoek bekend aan de QTSP
2. **Selecteer gewenste attestatie** - de gebruiker maakt bekend welke attestatie hij in zijn wallet wil laden
3. **Initieer authenticatie** - de QTSP moet de identiteit op hoog-betrouwbaarheidsniveau vaststellen
4. **Log in met EUDI-wallet** - de gebruiker authenticeert zich en verstrekt zijn PID aan de QTSP
5. **Vraag gegevens op** - de QTSP stelt vast dat het intermediair platform bevraagd moet worden
6. **Transformeer gegevensvraag** - het intermediaire platform omvormt de vraag voor het UBO integratielaag (van RegRep OOTS EDM naar Data API; van eDelivery AS4 naar Digikoppeling REST API)
7. **Beantwoord gegevensvraag** - de UBO integratielaag zorgt voor ophalen bij de juiste bron
8. **Transformeer response** - het intermediair platform transformeert de response terug naar QTSP-formaat
9. **Ontvang gegevens** - de QTSP controleert of de attestatie hiermee uitgegeven kan worden
10. **Genereer gekwalificeerde attestatie** - de QTSP genereert een QEAA conform ARF (MDOC en/of SD-JWT)
11. **Bied attestatie aan** - de QTSP verstrekt de gegevens aan de EUDI-wallet van de gebruiker
12. **Ontvang en controleer attestatie** - de gebruiker krijgt een voorinzage van de gegevens
13. **Accepteer attestatie** - de gebruiker stemt in met het laden van de attestatie in zijn wallet

### 7.7 Use case EUDI-wallet 2: attribuutverificatie (processtappen)

1. **Verzoek om attestatie in wallet te laden** - de gebruiker benadert een QTSP met de vraag om de gegevens die de gebruiker heeft als hoog-betrouwbare attestatie te laden (verschil: de gebruiker heeft de gegevens zelf al)
2. **Selecteer gewenste attestatie** - de gebruiker kiest het attestatietype
3. **Specificeer benodigde informatie** - de QTSP stelt vast welke claims en bewijzen nodig zijn
4. **Initieer authenticatie** - identiteitsvaststelling op hoog-betrouwbaarheidsniveau
5. **Log in met EUDI-wallet** - de gebruiker verstrekt zijn PID
6. **Verstrek benodigde informatie** - de gebruiker levert bewijzen (bijv. upload PDF of handmatige invulling)
7. **Verzoek om attribuutverificatie** - de QTSP bepaalt welke validatieservice bevraagd moet worden
8. **Ontvang verificatieverzoek** - het intermediaire platform ontvangt en bepaalt welke gegevens achterhaald moeten worden
9. **Vraag gegevens op** - het intermediaire platform bevraagt de UBO integratielaag (per attribuut, kan herhaald worden voor meerdere bronnen)
10. **Beantwoord gegevensvraag** - de UBO integratielaag haalt gegevens op bij de juiste bron
11. **Ontvang gegevens** - het intermediair platform controleert of het verificatieverzoek uitgevoerd kan worden
12. **Vergelijk gegevens** - het intermediaire platform vergelijkt de datasets per attribuut (ja/nee)
13. **Stel mate van overeenstemming vast** - zowel per attribuut als over de complete set; nuances worden gecommuniceerd aan de QTSP
14. **Verstrek verificatieresultaat** - het intermediair platform informeert de QTSP over het certificatieresultaat
15. **Ontvang verificatieresultaat** - de QTSP beoordeelt of het hiermee de QEAA kan samenstellen
16. **Genereer gekwalificeerde attestatie** - de QTSP genereert een QEAA conform ARF (MDOC en/of SD-JWT)
17. **Bied attestatie aan** - de QTSP verstrekt de gegevens aan de EUDI-wallet
18. **Ontvang en controleer attestatie** - de gebruiker krijgt een voorinzage
19. **Accepteer attestatie** - de gebruiker stemt in met het laden

## 8. Beproevingen

### 8.1 Fase 1 (OOTS) - afgerond

**Afbakening:**

- Databron = BRP van Gemeente Rijswijk, ontsloten via datadistributiesysteem van Rijswijk
- Bevraging middels StUF
- Evidence type = certificate of birth, JSON
- Uniformering voor Data API: datumformaat, geboorteplaatscode naar geboorteplaatsnaam en BRP landencode naar ISO3166 code

**Niet beproefd:**

- Volledige implementatie van de API design rules
- Onderdeel van de Data API dat nodig is voor het EUDI-wallet kanaal
- Signing en encryptie
- FSC properties voor het onderscheiden van contracten
- FSC policies voor autorisatie van een gegevensvraag binnen een FSC-contract
- FSC delegatie
- Inzet van een UBO-specifieke FSC-directory

**Betrokken partners:**

- Gemeente Rijswijk - gemeentelijke databron
- Enable-U - UBO integratielaag
- Stichting RINIS - intermediaire platform

De beproeving is uitgevoerd met partnerorganisaties uit Polen en Oostenrijk. De beproeving heeft plaatsgevonden in de OOTS projectathon in mei 2025 en daar ook gedemonstreerd aan de overige lidstaten en de Europese Commissie.

**Beslissingen voor beproeving:**

| Onderwerp | Beslissing | Argumentatie |
|-----------|-----------|--------------|
| **Payload signing/encryption** | In fase 1 alleen BASE64 hashed | Vereenvoudiging; signing en encryption in fase 2 via RFC 7515 (JSON web signature) en RFC 7516 (JSON web encryption) |
| **Discovery** | Via algemene FSC directory (Stichting RINIS) | Eenvoud; bij verdere uitrol nadenken over optimale inrichting |
| **Bewijs distributieformaat** | JSON payload | Sluit aan op Data API en Europese beweging naar JSON; sluit aan bij EUDI-wallet specificaties; eenvoudiger dan XML; transformatie naar XML mogelijk |

Voor de definitie van het bewijstype "geboortegegevens" heeft afstemming plaatsgevonden met Duitsland, Belgie en Oostenrijk. Gezamenlijk hebben zij een eerste versie als gestructureerde data gespecificeerd, die in fase 1 gebruikt is en als startpunt dient voor bredere Europese afstemming.

### 8.2 Fase 2 (EUDI-wallet) - gepland

**Afbakening:**

- Kleinschalig: 1 QTSP, 1 gegevensbron, 1 attestatietype (geboortegegevens)
- Minimale afhankelijkheden van andere initiatieven (ARF, EDI-stelsel, beleidskeuzes OOTS-componenten voor EUDI-wallet)
- Gescheiden eDelivery uitwisselingsnetwerk (niet op OOTS-netwerk)
- PID-verstrekkingsproces wordt gesimuleerd (vooraf geladen PID)

**Betrokken partners:**

- Gemeente Rijswijk (en mogelijk andere gemeenten) - gemeentelijke databron
- Enable-U - UBO integratielaag
- Stichting RINIS - intermediaire platform
- Cleverbase - QTSP-platform en EUDI-wallet

**Beslissingen voor beproeving:**

| Onderwerp | Beslissing | Onderbouwing |
|-----------|-----------|--------------|
| **Hergebruik DATA API** | De beproeving hergebruikt de integratielaag (DATA API) die ook in fase 1 (OOTS) gebruikt is | Toont aan dat dezelfde oplossing voor OOTS en EUDI-wallet ingezet kan worden; wordt uitgebreid met signing en encryptie |
| **Hergebruik OOTS-V componenten** | Hergebruik FSC outway, FSC manager, OOTS-V backend integratie, eDelivery access point | Hergebruik voorkomt dat een nieuwe oplossing ontwikkeld moet worden; ook in OOTS use case vindt omvorming naar eDelivery plaats en volgt de OOTS-V het OOTS EDM |
| **Hergebruik OOTS-V netwerk** | Virtueel gescheiden eDelivery instantie op het bestaande netwerk | Voorkomt opzetten van nieuw eDelivery netwerk; strikte scheiding met OOTS-gegevensuitwisseling |
| **PID** | PID-verstrekkingsproces wordt gesimuleerd (vooraf geladen PID) | Beschikbaarheid van een PID is randvoorwaardelijk maar geen onderdeel van de scope |

## 9. Relevantie voor het FBS Berichtenbox project

### 9.1 Vergelijkbaar architectuurpatroon

Het UBO-patroon -- "verschillende gemeentelijke bronnen uniform ontsluiten via een gestandaardiseerde integratielaag" -- is direct vergelijkbaar met hoe het FBS gemeentelijke berichtenmagazijnen ontsluit. De "bron" is in het geval van het FBS geen BRP maar een berichtenmagazijn; het architectuurpatroon is echter hetzelfde.

| UBO concept | FBS equivalent | Toelichting |
|---|---|---|
| Databronnen | Berichtenmagazijnen | De gemeentelijke bronnen die ontsloten worden |
| Middleware | BerichtensessiecacheService + MagazijnResolver | Abstraheert verschillen tussen magazijnen, bepaalt welke magazijnen bevraagd worden |
| Data API | Berichtenmagazijn Ophaal- en Beheer API | Uniform koppelvlak conform Digikoppeling REST API profiel en OpenAPI-specificatie |
| OpenFSC Inway | FSC Inway | Authenticatie, autorisatie en routering van verzoeken |
| Uniformeren | PseudoniemService + aggregatie in sessiecache | Omvorming en bundeling van gegevens uit meerdere bronnen |
| Aanbieden (FSC-catalogus) | FSC service publicatie | Beschikbaar stellen van de service aan afnemers |
| Logging (audit trail) | Logboek Dataverwerkingen (ClickHouse) | Vastleggen van gegevensleveringen voor verantwoording |

### 9.2 Conclusie: het patroon wordt al gevolgd

Het FBS volgt in de huidige C4-architectuur al hetzelfde integratielaag-patroon als UBO:

- **Uniform koppelvlak** via Digikoppeling REST API + FSC voor alle magazijn-communicatie
- **Integratielaag** (sessiecache) die magazijnverschillen abstraheert en berichten uit meerdere magazijnen aggregeert
- **Pseudoniemtransformatie** per magazijn (PP naar EP via BSNk), vergelijkbaar met de persoonsidentificatie-omvorming in UBO
- **mTLS met PKIoverheid-certificaten** en cryptografisch ondertekende FSC-contracten
- **Authenticatie via DigiD/eHerkenning** via de Interactielaag, vergelijkbaar met de authenticatieservice in de Basisinrichting OOTS-V

Er is op dit moment **geen aanleiding om de C4-architectuur aan te passen** op basis van dit document.

### 9.3 Denkrichtingen bij opschaling

De UBO-architectuur biedt wel bruikbare patronen voor wanneer het FBS opschaalt:

- **FSC contract properties voor autorisatiescoping:** UBO definieert properties op contractniveau (AVG-grondslag, verwerkrelatie, kanaal) die bepalen welke bevragingen binnen een contract zijn toegestaan. Dit is een aanvullend patroon naast de AuthZEN-autorisatie die het FBS al kent, en wordt relevant bij het aansluiten van meer magazijnen met verschillende autorisatieregimes.
- **Kanaalonafhankelijke API:** UBO's Data API is bewust kanaalonafhankelijk ontworpen, zodat dezelfde bron via OOTS, EUDI-wallet of andere kanalen ontsloten kan worden. Als het FBS in de toekomst ook via andere kanalen (bijv. EUDI-wallet attestaties van berichtstatus) zou ontsluiten, is dit patroon direct toepasbaar.
- **FSC service discovery:** UBO's publicatie in de FSC-catalogus maakt dat afnemers de service kunnen ontdekken. Bij meer aangesloten magazijnen wordt dit relevant voor het FBS.
- **FSC contracteringsflows:** De gedetailleerde contracteringsflows (10 stappen voor OOTS, 17 stappen voor EUDI-wallet met delegatie) bieden een referentie voor hoe FSC-contracten bij opschaling ingericht kunnen worden.

## 10. Aandachtspunten en risico's

1. **Veel veronderstellingen:** Het document erkent expliciet dat keuzes soms op veronderstellingen zijn gebaseerd omdat EUDI-wallet, ARF en FSC-profielen nog in ontwikkeling zijn. Als een veronderstelling geen stand houdt, kan dat van invloed zijn op de architectuur.
2. **BSN-verwerking bij EUDI-wallet:** Drie opties beschreven, geen definitieve keuze gemaakt. BRPk-wallet bestaat nog niet.
3. **Geen nationale Pub-TSP:** EZ en BZK hebben geen plannen voor een nationale publieke trust service provider, waardoor de QTSP-route de enige optie is voor attribuutverstrekking.
4. **PID nog niet beschikbaar:** In de beproeving wordt het PID-verstrekkingsproces gesimuleerd met een vooraf geladen PID.
5. **Signing en encryptie:** In fase 1 niet geimplementeerd (alleen BASE64 hash), pas in fase 2 via RFC 7515/7516.
6. **FSC signing service:** Optie 3 voor contractbeheer via een centrale webapplicatie is nog in ontwikkeling en niet beschikbaar voor beproeving.
7. **Catalogus authentieke bronnen:** Moet nog door de Europese Commissie worden ontworpen en gerealiseerd.
8. **OOTS EDM uitbreiding nodig:** De huidige versie (1.2) bevat de PID-attributen van de EUDI-wallet nog niet; ook moet een 2e patroon worden toegevoegd voor attribuutverificatie. Deze specificaties zijn opgesteld door DG DIGIT maar hebben nog geen officiele status.
9. **Europees eDelivery netwerk voor QTSP's:** Er wordt verwacht dat een Europees netwerk van QTSP's en authentieke bronnen opgezet wordt, maar dit vereist Europese onboardingafspraken die er nog niet zijn.
