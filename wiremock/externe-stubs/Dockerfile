# WireMock-stubs voor externe diensten in ZAD-deploys: de Profiel-service
# (GET partij) én de Notificatie-dienst (CloudEvents-webhook POST /events). Beide
# zijn (nog) niet bereikbaar, dus draait er per deployment een stub met dezelfde
# mappings als lokaal (compose bind-mount kan niet in ZAD → mappings hier in het
# image gebakken). Eén image, als twee losse componenten gedeployd (profiel +
# notificatie); elk component krijgt op zijn eigen subdomein alleen zijn eigen
# verkeer, de overige mappings staan ongebruikt mee. WireMock serveert op poort
# 8080 en leest /home/wiremock/mappings.
FROM wiremock/wiremock:3.13.2
COPY mappings /home/wiremock/mappings
