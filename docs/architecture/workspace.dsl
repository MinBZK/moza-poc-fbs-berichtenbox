workspace "Federatief Berichtenstelsel" "Referentie-implementatie van het Federatief Berichtenstelsel (FBS) - BBO-opdracht Logius/BZK" {
    !docs workspace-docs

    properties {
        "nfr.betrouwbaarheid.berichtverlies" "RPO=0: geen berichtverlies; bij verstoring weigert de circuit breaker schrijfoperaties totdat duurzame persistentie is hersteld"
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
        interactielaag = softwareSystem "Interactielaag" "Portaal of app waarmee burgers en ondernemers communiceren met het berichtenstelsel (b.v. MijnOverheid portaal, of andere portalen)" "Extern Systeem"
        eHerkenning = softwareSystem "eHerkenning" "Authenticatie en machtigingen voor zakelijke gebruikers — stelsel met machtigingenvoorziening en dienstencatalogus" "Extern Systeem"
        digiD = softwareSystem "DigiD" "Authenticatie voor burgers" "Extern Systeem"

        orgA = softwareSystem "Organisatie A" "Deelnemende overheidsorganisatie - host zelf een berichtenmagazijn" "Deelnemer"
        orgB = softwareSystem "Organisatie B" "Deelnemende overheidsorganisatie - neemt een berichtenmagazijn af bij BBO" "Deelnemer"

        group "Federatief Berichtenstelsel (FBS)" {

            decentraalMagazijn = softwareSystem "Berichtenmagazijn (per deelnemende organisatie)" "Berichten opslaan, ophalen en beheren (incl. metadata)" "Magazijn" {
                properties {
                    "deployment.model" "Elke deelnemende organisatie host een eigen instantie, of neemt er een af bij BBO"
                }
                magazijnOphaalBeheerApi = container "Berichtenmagazijn Ophaal- en Beheer API" "REST API voor het ophalen van berichten en bijlagen, en het vastleggen van metadata (gelezen-bevestigingen per gebruiker)" "Quarkus / Kotlin" "Magazijn Service"
                magazijnAanleverApi = container "Berichtenmagazijn Aanlever API" "REST API voor het aanleveren van berichten door organisaties" "Quarkus / Kotlin" "Magazijn Service" {
                    magazijnAanleverResource = component "Aanlever REST API" "REST endpoints voor het aanleveren van berichten en bijlagen" "JAX-RS Resource" "Magazijn Component"
                    magazijnCircuitBreaker = component "CircuitBreaker" "Weigert schrijfoperaties wanneer RPO=0 niet gegarandeerd kan worden (dataopslag onbeschikbaar)" "MicroProfile Fault Tolerance" "Magazijn Component"
                    magazijnBerichtService = component "BerichtService" "Berichtlevenscyclus: valideren, opslaan en aanmelden" "CDI Bean" "Magazijn Component"

                    magazijnAanleverResource -> magazijnCircuitBreaker "Schrijfoperaties via"
                    magazijnCircuitBreaker -> magazijnBerichtService "Delegeert naar (als circuit closed)"
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
                        "cloudevents.source" "urn:nld:fbs:magazijn:{organisatie-oin}"
                        "cloudevents.type" "nl.rijksoverheid.fbs.bericht.gepubliceerd"
                    }
                }

                magazijnOphaalBeheerApi -> magazijnDatastore "Leest berichten en bijlagen; schrijft gelezen-bevestigingen per gebruiker"
                magazijnBerichtService -> magazijnDatastore "Schrijft berichten en bijlagen"
                magazijnBerichtService -> validatieApi "Stuurt bericht ter validatie"
                magazijnBerichtService -> publicatieStream "Stuurt gevalideerd bericht door"
                publicatieStream -> magazijnDatastore "Leest berichten met status 'te publiceren' en werkt status bij na succesvolle aanmelding"
            }

            group "Centraal gehoste services" {

                berichtenUitvraagSysteem = softwareSystem "Berichten Uitvraag Systeem" "Centraal systeem voor het uitvragen, beheren en aanleveren van berichten in het Federatief Berichtenstelsel" "FBS Dienst" {

                    sessiecacheApp = container "Berichtensessiecache" "Aggregeert berichten uit alle aangesloten magazijnen voor een burger of zakelijke gebruiker" "Quarkus / Kotlin" "Service" {
                        sessiecacheResource = component "Berichtensessiecache API" "REST endpoints voor berichtensessiecache en zoeken" "JAX-RS Resource"
                        sessiecacheService = component "BerichtensessiecacheService" "Bepaalt op basis van gebruikersclaims (PP, machtigingen) welke magazijnen bevraagd worden; transformeert PP naar EP per magazijn via BSNk; aggregeert en cachet berichten" "CDI Bean"
                        sessiecacheCache = component "Cache" "Cache voor berichten met full-text zoekindex (60s TTL)" "Redis / RediSearch"
                        sessiecacheMagazijnClient = component "MagazijnClient" "REST client naar berichtenmagazijnen" "REST Client"
                        sessiecacheResource -> sessiecacheService "Gebruikt"
                        sessiecacheService -> sessiecacheCache "Leest/schrijft cache"
                        sessiecacheService -> sessiecacheMagazijnClient "Haalt berichten op"
                    }

                    uitvraagApi = container "Berichten Uitvraag Service" "Service voor burgers en ondernemers - berichtenbox inzien en berichten beheren" "Quarkus / Kotlin" "Service" {
                        uitvraagResource = component "Berichten Uitvraag API" "REST endpoints voor berichtenbox, mappen en berichten" "JAX-RS Resource"
                        tokenValidatie = component "Token Validatie" "Valideert JWT bearer tokens van de Interactielaag en stelt de gebruikersidentiteit vast" "CDI Bean"
                        uitvraagBerichtenlijst = component "Berichtenlijst Service" "Lever per map een berichtenlijst, verplaats berichten naar andere map, verwijder berichten" "CDI Bean"
                        uitvraagOpvraag = component "Opvraag Service" "Haal berichten en bijlagen op; berichten uit cache, bijlagen en berichtstatus uit berichtenmagazijn" "CDI Bean"

                        uitvraagResource -> tokenValidatie "Valideert identiteit aanroeper"
                        uitvraagResource -> uitvraagBerichtenlijst "Berichtenlijst en mappenbeheer"
                        uitvraagResource -> uitvraagOpvraag "Berichten en bijlagen ophalen"
                    }

                    bsnkTransformatie = container "BSNk Transformatie" "Transformeert polymorfe pseudoniemen (PP) naar dienst-specifieke pseudoniemen (EP) per berichtenmagazijn" "BSNk container (Logius)" "Extern Geleverd"

                    aanmeldService = container "Aanmeld Service" "Werkt de cache bij voor nieuwe berichten verzonden tijdens de sessie van de ontvanger" "Quarkus / Kotlin" "Service"

                    sessiecacheService -> bsnkTransformatie "Transformeert pseudoniem per magazijn"
                    aanmeldService -> sessiecacheApp "Werkt cache bij" "REST API (intern)"
                    uitvraagOpvraag -> sessiecacheApp "Haalt berichten op uit cache" "REST API (intern)"
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
        ondernemer -> eHerkenning "Logt in en verkrijgt machtigingen voor diensten" "HTTPS (browser redirect)"

        notificatieService -> burger "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"
        notificatieService -> ondernemer "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"

        interactielaag -> uitvraagResource "Berichten en metadata ophalen, bewerken en verwijderen" "Digikoppeling REST API via FSC (JWT bearer token met PP en eHerkenning-machtigingen)"
        interactielaag -> profielService "Toestemming bekijken en wijzigen" "Digikoppeling REST API via FSC"
        interactielaag -> digiD "Authenticatie burgers" "SAML 2.0"
        interactielaag -> eHerkenning "Authenticatie zakelijke gebruikers; ontvangt gemachtigde diensten via SAML-assertion" "SAML 2.0"

        uitvraagOpvraag -> magazijnOphaalBeheerApi "Haalt bijlagen op; beheert berichtstatus (map, gelezen, verwijderd, etc.)" "Digikoppeling REST API via FSC (JWT met EP per magazijn)"

        publicatieStream -> aanmeldService "Meldt nieuw bericht aan" "Digikoppeling REST API via FSC"
        publicatieStream -> notificatieService "Stuurt bericht-events door" "CloudEvents webhook" "Async"

        notificatieService -> profielService "Haalt contactgegevens en voorkeuren op" "Digikoppeling REST API via FSC"

        orgA -> magazijnAanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"
        orgB -> magazijnAanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"

        validatieToestemming -> profielService "Controleert of de ontvanger toestemming gegeven heeft" "Digikoppeling REST API via FSC"

        sessiecacheMagazijnClient -> magazijnOphaalBeheerApi "Haalt berichten op" "Digikoppeling REST API via FSC (JWT met EP per magazijn)"

        magazijnOphaalBeheerApi -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        magazijnBerichtService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        validatieApi -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        sessiecacheService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        tokenValidatie -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        publicatieStream -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        aanmeldService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        uitvraagBerichtenlijst -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        uitvraagOpvraag -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
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
