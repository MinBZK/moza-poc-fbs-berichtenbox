# Wachtwoord van de wegwerpketen zonder optieprefix — implementatieplan

**Status:** Uitgevoerd

**Doel:** het stelselregister maakt in ontwikkel- en testmodus altijd een wegwerpketen, en de tests
van het stelselregister falen niet meer af en toe op `keytool`.

## Context

`OndertekensleutelTest` faalde in CI een enkele keer met `keytool -genkeypair mislukte: Error: Could
not find or load main class <willekeurige tekst>` (gezien bij
[MinBZK/moza-poc-fbs-berichtenbox#397](https://github.com/MinBZK/moza-poc-fbs-berichtenbox/pull/397),
groen na opnieuw draaien).

- **Oorzaak:** `Wegwerpketen.nieuwWachtwoord()` maakte een Base64-URL-wachtwoord, en dat kan met `-`
  beginnen. De launcher van `keytool` geeft elk argument dat met `-J` begint als optie aan de JVM,
  ook als het de waarde van `-storepass` is. Wat na `-J` komt, leest de JVM dan als hoofdklasse.
  Lokaal nagebootst met `keytool ... -storepass -JabcDEF123`: `Could not find or load main class abcDEF123`.
- **Kans:** 1 op 4096 per wachtwoord (`-` en dan `J`, elk 1 op 64). Een testrun maakt per keten in
  `Testketens` en per gestarte Quarkus in testmodus een wachtwoord.
- **Ook buiten de tests:** `Sleutelbron` maakt bij het starten in ontwikkel- en testmodus dezelfde
  keten, dus ook `quarkus:dev` kon hierop falen.

## Ontwerp

- **Hexadecimaal in plaats van Base64-URL** (`HexFormat`). Een hexadecimaal wachtwoord begint nooit
  met `-`. Het blijft 24 willekeurige bytes (192 bit), nu als 48 tekens. Het demo-script
  `demo/environment/stelselregister/pki/maak-keten.sh` doet hetzelfde al met `openssl rand -hex 24`.

Bewust niet:

- **Het wachtwoord via `-storepass:env` meegeven** in plaats van als argument. Dat haalt elke waarde
  uit de argumenten, ook voor wat er later bijkomt, maar vraagt een tweede parameter op `keytool()`
  en `opslag()` en aanpassingen in `Testketens`. Voor een keystore die alleen in ontwikkel- en
  testmodus bestaat en direct na het laden wordt opgeruimd, weegt dat niet op tegen de wijziging van
  één regel. De andere argumenten (alias, dname, pad) zijn vast of komen uit een tijdelijke map, en
  beginnen nooit met `-J`.

## Tests

- `WegwerpketenTest` (unit): 1000 wachtwoorden bestaan elk uit precies 48 hexadecimale tekens en
  zijn allemaal verschillend. Met de Base64-URL-variant faalt de test direct, want daar zitten
  hoofdletters, `-` en `_` in.
- De bestaande tests van het stelselregister draaien `keytool` met het nieuwe wachtwoord via
  `Testketens` en `Wegwerpketen.alsOndertekensleutel`.

## Verificatie

- `./mvnw clean test -pl services/stelselregister -am` en `detekt:check`.
