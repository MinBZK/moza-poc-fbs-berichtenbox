# Vergelijking VoRijk (Blauwe Knop) vs. FBS Berichtenbox

## Wat is VoRijk?

Het **Vorderingenoverzicht Rijk** is een initiatief waarbij burgers hun openstaande betalingsverplichtingen bij overheidsorganisaties (Belastingdienst, CJIB, DUO, UWV, etc.) kunnen inzien. Het is gebouwd op het **Blauwe Knop protocol** — een standaard voor directe, burger-geïnitieerde gegevensuitwisseling met overheidsorganisaties.

## Fundamenteel verschil: wie initieert de communicatie?

| Aspect | FBS Berichtenbox | VoRijk (Blauwe Knop) |
|--------|------------------|----------------------|
| **Initiator** | Burger logt in op Interactielaag (webportaal), die namens burger bij magazijnen opvraagt | Burger gebruikt een **mobiele app** die **direct** met elke bronorganisatie communiceert |
| **Intermediair** | Ja — de Interactielaag en Berichten Uitvraag Service als tussenstations | **Nee** — geen intermediair verwerkt de data |
| **Vertrouwensmodel** | Decentraal systeem vertrouwt JWT van Interactielaag | Decentraal systeem verifieert burger's cryptografische bewijs **rechtstreeks** |

## Authenticatie vergeleken

| Aspect | FBS | VoRijk / Blauwe Knop |
|--------|-----|----------------------|
| **Burger-authenticatie** | DigiD/eHerkenning via SAML 2.0 → Interactielaag transformeert naar JWT (RFC 9068) | App genereert **eigen keypair** op device; identiteit wordt bevestigd via **Verifiable Credential** (uitgegeven door App Manager, na DigiD/eHerkenning verificatie) |
| **Protocollen** | OIDC NL GOV v1.0.1, OAuth NL profiel v1.1.0, SAML 2.0 | **OpenID4VP** of **CreateSession** (eigen BK-variant), W3C Verifiable Credentials, JWS/JWE |
| **Sessie-opzet** | Burger → DigiD/eHerkenning → Interactielaag → JWT | Burger-app → challenge/response met nonce + ephemeral keys → session token per organisatie |
| **Cryptografie** | JWT signing, mTLS (PKIoverheid) | ECDSA P-256/EdDSA, ECIES + optioneel **ML-KEM-768** (post-quantum), AES-256-GCM |

## Het kernvraagstuk: hoe vertrouwt het decentrale systeem?

### FBS — Vertrouwen via de keten (transitief)

1. Burger authenticeeert bij DigiD/eHerkenning
2. Interactielaag ontvangt SAML-assertion en maakt een **signed JWT** met claims (PP/KvK, dienstcodes, machtigingen)
3. Berichten Uitvraag Service stuurt JWT + FSC certificate-bound token naar magazijn
4. Magazijn **vertrouwt de JWT-issuer** (valideert signature via JWKS, checkt iss/aud/exp/acr)
5. Magazijn vertrouwt FSC-contract (mTLS + OIN in PKIoverheid-certificaat)

→ **Het vertrouwen is transitief**: magazijn vertrouwt Interactielaag, die DigiD/eHerkenning vertrouwt.

### VoRijk (Blauwe Knop) — Vertrouwen zonder intermediair (direct)

1. Burger heeft een app met een **eigen cryptografisch keypair** (hardware-backed via Secure Enclave/TEE)
2. App Manager (bijv. DigiD) heeft de identiteit geverifieerd en een **Verifiable Credential** uitgegeven, gebonden aan het keypair
3. Burger-app maakt **direct** contact met elke bronorganisatie
4. Organisatie verifieert: (a) het Verifiable Credential van de App Manager, (b) de cryptografische handtekening van de burger
5. **Stelseldocument** (ondertekend door stelselbeheerder) is de root-of-trust — hierin staan geregistreerde organisaties en App Managers

→ **Geen transitief vertrouwen nodig**: de bronorganisatie verifieert de burger zelf.

## Machtigingen (eHerkenning ketenmachtigingen)

| Aspect | FBS | VoRijk |
|--------|-----|--------|
| **Hoe** | Machtigingsclaims als JWT-claims: KvK-nummer, dienstcodes, machtigingstype (ketenmachtiging) | Machtigingsinfo in het **Verifiable Credential** of als aparte claim in de presentatie |
| **Verificatie** | Magazijn vertrouwt dat Interactielaag de machtiging correct heeft overgenomen uit eHerkenning SAML | Bronorganisatie kan het Verifiable Credential **zelf** cryptografisch verifiëren |
| **PEP/PDP** | AuthZEN NL GOV conform: Token Validatie als PEP, MagazijnResolver als PDP | Autorisatie in de servicelaag van de bronorganisatie zelf |

## Privacy en pseudonimisering

| Aspect | FBS | VoRijk |
|--------|-----|--------|
| **Aanpak** | **BSNk**: PP → EP transformatie per magazijn, cryptografisch gescheiden | Burger-app keypair is **per organisatie** niet gekoppeld; end-to-end encryptie |
| **Cross-koppeling** | Cryptografisch uitgesloten (per-magazijn EP) | App kan kiezen wat te delen; encryptie naar organisatie-specifieke sleutel |

## Relevantie voor de FBS-uitdaging

De uitdaging is: *"Kan een decentraal systeem de authenticatie vertrouwen, maar ook de autorisatie die meegestuurd wordt?"*

**VoRijk/Blauwe Knop lost dit op door het probleem te elimineren**: er ís geen intermediair die namens de burger opvraagt. De burger communiceert direct, met cryptografisch bewijs.

**Maar** — dit past niet 1-op-1 op de FBS-usecase, want:

1. **FBS is een webportaal** (Interactielaag), geen mobiele app. De burger heeft geen eigen keypair.
2. **FBS aggregeert berichten** uit meerdere magazijnen in één sessie — dat vereist een centraal ophaalmoment.
3. **eHerkenning ketenmachtigingen** zijn complexer dan persoonlijke identificatie — de machtigingsketen (Persoon → Gemachtigde Organisatie → Vertegenwoordigde Organisatie) moet meereizen.

## Wat FBS kan overnemen

1. **Verifiable Credentials als machtigingsbewijs**: In plaats van machtigingsclaims als "gewone" JWT-claims door te sturen, zou de Interactielaag een **cryptografisch ondertekend machtigingsbewijs** kunnen meesturen dat het decentrale magazijn zelfstandig kan verifiëren — vergelijkbaar met hoe BK Connect Verifiable Credentials gebruikt.

2. **Stelseldocument-concept**: FBS heeft al FSC-contracts en het OIN-stelsel — dit is functioneel vergelijkbaar met het stelseldocument van VoRijk. De trust-basis is er al.

3. **OpenID4VP als aanvulling**: Naast het huidige JWT-bearer model zou OpenID4VP gebruikt kunnen worden om machtigingsbewijzen te presenteren aan decentrale systemen, waardoor die systemen het bewijs zelf kunnen verifiëren in plaats van de JWT-issuer te moeten vertrouwen.

## Samenvatting

|  | FBS | VoRijk (Blauwe Knop) |
|--|-----|----------------------|
| **Trust model** | Transitief (keten) | Direct (burger ↔ organisatie) |
| **Auth mechanisme** | SAML → JWT → FSC | Verifiable Credentials + device keypair |
| **Machtigingen** | JWT claims | VC-gebonden claims |
| **Intermediair** | Interactielaag + Berichten Uitvraag | Geen |
| **Privacy** | BSNk pseudonimisering | End-to-end encryptie |
| **Geschikt voor** | Webportaal, aggregatie | Mobiele app, directe toegang |

De kern: VoRijk kiest voor **"de burger bewijst zelf wie hij is"** terwijl FBS kiest voor **"de Interactielaag staat garant voor de burger"**. Beide zijn valide, maar bij FBS moet het decentrale systeem de Interactielaag vertrouwen — en dat vertrouwen is geborgd via FSC-contracts, mTLS, PKIoverheid-certificaten en het OIN-stelsel.

## Bronnen

- [Blauwe Knop Connect specificatie](https://vorijk.nl/standaard/connect/draft-bk-connect-00.html)
- [VoRijk Introductie](https://vorijk.nl/docs/introductie/)
- [VoRijk Standaarden](https://vorijk.nl/docs/architectuur/standaarden/)
- [VoRijk Als bronorganisatie](https://vorijk.nl/docs/aan-de-slag/bronorganisatie/)
- [Blauwe Knop Standaard](https://vorijk.nl/standaard/)
- [Proeftuin Blauwe Knop](https://blauweknop.app/docs/protocol/)

---

## Appendix: OpenID4VP als toekomstig authenticatiekanaal

### Wat is OpenID4VP?

**OpenID for Verifiable Presentations** ([OpenID4VP](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html)) is het protocol waarmee een Relying Party (verifier) een burger vraagt om een **Verifiable Presentation** (VP) vanuit een wallet te tonen. De burger bepaalt zelf welke gegevens worden gedeeld — alleen de claims die de dienst daadwerkelijk nodig heeft (selective disclosure via SD-JWT VC).

OpenID4VP is onderdeel van het **EUDI wallet ecosysteem** dat voortkomt uit de herziene eIDAS-verordening (mei 2024). Elke EU-lidstaat moet eind 2026 minstens één gecertificeerde wallet beschikbaar stellen. In Nederland is dat de [NL-Wallet (EDI-wallet)](https://github.com/MinBZK/nl-wallet), ontwikkeld als open source.

### Waar leeft de wallet?

| Component | Locatie | Rol |
|-----------|---------|-----|
| **Wallet-app** | Mobiel apparaat van de burger (Android/iOS) | Beheert credentials, toont presentaties, burger heeft regie |
| **Wallet Provider backend** | Beheerd door overheid (RvIG/BZK) | Geeft wallet-app uit, faciliteert PID-uitgifte (via OpenID4VCI), wallet attestation |
| **Issuers (bronhouders)** | RvIG (PID/identiteit), KvK (organisatie), DUO (diploma), RDW (rijbewijs) | Geven Verifiable Credentials uit die in de wallet worden opgeslagen |

De burger draagt de wallet dus letterlijk bij zich. Credentials worden lokaal opgeslagen en zijn cryptografisch gebonden aan het device (hardware-backed keys via Secure Enclave/TEE) — vergelijkbaar met het keypair-model van VoRijk/Blauwe Knop.

### Wat kan OpenID4VP vervangen of aanvullen in FBS?

| Huidig FBS-component | Met OpenID4VP | Relatie |
|----------------------|---------------|---------|
| **DigiD (SAML 2.0)** voor burgers | Wallet presenteert PID-credential (pseudoniem, naam) | **Aanvullen** — DigiD blijft verplicht kanaal; wallet wordt derde optie |
| **eHerkenning (SAML 2.0)** voor bedrijven | Wallet presenteert organisatie-credential (KvK, machtiging) | **Aanvullen** — eHerkenning-ketenmachtigingen passen nog niet goed in het VC-model |
| **BSNk pseudoniemtransformatie** | Wallet kan pseudonieme PID presenteren via selective disclosure | **Aanvullen** — BSNk blijft nodig voor per-magazijn ontkoppeling (PP→EP) |
| **Interactielaag (OIDC Provider)** | Wordt Relying Party/Verifier die OpenID4VP-requests initieert | **Uitbreiden** — de Interactielaag krijgt er een authenticatiekanaal bij |

**Kernpunt:** OpenID4VP vervangt DigiD en eHerkenning niet, maar wordt een **derde inlogkanaal** ernaast. De eIDAS2-verordening eist dat publieke organisaties de EUDI wallet accepteren zodra deze beschikbaar is.

### Hoe zou de flow eruitzien?

```
Burger opent MijnOverheid Zakelijk (portaal)
  │
  ├─ [bestaand] Redirect naar DigiD (SAML) ─────────────────┐
  ├─ [bestaand] Redirect naar eHerkenning (SAML) ───────────┤
  └─ [nieuw]    OpenID4VP-request naar wallet ───────┐       │
                                                     │       │
        Burger's telefoon toont:                     │       │
        "MijnOverheid Zakelijk vraagt:               │       │
         - Uw pseudoniem (PID)"                      │       │
                                                     │       │
        Burger accordeert → VP (SD-JWT) ─────────────┤       │
                                                     ▼       ▼
                                            Interactielaag valideert
                                            (VP-signature of SAML-assertion)
                                                     │
                                                     ▼
                                            Signed JWT (RFC 9068)
                                            met PP/KvK + claims
                                                     │
                                                     ▼
                                            Zelfde flow als nu:
                                            BSNk → Berichten Uitvraag → Magazijn
```

De Interactielaag abstraheert het authenticatiekanaal: of de burger nu via DigiD, eHerkenning of wallet binnenkomt, het resultaat is hetzelfde signed JWT richting de rest van het FBS-stelsel.

### Vergelijking van de drie authenticatiekanalen

| Aspect | DigiD/eHerkenning (huidig) | VoRijk / Blauwe Knop | OpenID4VP / EUDI Wallet |
|--------|---------------------------|----------------------|-------------------------|
| **Initiatief** | Redirect vanuit webportaal | Burger-app direct naar organisatie | Wallet-presentatie via webportaal of app |
| **Intermediair** | Interactielaag (SAML→JWT) | Geen | Interactielaag (VP→JWT) |
| **Vertrouwensmodel** | Transitief (via JWT-issuer) | Direct (burger bewijst zelf) | Hybride: wallet bewijst, Interactielaag vertaalt |
| **Credential-formaat** | SAML-assertion | W3C VC + eigen BK-protocol | SD-JWT VC (selective disclosure) |
| **Dataminimalisatie** | Beperkt (vaste claimsets) | Burger kiest wat te delen | Burger kiest wat te delen (selectief) |
| **Machtigingen** | JWT-claims (eHerkenning-keten) | VC-gebonden claims | Nog onvoldoende uitgewerkt voor ketenmachtigingen |
| **Standaard-status** | Vastgesteld en verplicht | Proeftuin / concept | Richting verplicht (eIDAS2), specificatie in ontwikkeling |
| **Beschikbaarheid** | Nu | Pilot | Verwacht eind 2026/2027 |

### Aandachtspunten voor FBS

1. **Tijdlijn** — De NL-Wallet en OpenID4VP-specificaties zijn nog niet definitief vastgesteld. De standaard gaat richting verplicht, maar is nog in ontwikkeling. Implementatie in de PoC is prematuur.

2. **BSNk-integratie** — Het is nog onduidelijk hoe BSNk-pseudoniemen exact in wallet-credentials landen. De PID-credential bevat een identifier, maar de PP→EP transformatie per magazijn blijft nodig om cross-magazine koppeling te voorkomen.

3. **eHerkenning-ketenmachtigingen** — De complexe machtigingsstructuur (Persoon → Gemachtigde Organisatie → Vertegenwoordigde Organisatie) past niet eenvoudig in het huidige VC-model. Dit is een open vraagstuk in de EUDI-architectuur.

4. **Dual/triple-channel** — De Interactielaag moet DigiD, eHerkenning én wallet ondersteunen. De huidige abstractie (authenticatiemiddel in, JWT uit) is hier goed op voorbereid, mits de Interactielaag als OpenID4VP Relying Party kan optreden.

5. **Overlap met VoRijk** — VoRijk's Blauwe Knop en de EUDI wallet gebruiken beide Verifiable Credentials en device-gebonden keypairs. Op termijn zou de EUDI wallet het Blauwe Knop-protocol kunnen absorberen, waardoor één wallet beide usecases dekt.

### Aanbeveling

Voor de huidige PoC: **nog niet implementeren**, maar wel de architectuur voorbereiden:

- De Interactielaag zo ontwerpen dat meerdere authenticatiekanalen naast elkaar werken (dit is al het geval met de SAML→JWT abstractie)
- In het C4-model de EUDI wallet als toekomstige actor opnemen
- De NL-Wallet referentie-implementatie volgen voor concrete API-voorbeelden zodra de specificaties stabiliseren

### Bronnen
- [OpenID4VP specificatie](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html)
- [EUDI Architecture and Reference Framework](https://eu-digital-identity-wallet.github.io/eudi-doc-architecture-and-reference-framework/latest/architecture-and-reference-framework-main/)
- [NL-Wallet (EDI-wallet) open source repository](https://github.com/MinBZK/nl-wallet)
- [EDI-wallet informatie (edi.pleio.nl)](https://edi.pleio.nl)
- [SD-JWT VC specificatie (IETF draft)](https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/)
