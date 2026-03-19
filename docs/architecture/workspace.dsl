workspace "Federatief Berichtenstelsel" "Referentie-implementatie van het Federatief Berichtenstelsel (FBS) - BBO-opdracht Logius/BZK" {
    !docs workspace-docs

    properties {
        "nfr.betrouwbaarheid.berichtverlies" "RPO=0: geen berichtverlies; bij verstoring weigert de circuit breaker schrijfoperaties totdat duurzame persistentie is hersteld"
        "nfr.betrouwbaarheid.applicatielogging" "Bij onbeschikbaarheid logserver worden applicatie-logberichten lokaal opgeslagen voor maximaal 72 uur"
    }

    model {
        properties {
            "structurizr.groupSeparator" "/"
        }

        // Personen
        burger = person "Burger" "Ontvangt berichten en notificaties van overheidsorganisaties"
        ondernemer = person "Ondernemer / Zakelijke gebruiker" "Ontvangt berichten en notificaties van overheidsorganisaties"
        medewerkerA = person "Medewerker A" "Verstuurt berichten namens Organisatie A"
        medewerkerB = person "Medewerker B" "Verstuurt berichten namens Organisatie B"
        // Externe systemen
        authzen = softwareSystem "AuthZEN / FTV" "Federatieve Toegangsverlening - autorisatie van verzoeken" "Extern Systeem"
        profielService = softwareSystem "Profiel Service" "Contactgegevens, communicatievoorkeuren en toestemmingsbeheer (MoZa)" "Extern Systeem"
        notificatieService = softwareSystem "Notificatie Service" "Multi-channel notificatiebezorging via e-mail, SMS en app (MoZa)" "Extern Systeem"
        digitaleBereikbaarheid = softwareSystem "Digitale Bereikbaarheid Service" "Tonen en muteren toestemming voor digitale communicatie per organisatie" "Extern Systeem"
        interactielaag = softwareSystem "Interactielaag" "Portaal of app waarmee burgers en ondernemers communiceren met het berichtenstelsel (b.v. MijnOverheid portaal, of andere portalen)" "Extern Systeem"


        // Deelnemende organisaties
        orgA = softwareSystem "Organisatie A" "Deelnemende overheidsorganisatie - host zelf een decentraal magazijn" "Deelnemer"
        orgB = softwareSystem "Organisatie B" "Deelnemende overheidsorganisatie - neemt een decentraal magazijn af bij BBO" "Deelnemer"

        // Het Federatief Berichtenstelsel
        group "Federatief Berichtenstelsel (FBS)" {

            // Decentraal Berichtenmagazijn - functioneel identiek, kan zelf gehost of bij BBO afgenomen worden
            decentraalMagazijn = softwareSystem "Decentraal Berichtenmagazijn (per deelnemende organisatie)" "Berichten opslaan en ophalen - elke deelnemende organisatie host een eigen instantie, of neemt er een af bij BBO" "Magazijn" {
                magazijnOphaalApi = container "Berichtenmagazijn Ophaal API" "REST API voor het ophalen van berichten en bijlagen" "Quarkus / Kotlin" "Magazijn Service"
                magazijnOpslaanApi = container "Berichtenmagazijn Opslaan API" "REST API voor het opslaan van berichten door organisaties" "Quarkus / Kotlin" "Magazijn Service" {
                    magazijnOpslaanResource = component "Opslaan REST API" "REST endpoints voor het aanleveren van berichten en bijlagen" "JAX-RS Resource" "Magazijn Component"
                    magazijnCircuitBreaker = component "CircuitBreaker" "Weigert schrijfoperaties wanneer RPO=0 niet gegarandeerd kan worden (dataopslag onbeschikbaar)" "MicroProfile Fault Tolerance" "Magazijn Component"
                    magazijnBerichtService = component "BerichtService" "Berichtlevenscyclus: valideren, opslaan en aanmelden" "CDI Bean" "Magazijn Component"

                    magazijnOpslaanResource -> magazijnCircuitBreaker "Schrijfoperaties via"
                    magazijnCircuitBreaker -> magazijnBerichtService "Delegeert naar (als circuit closed)"
                }
                magazijnLogBuffer = container "Lokale Log Buffer" "Lokale opslag voor applicatie-logberichten bij onbeschikbaarheid logserver (max 72 uur retentie)" "Disk" "Magazijn Database"
                magazijnDatastore = container "Dataopslag" "Berichtmetadata, inhoud en bijlagen (0 berichtverlies)" "" "Magazijn Database"

                berichtValidatie = container "Bericht Validatie Service" "Valideert berichten op technische eisen en controleert toestemming via Digitale Bereikbaarheid Service" "Quarkus / Kotlin" "Magazijn Service" {
                    validatieApi = component "Validatie API" "REST endpoint voor berichtvalidatie" "JAX-RS Resource" "Magazijn Component"
                    validatieTechnisch = component "Technische Validatie" "Valideert PDF-type, grootte en aantal bijlagen" "CDI Bean" "Magazijn Component"
                    validatieToestemming = component "Toestemming Controle" "Controleert of de ontvanger toestemming gegeven heeft voor digitale communicatie" "CDI Bean" "Magazijn Component"

                    validatieApi -> validatieTechnisch "Valideert technische eisen"
                    validatieApi -> validatieToestemming "Controleert toestemming"
                }
                publicatieStream = container "Publicatie Stream" "Wacht met aanmelden van een bericht tot de publicatiedatum is verstreken" "Quarkus / Kotlin" "Magazijn Service" {
                    properties {
                        "cloudevents.profiel" "NL GOV profiel CloudEvents v1.1"
                        "cloudevents.source" "urn:nld:fbs:magazijn:{organisatie-oin}"
                        "cloudevents.type" "nl.rijksoverheid.fbs.bericht.gepubliceerd"
                    }
                }

                magazijnOphaalApi -> magazijnDatastore "Leest berichten en bijlagen"
                magazijnBerichtService -> magazijnDatastore "Schrijft berichten en bijlagen"
                magazijnBerichtService -> validatieApi "Stuurt bericht ter validatie"
                magazijnBerichtService -> publicatieStream "Stuurt gevalideerd bericht door"
            }

            group "Centraal gehoste services" {

                // Berichten Uitvraag Systeem
                berichtenUitvraagSysteem = softwareSystem "Berichten Uitvraag Systeem" "Centraal systeem voor het uitvragen, beheren en aanleveren van berichten in het Federatief Berichtenstelsel" "FBS Dienst" {

                    // Berichtensessiecache
                    sessiecacheLogBuffer = container "Lokale Log Buffer" "Lokale opslag voor applicatie-logberichten bij onbeschikbaarheid logserver (max 72 uur retentie)" "Disk" "Database"
                    sessiecacheApp = container "Berichtensessiecache" "Aggregeert berichten uit alle aangesloten magazijnen voor een burger of onderneming" "Quarkus / Kotlin" "Service" {
                        sessiecacheResource = component "Berichtensessiecache API" "REST endpoints voor berichtensessiecache en zoeken" "JAX-RS Resource"
                        sessiecacheService = component "BerichtensessiecacheService" "Aggregeert en cachet berichten; filtert berichten op basis van autorisatie via FTV/AuthZEN" "CDI Bean"
                        sessiecacheCache = component "Cache" "Cache voor berichten met full-text zoekindex (60s TTL)" "Redis / RediSearch"
                        sessiecacheMagazijnClient = component "MagazijnClient" "REST client naar decentrale berichtenmagazijnen" "REST Client"
                        sessiecacheAppLogger = component "Applicatie Logger" "Applicatie-logging (foutmeldingen, audit); buffert lokaal bij uitval logserver (max 72 uur)" "SLF4J / Logback"
                        sessiecacheResource -> sessiecacheService "Gebruikt"
                        sessiecacheService -> sessiecacheCache "Leest/schrijft cache"
                        sessiecacheService -> sessiecacheMagazijnClient "Haalt berichten op"
                        sessiecacheService -> sessiecacheAppLogger "Logt applicatie-events"
                    }

                    // Berichten Uitvraag API
                    uitvraagApi = container "Berichten Uitvraag API" "API voor burgers en ondernemers - berichtenbox inzien en berichten beheren" "Quarkus / Kotlin" "Service" {
                        uitvraagBerichtenlijst = component "Berichtenlijst Service" "Lever per map een berichtenlijst, verplaats berichten naar andere map, verwijder berichten" "CDI Bean"
                        uitvraagOpvraag = component "Opvraag Service" "Haal berichten en bijlagen op; berichten uit cache, bijlagen uit berichtenmagazijn" "CDI Bean"
                    }

                    // Aanmeld Service
                    aanmeldService = container "Aanmeld Service" "Werkt de cache bij voor nieuwe berichten verzonden tijdens de sessie van de ontvanger" "Quarkus / Kotlin" "Service"

                    // Interne relaties
                    aanmeldService -> sessiecacheApp "Werkt cache bij" "REST API (intern)"
                    uitvraagOpvraag -> sessiecacheApp "Haalt berichten op uit cache" "REST API (intern)"
                    uitvraagBerichtenlijst -> sessiecacheApp "Haalt berichtenlijst op" "REST API (intern)"
                    sessiecacheAppLogger -> sessiecacheLogBuffer "Buffert applicatie-logberichten lokaal bij uitval logserver" "Disk I/O"
                }

                // Gedeelde infrastructuur
                kafka = softwareSystem "Kafka" "Duurzame event streaming voor bericht-lifecycle events (acks=all, 0 berichtverlies)" "Infrastructuur"
                ldvLogboek = softwareSystem "LDV Logboek" "Logboek Dataverwerkingen - logging van dataverwerkingen conform LDV-standaard" "Infrastructuur"
            }
        }

        // === Landscape relaties ===

        // Medewerkers -> hun organisatie
        medewerkerA -> orgA "Verstuurt berichten via"
        medewerkerB -> orgB "Verstuurt berichten via"


        // Burger en Ondernemer - interactie via interactielaag
        burger -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, verwijdert" "HTTPS (browser/app)"
        ondernemer -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, verwijdert" "HTTPS (browser/app)"

        notificatieService -> burger "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"
        notificatieService -> ondernemer "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"

        // Interactielaag -> Berichten Uitvraag API
        interactielaag -> uitvraagApi "Berichten ophalen en lijsten" "Digikoppeling REST API via FSC"

        // Interactielaag -> Digitale Bereikbaarheid Service
        interactielaag -> digitaleBereikbaarheid "Toestemming bekijken en wijzigen" "Digikoppeling REST API via FSC"

        // Opvraag Service -> Decentraal Magazijn (voor bijlagen)
        uitvraagOpvraag -> magazijnOphaalApi "Haalt bijlagen op uit berichtenmagazijn" "Digikoppeling REST API via FSC"

        // Publicatie Stream meldt nieuwe berichten aan bij het uitvraag systeem
        publicatieStream -> aanmeldService "Meldt nieuw bericht aan" "Digikoppeling REST API via FSC"

        // Publicatie Stream notificeert externe Notificatie Service
        publicatieStream -> notificatieService "Stuurt bericht-events door" "CloudEvents webhook" "Async"

        // Notificatie Service (extern) haalt contactgegevens op
        notificatieService -> profielService "Haalt contactgegevens en voorkeuren op" "Digikoppeling REST API via FSC"

        // Organisaties leveren berichten aan bij hun decentraal magazijn
        orgA -> magazijnOpslaanApi "Levert berichten aan" "Digikoppeling REST API via FSC"
        orgB -> magazijnOpslaanApi "Levert berichten aan" "Digikoppeling REST API via FSC"

        // Bericht Validatie Service -> Digitale Bereikbaarheid Service
        validatieToestemming -> digitaleBereikbaarheid "Controleert of de ontvanger toestemming gegeven heeft" "Digikoppeling REST API via FSC"

        // Autorisatie (component-niveau)
        sessiecacheService -> authzen "Filtert berichten op autorisatie" "AuthZEN REST API"

        // Berichtensessiecache -> decentrale magazijnen (alle gelijk behandeld)
        sessiecacheMagazijnClient -> magazijnOphaalApi "Haalt berichten op" "Digikoppeling REST API via FSC"

        // LDV Logboek (containers/componenten die persoonsgegevens verwerken)
        magazijnOphaalApi -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        magazijnBerichtService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        validatieApi -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        sessiecacheService -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        uitvraagBerichtenlijst -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
        uitvraagOpvraag -> ldvLogboek "Logt dataverwerkingen" "OpenTelemetry (OTLP)"
    }

    views {
        properties {
            "generatr.site.externalTag" "Extern Systeem"
            "generatr.site.nestGroups" "false"
        }

        systemLandscape "SystemLandscape" "Het Federatief Berichtenstelsel - een stelsel van federatief gekoppelde diensten" {
            include *
            autoLayout
        }

        systemContext decentraalMagazijn "DecentraalMagazijn" "Context van het Decentraal Berichtenmagazijn" {
            include *
            autoLayout
        }

        systemContext berichtenUitvraagSysteem "BerichtenUitvraagSysteem" "Context van het Berichten Uitvraag Systeem" {
            include *
            autoLayout
        }

        container decentraalMagazijn "DecentraalMagazijnContainers" "Containers binnen het Decentraal Berichtenmagazijn" {
            include *
            autoLayout
        }

        container berichtenUitvraagSysteem "BerichtenUitvraagSysteemContainers" "Containers binnen het Berichten Uitvraag Systeem" {
            include *
            autoLayout
        }

        component uitvraagApi "BerichtenUitvraagAPIComponenten" "Componenten binnen de Berichten Uitvraag API" {
            include *
            autoLayout
        }

        component sessiecacheApp "BerichtensessiecacheComponenten" "Componenten binnen de Berichtensessiecache" {
            include *
            autoLayout
        }

        component magazijnOpslaanApi "OpslaanAPIComponenten" "Componenten binnen de Berichtenmagazijn Opslaan API" {
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
