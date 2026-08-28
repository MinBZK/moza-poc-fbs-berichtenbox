# Toxiproxy, ongewijzigd doorgepubliceerd onder onze eigen ghcr-namespace.
#
# Waarom: ZAD trekt images via een Quay-pull-through-cache, en die struikelt over de manifest list
# van Toxiproxy. Die bevat vier entries met drie unieke digests — `linux/arm/v6` staat er twee keer
# in, met exact dezelfde digest. Dat geldt voor 2.9.0 tot en met 2.12.0 en voor `latest`, dus het is
# een vaste eigenaardigheid van hun releasepijplijn. Docker en containerd negeren het; Quay schrijft
# per child een rij in `ManifestChild` en die tabel heeft een unique constraint, dus de tweede
# identieke child geeft een IntegrityError die als HTTP 500 naar buiten komt.
#
# Niet de namespace: op hetzelfde cluster draaien pods uit acht andere niet-eigen ghcr-namespaces
# via dezelfde mirror.
#
# Upstream Quay heeft dit gerepareerd (PROJQUAY-10068, 4 augustus 2026), maar die dedupe zit nog in
# geen enkele release — ook niet in v3.17.4. Wachten is dus voorlopig geen route.
#
# Er is ook geen onderhouden alternatief op Docker Hub: `shopify/toxiproxy` staat daar sinds januari
# 2019 stil op 2.1.4, en de overige treffers zijn herbouwsels van derden zonder herkomst.
#
# Dit is hetzelfde patroon als `wiremock/demo-profiel/Dockerfile`: een upstream-image dat wij
# doorpubliceren. De digest erbij maakt de herkomst harder dan een kale tag — een verplaatste tag
# levert dan een bouwfout op in plaats van stilzwijgend een andere image.
#
# Geen eigen inhoud: Toxiproxy start met zijn default-CMD (`-host=0.0.0.0`) op met nul proxies, en de
# demo-console maakt ze daarna zelf aan via de admin-API. Verandert dat, dan hoort de wijziging bij
# de console en niet hier.
#
# Twee routes hieruit, allebei zonder dit bestand. Upstream kan de dubbele child uit zijn manifest
# list halen, of Quay brengt de dedupe in een release. Tot die tijd is er nog een kortere weg die we
# niet beproefd hebben: pinnen op de platform-specifieke child in plaats van op de lijst
# (`ghcr.io/shopify/toxiproxy@sha256:a3e244375123dad8849091bcc59775e188624d3f602db01901f9af855682fef8`
# is de amd64-child van 2.12.0). Dat is een gewoon manifest zonder kinderen, dus Quay struikelt er
# niet over. Prijs: die verwijzing draagt maar één architectuur, dus `compose.yaml` moet de
# multi-arch-tag houden voor wie op arm werkt — en dan bewaakt pin-consistency.yml de twee niet meer
# als één pin.
FROM ghcr.io/shopify/toxiproxy:2.12.0@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e
