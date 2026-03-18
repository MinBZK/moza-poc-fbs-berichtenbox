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
        beheerder = person "Beheerder" "Monitort en beheert het berichtenstelsel"

        // Externe systemen
        authzen = softwareSystem "AuthZEN / FTV" "Federatieve Toegangsverlening - autorisatie van verzoeken" "Extern Systeem"
        profielService = softwareSystem "Profiel Service" "Contactgegevens, communicatievoorkeuren en toestemmingsbeheer (MoZa)" "Extern Systeem"
        notificatieService = softwareSystem "Notificatie Service" "Multi-channel notificatiebezorging via e-mail, SMS en app (MoZa)" "Extern Systeem"
        digitaleBereikbaarheid = softwareSystem "Digitale Bereikbaarheid Service" "Tonen en muteren toestemming voor digitale communicatie per organisatie" "Extern Systeem"
        interactielaag = softwareSystem "Interactielaag" "Portaal of app waarmee burgers en ondernemers communiceren met het berichtenstelsel (b.v. MijnOverheid portaal, of andere portalen)" "Extern Systeem"
        emailService = softwareSystem "E-mail Service" "Externe e-maildienst voor het doorsturen van berichten" "Extern Systeem"

        // Deelnemende organisaties
        orgA = softwareSystem "Organisatie A" "Deelnemende overheidsorganisatie - host zelf een decentraal magazijn" "Deelnemer"
        orgB = softwareSystem "Organisatie B" "Deelnemende overheidsorganisatie - neemt een decentraal magazijn af bij BBO" "Deelnemer"

        // Het Federatief Berichtenstelsel
        group "Federatief Berichtenstelsel (FBS)" {

            // Decentraal Berichtenmagazijn - functioneel identiek, kan zelf gehost of bij BBO afgenomen worden
            decentraalMagazijn = softwareSystem "Decentraal Berichtenmagazijn (per deelnemende organisatie)" "Berichten opslaan en ophalen - elke deelnemende organisatie host een eigen instantie, of neemt er een af bij BBO" "Magazijn" {
                dmOphaalApi = container "Berichtenmagazijn Ophaal API" "REST API voor het ophalen van berichten en bijlagen" "Quarkus / Kotlin" "Service"
                dmOpslaanApi = container "Berichtenmagazijn Opslaan API" "REST API voor het opslaan van berichten door organisaties" "Quarkus / Kotlin" "Service"
                dmLogBuffer = container "Lokale Log Buffer" "Lokale opslag voor applicatie-logberichten bij onbeschikbaarheid logserver (max 72 uur retentie)" "Disk" "Database"
                dmPg = container "PostgreSQL" "Berichtmetadata (transactioneel, 0 berichtverlies)" "PostgreSQL 16" "Database"
                dmMinio = container "MinIO" "Berichtinhoud en bijlagen" "MinIO" "Database"

                adApp = container "Admin Dashboard" "Web-based beheeromgeving voor het magazijn" "Quarkus / Vaadin" "Service" {
                    adViews = component "Vaadin Views" "Dashboard, Berichten, Systeemstatus en LDV Audit Log views" "Vaadin"
                    adDataService = component "DashboardDataService" "Haalt berichtdata op via interne services" "CDI Bean"
                    adHealthChecker = component "ServiceHealthChecker" "Controleert beschikbaarheid van magazijndiensten" "HTTP Client"
                    adLdvLogger = component "LDV Logger" "Logt dataverwerkingen conform LDV-standaard" "OpenTelemetry"
                    adAppLogger = component "Applicatie Logger" "Applicatie-logging (foutmeldingen, audit); buffert lokaal bij uitval logserver (max 72 uur)" "SLF4J / Logback"

                    adViews -> adDataService "Toont data van"
                    adViews -> adHealthChecker "Toont status van"
                    adDataService -> adLdvLogger "Logt verwerkingen"
                    adDataService -> adAppLogger "Logt applicatie-events"
                }

                berichtValidatie = container "Bericht Validatie Service" "Valideert berichten op PDF-type, grootte en aantal bijlagen" "Quarkus / Kotlin" "Service"
                publicatieStream = container "Publicatie Stream" "Wacht met aanmelden van een bericht tot de publicatiedatum is verstreken" "Quarkus / Kotlin" "Service"

                dmOphaalApi -> dmPg "Leest metadata" "JDBC"
                dmOphaalApi -> dmMinio "Leest inhoud en bijlagen" "S3 REST API"
                dmOpslaanApi -> dmPg "Schrijft metadata" "JDBC"
                dmOpslaanApi -> dmMinio "Slaat inhoud en bijlagen op" "S3 REST API"
                dmOpslaanApi -> berichtValidatie "Stuurt bericht ter validatie" "REST API"
                adDataService -> dmOphaalApi "Beheert berichten" "REST API"
                adHealthChecker -> dmOphaalApi "Controleert gezondheid" "HTTP"
                adHealthChecker -> dmOpslaanApi "Controleert gezondheid" "HTTP"
                dmOpslaanApi -> publicatieStream "Stuurt gevalideerd bericht door"
            }

            group "Centraal gehoste services" {

                // Berichten Uitvraag Systeem
                berichtenUitvraagSysteem = softwareSystem "Berichten Uitvraag Systeem" "Centraal systeem voor het uitvragen, beheren en aanleveren van berichten in het Federatief Berichtenstelsel" "FBS Dienst" {

                    // Berichtensessiecache
                    blLogBuffer = container "Lokale Log Buffer" "Lokale opslag voor applicatie-logberichten bij onbeschikbaarheid logserver (max 72 uur retentie)" "Disk" "Database"
                    blApp = container "Berichtensessiecache" "Aggregeert berichten uit alle aangesloten magazijnen voor een burger of onderneming" "Quarkus / Kotlin" "Service" {
                        blResource = component "Berichtensessiecache API" "REST endpoints voor berichtensessiecache en zoeken" "JAX-RS Resource"
                        blService = component "BerichtensessiecacheService" "Aggregeert en cachet berichten; filtert berichten op basis van autorisatie via FTV/AuthZEN" "CDI Bean"
                        blCache = component "Cache" "In-memory cache voor berichten (60s TTL)" "Caffeine"
                        blMagazijnClient = component "MagazijnClient" "REST client naar decentrale berichtenmagazijnen" "REST Client"
                        blLdvLogger = component "LDV Logger" "Logt dataverwerkingen conform LDV-standaard" "OpenTelemetry"
                        blAppLogger = component "Applicatie Logger" "Applicatie-logging (foutmeldingen, audit); buffert lokaal bij uitval logserver (max 72 uur)" "SLF4J / Logback"
                        blResource -> blService "Gebruikt"
                        blService -> blCache "Leest/schrijft cache"
                        blService -> blMagazijnClient "Haalt berichten op"
                        blService -> blLdvLogger "Logt verwerkingen"
                        blService -> blAppLogger "Logt applicatie-events"
                    }

                    // Berichten Uitvraag API
                    buApi = container "Berichten Uitvraag API" "API voor burgers en ondernemers - berichtenbox inzien, berichten beheren, doorsturen" "Quarkus / Kotlin" "Service" {
                        buBerichtenlijst = component "Berichtenlijst Service" "Lever per map een berichtenlijst, verplaats berichten naar andere map, verwijder berichten" "CDI Bean"
                        buOpvraag = component "Opvraag Service" "Haal berichten en bijlagen op; berichten uit cache, bijlagen uit berichtenmagazijn" "CDI Bean"
                    }

                    // Aanmeld Service
                    aanmeldService = container "Aanmeld Service" "Werkt de cache bij voor nieuwe berichten verzonden tijdens de sessie van de ontvanger" "Quarkus / Kotlin" "Service"

                    // Interne relaties
                    aanmeldService -> blApp "Werkt cache bij" "REST API"
                    buOpvraag -> blApp "Haalt berichten op uit cache" "REST API"
                    buBerichtenlijst -> blApp "Haalt berichtenlijst op" "REST API"
                    blAppLogger -> blLogBuffer "Buffert applicatie-logberichten lokaal bij uitval logserver" "Disk I/O"
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
        burger -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, stuurt door, verwijdert" "HTTPS (browser/app)"
        ondernemer -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, stuurt door, verwijdert" "HTTPS (browser/app)"
        emailService -> burger "Bezorgt doorgestuurde berichten" "E-mail"
        emailService -> ondernemer "Bezorgt doorgestuurde berichten" "E-mail"
        notificatieService -> burger "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"
        notificatieService -> ondernemer "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"

        // Interactielaag -> Berichten Uitvraag API
        interactielaag -> buApi "Berichten ophalen, lijsten, doorsturen" "REST API"

        // Interactielaag -> Digitale Bereikbaarheid Service
        interactielaag -> digitaleBereikbaarheid "Toestemming bekijken en wijzigen" "REST API"

        // Opvraag Service -> Decentraal Magazijn (voor bijlagen)
        buOpvraag -> dmOphaalApi "Haalt bijlagen op uit berichtenmagazijn" "REST API"

        // Beheerder
        beheerder -> adViews "Beheert systeem via" "HTTPS (browser)"

        // Publicatie Stream meldt nieuwe berichten aan bij het uitvraag systeem
        publicatieStream -> aanmeldService "Meldt nieuw bericht aan" "REST API"

        // Publicatie Stream notificeert externe Notificatie Service
        publicatieStream -> notificatieService "Stuurt bericht-events door" "CloudEvents webhook" "Async"

        // Notificatie Service (extern) haalt contactgegevens op
        notificatieService -> profielService "Haalt contactgegevens en voorkeuren op" "REST API"

        // Organisaties leveren berichten aan bij hun decentraal magazijn
        orgA -> dmOpslaanApi "Levert berichten aan" "Digikoppeling REST API via FSC"
        orgB -> dmOpslaanApi "Levert berichten aan" "Digikoppeling REST API via FSC"

        // Bericht Validatie Service -> Digitale Bereikbaarheid Service
        berichtValidatie -> digitaleBereikbaarheid "Controleert of de ontvanger toestemming gegeven heeft" "REST API"

        // Autorisatie (component-niveau)
        blService -> authzen "Filtert berichten op autorisatie" "AuthZEN REST API"

        // Berichtensessiecache -> decentrale magazijnen (alle gelijk behandeld)
        blMagazijnClient -> dmOphaalApi "Haalt berichten op" "Digikoppeling REST API via FSC"

        // LDV Logboek
        dmOphaalApi -> ldvLogboek "Logt dataverwerkingen" "OTLP"
        dmOpslaanApi -> ldvLogboek "Logt dataverwerkingen" "OTLP"
        blLdvLogger -> ldvLogboek "Logt dataverwerkingen" "OTLP"
        adLdvLogger -> ldvLogboek "Logt dataverwerkingen" "OTLP"
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

        component buApi "BerichtenUitvraagAPIComponenten" "Componenten binnen de Berichten Uitvraag API" {
            include *
            autoLayout
        }

        component blApp "BerichtensessiecacheComponenten" "Componenten binnen de Berichtensessiecache" {
            include *
            autoLayout
        }

        component adApp "AdminDashboardComponenten" "Componenten binnen het Admin Dashboard" {
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
