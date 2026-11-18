# De demo-App Manager in het stelseldocument van de demo-stack

**Status:** Uitgevoerd

## Context

Het stelselregister kan App Managers met hun sleutels publiceren
(`stelseldocument.app-managers."<OIN>"...`), en `demo/app-manager` is de demo-App Manager die
credentials uitgeeft. Maar de demo-stack (`demo/up.sh`, `docker compose --profile demo`) geeft het
stelselregister geen App Manager mee: `app_managers` is daar leeg. Alleen het `%test`-profiel zet
er een.

Er is ook niets om mee te geven. Zonder `app-manager.sleutel-pad` ondertekent de App Manager in
ontwikkelmodus met een wegwerpsleutel, die bij elke start anders is. Een vaste regel in de
configuratie van het stelselregister zou daar nooit bij passen.

## Doel

Na `demo/up.sh up` noemt het stelseldocument de demo-App Manager (`00000000000000001100`), met de
publieke helft van een vaste demosleutel. Een App Manager die met de private helft start,
ondertekent dus met een sleutel die het document aanwijst.

## Ontwerpkeuzes

1. **Een eigen map, `demo/environment/app-manager/`.** De sleutel hoort bij de App Manager en niet
   bij de stelselbeheerder. Daarom komt hij niet in `stelselregister/pki/maak-keten.sh`. Het
   script `pki/maak-sleutel.sh` volgt de vorm van `maak-keten.sh`: openssl, alles buiten git,
   en `up.sh` roept het aan.
2. **Twee uitvoermappen, één per afnemer.** `pki/out/app-manager/sleutel.pem` bevat de private en
   de publieke sleutel (het formaat dat `Ondertekensleutel.leesPem` leest) en is alleen voor de
   eigenaar leesbaar. `pki/out/stelselregister/app-managers.properties` bevat alleen de publieke
   coördinaten en is leesbaar voor de container. Compose mount alleen die tweede map, zodat de
   private sleutel nooit in de container van het stelselregister zit. Het is een map en geen
   bestand, om dezelfde reden als bij de keystore: een ontbrekend bestand wordt bij een bind-mount
   een map met de naam van dat bestand. Start compose zonder het script, dan slaat SmallRye de
   ontbrekende locatie over en start het stelselregister met een lege lijst, zoals voorheen. De
   rookproef vangt dat.
3. **Een bestaande sleutel blijft staan.** Het script maakt alleen een sleutel als er nog geen is.
   De properties maakt het bij elke run opnieuw uit de sleutel, zodat ze er altijd bij horen. Zo
   houden document en App Manager na elke `up.sh` dezelfde sleutel. Wie een nieuwe sleutel wil,
   gooit `pki/out/` weg.
4. **De defaults van de App Manager, vast.** OIN, `kid` en geldigheid zijn de defaults van
   `AppManagerConfig` (`00000000000000001100`, `app-manager-1`, `2099-01-01T00:00:00Z`). Een App
   Manager heeft dan alleen `APP_MANAGER_SLEUTEL_PAD` nodig. Overschrijfbaar maken zou om
   invoercontrole vragen, en niemand heeft een andere waarde nodig.
5. **Coördinaten uit de DER-vorm, met een controle op de kop.** Een P-256-SPKI is altijd 91
   bytes: een vaste kop van 27 bytes (tot en met `04`, ongecomprimeerd), daarna x en y van 32
   bytes elk. Het script controleert die kop en weigert bij een afwijking. Het stelselregister
   controleert bij de start nog of het punt op de curve ligt.
6. **De rookproef controleert het document.** `demo/smoke.sh` krijgt een stap die het
   stelseldocument ophaalt en eist dat de demo-App Manager erin staat met de `x` uit de
   gegenereerde properties. Zo valt een stack die de bedrading kwijtraakt op, en niet pas bij een
   app die geen credential geaccepteerd krijgt.

## Stappen

1. `demo/environment/app-manager/pki/maak-sleutel.sh` en een `README.md` erbij.
2. `compose.yaml`: het stelselregister mount `pki/out/stelselregister` en krijgt het bestand
   erbij in `SMALLRYE_CONFIG_LOCATIONS`; `compose.podman.yaml` krijgt dezelfde mount met `:z`.
3. `demo/up.sh`: het script draaien na `maak-keten.sh`.
4. `demo/smoke.sh`: een stap voor het stelseldocument.
5. Documentatie: `docs/demo-runbook.md` (handmatige start), `demo/environment/stelselregister/README.md`,
   en de App Manager met de vaste sleutel starten.

## Buiten deze PR

- **De App Manager als container in de stack.** Een uitgerold image eist https voor `terug-url`
  en `issuer-url`, ook met `QUARKUS_PROFILE=dev` (`ConfigControle` hangt aan de launch mode), en
  de lokale stack draait op http. De App Manager draait daarom zoals nu in ontwikkelmodus, met
  `APP_MANAGER_SLEUTEL_PAD` op de sleutel uit dit script. Komt hij later als container in de stack,
  dan moet hij de sleutel als bind-mount kunnen lezen; nu is het bestand alleen voor de eigenaar
  leesbaar.
- **De simulator die de App Manager vertrouwt.** Op de branch van het ophalen
  (`simulator.rechtstreeks.app-managers`) bestaat die configuratie al. Daar kan hetzelfde script
  een tweede properties-bestand schrijven.
- **ZAD.** Daar draait geen App Manager. Het stelselregister op ZAD blijft zonder App Managers.

## Verificatie

- `shellcheck` op het script, `up.sh` en `smoke.sh`.
- Het script twee keer draaien: bij de tweede run is de sleutel gelijk en zijn de properties gelijk.
- De x en y uit het script vergelijken met wat openssl zelf over de sleutel zegt (`openssl pkey -text`).
- Het stelselregister als jar starten met de keystore uit `maak-keten.sh` en de gegenereerde
  properties via `SMALLRYE_CONFIG_LOCATIONS`: het document noemt de App Manager met die x en y.
- De App Manager starten met `APP_MANAGER_SLEUTEL_PAD` op de sleutel: de x en y die hij logt, zijn
  gelijk aan die in het document.
- `docker compose config` met de podman-overlays: de nieuwe mount staat erin met `:z`
  (`.github/scripts/selinux-label-guard.sh`).
