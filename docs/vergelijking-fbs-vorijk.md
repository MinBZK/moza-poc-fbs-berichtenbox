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
