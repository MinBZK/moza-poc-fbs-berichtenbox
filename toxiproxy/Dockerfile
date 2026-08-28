# Toxiproxy, ongewijzigd doorgepubliceerd onder onze eigen ghcr-namespace.
#
# Waarom: ZAD trekt images via de pull-through-mirror `rcr.rijksapps.nl/ghcr-rig/*`, en die geeft op
# `ghcr.io/shopify/toxiproxy` een HTTP 500 — over meerdere pod-generaties heen, terwijl diezelfde tag
# rechtstreeks bij ghcr.io anoniem gewoon 200 geeft. `ghcr.io/minbzk/*` komt er wél doorheen. Er is
# geen onderhouden alternatief op Docker Hub: `shopify/toxiproxy` staat daar sinds januari 2019 stil
# op 2.1.4, en de overige treffers zijn herbouwsels van derden zonder herkomst.
#
# Dit is hetzelfde patroon als `wiremock/demo-profiel/Dockerfile`: een upstream-image dat wij
# doorpubliceren. De digest erbij maakt de herkomst harder dan een kale tag — een verplaatste tag
# levert dan een bouwfout op in plaats van stilzwijgend een andere image.
#
# Geen eigen inhoud: Toxiproxy start met zijn default-CMD (`-host=0.0.0.0`) op met nul proxies, en de
# demo-console maakt ze daarna zelf aan via de admin-API. Verandert dat, dan hoort de wijziging bij
# de console en niet hier.
#
# Verdwijnt de mirror-storing bij RIG, dan kan dit bestand weg en gaat `TOXIPROXY_IMAGE` in
# deploy.yml terug naar `ghcr.io/shopify/toxiproxy` — de pin blijft dan op één plek staan.
FROM ghcr.io/shopify/toxiproxy:2.12.0@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e
