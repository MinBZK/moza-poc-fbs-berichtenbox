# Profiel-service WireMock-stub voor ZAD-deploys. De officiële Profiel-service is
# (nog) niet bereikbaar, dus draait er per deployment een stub met dezelfde mappings
# als lokaal (compose bind-mount kan niet in ZAD → mappings hier in het image gebakken).
# WireMock serveert op poort 8080 en leest /home/wiremock/mappings.
FROM wiremock/wiremock:3.13.2
COPY mappings /home/wiremock/mappings
