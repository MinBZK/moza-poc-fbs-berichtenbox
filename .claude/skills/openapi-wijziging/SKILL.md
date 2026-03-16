---
name: openapi-wijziging
description: Wijzig de OpenAPI spec, genereer code, en pas de Kotlin resource aan
---

# OpenAPI-wijziging workflow

Bij elke API-wijziging volg je deze stappen in volgorde:

1. **Wijzig de OpenAPI spec**
   Bewerk `services/berichtensessiecache/src/main/resources/openapi/berichtensessiecache-api.yaml`
   Dit is de bron van waarheid — alle endpoint-, model- en validatiewijzigingen beginnen hier.

2. **Genereer de interfaces**
   ```bash
   ./mvnw compile -pl services/berichtensessiecache
   ```
   Controleer dat de gegenereerde interfaces in `target/generated-sources/openapi/` correct zijn.
   Pas gegenereerde code NOOIT handmatig aan.

3. **Implementeer in Kotlin**
   Pas de resource-klasse aan zodat deze de nieuwe/gewijzigde interface-methode implementeert.
   Volg de functionele package-structuur: `berichten/`, `magazijn/`, `notificatie/`.

4. **Tests schrijven**
   Voeg tests toe voor zowel happy als unhappy paths.
   ```bash
   ./mvnw test -pl services/berichtensessiecache
   ```

5. **Valideer NL API Design Rules**
   Controleer: `/api/v1` prefix, camelCase JSON, `application/problem+json` fouten, `API-Version` header.
