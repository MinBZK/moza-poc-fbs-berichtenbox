# Demo-App Manager — demo-omgeving

De sleutel waarmee de demo-App Manager (`demo/app-manager`, OIN `00000000000000001100`)
credentials ondertekent, en de regels waarmee het stelselregister hem in het stelseldocument zet.

| Pad | Inhoud |
|---|---|
| [`pki/maak-sleutel.sh`](pki/maak-sleutel.sh) | maakt een P-256-sleutel als die er nog niet is, en elke keer de properties voor het stelselregister |
| `pki/out/app-manager/sleutel.pem` | private en publieke sleutel voor de App Manager; buiten git en alleen voor de eigenaar leesbaar |
| `pki/out/stelselregister/app-managers.properties` | alleen de publieke coördinaten, voor `SMALLRYE_CONFIG_LOCATIONS` van het stelselregister; buiten git |

`demo/up.sh` roept het script zelf aan, en compose mount alleen `pki/out/stelselregister/` in het
stelselregister. Wie de stack met de hand start, draait het script één keer vooraf. Zonder dat
script blijft `app_managers` in het stelseldocument leeg.

Een bestaande sleutel blijft staan. Voor een nieuwe: gooi `pki/out/` weg, draai het script en
herstart stelselregister en App Manager. Het stelselregister leest de properties alleen bij de start.

## De App Manager met deze sleutel starten

Zonder sleutel ondertekent de App Manager in ontwikkelmodus met een wegwerpsleutel die het
stelseldocument niet kent. Geef hem daarom het pad mee:

```bash
./mvnw install -pl demo/app-manager -am -DskipTests
# zonder -am: anders start de personadienst mee
APP_MANAGER_SLEUTEL_PAD="$PWD/demo/environment/app-manager/pki/out/app-manager/sleutel.pem" \
  ./mvnw quarkus:dev -pl demo/app-manager
```

Bij de start logt hij `kid`, `x` en `y`. Die zijn gelijk aan de regels in
`pki/out/stelselregister/app-managers.properties`, en aan wat het stelseldocument noemt. OIN, `kid`
en geldigheid in het script zijn de defaults van de App Manager. Zet je die bij de App Manager
anders, dan wijst het document niet meer naar zijn sleutel.

De demo-App Manager authenticeert niemand. Hij hoort daarom alleen in een document met
`STELSELDOCUMENT_OMGEVING=demo`, zoals compose dat zet. Op ZAD draait geen App Manager, en daar
blijft de lijst leeg.
