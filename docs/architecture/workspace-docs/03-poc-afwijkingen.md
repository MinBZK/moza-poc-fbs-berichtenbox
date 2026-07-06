# PoC-afwijkingen t.o.v. de doel-architectuur

Het model beschrijft de doel-architectuur van het Federatief Berichtenstelsel. De
Proof of Concept werkt toe naar dat **volledige** doel — geen bewust afgebakende
deelverzameling. Eén samenhangend deel is nog niet gerealiseerd: authenticatie en
autorisatie over de hele keten. Daarvoor is nog geen oplossingsrichting, dus dat
stellen we uit en werken we door aan wat we wél in de hand hebben. Deze pagina houdt
bij wat daardoor nu nog niet gebouwd is, zodat het model leesbaar blijft als doelbeeld
zonder te suggereren dat alles al gebouwd is.

## Nog niet gerealiseerd: authenticatie, autorisatie en vertrouwen over de keten

Deze onderdelen zijn gemodelleerd als doelbeeld, maar wachten op een
oplossingsrichting voor keten-brede authenticatie en autorisatie binnen MOZa.

- **Interactielaag, DigiD, eHerkenning** — authenticatie en token-uitgifte (OIDC NL GOV)
  zijn gemodelleerd maar nog niet gerealiseerd. Er is geen browser-/portaal-laag.
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
