# externe-stubs

WireMock-stubs voor externe diensten in ZAD-deploys: de Profiel-service (`POST partij`) en de
Notificatie-dienst (CloudEvents-webhook `POST /events`). Eén image, als twee losse componenten
gedeployd (profiel + notificatie); elk component serveert op zijn eigen subdomein alleen zijn
eigen verkeer. De mappings worden in het image gebakken (een compose bind-mount kan niet in ZAD).

## Alleen synthetische identificatienummers

Dit image is publiek (GHCR) en wordt breed uitgerold. Mappings mogen **nooit** een echt BSN/RSIN
bevatten — alleen synthetische waarden die de elfproef falen (bijv. BSN `111222333`). Controleer
dit bij elke nieuwe of gewijzigde mapping; een echt persoonsgegeven in een publiek image is een
datalek (AVG art. 32).

## Admin-API dicht

Op ZAD staat elke stub achter een publieke route. Daarom zet `admin-dicht.sh` (het entrypoint van
dit image én van `demo-profiel`) bij elke start een wegwerpwachtwoord op `/__admin`; niemand op ZAD
heeft die API nodig. Gezondheid lees je af aan de stub-mapping `GET /gezondheid`, die 200 met
`{"status":"healthy"}` geeft. Lokaal draait compose het kale `wiremock/wiremock`-image met deze
mappings als bind mount, en blijft de admin-API open voor de smoke-scripts.
