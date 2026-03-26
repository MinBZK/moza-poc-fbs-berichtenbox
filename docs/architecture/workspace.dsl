workspace "Federatief Berichtenstelsel" "Doel-architectuur van het Federatief Berichtenstelsel (FBS) - BBO-opdracht Logius/BZK" {
    !docs workspace-docs

    properties {
        "nfr.betrouwbaarheid.berichtverlies" "RPO=0: geen berichtverlies; bij verstoring weigert de circuit breaker schrijfoperaties totdat duurzame persistentie is hersteld"
        "security.fsc" "Alle FSC-verbindingen vereisen mTLS met PKIoverheid-certificaten"
        "nfr.beschikbaarheid.ratelimiting" "Rate limiting en throttling op FSC-contractniveau ter bescherming van magazijnen en BSNk tegen overbelasting"
        "security.vertrouwensmodel" "Twee beveiligingslagen: (1) FSC levert organisatievertrouwen via mTLS + cryptografisch ondertekende contracten (PeerID, certificate-bound tokens); (2) het ondertekende versleutelde pseudoniem (EP) levert persoonsidentificatie per magazijn. Het EP bevat een doelmagazijn-binding (versleuteld met de publieke sleutel van het magazijn) en een BSNk-handtekening (verifieerbaar met de U-sleutel). De Interactielaag fungeert als token-issuer: ontvangt SAML-assertions van DigiD/eHerkenning en geeft JWT bearer tokens uit met PP (burgers) of per-magazijn pseudoniemen en machtigingsclaims (zakelijke gebruikers). Token Validatie verifieert de JWT-handtekening, issuer, audience en expiration conform het OIDC NL GOV profiel."
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
                magazijnOphaalBeheerApi = container "Berichtenmagazijn Ophaal- en Beheer API" "REST API voor het ophalen van berichten en bijlagen, en het vastleggen van berichtstatus (gelezen, map, verwijderd) per gebruiker" "Quarkus / Kotlin" "Magazijn Service"
                magazijnAanleverApi = container "Berichtenmagazijn Aanlever API" "REST API voor het aanleveren van berichten door organisaties" "Quarkus / Kotlin" "Magazijn Service" {
                    magazijnAanleverResource = component "Aanlever REST API" "REST endpoints voor het aanleveren van berichten en bijlagen" "JAX-RS Resource" "Magazijn Component"
                    magazijnCircuitBreaker = component "CircuitBreaker" "Weigert schrijfoperaties wanneer RPO=0 niet gegarandeerd kan worden (dataopslag onbeschikbaar)" "MicroProfile Fault Tolerance" "Magazijn Component"
                    magazijnOpslagService = component "Bericht Opslag Service" "Berichtlevenscyclus: valideren, opslaan en aanmelden" "CDI Bean" "Magazijn Component"

                    magazijnAanleverResource -> magazijnCircuitBreaker "Schrijfoperaties via"
                    magazijnCircuitBreaker -> magazijnOpslagService "Delegeert naar (als circuit closed)"
                }
                magazijnDatastore = container "Dataopslag" "Berichtmetadata, inhoud en bijlagen (0 berichtverlies)" "Naar keuze implementatie" "Magazijn Database"

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

                magazijnOphaalBeheerApi -> magazijnDatastore "Leest berichten en bijlagen; schrijft berichtstatus (gelezen, map, verwijderd) per gebruiker"
                magazijnOpslagService -> magazijnDatastore "Schrijft berichten en bijlagen"
                magazijnOpslagService -> validatieApi "Stuurt bericht ter validatie"
                magazijnOpslagService -> publicatieStream "Stuurt gevalideerd bericht door"
                publicatieStream -> magazijnDatastore "Leest berichten met status 'te publiceren' en werkt status bij na succesvolle aanmelding"

                autorisatieService = container "Autorisatie Service" "Toetst verzoeken aan het autorisatiebeleid van de deelnemende organisatie — per tenant configureerbaar bij centraal gehoste magazijnen" "Quarkus / Kotlin" "Magazijn Service"

                magazijnOphaalBeheerApi -> autorisatieService "Toetst autorisatie per verzoek"
            }

            group "Centraal gehoste services" {

                berichtenUitvraagSysteem = softwareSystem "Berichten Uitvraag Systeem" "Centraal systeem voor het uitvragen, beheren en aanleveren van berichten in het Federatief Berichtenstelsel" "FBS Dienst" {

                    sessiecacheApp = container "Berichtensessiecache" "Aggregeert berichten uit alle aangesloten magazijnen voor een burger of zakelijke gebruiker" "Quarkus / Kotlin" "Service" {
                        sessiecacheResource = component "Berichtensessiecache API" "REST endpoints voor berichtensessiecache en zoeken" "JAX-RS Resource"
                        magazijnResolver = component "MagazijnResolver" "Bepaalt op basis van dienstvoorkeuren (Profiel Service) en machtigingen welke magazijnen bevraagd worden" "CDI Bean"
                        pseudoniemService = component "PseudoniemService" "Transformeert polymorfe pseudoniemen (PP) naar versleutelde pseudoniemen (EP) per magazijn via BSNk (alleen burgers; zakelijke pseudoniemen komen direct uit het JWT via eHerkenning-dienstbemiddeling)" "CDI Bean"
                        sessiecacheService = component "BerichtensessiecacheService" "Aggregeert berichten uit de door MagazijnResolver bepaalde magazijnen en cachet de resultaten per pseudoniem (sessie-scope, levensduur volgt JWT)" "CDI Bean"
                        sessiecacheCache = component "Cache" "Sessiecache voor berichten met full-text zoekindex — levensduur gekoppeld aan JWT-sessie, sleutel is het pseudoniem" "Redis / RediSearch"
                        sessiecacheMagazijnClient = component "MagazijnClient" "REST client naar berichtenmagazijnen" "REST Client"
                        sessiecacheResource -> sessiecacheService "Gebruikt"
                        sessiecacheService -> magazijnResolver "Vraagt op welke magazijnen bevraagd moeten worden"
                        sessiecacheService -> pseudoniemService "Transformeert PP naar ondertekend EP per magazijn"
                        sessiecacheService -> sessiecacheCache "Leest/schrijft cache"
                        sessiecacheService -> sessiecacheMagazijnClient "Haalt berichten op"
                    }

                    uitvraagApi = container "Berichten Uitvraag Service" "Service voor burgers en ondernemers - berichtenbox inzien en berichten beheren" "Quarkus / Kotlin" "Service" {
                        uitvraagResource = component "Berichten Uitvraag API" "REST endpoints voor berichtenbox, mappen en berichten" "JAX-RS Resource"
                        tokenValidatie = component "Token Validatie" "Valideert JWT bearer tokens uitgegeven door de Interactielaag en stelt de gebruikersidentiteit vast" "CDI Bean"
                        uitvraagBerichtenlijst = component "Berichtenlijst Service" "Levert per map een berichtenlijst, verplaatst berichten naar andere map en verwijdert berichten" "CDI Bean"
                        uitvraagOphaalService = component "Bericht Ophaal Service" "Haal berichten en bijlagen op; berichten uit cache, bijlagen en berichtstatus uit berichtenmagazijn" "CDI Bean"

                        uitvraagResource -> tokenValidatie "Valideert identiteit aanroeper"
                        uitvraagResource -> uitvraagBerichtenlijst "Berichtenlijst en mappenbeheer"
                        uitvraagResource -> uitvraagOphaalService "Berichten en bijlagen ophalen"
                    }

                    bsnkTransformatie = container "BSNk Transformatie" "Transformeert polymorfe pseudoniemen (PP) naar versleutelde pseudoniemen (EP, formeel SignedEncryptedPseudonym) per berichtenmagazijn — vereist sleutelmateriaal per deelnemer; handtekening verifieerbaar met BSNk U-sleutel" "BSNk container (Logius)" "Extern Geleverd"

                    aanmeldService = container "Aanmeld Service" "Werkt de cache bij voor nieuwe berichten verzonden tijdens de sessie van de ontvanger" "Quarkus / Kotlin" "Service"

                    pseudoniemService -> bsnkTransformatie "Transformeert PP naar EP per magazijn" "BSNk API (lokaal)"
                    aanmeldService -> sessiecacheApp "Werkt cache bij" "REST API (intern)"
                    uitvraagOphaalService -> sessiecacheApp "Haalt berichten op uit cache" "REST API (intern)"
                    uitvraagBerichtenlijst -> sessiecacheApp "Haalt berichtenlijst op" "REST API (intern)"
                }

                ldvLogboek = softwareSystem "LDV Logboek" "Logboek Dataverwerkingen - logging van dataverwerkingen conform LDV-standaard" "Infrastructuur"
            }
        }

        berichtenUitvraagSysteem -> decentraalMagazijn "Wisselt berichten en bijlagen uit" "Digikoppeling REST API via FSC"

        medewerkerA -> orgA "Verstuurt berichten via"
        medewerkerB -> orgB "Verstuurt berichten via"

        burger -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, verwijdert" "HTTPS (browser/app)"
        burger -> digiD "Logt in" "HTTPS (browser redirect)"
        ondernemer -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, verwijdert" "HTTPS (browser/app)"
        ondernemer -> eHerkenning "Logt in" "HTTPS (browser redirect)"

        notificatieService -> burger "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"
        notificatieService -> ondernemer "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"

        interactielaag -> uitvraagResource "Berichten en metadata ophalen, bewerken en verwijderen" "Digikoppeling REST API via FSC (JWT bearer token met PP voor burgers, of per-magazijn pseudoniemen en machtigingen voor zakelijke gebruikers)"
        interactielaag -> profielService "Toestemming bekijken en wijzigen" "Digikoppeling REST API via FSC"
        interactielaag -> digiD "Authenticatie burgers" "SAML 2.0"
        interactielaag -> eHerkenning "Authenticatie zakelijke gebruikers; ontvangt gemachtigde diensten via SAML-assertion" "SAML 2.0"

        uitvraagOphaalService -> magazijnOphaalBeheerApi "Haalt bijlagen op; beheert berichtstatus (map, gelezen, verwijderd, etc.)" "Digikoppeling REST API via FSC (ondertekend EP en machtigingsclaims als parameters)"

        publicatieStream -> aanmeldService "Meldt nieuw bericht aan" "Digikoppeling REST API via FSC"
        publicatieStream -> notificatieService "Stuurt bericht-events door" "CloudEvents webhook" "Async"

        notificatieService -> profielService "Haalt contactgegevens en voorkeuren op" "Digikoppeling REST API via FSC"

        orgA -> magazijnAanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"
        orgB -> magazijnAanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"

        validatieToestemming -> profielService "Controleert of de ontvanger toestemming gegeven heeft" "Digikoppeling REST API via FSC"

        magazijnResolver -> profielService "Haalt dienstvoorkeuren op om te bepalen welke magazijnen bevraagd worden" "Digikoppeling REST API via FSC"
        sessiecacheMagazijnClient -> magazijnOphaalBeheerApi "Haalt berichten op" "Digikoppeling REST API via FSC (ondertekend EP en machtigingsclaims als parameters)"

        magazijnOphaalBeheerApi -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        magazijnOpslagService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        validatieApi -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        sessiecacheService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        magazijnResolver -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        pseudoniemService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        tokenValidatie -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        autorisatieService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        publicatieStream -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        aanmeldService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        uitvraagBerichtenlijst -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        uitvraagOphaalService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
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
            include *
            exclude "decentraalMagazijn -> berichtenUitvraagSysteem"
            autoLayout
        }

        systemContext berichtenUitvraagSysteem "BerichtenUitvraagSysteem" "Context van het Berichten Uitvraag Systeem" {
            include *
            exclude "decentraalMagazijn -> berichtenUitvraagSysteem"
            autoLayout
        }

        container decentraalMagazijn "BerichtenmagazijnContainers" "Containers binnen het Berichtenmagazijn" {
            include *
            autoLayout
        }

        container berichtenUitvraagSysteem "BerichtenUitvraagSysteemContainers" "Containers binnen het Berichten Uitvraag Systeem" {
            include *
            autoLayout
        }

        component uitvraagApi "BerichtenUitvraagServiceComponenten" "Componenten binnen de Berichten Uitvraag Service" {
            include *
            autoLayout
        }

        component sessiecacheApp "BerichtensessiecacheComponenten" "Componenten binnen de Berichtensessiecache" {
            include *
            autoLayout
        }

        component magazijnAanleverApi "AanleverAPIComponenten" "Componenten binnen de Berichtenmagazijn Aanlever API" {
            include *
            autoLayout
        }

        component berichtValidatie "BerichtValidatieComponenten" "Componenten binnen de Bericht Validatie Service" {
            include *
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
