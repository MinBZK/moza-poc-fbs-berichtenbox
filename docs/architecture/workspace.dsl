workspace "Federatief Berichtenstelsel" "Doel-architectuur van het Federatief Berichtenstelsel (FBS) - BBO-opdracht Logius/BZK" {
    !docs workspace-docs

    properties {
        "nfr.betrouwbaarheid.berichtverlies" "RPO=0: geen berichtverlies; bij verstoring weigert de circuit breaker schrijfoperaties totdat duurzame persistentie is hersteld"
        "security.fsc" "Alle FSC-verbindingen vereisen mTLS met PKIoverheid-certificaten"
        "nfr.beschikbaarheid.ratelimiting" "Rate limiting en throttling op Inway-niveau per FSC-verbinding ter bescherming van magazijnen en BSNk tegen overbelasting"
        "security.vertrouwensmodel" "Twee beveiligingslagen: (1) FSC levert organisatievertrouwen via mTLS + cryptografisch ondertekende contracten (PeerID, certificate-bound tokens); (2) het ondertekende versleutelde pseudoniem (EP) levert persoonsidentificatie per magazijn. Het EP bevat een doelmagazijn-binding (versleuteld met de publieke sleutel van het magazijn) en een BSNk-handtekening (verifieerbaar met de U-sleutel). De Interactielaag fungeert als token-issuer: ontvangt SAML-assertions van DigiD/eHerkenning en geeft JWT bearer tokens uit met PP (burgers) of per-magazijn pseudoniemen en machtigingsclaims (zakelijke gebruikers). Token Validatie verifieert de JWT-handtekening, issuer, audience en expiration conform het OIDC NL GOV profiel."
        "security.betrouwbaarheidsniveaus" "DigiD: minimaal Substantieel (app of sms-controle). eHerkenning: minimaal EH3 (standaard niveau). Betrouwbaarheidsniveau wordt meegegeven als acr-claim in het JWT conform OIDC NL GOV profiel."
        "security.jwt-claims" "Het JWT bevat minimaal: iss (Interactielaag), sub (gebruikersidentifier), aud (Berichten Uitvraag Systeem), exp, iat, jti, acr (betrouwbaarheidsniveau). Voor burgers: PP als sub-claim. Voor zakelijke gebruikers: per-magazijn pseudoniemen en machtigingsclaims (KvK-nummer, dienstcodes) als aanvullende claims."
        "security.bsnk-dienstregistratie" "Elk berichtenmagazijn is als aparte dienst geregistreerd bij BSNk, zodat de PP-naar-EP-transformatie een uniek EP per magazijn oplevert. Dit voorkomt cross-magazijn koppelbaarheid van pseudoniemen."
        "architectuur.fsc-abstractie" "FSC-infrastructuur (Inway, Outway, Manager, Directory) is bewust niet als aparte containers gemodelleerd. FSC wordt behandeld als cross-cutting transportlaag, zichtbaar in relatiebeschrijvingen ('Digikoppeling REST API via FSC'). Zie fsc-core v1.1.2 voor de componentarchitectuur."
        "architectuur.traceerbaarheid" "Cross-organisatie verwerkingen zijn traceerbaar via W3C Trace Context (traceparent header). FSC-verkeer propageert trace-context over organisatiegrenzen conform de LDV-standaard. De Fsc-Transaction-Id header wordt aanvullend gebruikt voor FSC-specifieke transactielogging."
        "architectuur.ldv-logging" "Alle componenten die persoonsgegevens verwerken loggen naar het LDV Logboek via OpenTelemetry (OTLP). Dit betreft: magazijnOphaalBeheerApi, magazijnOpslagService, validatieApi, sessiecacheService, magazijnResolver, pseudoniemService, tokenValidatie, autorisatieService, publicatieStream, aanmeldService, uitvraagBerichtenlijst, uitvraagOphaalService, uitvraagBeheerService. LDV-relaties zijn niet in de views gemodelleerd om de leesbaarheid te bewaren."
    }

    model {
        properties {
            "structurizr.groupSeparator" "/"
        }

        burger = person "Burger" "Ontvangt berichten en notificaties van overheidsorganisaties"
        ondernemer = person "Ondernemer / Zakelijke gebruiker" "Ontvangt berichten en notificaties van overheidsorganisaties"
        medewerkerA = person "Medewerker A" "Verstuurt berichten namens Organisatie A"
        medewerkerB = person "Medewerker B" "Verstuurt berichten namens Organisatie B"

        profielService = softwareSystem "Profiel Service" "Contactgegevens, communicatievoorkeuren en toestemmingsbeheer (MoZa)" "Extern Systeem"
        notificatieService = softwareSystem "Notificatie Service" "Multi-channel notificatiebezorging via e-mail, SMS en app (MoZa)" "Extern Systeem"
        interactielaag = softwareSystem "Interactielaag" "Portaal of app waarmee burgers en ondernemers communiceren met het berichtenstelsel" "Extern Systeem"
        eHerkenning = softwareSystem "eHerkenning" "Authenticatie en machtigingen voor zakelijke gebruikers — stelsel met machtigingenvoorziening en dienstencatalogus" "Extern Systeem"
        digiD = softwareSystem "DigiD" "Authenticatie voor burgers" "Extern Systeem"

        orgA = softwareSystem "Organisatie A" "Deelnemende overheidsorganisatie - host zelf een berichtenmagazijn" "Deelnemer"
        orgB = softwareSystem "Organisatie B" "Deelnemende overheidsorganisatie - neemt een berichtenmagazijn af bij BBO" "Deelnemer"

        group "Federatief Berichtenstelsel (FBS)" {

            decentraalMagazijn = softwareSystem "Berichtenmagazijn (per deelnemende organisatie)" "Berichten opslaan, ophalen en beheren (incl. berichtstatus)" "Magazijn" {
                properties {
                    "deployment.model" "Elke deelnemende organisatie host een eigen instantie, of neemt er een af bij BBO"
                }
                magazijnOphaalBeheerApi = container "Berichtenmagazijn Ophaal- en Beheer API" "REST API voor het ophalen van berichten en bijlagen, en het beheren van berichtstatus per gebruiker" "Quarkus / Kotlin" "Magazijn Service"
                magazijnAanleverApi = container "Berichtenmagazijn Aanlever API" "REST API voor het aanleveren van berichten door organisaties" "Quarkus / Kotlin" "Magazijn Service" {
                    magazijnAanleverResource = component "Aanlever REST API" "REST endpoints voor het aanleveren van berichten en bijlagen" "JAX-RS Resource" "Magazijn Component"
                    magazijnCircuitBreaker = component "CircuitBreaker" "Weigert schrijfoperaties wanneer RPO=0 niet gegarandeerd kan worden (dataopslag onbeschikbaar)" "MicroProfile Fault Tolerance" "Magazijn Component"
                    magazijnOpslagService = component "Bericht Opslag Service" "Berichtlevenscyclus: valideren, opslaan en aanmelden" "CDI Bean" "Magazijn Component"

                    magazijnAanleverResource -> magazijnCircuitBreaker "Schrijfoperaties via"
                    magazijnCircuitBreaker -> magazijnOpslagService "Delegeert naar (als circuit closed)"
                }
                magazijnDatastore = container "Dataopslag" "Berichtstatus, inhoud en bijlagen (0 berichtverlies)" "Naar keuze implementatie" "Magazijn Database"

                berichtValidatie = container "Bericht Validatie Service" "Valideert berichten op technische eisen en controleert toestemming via Profiel Service" "Quarkus / Kotlin" "Magazijn Service" {
                    validatieApi = component "Validatie API" "REST endpoint voor berichtvalidatie" "JAX-RS Resource" "Magazijn Component"
                    validatieTechnisch = component "Technische Validatie" "Valideert PDF-type, grootte en aantal bijlagen" "CDI Bean" "Magazijn Component"
                    validatieToestemming = component "Toestemming Controle" "Controleert of de ontvanger toestemming gegeven heeft voor digitale communicatie" "CDI Bean" "Magazijn Component"

                    validatieApi -> validatieTechnisch "Valideert technische eisen"
                    validatieApi -> validatieToestemming "Controleert toestemming"
                }
                publicatieStream = container "Publicatie Stream" "Wacht met aanmelden van een bericht tot de publicatiedatum is verstreken (outbox-patroon voor gegarandeerde bezorging)" "Quarkus / Kotlin" "Magazijn Service" {
                    properties {
                        "cloudevents.profiel" "NL GOV profiel CloudEvents v1.1"
                        "cloudevents.source" "urn:nld:oin:{organisatie-oin}:systeem:fbs-magazijn"
                        "cloudevents.type" "nl.rijksoverheid.fbs.bericht.gepubliceerd"
                    }
                }

                magazijnOphaalBeheerApi -> magazijnDatastore "Leest berichten en bijlagen; schrijft berichtstatus per gebruiker"
                magazijnOpslagService -> magazijnDatastore "Schrijft berichten en bijlagen"
                magazijnOpslagService -> validatieApi "Stuurt bericht ter validatie"
                magazijnOpslagService -> publicatieStream "Stuurt gevalideerd bericht door"
                publicatieStream -> magazijnDatastore "Leest berichten met status 'te publiceren' en werkt status bij na succesvolle aanmelding"

                autorisatieService = container "Autorisatie Service" "Toetst ophaal- en beheerverzoeken aan het autorisatiebeleid van de deelnemende organisatie" "Quarkus / Kotlin" "Magazijn Service"

                magazijnOphaalBeheerApi -> autorisatieService "Toetst autorisatie per verzoek"
            }

            group "Centraal gehoste services" {

                berichtenUitvraagSysteem = softwareSystem "Berichten Uitvraag Systeem" "Centraal systeem voor het uitvragen, beheren en aanleveren van berichten in het Federatief Berichtenstelsel" "FBS Dienst" {

                    sessiecacheApp = container "Berichtensessiecache" "Aggregeert berichten uit alle aangesloten magazijnen voor een burger of zakelijke gebruiker" "Quarkus / Kotlin" "Service" {
                        sessiecacheResource = component "Berichtensessiecache API" "REST endpoints voor berichtensessiecache en zoeken" "JAX-RS Resource"
                        magazijnResolver = component "MagazijnResolver" "Bepaalt op basis van dienstvoorkeuren (Profiel Service) en machtigingen welke magazijnen bevraagd worden" "CDI Bean"
                        pseudoniemService = component "PseudoniemService" "Transformeert PP naar EP per magazijn via BSNk" "CDI Bean"
                        sessiecacheService = component "BerichtensessiecacheService" "Aggregeert berichten uit de door MagazijnResolver bepaalde magazijnen en cachet de resultaten per pseudoniem" "CDI Bean"
                        sessiecacheCache = component "Cache" "Sessiecache voor berichten met full-text zoekindex" "Redis / RediSearch"
                        sessiecacheMagazijnClient = component "MagazijnClient" "REST client naar berichtenmagazijnen" "REST Client"
                        sessiecacheResource -> sessiecacheService "Gebruikt"
                        sessiecacheService -> magazijnResolver "Vraagt op welke magazijnen bevraagd moeten worden"
                        sessiecacheService -> pseudoniemService "Transformeert PP naar EP per magazijn"
                        sessiecacheService -> sessiecacheCache "Leest/schrijft cache"
                        sessiecacheService -> sessiecacheMagazijnClient "Haalt berichten op"
                    }

                    uitvraagApi = container "Berichten Uitvraag Service" "Service voor burgers en ondernemers - berichtenbox inzien en berichten beheren" "Quarkus / Kotlin" "Service" {
                        uitvraagResource = component "Berichten Uitvraag API" "REST endpoints voor berichtenlijst, ophalen, beheer en verwijderen" "JAX-RS Resource"
                        tokenValidatie = component "Token Validatie" "Valideert JWT bearer tokens en stelt de gebruikersidentiteit vast" "CDI Bean"
                        uitvraagBerichtenlijst = component "Berichtenlijst Service" "Levert per map een berichtenlijst" "CDI Bean"
                        uitvraagOphaalService = component "Bericht Ophaal Service" "Haalt berichten en bijlagen op uit cache en berichtenmagazijn" "CDI Bean"
                        uitvraagBeheerService = component "Bericht Beheer Service" "Verplaatst berichten naar andere map, verwijdert berichten en beheert berichtstatus (gelezen, etc.)" "CDI Bean"

                        uitvraagResource -> tokenValidatie "Valideert identiteit aanroeper"
                        uitvraagResource -> uitvraagBerichtenlijst "Berichtenlijst per map"
                        uitvraagResource -> uitvraagOphaalService "Berichten en bijlagen ophalen"
                        uitvraagResource -> uitvraagBeheerService "Berichtstatus en mappenbeheer"
                    }

                    bsnkTransformatie = container "BSNk Transformatie" "Transformeert PP naar EP per berichtenmagazijn — vereist sleutelmateriaal per deelnemer" "BSNk container (Logius)" "Extern Geleverd"

                    aanmeldService = container "Aanmeld Service" "Werkt de cache bij voor nieuwe berichten verzonden tijdens de sessie van de ontvanger" "Quarkus / Kotlin" "Service"

                    pseudoniemService -> bsnkTransformatie "Transformeert PP naar EP per magazijn" "BSNk API (lokaal)"
                    aanmeldService -> sessiecacheApp "Werkt cache bij" "REST API (intern)"
                    uitvraagOphaalService -> sessiecacheResource "Haalt berichten op" "REST API (intern)"
                    uitvraagBerichtenlijst -> sessiecacheResource "Haalt berichtenlijst op" "REST API (intern)"
                    uitvraagBeheerService -> sessiecacheResource "Werkt berichtstatus bij in cache" "REST API (intern)"
                }

                ldvLogboek = softwareSystem "LDV Logboek" "Logboek Dataverwerkingen - logging van dataverwerkingen conform LDV-standaard" "Infrastructuur"
            }
        }

        berichtenUitvraagSysteem -> decentraalMagazijn "Haalt berichten, bijlagen en berichtstatus op; beheert berichtstatus per gebruiker" "Digikoppeling REST API via FSC"

        medewerkerA -> orgA "Verstuurt berichten via"
        medewerkerB -> orgB "Verstuurt berichten via"

        burger -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, verwijdert" "HTTPS (browser/app)"
        burger -> digiD "Logt in" "HTTPS (browser redirect)"
        ondernemer -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, verwijdert" "HTTPS (browser/app)"
        ondernemer -> eHerkenning "Logt in" "HTTPS (browser redirect)"

        notificatieService -> burger "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"
        notificatieService -> ondernemer "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"

        interactielaag -> uitvraagResource "Berichtenbox API-aanroepen namens burger of ondernemer" "Digikoppeling REST API via FSC (JWT bearer token)"
        interactielaag -> profielService "Toestemming bekijken en wijzigen" "Digikoppeling REST API via FSC"
        interactielaag -> digiD "Authenticatie burgers" "SAML 2.0"
        interactielaag -> eHerkenning "Authenticatie zakelijke gebruikers" "SAML 2.0"

        uitvraagOphaalService -> magazijnOphaalBeheerApi "Haalt bijlagen op" "Digikoppeling REST API via FSC"
        uitvraagBeheerService -> magazijnOphaalBeheerApi "Beheert berichtstatus" "Digikoppeling REST API via FSC"

        publicatieStream -> aanmeldService "Meldt nieuw bericht aan" "Digikoppeling REST API via FSC"
        publicatieStream -> notificatieService "Stuurt bericht-events door" "CloudEvents webhook" "Async"

        notificatieService -> profielService "Haalt contactgegevens en voorkeuren op" "Digikoppeling REST API via FSC"

        orgA -> magazijnAanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"
        orgB -> magazijnAanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"

        validatieToestemming -> profielService "Controleert of de ontvanger toestemming gegeven heeft" "Digikoppeling REST API via FSC"

        magazijnResolver -> profielService "Haalt dienstvoorkeuren op om te bepalen welke magazijnen bevraagd worden" "Digikoppeling REST API via FSC"
        sessiecacheMagazijnClient -> magazijnOphaalBeheerApi "Haalt berichten op" "Digikoppeling REST API via FSC"

    }

    views {
        properties {
            "generatr.site.externalTag" "Extern Systeem"
            "generatr.site.exporter" "structurizr"
            "generatr.site.nestGroups" "false"
        }

        systemLandscape "SystemLandscape" "Het Federatief Berichtenstelsel - een stelsel van federatief gekoppelde diensten" {
            include *
            exclude "decentraalMagazijn -> berichtenUitvraagSysteem"
            autoLayout
        }

        systemContext decentraalMagazijn "Berichtenmagazijn" "Context van het Berichtenmagazijn" {
            include *?
            autoLayout
        }

        systemContext berichtenUitvraagSysteem "BerichtenUitvraagSysteem" "Context van het Berichten Uitvraag Systeem" {
            include *?
            autoLayout
        }

        container decentraalMagazijn "BerichtenmagazijnContainers" "Containers binnen het Berichtenmagazijn" {
            include *?
            autoLayout
        }

        container berichtenUitvraagSysteem "BerichtenUitvraagSysteemContainers" "Containers binnen het Berichten Uitvraag Systeem" {
            include *?
            autoLayout
        }

        component uitvraagApi "BerichtenUitvraagServiceComponenten" "Componenten binnen de Berichten Uitvraag Service" {
            include *?
            autoLayout
        }

        component sessiecacheApp "BerichtensessiecacheComponenten" "Componenten binnen de Berichtensessiecache" {
            include *?
            autoLayout
        }

        component magazijnAanleverApi "AanleverAPIComponenten" "Componenten binnen de Berichtenmagazijn Aanlever API" {
            include *?
            autoLayout
        }

        component berichtValidatie "BerichtValidatieComponenten" "Componenten binnen de Bericht Validatie Service" {
            include *?
            autoLayout
        }

        styles {
            element "Element" {
                background #1168BD
                color #ffffff
                stroke #0B4884
                fontSize 22
            }
            element "Software System" {
                background #1168BD
                color #ffffff
            }
            element "FBS Dienst" {
                background #438DD5
                color #ffffff
                stroke #2E6295
            }
            element "Deelnemer" {
                background #8C8C8C
                color #ffffff
                border dashed
            }
            element "Magazijn" {
                background #2D8A4E
                color #ffffff
                stroke #1E6B38
            }
            element "Magazijn Service" {
                shape RoundedBox
                background #3BA55D
                color #ffffff
                stroke #2D8A4E
            }
            element "Magazijn Database" {
                shape Cylinder
                background #B3B3B3
                color #000000
                stroke #2D8A4E
            }
            element "Magazijn Component" {
                background #3BA55D
                color #ffffff
                stroke #2D8A4E
            }
            element "Infrastructuur" {
                background #999999
                color #ffffff
                stroke #6B6B6B
                shape Pipe
            }
            element "Extern Systeem" {
                background #666666
                color #ffffff
                border dashed
            }
            element "Person" {
                shape Person
                background #08427B
                color #ffffff
                stroke #052E56
            }
            element "Service" {
                shape RoundedBox
                background #438DD5
                color #ffffff
                stroke #2E6295
            }
            element "Extern Geleverd" {
                shape RoundedBox
                background #B3B3B3
                color #000000
                border dashed
            }
            element "Database" {
                shape Cylinder
                background #B3B3B3
                color #000000
            }
            element "Queue" {
                shape Pipe
                background #B3B3B3
                color #000000
            }
            relationship "Relationship" {
                color #707070
                thickness 2
            }
            relationship "Async" {
                style dashed
                color #707070
            }
        }
    }

}
