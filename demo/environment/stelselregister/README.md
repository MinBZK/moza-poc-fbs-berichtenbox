# Stelselregister — demo-omgeving

Wat de demo-omgeving nodig heeft om het stelseldocument te ondertekenen.

| Pad | Inhoud |
|---|---|
| [`pki/maak-keten.sh`](pki/maak-keten.sh) | maakt een root, een ondertekencertificaat eronder en de PKCS#12-keystore voor de dienst |
| `pki/ca/` | de root met zijn sleutel — buiten git, en nooit naar de dienst of het platform |
| `pki/out/` | keystore, wachtwoord en de root voor app-bouwers — buiten git |

```bash
pki/maak-keten.sh            # maakt wat ontbreekt
pki/maak-keten.sh --roteer   # nieuw ondertekencertificaat onder dezelfde root
```

`demo/podman-up.sh` roept het script zelf aan. Wie de stack met `docker compose` start, draait
het één keer vooraf; zonder keystore start de container `stelselregister` niet.

De uitgever in het certificaat is standaard de test-OIN `00000000000000001000` (Logius in deze
omgeving). Een andere waarde gaat via `STELSELDOCUMENT_UITGEVER_OIN` en moet dan ook bij de dienst
gezet worden: die weigert een uitgever die niet in zijn certificaat staat.

De inrichting op ZAD staat in [`../zad-demo/stelselregister.md`](../zad-demo/stelselregister.md);
sleutelbeheer in
[`docs/operator-handleiding-stelselregister.md`](../../../docs/operator-handleiding-stelselregister.md).
