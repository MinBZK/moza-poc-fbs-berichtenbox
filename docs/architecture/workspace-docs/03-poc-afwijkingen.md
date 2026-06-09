# PoC-afwijkingen t.o.v. de doel-architectuur

Het model beschrijft de doel-architectuur van het Federatief Berichtenstelsel. De
huidige Proof of Concept implementeert daarvan een deel; sommige containers en relaties
zijn nog niet (of anders) gerealiseerd. Deze pagina houdt die afwijkingen bij zodat het
model leesbaar blijft als doelbeeld zonder te suggereren dat alles al gebouwd is.

## Niet geïmplementeerd in de PoC

- **Interactielaag, DigiD, eHerkenning** — authenticatie en token-uitgifte (OIDC NL GOV)
  zijn gemodelleerd maar buiten PoC-scope. Er is geen browser-/portaal-laag.
- **Token Validatie** (JWT bearer) in de Berichten Uitvraag Service is nog niet gerealiseerd.
  De ontvanger wordt in de PoC meegegeven via de `X-Ontvanger`-header (`BSN:<waarde>` /
  `RSIN:<waarde>` / OIN), niet via een gevalideerd JWT.
- **BSNk Transformatie en PseudoniemService** (PP → EP per magazijn) — geen pseudonimisering
  in de PoC. Identificatie loopt rechtstreeks via getypeerde ontvanger-identificatienummers.
- **FSC-transportlaag** (Inway/Outway/Manager/Directory, mTLS met PKIoverheid, ondertekende
  contracten) — relaties gemarkeerd als `Digikoppeling REST API via FSC` zijn in de PoC
  gewone HTTP/REST-clients zonder FSC-infrastructuur.
- **AuthZEN PEP/PDP** — de Autorisatie Service is in het model een PEP/PDP-container; de PoC
  doet ontvanger-autorisatie centraal in-process via `opslag/BerichtAutorisatie` in
  berichtenmagazijn (één `vereisOntvanger`-check, geen externe PDP).

## Anders gerealiseerd dan gemodelleerd

- **Bericht Validatie & Autorisatie als in-process CDI, niet als losse REST-services.** Het
  model toont losse containers met `REST API (intern, mTLS)`. In de PoC zijn dit CDI-beans
  binnen berichtenmagazijn; de relaties zijn in-process method-calls. Zie
  [Deployment-units](02-deployment-units.md). Toestemmingscontrole via de Profiel Service
  (`fbs-common/profiel/ProfielServiceClient`) is wél geïmplementeerd.
- **Magazijn-routering via Magazijnregister + MagazijnRouter.** In de Berichten Uitvraag
  Service routeert een `MagazijnRouter` ophaal-/beheer-calls (bijlage-download, status-write)
  naar het juiste magazijn-endpoint. Het `magazijnId` dat door de sessiecache stroomt ís de
  afzender-OIN; `MagazijnRouter` zoekt de bijbehorende inschrijving op in het Magazijnregister
  (1:1 OIN↔magazijn) en bouwt per magazijn een REST-client. Het register is config-backed
  (`magazijnen."<OIN>".{url,naam}`); database-opslag + beheer-interface volgen later.
- **Aanmeld-deduplicatie.** De Aanmeld Service ontdubbelt inkomende aanmeldingen idempotent
  (`RedisAanmeldDeduplicatie`) zodat herhaalde levering van hetzelfde gepubliceerde bericht
  de cache niet dubbel bijwerkt — een implementatiedetail dat het model bewust niet toont.
- **Veerkracht rond magazijn-aggregatie.** De Berichtensessiecache schermt de aggregatie af
  met een semafoor-bulkhead (`MagazijnAggregatieBulkhead`) en een per-magazijn circuit breaker
  (`MagazijnCircuitBreaker`), zodat één traag/onbereikbaar magazijn de overige verzoeken niet
  blokkeert. Deze componenten staan inmiddels in het model.
- **Dataopslag = PostgreSQL.** De doel-architectuur liet de opslagtechniek vrij ("naar keuze");
  de PoC gebruikt PostgreSQL 18 met Hibernate ORM Panache en Flyway-migraties (geen H2). De
  Publicatie Stream is een database-outbox (`SELECT ... FOR UPDATE SKIP LOCKED`); de Retentie
  Service ruimt soft-deleted berichten op via dezelfde lock-strategie.
