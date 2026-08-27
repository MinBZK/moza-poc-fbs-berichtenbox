# De demo op ZAD verifiëren

Vier stappen na de eenmalige creatie uit `README.md`. Ze staan in de volgorde waarin een fout de
volgende stap onbruikbaar maakt, dus stop bij de eerste die niet klopt.

Alles gaat door de authorization-wall, dus doe dit in een browser waarin je bent ingelogd. Een
`curl` zonder sessie krijgt HTTP 403 met de inlogpagina terug — dat is de muur, niet een kapot
component.

```
CONSOLE=https://democonsole-test-mpfm-w3h.rig.prd1.gn2.quattro.rijksapps.nl
```

## 1. De omgeving beschrijft zichzelf

Open `$CONSOLE/api/demo/omgeving`.

Verwacht:

- `uitvraagBasis` wijst naar de publieke uitvraag van dezelfde deployment, **inclusief** `/api/v1`.
  Zonder dat pad faalt elke aanroep vanaf de Berichtenbox-pagina zichtbaar voor de gebruiker.
- `storingen` is leeg: er staat op ZAD geen Toxiproxy.
- `sessiecache` is `false`.

Staat er een proxy in `storingen` die er niet hoort, dan is een `TOXIPROXY_*_URL` niet leeg gezet en
toont het paneel een knop die gegarandeerd faalt.

## 2. Vullen

Open `$CONSOLE/` en druk op **Herstel demo**. Dat stopt de stroom, reset de storingen, leegt de
magazijnen en laadt de basisvulling — in die volgorde.

Verwacht: een antwoord zonder fout, en daarna op **Status (aantal berichten)** een aantal groter dan
nul per magazijn.

Faalt het legen op een verbindingsfout, dan klopt een van de `MAGAZIJN_*_DB_*`-aliassen niet. Faalt
het vullen met een 403, dan kent de profielservice-stub de persona niet — controleer of de
externe-stubs van dezelfde deployment draaien.

## 3. De keten

Open `$CONSOLE/berichtenbox.html`, kies een persona en haal berichten op.

Verwacht: de berichten uit de basisvulling verschijnen, uit beide magazijnen. Dit is de enige stap
die de hele keten aanraakt — console → magazijn → uitvraag → sessiecache → terug.

Blijft de lijst leeg terwijl stap 2 wel berichten telde, kijk dan in de browserconsole naar een
CORS-fout: dan noemt `QUARKUS_HTTP_CORS_ORIGINS` op de uitvraag deze console-origin niet.

## 4. De schemacontrole

**Sla deze niet over.** Dit is de enige stap die een verkeerd `MAGAZIJN_*_DB_SCHEMA` aanwijst, en
een verkeerd schema faalt stil: de console leegt dan een leeg schema en meldt tevreden nul.

1. `$CONSOLE/api/demo/status` — noteer het aantal per magazijn. Moet groter dan nul zijn (stap 2).
2. Druk op **Magazijnen legen**.
3. `$CONSOLE/api/demo/status` opnieuw — moet nu nul per magazijn zijn.

Blijft het aantal in stap 3 gelijk aan dat van stap 1, terwijl het legen wél "gelukt" meldde, dan
wijst minstens één schema naar de verkeerde plek. Lees de juiste waarden af met
`zadctl env list -c magazijna` en `-c magazijnb`, en zet ze met `zadctl env set -c democonsole`.

## Daarna

Laat de omgeving niet leeg achter: druk nog een keer op **Herstel demo**, zodat de volgende
bezoeker een gevulde demo aantreft.
