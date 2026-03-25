**Status:** Uitgevoerd

# Plan: Gelezen-bevestigingen toevoegen aan C4-model

## Context

Het berichtenmagazijn moet per gebruiker bijhouden of een bericht gelezen is. Pas wanneer de gebruiker het bericht daadwerkelijk gelezen heeft in de interactielaag, wordt een gelezen-bevestiging teruggestuurd naar het magazijn en daar opgeslagen. Dit geldt alleen voor berichten, niet voor bijlagen.

**Flow gelezen-bevestiging:** Interactielaag → uitvraagResource → uitvraagOpvraag → magazijnOphaalApi → Dataopslag

## Wijzigingen in `docs/architecture/workspace.dsl`

### 1. Beschrijving Ophaal API aanpassen (regel 33)

**Van:**
```dsl
magazijnOphaalApi = container "Berichtenmagazijn Ophaal API" "REST API voor het ophalen van berichten en bijlagen" ...
```
**Naar:**
```dsl
magazijnOphaalApi = container "Berichtenmagazijn Ophaal API" "REST API voor het ophalen van berichten en bijlagen, en het vastleggen van gelezen-bevestigingen per gebruiker" ...
```

### 2. Relatie Ophaal API → Dataopslag aanpassen (regel 60)

**Van:**
```dsl
magazijnOphaalApi -> magazijnDatastore "Leest berichten en bijlagen"
```
**Naar:**
```dsl
magazijnOphaalApi -> magazijnDatastore "Leest berichten en bijlagen; schrijft gelezen-bevestigingen per gebruiker"
```

### 3. Nieuwe relatie uitvraagOpvraag → magazijnOphaalApi toevoegen (na regel 119)

De bestaande relatie op regel 119 blijft ongewijzigd (bijlagen ophalen). Een aparte relatie wordt toegevoegd voor gelezen-bevestigingen:

```dsl
uitvraagOpvraag -> magazijnOphaalApi "Stuurt gelezen-bevestigingen" "Digikoppeling REST API via FSC"
```

### 4. Relatie interactielaag → uitvraagResource aanpassen (regel 114)

**Van:**
```dsl
interactielaag -> uitvraagResource "Berichten ophalen en lijsten (incl. gemachtigde diensten)" "Digikoppeling REST API via FSC"
```
**Naar:**
```dsl
interactielaag -> uitvraagResource "Berichten ophalen en lijsten; gelezen-bevestigingen (incl. gemachtigde diensten)" "Digikoppeling REST API via FSC"
```

## Bestand

`docs/architecture/workspace.dsl` — enige bestand dat gewijzigd wordt.

## Verificatie

1. Ververs Structurizr Lite op http://localhost:8080
2. Container-view Berichtenmagazijn: relatie Ophaal API → Dataopslag vermeldt gelezen-bevestigingen
3. Component-view Berichten Uitvraag Service: twee aparte relaties uitvraagOpvraag → magazijnOphaalApi (bijlagen en gelezen-bevestigingen)
4. De bijlagen-flow is ongewijzigd
