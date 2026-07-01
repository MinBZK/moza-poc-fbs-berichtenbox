# externe-stubs

WireMock-stubs voor externe diensten in ZAD-deploys: de Profiel-service (GET partij) en de
Notificatie-dienst (CloudEvents-webhook `POST /events`). Eén image, als twee losse componenten
gedeployd (profiel + notificatie); elk component serveert op zijn eigen subdomein alleen zijn
eigen verkeer. De mappings worden in het image gebakken (een compose bind-mount kan niet in ZAD).

## Alleen synthetische identificatienummers

Dit image is publiek (GHCR) en wordt breed uitgerold. Mappings mogen **nooit** een echt BSN/RSIN
bevatten — alleen synthetische waarden die de elfproef falen (bijv. BSN `111222333`). Controleer
dit bij elke nieuwe of gewijzigde mapping; een echt persoonsgegeven in een publiek image is een
datalek (AVG art. 32).
