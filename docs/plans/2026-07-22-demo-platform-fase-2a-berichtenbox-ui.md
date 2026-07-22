**Status:** Concept

# Demo-platform fase 2a — Berichtenbox-UI (lezen) — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> of `superpowers:executing-plans` om dit plan taak voor taak uit te voeren. Stappen gebruiken
> checkbox-syntax (`- [ ]`).

**Ontwerp:** `docs/plans/2026-07-22-demo-platform-fase-2a-berichtenbox-ui-design.md`

**Doel:** een wegwerp demo-frontend op demo-console (:8095) waarmee een persona inlogt,
berichten ophaalt, de lijst ziet, een bericht opent, een bijlage downloadt en gelezen/
ongelezen markeert.

**Architectuur:** drie statische bestanden (`berichtenbox.html/js/css`) in demo-console,
vanilla JS. De pagina roept de uitvraag-API (:8086) cross-origin aan; berichtenuitvraag
krijgt dev-only CORS. `_ophalen` en bijlage-download gaan via `fetch` (niet `EventSource`/
`<a href>`, die kunnen de `X-Ontvanger`-header niet zetten).

**Tech stack:** HTML/CSS/vanilla JS (Fetch + streams), Quarkus static resources.

## Global Constraints

- **Geen wijziging aan `%test`/`%prod`** van berichtenuitvraag. CORS uitsluitend onder `%dev`.
- **Geen nieuwe Kotlin** — puur statische resources + één config-blok. detekt niet geraakt.
- **Uitvraag-base-URL** in de JS: `http://localhost:8086/api/v1` (de browser draait op de
  host; `localhost` klopt bij `quarkus:dev` én in de container via de gepubliceerde poort).
- **Personas (fase 1):** `BSN:999993653` (J. Pietersen), `BSN:123456782` (Bakkerij De
  Vroege Vogel), `KVK:12345678` (Garage Van Dijk B.V.). `X-Ontvanger`-pattern
  `^(BSN|RSIN|KVK|OIN):[0-9]+$`.
- **Contract-invarianten:** `magazijnId` per bericht bewaren (PATCH vereist `?magazijnId=`);
  `status` is `gelezen`/`ongelezen`/afwezig; optionele velden kunnen ontbreken
  (`serialization-inclusion=non_null`) — defensief lezen; 409 op `GET /berichten` vóór
  ophalen netjes afvangen.
- **Altijd `clean` vóór `test`.** **Nooit direct naar `main`.**

## Bestandsoverzicht

| Bestand | Verantwoordelijkheid | Actie |
|---|---|---|
| `services/berichtenuitvraag/src/main/resources/application.properties` | dev-only CORS | Wijzigen |
| `services/demo-console/src/main/resources/META-INF/resources/berichtenbox.html` | markup, persona-kiezer, lay-out | Aanmaken |
| `.../META-INF/resources/berichtenbox.css` | kale styling | Aanmaken |
| `.../META-INF/resources/berichtenbox.js` | API-client, ophaal-stream, rendering, acties | Aanmaken |
| `.../META-INF/resources/index.html` | link naar de berichtenbox toevoegen | Wijzigen |

## Verificatie zonder Docker

De browser-JS is niet zinnig unit-testbaar zonder een browser en er is geen JS-testinfra in
dit repo. Lokaal toetsbaar: de CORS-config via Quarkus-augmentatie en dat `./mvnw clean test`
groen blijft (bewijst `%test`/`%prod` ongemoeid). De browser-flow zelf is pas bewezen na een
handmatige doorloop tegen de draaiende stack (Docker) — elke taak markeert dat expliciet.

---

### Taak 1: Dev-only CORS op berichtenuitvraag

**Files:**
- Wijzigen: `services/berichtenuitvraag/src/main/resources/application.properties`

**Interfaces:**
- Produceert: berichtenuitvraag accepteert cross-origin calls vanaf `http://localhost:8095`
  in `%dev`.

- [ ] **Stap 1: Leg de nulmeting vast**

Run: `./mvnw -B clean test -pl services/berichtenuitvraag -am 2>&1 | tail -15`
Expected: `BUILD SUCCESS`. Noteer `Tests run: N`.

- [ ] **Stap 2: Voeg het CORS-blok toe**

Zoek in `application.properties` het globale security-headers-blok (rond regel 25-35) en voeg
er direct ná toe:

```properties
# Dev-only CORS: de wegwerp Berichtenbox-UI wordt vanaf demo-console (:8095) geserveerd en
# roept deze API cross-origin aan. Alleen %dev — productie/ZAD (%prod/%staging/%acceptatie)
# krijgen geen CORS, dus daar verandert niets. X-Ontvanger is een niet-simpele header en
# lokt een preflight OPTIONS uit; het Quarkus CORS-filter beantwoordt die.
%dev.quarkus.http.cors=true
%dev.quarkus.http.cors.origins=http://localhost:8095
%dev.quarkus.http.cors.methods=GET,POST,PATCH,DELETE,OPTIONS
%dev.quarkus.http.cors.headers=X-Ontvanger,Content-Type
```

- [ ] **Stap 3: Bevestig ongewijzigd testgedrag**

Run: `./mvnw -B clean test -pl services/berichtenuitvraag -am 2>&1 | tail -15`
Expected: `BUILD SUCCESS` met hetzelfde aantal tests als stap 1.

- [ ] **Stap 4: Bevestig dat de diff geen `%test`/`%prod` raakt**

Run: `git diff services/berichtenuitvraag/src/main/resources/application.properties | grep -E '^[-+]' | grep -viE '^\+#|^[-+]{3}'`
Expected: uitsluitend toegevoegde `%dev.quarkus.http.cors*`-regels.

- [ ] **Stap 5: Commit**

```bash
git add services/berichtenuitvraag/src/main/resources/application.properties
git commit -m "config(uitvraag): dev-only CORS voor de demo Berichtenbox-UI

Alleen %dev; %prod/%staging/%acceptatie ongemoeid. Zodat de UI op :8095 de
uitvraag-API op :8086 cross-origin mag aanroepen."
```

---

### Taak 2: Pagina-scaffold, persona-kiezer en ophaal-flow met lijst

Grootste taak: de HTML-schil, de persona-dropdown, de `_ophalen`-stream met voortgang, en
daarna `GET /berichten` met lijstweergave. Levert de eerste demobare mijlpaal.

**Files:**
- Aanmaken: `services/demo-console/src/main/resources/META-INF/resources/berichtenbox.html`
- Aanmaken: `.../berichtenbox.css`
- Aanmaken: `.../berichtenbox.js`

**Interfaces:**
- Produceert (JS-functies waarop taak 3-5 voortbouwen): `api(pad, opties)`,
  `huidigeOntvanger()`, `ophalen()`, `laadLijst()`, `renderLijst(berichten)`,
  `magazijnVan(berichtId)`, `toon(elementId)`.

- [ ] **Stap 1: Schrijf de HTML**

`berichtenbox.html`:

```html
<!doctype html>
<html lang="nl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Berichtenbox (demo)</title>
  <link rel="stylesheet" href="berichtenbox.css">
</head>
<body>
  <header>
    <h1>Berichtenbox</h1>
    <div class="persona">
      <label>Ingelogd als
        <select id="persona">
          <option value="BSN:999993653">J. Pietersen (ZZP)</option>
          <option value="BSN:123456782">Bakkerij De Vroege Vogel</option>
          <option value="KVK:12345678">Garage Van Dijk B.V.</option>
        </select>
      </label>
      <button id="ophalen">Ophalen</button>
      <a href="index.html">&rarr; bedieningspaneel</a>
    </div>
  </header>

  <section id="voortgang" class="voortgang" hidden></section>

  <main>
    <ul id="lijst" class="lijst"></ul>
    <p id="lijst-leeg" class="melding" hidden></p>
    <article id="detail" class="detail" hidden></article>
  </main>

  <script src="berichtenbox.js"></script>
</body>
</html>
```

- [ ] **Stap 2: Schrijf de CSS**

`berichtenbox.css`:

```css
* { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; margin: 0; color: #1a1a1a; }
header { background: #154273; color: #fff; padding: 1rem 1.5rem; }
header h1 { margin: 0 0 0.5rem; font-size: 1.4rem; }
.persona { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; }
.persona a { color: #a6c8ff; margin-left: auto; }
select, button { font-size: 1rem; padding: 0.4rem 0.6rem; }
main { padding: 1.5rem; max-width: 50rem; }
.voortgang { padding: 0.75rem 1.5rem; background: #f4f4f4; font-family: monospace; white-space: pre-wrap; }
.lijst { list-style: none; padding: 0; margin: 0; }
.lijst li { border-bottom: 1px solid #ddd; padding: 0.75rem 0.5rem; cursor: pointer; display: flex; gap: 0.5rem; }
.lijst li:hover { background: #f0f6ff; }
.lijst .ongelezen { font-weight: 700; }
.lijst .meta { color: #555; font-size: 0.85rem; margin-left: auto; white-space: nowrap; }
.melding { color: #555; }
.detail { border: 1px solid #ccc; padding: 1rem; margin-top: 1rem; }
.detail h2 { margin-top: 0; }
.detail .inhoud { white-space: pre-wrap; }
.detail .bijlagen a { display: inline-block; margin: 0.25rem 0.5rem 0 0; }
.fout { color: #b00; }
```

- [ ] **Stap 3: Schrijf de JS (client, ophaal-stream, lijst)**

`berichtenbox.js`:

```javascript
'use strict';

const BASIS = 'http://localhost:8086/api/v1';

// magazijnId per bericht onthouden — PATCH/DELETE (taak 4/5) vereisen ?magazijnId=.
const magazijnPerBericht = new Map();

const el = (id) => document.getElementById(id);

function huidigeOntvanger() {
  return el('persona').value;
}

function magazijnVan(berichtId) {
  return magazijnPerBericht.get(berichtId);
}

function toon(element, zichtbaar) {
  element.hidden = !zichtbaar;
}

// Fetch-helper: zet altijd de X-Ontvanger-header en de basis-URL.
async function api(pad, opties = {}) {
  const headers = Object.assign({ 'X-Ontvanger': huidigeOntvanger() }, opties.headers || {});

  return fetch(BASIS + pad, Object.assign({}, opties, { headers }));
}

function toonVoortgang(regels) {
  const vak = el('voortgang');

  toon(vak, true);
  vak.textContent = regels.join('\n');
}

// _ophalen via fetch (EventSource kan de X-Ontvanger-header niet zetten). De stream is
// text/event-stream; elk frame heeft een data:-regel met één MagazijnEvent als JSON.
async function ophalen() {
  const regels = ['Ophalen gestart…'];

  toonVoortgang(regels);

  let respons;

  try {
    respons = await api('/berichten/_ophalen');
  } catch (fout) {
    toonVoortgang(['Netwerkfout bij ophalen: ' + fout]);

    return;
  }

  if (!respons.ok) {
    const detail = await leesProblem(respons);

    toonVoortgang([`Ophalen mislukt (HTTP ${respons.status}): ${detail}`]);

    return;
  }

  const lezer = respons.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let klaar = false;

  while (!klaar) {
    const { done, value } = await lezer.read();

    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    let scheiding;

    while ((scheiding = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, scheiding);

      buffer = buffer.slice(scheiding + 2);

      const dataRegel = frame.split('\n').find((r) => r.startsWith('data:'));

      if (dataRegel) {
        const gebeurtenis = JSON.parse(dataRegel.slice(5).trim());

        klaar = verwerkOphaalEvent(gebeurtenis, regels) || klaar;
      }
    }
  }

  await laadLijst();
}

// Werkt de voortgangsregels bij; geeft true terug bij een terminaal event.
function verwerkOphaalEvent(gebeurtenis, regels) {
  switch (gebeurtenis.event) {
    case 'magazijn-bevraging-gestart':
      regels.push(`${gebeurtenis.naam || gebeurtenis.magazijnId}: bevragen…`);
      break;

    case 'magazijn-bevraging-voltooid':
      regels.push(
        `${gebeurtenis.naam || gebeurtenis.magazijnId}: ${gebeurtenis.status}` +
          (gebeurtenis.status === 'OK' ? ` (${gebeurtenis.aantalBerichten} berichten)` : ` — ${gebeurtenis.foutmelding || ''}`),
      );
      break;

    case 'ophalen-gereed':
      regels.push(`Klaar: ${gebeurtenis.totaalBerichten} berichten uit ${gebeurtenis.totaalMagazijnen} magazijnen (${gebeurtenis.mislukt || 0} mislukt).`);

      toonVoortgang(regels);

      return true;

    case 'ophalen-fout':
      regels.push(`Ophalen mislukt: ${gebeurtenis.foutmelding}`);

      toonVoortgang(regels);

      return true;

    default:
      break;
  }

  toonVoortgang(regels);

  return false;
}

async function laadLijst() {
  const respons = await api('/berichten');

  if (respons.status === 409) {
    toonLeeg('Nog niet opgehaald — klik op Ophalen.');

    return;
  }

  if (!respons.ok) {
    toonLeeg(`Lijst laden mislukt (HTTP ${respons.status}).`, true);

    return;
  }

  const lijst = await respons.json();

  renderLijst(lijst.berichten || []);
}

function renderLijst(berichten) {
  const ul = el('lijst');

  toon(el('detail'), false);
  ul.innerHTML = '';
  magazijnPerBericht.clear();

  if (berichten.length === 0) {
    toonLeeg('Geen berichten.');

    return;
  }

  toon(el('lijst-leeg'), false);

  berichten.forEach((bericht) => {
    magazijnPerBericht.set(bericht.berichtId, bericht.magazijnId);
    ul.appendChild(lijstItem(bericht));
  });
}

function lijstItem(bericht) {
  const li = document.createElement('li');

  if (bericht.status !== 'gelezen') li.classList.add('ongelezen');

  const titel = document.createElement('span');

  titel.textContent = bericht.onderwerp;

  const meta = document.createElement('span');

  meta.className = 'meta';
  meta.textContent =
    new Date(bericht.publicatietijdstip).toLocaleDateString('nl-NL') +
    (bericht.aantalBijlagen > 0 ? ` 📎${bericht.aantalBijlagen}` : '');

  li.append(titel, meta);
  li.addEventListener('click', () => toonDetail(bericht.berichtId));

  return li;
}

function toonLeeg(tekst, fout) {
  const p = el('lijst-leeg');

  el('lijst').innerHTML = '';
  toon(el('detail'), false);
  toon(p, true);
  p.textContent = tekst;
  p.classList.toggle('fout', Boolean(fout));
}

async function leesProblem(respons) {
  try {
    const body = await respons.json();

    return body.detail || body.title || respons.statusText;
  } catch (fout) {
    return respons.statusText;
  }
}

el('ophalen').addEventListener('click', ophalen);
el('persona').addEventListener('change', () => {
  toon(el('voortgang'), false);
  toonLeeg('Persona gewijzigd — klik op Ophalen.');
});

// toonDetail wordt in taak 3 ingevuld; placeholder zodat de lijst niet crasht.
function toonDetail(berichtId) {
  console.log('detail volgt in taak 3', berichtId);
}
```

- [ ] **Stap 4: Link vanuit het bedieningspaneel**

Voeg in `index.html` (de demo-console) onder de `<h1>` een link toe:

```html
  <p><a href="berichtenbox.html">&rarr; Berichtenbox (ondernemer-weergave)</a></p>
```

- [ ] **Stap 5: Bouw de module (resources in de image)**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD|Building jar"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 6: Handmatige verificatie (Docker)**

Bouw de images (`-Dquarkus.jib.platforms=linux/arm64` op Apple Silicon) en start
`docker compose --profile demo up -d`. Zorg dat er data is via het paneel (basisvulling).
Open <http://localhost:8095/berichtenbox.html>:

- Kies persona `Garage Van Dijk B.V.` (KVK) → klik **Ophalen** → voortgangsregels per
  magazijn verschijnen, eindigend op "Klaar: N berichten…", daarna vult de lijst zich.
- Wissel naar `J. Pietersen` → lijst leegt met "Persona gewijzigd" → Ophalen → andere lijst.
- Klik **Ophalen** vóór data bestaat / op een lege postbus → "Geen berichten".
- (Regressie) Roep zonder ophalen `GET /berichten` niet aan; de UI doet dat pas na ophalen.

- [ ] **Stap 7: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources
git commit -m "feat(demo-console): Berichtenbox-UI — persona, ophalen, lijst

Statische pagina op :8095 met persona-kiezer, _ophalen via fetch (met
voortgang per magazijn) en lijstweergave. Detail/bijlage/gelezen volgen."
```

---

### Taak 3: Detailweergave

**Files:**
- Wijzigen: `.../berichtenbox.js` (vervang de `toonDetail`-placeholder)

**Interfaces:**
- Consumeert: `api`, `toon`, `el` (taak 2).
- Produceert: gevulde `toonDetail(berichtId)`; `renderBijlagen(bericht)` als helper waarop
  taak 4 voortbouwt.

- [ ] **Stap 1: Vervang de `toonDetail`-placeholder**

Vervang de placeholder-functie onderaan `berichtenbox.js` door:

```javascript
async function toonDetail(berichtId) {
  const respons = await api('/berichten/' + berichtId);

  if (!respons.ok) {
    alert(`Bericht laden mislukt (HTTP ${respons.status}).`);

    return;
  }

  const bericht = await respons.json();
  const detail = el('detail');

  detail.innerHTML = '';
  detail.append(detailKop(bericht), detailInhoud(bericht), renderBijlagen(bericht));
  toon(detail, true);
}

function detailKop(bericht) {
  const h2 = document.createElement('h2');

  h2.textContent = bericht.onderwerp;

  const afz = document.createElement('p');

  afz.className = 'meta';
  afz.textContent =
    (bericht.afzender ? 'Van: ' + bericht.afzender + ' — ' : '') +
    new Date(bericht.publicatietijdstip).toLocaleString('nl-NL');

  const frag = document.createDocumentFragment();

  frag.append(h2, afz);

  return frag;
}

function detailInhoud(bericht) {
  const p = document.createElement('p');

  p.className = 'inhoud';
  p.textContent = bericht.inhoud || '(geen inhoud)';

  return p;
}

// Bijlagen: alleen bijlageId + naam zijn gevuld; de download-actie komt in taak 4.
function renderBijlagen(bericht) {
  const div = document.createElement('div');

  div.className = 'bijlagen';

  const bijlagen = bericht.bijlagen || [];

  if (bijlagen.length === 0) return div;

  const kop = document.createElement('strong');

  kop.textContent = 'Bijlagen: ';
  div.appendChild(kop);

  bijlagen.forEach((bijlage) => {
    const knop = document.createElement('a');

    knop.href = '#';
    knop.textContent = bijlage.naam;
    knop.addEventListener('click', (gebeurtenis) => {
      gebeurtenis.preventDefault();
      console.log('download volgt in taak 4', bericht.berichtId, bijlage.bijlageId);
    });
    div.appendChild(knop);
  });

  return div;
}
```

- [ ] **Stap 2: Bouw**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 3: Handmatige verificatie (Docker)**

Na herbouw van het image en herstart: klik in de lijst op een bericht → detail toont
onderwerp, afzender/datum, inhoud, en (indien aanwezig) de bijlage-namen als links.

- [ ] **Stap 4: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js
git commit -m "feat(demo-console): Berichtenbox-detailweergave

Klik op een bericht toont onderwerp, afzender, inhoud en bijlage-namen."
```

---

### Taak 4: Bijlage-download

**Files:**
- Wijzigen: `.../berichtenbox.js` (de bijlage-click-handler in `renderBijlagen`)

**Interfaces:**
- Consumeert: `api` (taak 2).
- Produceert: `downloadBijlage(berichtId, bijlageId, naam)`.

- [ ] **Stap 1: Voeg de download-functie toe en koppel de click-handler**

Voeg onderaan `berichtenbox.js` toe:

```javascript
// Download via fetch (geen <a href>: dat stuurt de X-Ontvanger-header niet mee). De
// respons is binair; we maken er een blob-URL van en triggeren de download programmatisch.
async function downloadBijlage(berichtId, bijlageId, naam) {
  const respons = await api(`/berichten/${berichtId}/bijlagen/${bijlageId}`);

  if (!respons.ok) {
    alert(`Bijlage downloaden mislukt (HTTP ${respons.status}).`);

    return;
  }

  const blob = await respons.blob();
  const url = URL.createObjectURL(blob);
  const anker = document.createElement('a');

  anker.href = url;
  anker.download = naam;
  document.body.appendChild(anker);
  anker.click();
  anker.remove();
  URL.revokeObjectURL(url);
}
```

Vervang in `renderBijlagen` de placeholder-click-handler door:

```javascript
    knop.addEventListener('click', (gebeurtenis) => {
      gebeurtenis.preventDefault();
      downloadBijlage(bericht.berichtId, bijlage.bijlageId, bijlage.naam);
    });
```

- [ ] **Stap 2: Bouw**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 3: Handmatige verificatie (Docker)**

De basisdataset heeft geen bijlagen; voer eerst een bericht mét bijlage op. Snelste weg:
lever handmatig een bericht met een `application/pdf`-bijlage aan bij een magazijn
(`POST /api/v1/berichten` met `bijlagen:[{naam,mimeType:"application/pdf",inhoud:<base64>}]`,
afzender `00000001003214345000`, ontvanger een persona). Ophalen → open het bericht →
klik de bijlage → het bestand downloadt als attachment met de juiste naam.

- [ ] **Stap 4: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js
git commit -m "feat(demo-console): bijlage-download via fetch+blob

Een <a href> stuurt de X-Ontvanger-header niet mee; download daarom via fetch,
blob-URL en een programmatische klik."
```

---

### Taak 5: Gelezen/ongelezen markeren

**Files:**
- Wijzigen: `.../berichtenbox.js` (`toonDetail` breidt uit met een markeer-knop; helper `markeer`)

**Interfaces:**
- Consumeert: `api`, `magazijnVan`, `laadLijst` (taak 2), `toonDetail` (taak 3).
- Produceert: `markeer(berichtId, status)`.

- [ ] **Stap 1: Voeg de markeer-functie toe**

Voeg onderaan `berichtenbox.js` toe:

```javascript
// PATCH vereist ?magazijnId= (uit de lijst bewaard) en content-type merge-patch+json.
async function markeer(berichtId, status) {
  const magazijnId = magazijnVan(berichtId);

  if (!magazijnId) {
    alert('Geen magazijnId bekend — haal eerst de lijst op.');

    return;
  }

  const respons = await api(`/berichten/${berichtId}?magazijnId=${encodeURIComponent(magazijnId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json' },
    body: JSON.stringify({ status }),
  });

  if (!respons.ok) {
    alert(`Markeren mislukt (HTTP ${respons.status}).`);

    return;
  }

  await toonDetail(berichtId);
  await laadLijst();
}
```

- [ ] **Stap 2: Voeg de knop toe in `detailKop`**

Breid `detailKop` uit: neem de huidige status mee en voeg een knop toe die naar de andere
status schakelt. Vervang de `frag.append(h2, afz)`-regel door:

```javascript
  const knop = document.createElement('button');

  const isGelezen = bericht.status === 'gelezen';

  knop.textContent = isGelezen ? 'Markeer ongelezen' : 'Markeer gelezen';
  knop.addEventListener('click', () => markeer(bericht.berichtId, isGelezen ? 'ongelezen' : 'gelezen'));

  frag.append(h2, afz, knop);
```

- [ ] **Stap 3: Bouw**

Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 4: Handmatige verificatie (Docker)**

Open een ongelezen bericht (vetgedrukt in de lijst) → klik **Markeer gelezen** → de knop
wordt "Markeer ongelezen" en het lijstitem is niet meer vet. Andersom werkt ook. Ververs
(Ophalen) en controleer dat de status behouden blijft.

- [ ] **Stap 5: detekt + volledige suite als eindcontrole (Docker)**

Run: `./mvnw -B clean verify -pl services/demo-console,services/berichtenuitvraag -am 2>&1 | tail -20`
Expected: `BUILD SUCCESS`. Geen nieuwe Kotlin, dus detekt onveranderd; dit bevestigt dat de
CORS-config en de resources de bestaande suites niet breken.

- [ ] **Stap 6: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js
git commit -m "feat(demo-console): gelezen/ongelezen markeren

PATCH ?magazijnId= met merge-patch+json; lijst en detail verversen na de wijziging."
```

---

## Definition of done

- [ ] Dev-CORS actief in `%dev`, `%test`/`%prod` ongemoeid; `./mvnw clean test` groen
- [ ] Berichtenbox-pagina bereikbaar op :8095, gelinkt vanuit het bedieningspaneel
- [ ] Persona-kiezer zet `X-Ontvanger`; wisselen werkt
- [ ] Ophalen toont voortgang per magazijn en vult daarna de lijst
- [ ] 409 vóór ophalen en lege postbus tonen nette meldingen (geen crash)
- [ ] Detail toont onderwerp, afzender, inhoud, bijlage-namen
- [ ] Bijlage downloadt als attachment met de juiste naam
- [ ] Gelezen/ongelezen markeren werkt en spiegelt in lijst + detail
- [ ] `./mvnw clean verify -pl services/demo-console,services/berichtenuitvraag -am` groen

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| CORS-preflight faalt op `X-Ontvanger` | Browser blokkeert met CORS-fout in console | `headers`-lijst bevat `X-Ontvanger`; `methods` bevat de gebruikte methodes |
| SSE-frames gesplitst over chunk-grenzen | Voortgang mist events / JSON.parse faalt | Buffer accumuleert tot `\n\n`; frames pas parsen bij complete scheiding |
| KVK-persona levert geen berichten | Lijst leeg voor KVK terwijl BSN's werken | Bekend fase-1-risico; zo nodig persona naar BSN (raakt fase-1-dataset) |
| Bijlage-download opent i.p.v. downloadt | Bestand opent in tab | Server zet `Content-Disposition: attachment`; `a.download` versterkt dit |
| Detail vraagt om `magazijnId` | 404/502 bij PATCH | `magazijnId` uit de lijst bewaard in `magazijnPerBericht`; niet uit detail |

## Niet in deze fase

Verwijderen, sorteren, client-side filteren, eigen mappen en archiveren (fase 2b); de rode
vlag (fase 7); storingsscenario's/Toxiproxy (fase 3). De ophaal-voortgang per magazijn is
bewust al zichtbaar zodat fase 3 daarop kan voortbouwen.

