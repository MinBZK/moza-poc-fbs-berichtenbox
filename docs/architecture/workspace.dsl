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
        digitaleBereikbaarheid = softwareSystem "Digitale Bereikbaarheid Service" "Beheert digitale contactgegevens en bereikbaarheidsvoorkeuren van burgers en ondernemers (MoZa)" "Extern Systeem"
        interactielaag = softwareSystem "Interactielaag" "Portaal of app waarmee burgers en ondernemers communiceren met het berichtenstelsel (MoZa)" "Extern Systeem"
        emailService = softwareSystem "E-mail Service" "Externe e-maildienst voor het doorsturen van berichten" "Extern Systeem"

        // Deelnemende organisaties
        orgA = softwareSystem "Organisatie A" "Deelnemende overheidsorganisatie - host zelf een decentraal magazijn" "Deelnemer"
        orgB = softwareSystem "Organisatie B" "Deelnemende overheidsorganisatie - neemt een decentraal magazijn af bij BBO" "Deelnemer"

        // Het Federatief Berichtenstelsel
        group "Federatief Berichtenstelsel (FBS)" {

            // Decentraal Berichtenmagazijn - functioneel identiek, kan zelf gehost of bij BBO afgenomen worden
            decentraalMagazijn = softwareSystem "Decentraal Berichtenmagazijn (per deelnemende organisatie)" "Berichten opslaan en ophalen - elke deelnemende organisatie host een eigen instantie, of neemt er een af bij BBO" "Magazijn" {
                dmApp = container "Berichtenmagazijn API" "REST API voor berichten opslaan en ophalen" "Quarkus / Kotlin" "Service" {
                    dmApi = component "Berichtenmagazijn REST API" "REST endpoints voor berichten en bijlagen" "JAX-RS Resource"
                    dmCircuitBreaker = component "CircuitBreaker" "Weigert schrijfoperaties wanneer RPO=0 niet gegarandeerd kan worden (PostgreSQL, MinIO of Kafka onbeschikbaar)" "MicroProfile Fault Tolerance"
                    dmBerichtSvc = component "BerichtService" "Berichtlevenscyclus: aanmaken, ophalen, bijwerken, verwijderen" "CDI Bean"
                    dmValidatie = component "ValidatieService" "Valideert inkomende berichten en bijlagen op structuur, formaat en inhoud" "CDI Bean"
                    dmAutorisatie = component "AutorisatieService" "Verifieert autorisatie via AuthZEN/FTV (fail-closed)" "CDI Bean"
                    dmStorageSvc = component "ObjectStorageService" "Berichtinhoud en bijlagen opslaan/ophalen" "MinIO SDK"
                    dmRepository = component "BerichtRepository" "Persistentie van berichten en bijlagen" "Panache ORM"
                    dmLdvLogger = component "LDV Logger" "Logt dataverwerkingen conform LDV-standaard" "OpenTelemetry"
                    dmAppLogger = component "Applicatie Logger" "Applicatie-logging (foutmeldingen, audit); buffert lokaal bij uitval logserver (max 72 uur)" "SLF4J / Logback"

                    dmApi -> dmCircuitBreaker "Schrijfoperaties via"
                    dmCircuitBreaker -> dmBerichtSvc "Delegeert naar (als circuit closed)"
                    dmApi -> dmAutorisatie "Verifieert autorisatie"
                    dmBerichtSvc -> dmValidatie "Valideert bericht"
                    dmBerichtSvc -> dmRepository "Leest/schrijft"
                    dmBerichtSvc -> dmStorageSvc "Slaat inhoud op"
                    dmBerichtSvc -> dmLdvLogger "Logt verwerkingen"
                    dmBerichtSvc -> dmAppLogger "Logt applicatie-events"
                    dmCircuitBreaker -> dmAppLogger "Logt circuit state changes"
                }
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

                dmRepository -> dmPg "Leest/schrijft metadata" "JDBC"
                dmStorageSvc -> dmMinio "Slaat inhoud en bijlagen op" "S3 REST API"
                dmAppLogger -> dmLogBuffer "Buffert applicatie-logberichten lokaal bij uitval logserver" "Disk I/O"
                adDataService -> dmApp "Beheert berichten" "REST API"
                adHealthChecker -> dmApp "Controleert gezondheid" "HTTP"
            }

            group "Centraal gehoste services" {

                berichtenboxApi = softwareSystem "Berichtenbox API" "API voor burgers en ondernemers - berichtenbox inzien, berichten beheren, zoeken, doorsturen" "FBS Dienst"
                aanleverApi = softwareSystem "Aanlever API" "API voor berichtleveranciers - berichten aanleveren aan het stelsel" "FBS Dienst"

                berichtensessiecache = softwareSystem "Berichtensessiecache" "Aggregeert berichtrecords uit alle aangesloten magazijnen" "FBS Dienst" {
                    blLogBuffer = container "Lokale Log Buffer" "Lokale opslag voor applicatie-logberichten bij onbeschikbaarheid logserver (max 72 uur retentie)" "Disk" "Database"
                    blApp = container "Berichtensessiecache API" "REST API voor geaggregeerde berichtrecords" "Quarkus / Kotlin" "Service" {
                        blResource = component "Berichtensessiecache API" "REST endpoints voor berichtensessiecache en zoeken" "JAX-RS Resource"
                        blService = component "BerichtensessiecacheService" "Aggregeert en cachet berichtrecords" "CDI Bean"
                        blCache = component "Cache" "In-memory cache voor berichtrecords (60s TTL)" "Caffeine"
                        blMagazijnClient = component "MagazijnClient" "REST client naar decentrale berichtenmagazijnen" "REST Client"
                        blLdvLogger = component "LDV Logger" "Logt dataverwerkingen conform LDV-standaard" "OpenTelemetry"
                        blAppLogger = component "Applicatie Logger" "Applicatie-logging (foutmeldingen, audit); buffert lokaal bij uitval logserver (max 72 uur)" "SLF4J / Logback"
                        blEventForwarder = component "EventForwarder" "Stuurt bericht-events door naar Notificatie Service" "CloudEvents / REST Client"

                        blResource -> blService "Gebruikt"
                        blService -> blCache "Leest/schrijft cache"
                        blService -> blMagazijnClient "Haalt berichtrecords op"
                        blService -> blLdvLogger "Logt verwerkingen"
                        blService -> blAppLogger "Logt applicatie-events"
                        blService -> blEventForwarder "Stuurt bericht-events door"
                    }
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

        // Organisaties -> hun decentraal magazijn (functioneel identiek, hosting verschilt)
        orgA -> dmApi "Verstuurt en ontvangt berichten" "Digikoppeling REST API via FSC"
        orgB -> dmApi "Verstuurt en ontvangt berichten" "Digikoppeling REST API via FSC"

        // Burger en Ondernemer - interactie via interactielaag
        burger -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, stuurt door, verwijdert" "HTTPS (browser/app)"
        ondernemer -> interactielaag "Bekijkt berichten, zoekt, organiseert in mappen, stuurt door, verwijdert" "HTTPS (browser/app)"
        emailService -> burger "Bezorgt doorgestuurde berichten" "E-mail"
        emailService -> ondernemer "Bezorgt doorgestuurde berichten" "E-mail"
        notificatieService -> burger "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"
        notificatieService -> ondernemer "Notificeert over nieuwe berichten" "E-mail, SMS, app-notificatie" "Async"

        // Interactielaag -> achterliggende diensten
        interactielaag -> berichtenboxApi "Berichten, mappen, zoeken, abonnementen" "REST API"

        // Berichtenbox API -> achterliggende diensten
        berichtenboxApi -> blApp "Berichtensessiecache, mappen, zoeken" "REST API"
        berichtenboxApi -> dmApp "Berichten en bijlagen ophalen, verwijderen" "REST API"
        berichtenboxApi -> emailService "Doorstuuropdracht van burger/ondernemer naar derde partij" "SMTP / REST API"
        berichtenboxApi -> digitaleBereikbaarheid "Abonnementen bekijken en wijzigen" "REST API"

        // Beheerder
        beheerder -> adViews "Beheert systeem via" "HTTPS (browser)"

        // Berichtensessiecache notificeert externe Notificatie Service
        blEventForwarder -> notificatieService "Stuurt bericht-events door" "CloudEvents webhook" "Async"

        // Notificatie Service (extern) haalt contactgegevens op
        notificatieService -> profielService "Haalt contactgegevens en voorkeuren op" "REST API"

        // Organisaties leveren berichten aan via Aanlever API
        orgA -> aanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"
        orgB -> aanleverApi "Levert berichten aan" "Digikoppeling REST API via FSC"

        // Aanlever API -> achterliggende diensten
        aanleverApi -> dmApp "Slaat berichten op in magazijn" "REST API"
        aanleverApi -> kafka "Publiceert bericht-events" "Kafka Producer" "Async"

        // Autorisatie (component-niveau)
        dmAutorisatie -> authzen "Evalueert access request" "AuthZEN REST API"

        // Berichtensessiecache -> decentrale magazijnen (alle gelijk behandeld)
        blMagazijnClient -> dmApp "Haalt berichtrecords op" "Digikoppeling REST API via FSC"

        // LDV Logboek (component-niveau)
        dmLdvLogger -> ldvLogboek "Logt dataverwerkingen" "OTLP"
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

        systemContext berichtensessiecache "Berichtensessiecache" "Context van de Berichtensessiecache" {
            include *
            autoLayout
        }

        container decentraalMagazijn "DecentraalMagazijnContainers" "Containers binnen het Decentraal Berichtenmagazijn" {
            include *
            autoLayout
        }

        container berichtensessiecache "BerichtensessiecacheContainers" "Containers binnen de Berichtensessiecache" {
            include *
            autoLayout
        }

        component dmApp "BerichtenmagazijnComponenten" "Componenten binnen de Berichtenmagazijn API" {
            include *
            autoLayout
        }

        component blApp "BerichtensessiecacheComponenten" "Componenten binnen de Berichtensessiecache API" {
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
