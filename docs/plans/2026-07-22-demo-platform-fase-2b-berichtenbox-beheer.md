**Status:** Alle taken uitgevoerd — browser-runtime-verificatie (Docker) openstaand

> **Lokaal geverifieerd:** `berichtenbox.js` door `node --check` na elke taak; demo-console
> bouwt met de resources in de jar; 11 generator-tests groen (geen nieuwe Kotlin, detekt
> onveranderd). Taak 3 en 4 in één commit (wegwerp-`verwijder`-stub overgeslagen).
>
> **Nog te doen (Docker):** rebuild + herstart, dan per persona: zijbalk (Postvak IN +
> mappen + Archief met tellingen), sorteren (4 opties, geen nieuwe `/berichten`-call),
> ongelezen-filter, verplaatsen naar (nieuwe/bestaande) map, archiveren, verwijderen met
> bevestiging. Let op het te bevestigen punt: dat `PATCH map`/`DELETE` meteen in
> `GET /berichten` doorkomen (bij `PATCH status` werkte dat in 2a).

# Demo-platform fase 2b — Berichtenbox-UI (beheer) — implementatieplan

> **Voor agentic workers:** VERPLICHTE SUB-SKILL: gebruik `superpowers:subagent-driven-development`
> of `superpowers:executing-plans`. Stappen gebruiken checkbox-syntax (`- [ ]`).

**Ontwerp:** `docs/plans/2026-07-22-demo-platform-fase-2b-berichtenbox-beheer-design.md`

**Doel:** het beheer-deel toevoegen aan de Berichtenbox-UI: mappen-zijbalk, sorteren,
filteren, verplaatsen, archiveren en verwijderen.

**Architectuur:** uitbreiding van `berichtenbox.html/js/css` in demo-console. Geen
backend-wijziging: verplaatsen/archiveren via de bestaande `PATCH {map}`, verwijderen via
`DELETE`, sorteren/filteren client-side op een in-memory berichten-array.

## Global Constraints

- **Geen backend/Kotlin-wijziging.** Alleen statische resources. detekt niet geraakt.
- **`map` kan niet leeg gemaakt worden.** Per contract behandelt merge-patch `map:null` als
  "niet wijzigen" en `map:""` faalt (minLength 1). Verplaatsen naar een map/Archief kan dus
  wél, terug naar Postvak IN (map wissen) **niet**. Bewuste, gedocumenteerde grens.
- **`magazijnId` per bericht** (uit fase 2a, `magazijnPerBericht`) is vereist bij `PATCH`/`DELETE`.
- **Reserveringen:** map `"Archief"` = archief; afwezige `map` = Postvak IN.
- **Altijd `clean` vóór `test`.** **Nooit direct naar `main`.**

## Bestandsoverzicht

| Bestand | Actie |
|---|---|
| `.../META-INF/resources/berichtenbox.html` | zijbalk + werkbalk toevoegen |
| `.../berichtenbox.css` | twee-koloms lay-out, zijbalk, werkbalk |
| `.../berichtenbox.js` | state-array, mappen, sorteren/filteren, beheer-acties |

## Verificatie zonder Docker

`node --check` op de JS en `./mvnw clean test -pl services/demo-console` (geen nieuwe
Kotlin, dus onveranderd groen). De beheer-flow zelf is handmatig tegen de draaiende stack.

---

### Taak 1: State-array, mappen-zijbalk en mapfilter

De structurele basis: de geladen berichten in een array houden, en de lijst renderen via
een mapfilter. Zonder deze refactor kan geen van de latere taken client-side werken.

**Files:** `berichtenbox.html`, `berichtenbox.css`, `berichtenbox.js`

**Interfaces:**
- Produceert (voor taak 2-4): `alleBerichten` (array), `actieveMap` (state),
  `mapVan(bericht)`, `herteken()`, `tekenLijst(berichten)`, `zichtbareBerichten()`,
  `mapNamen()`.

- [ ] **Stap 1: Herstructureer de `<main>` in `berichtenbox.html`**

Vervang het bestaande `<main>`-blok:

```html
  <main class="werkblad">
    <nav id="mappen" class="mappen"></nav>
    <section class="inhoud">
      <div class="werkbalk">
        <label>Sorteer
          <select id="sorteer">
            <option value="datum-nieuw">Datum (nieuw → oud)</option>
            <option value="datum-oud">Datum (oud → nieuw)</option>
            <option value="onderwerp">Onderwerp A–Z</option>
            <option value="afzender">Afzender A–Z</option>
          </select>
        </label>
        <label><input type="checkbox" id="alleen-ongelezen"> alleen ongelezen</label>
        <span class="demo-label">demo — sorteren/filteren gebeurt in de browser</span>
      </div>
      <ul id="lijst" class="lijst"></ul>
      <p id="lijst-leeg" class="melding" hidden></p>
      <article id="detail" class="detail" hidden></article>
    </section>
  </main>
```

- [ ] **Stap 2: Voeg de lay-out toe aan `berichtenbox.css`**

```css
.werkblad { display: flex; gap: 1.5rem; align-items: flex-start; }
.mappen { flex: 0 0 12rem; list-style: none; }
.mappen button { display: flex; justify-content: space-between; width: 100%; border: none;
  background: none; text-align: left; padding: 0.5rem; cursor: pointer; font-size: 0.95rem; border-radius: 4px; }
.mappen button:hover { background: #f0f6ff; }
.mappen button.actief { background: #154273; color: #fff; }
.mappen .telling { color: inherit; opacity: 0.7; }
.inhoud { flex: 1 1 auto; min-width: 0; }
.werkbalk { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; margin-bottom: 0.5rem; }
.demo-label { color: #777; font-size: 0.8rem; font-style: italic; margin-left: auto; }
```

- [ ] **Stap 3: Voeg module-state en de render-pijplijn toe aan `berichtenbox.js`**

Direct ná de bestaande `const magazijnNamen = new Map();` (bovenin) toevoegen:

```javascript
// Laatst geladen lijst — bron voor client-side sorteren/filteren zonder nieuwe server-call.
let alleBerichten = [];
// Actieve map: null = Postvak IN, 'Archief' = archief, anders een mapnaam.
let actieveMap = null;
let sortering = 'datum-nieuw';
let alleenOngelezen = false;

// Absente map telt als Postvak IN (null).
function mapVan(bericht) {
  return bericht.map || null;
}

function mapNamen() {
  return [...new Set(alleBerichten.map(mapVan).filter((m) => m !== null))];
}
```

- [ ] **Stap 4: Vervang `laadLijst` en `renderLijst`**

Vervang de volledige functies `laadLijst` en `renderLijst` door:

```javascript
async function laadLijst() {
  const respons = await api('/berichten');

  if (respons.status === 409) {
    alleBerichten = [];
    toonLeeg('Nog niet opgehaald — klik op Ophalen.');

    return;
  }

  if (!respons.ok) {
    toonLeeg(`Lijst laden mislukt (HTTP ${respons.status}).`, true);

    return;
  }

  const lijst = await respons.json();

  alleBerichten = lijst.berichten || [];
  magazijnPerBericht.clear();
  alleBerichten.forEach((bericht) => magazijnPerBericht.set(bericht.berichtId, bericht.magazijnId));
  herteken();
}

// Her-rendert zijbalk + lijst uit de in-memory array (na laden, sorteren, filteren of actie).
function herteken() {
  // Een map die leeg is geraakt bestaat niet meer → val terug op Postvak IN.
  if (actieveMap !== null && actieveMap !== 'Archief' && !mapNamen().includes(actieveMap)) {
    actieveMap = null;
  }

  renderMappen();
  tekenLijst(zichtbareBerichten());
}

function zichtbareBerichten() {
  let lijst = alleBerichten.filter((bericht) => mapVan(bericht) === actieveMap);

  if (alleenOngelezen) {
    lijst = lijst.filter((bericht) => bericht.status !== 'gelezen');
  }

  return sorteer(lijst);
}

function sorteer(lijst) {
  const kopie = [...lijst];

  switch (sortering) {
    case 'datum-oud':
      return kopie.sort((a, b) => a.publicatietijdstip.localeCompare(b.publicatietijdstip));

    case 'onderwerp':
      return kopie.sort((a, b) => a.onderwerp.localeCompare(b.onderwerp, 'nl'));

    case 'afzender':
      return kopie.sort((a, b) => afzenderNaam(a).localeCompare(afzenderNaam(b), 'nl'));

    default:
      return kopie.sort((a, b) => b.publicatietijdstip.localeCompare(a.publicatietijdstip));
  }
}

function tekenLijst(berichten) {
  const ul = el('lijst');

  toon(el('detail'), false);
  ul.innerHTML = '';

  if (berichten.length === 0) {
    toonLeeg('Geen berichten in deze map.');

    return;
  }

  toon(el('lijst-leeg'), false);
  berichten.forEach((bericht) => ul.appendChild(lijstItem(bericht)));
}
```

- [ ] **Stap 5: Voeg `renderMappen` en `kiesMap` toe**

Voeg toe (bij de andere renderfuncties):

```javascript
function renderMappen() {
  const tellingen = new Map();
  let postvakIn = 0;
  let archief = 0;

  alleBerichten.forEach((bericht) => {
    const map = mapVan(bericht);

    if (map === null) {
      postvakIn += 1;
    } else if (map === 'Archief') {
      archief += 1;
    } else {
      tellingen.set(map, (tellingen.get(map) || 0) + 1);
    }
  });

  const nav = el('mappen');

  nav.innerHTML = '';
  nav.appendChild(mapKnop('Postvak IN', null, postvakIn));

  [...tellingen.keys()].sort((a, b) => a.localeCompare(b, 'nl')).forEach((naam) => {
    nav.appendChild(mapKnop(naam, naam, tellingen.get(naam)));
  });

  if (archief > 0) {
    nav.appendChild(mapKnop('Archief', 'Archief', archief));
  }
}

function mapKnop(label, mapWaarde, telling) {
  const knop = document.createElement('button');

  if (mapWaarde === actieveMap) knop.classList.add('actief');

  const naam = document.createElement('span');

  naam.textContent = label;

  const badge = document.createElement('span');

  badge.className = 'telling';
  badge.textContent = telling > 0 ? String(telling) : '';

  knop.append(naam, badge);
  knop.addEventListener('click', () => kiesMap(mapWaarde));

  return knop;
}

function kiesMap(mapWaarde) {
  actieveMap = mapWaarde;
  herteken();
}
```

- [ ] **Stap 6: `node --check` en build**

Run: `node --check services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js`
Expected: geen output (exit 0).
Run: `./mvnw -B clean package -DskipTests -pl services/demo-console 2>&1 | grep -iE "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Stap 7: Handmatige verificatie (Docker)**

Na rebuild + herstart, ophalen als een persona met data: de zijbalk toont "Postvak IN" met
telling; de lijst toont alleen Postvak IN-berichten. (Er zijn nog geen mappen/archief tot je
in taak 3 verplaatst.) Klikken op Postvak IN blijft werken.

- [ ] **Stap 8: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources
git commit -m "feat(demo-console): mappen-zijbalk + in-memory lijst-state

Berichten in een array; zijbalk toont Postvak IN/mappen/Archief met tellingen en
filtert de lijst. Basis voor client-side sorteren/filteren en beheer-acties."
```

---

### Taak 2: Sorteren en ongelezen-filter

**Files:** `berichtenbox.js`

**Interfaces:** consumeert `sortering`, `alleenOngelezen`, `herteken` (taak 1).

- [ ] **Stap 1: Koppel de werkbalk-besturing**

Voeg toe bij de andere `addEventListener`-regels (onderaan, naast `el('ophalen')...`):

```javascript
el('sorteer').addEventListener('change', (gebeurtenis) => {
  sortering = gebeurtenis.target.value;
  herteken();
});

el('alleen-ongelezen').addEventListener('change', (gebeurtenis) => {
  alleenOngelezen = gebeurtenis.target.checked;
  herteken();
});
```

- [ ] **Stap 2: `node --check` en build**

Run: `node --check services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js`
Expected: exit 0.

- [ ] **Stap 3: Handmatige verificatie (Docker)**

Sorteeropties wijzigen de volgorde zonder netwerkverkeer (zichtbaar in de dev-tools Network-tab:
geen nieuwe `/berichten`-call). "Alleen ongelezen" verbergt gelezen berichten.

- [ ] **Stap 4: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js
git commit -m "feat(demo-console): sorteren en ongelezen-filter (client-side)"
```

---

### Taak 3: Verplaatsen naar map en archiveren

**Files:** `berichtenbox.js`

**Interfaces:**
- Consumeert: `api`, `magazijnVan`, `laadLijst`, `mapNamen` (taak 1).
- Produceert: `schrijfMap(berichtId, map)`, `verplaats(berichtId, huidigeMap)`,
  `archiveer(berichtId)`, en een acties-blok in het detail.

- [ ] **Stap 1: Voeg de map-acties toe**

Voeg toe (bij de andere acties, na `markeer`):

```javascript
// PATCH {map}. Merge-patch kan `map` niet wissen (null = niet wijzigen), dus verplaatsen
// gaat alleen náár een map/Archief, niet terug naar Postvak IN.
async function schrijfMap(berichtId, map) {
  const magazijnId = magazijnVan(berichtId);

  const respons = await api(`/berichten/${berichtId}?magazijnId=${encodeURIComponent(magazijnId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json' },
    body: JSON.stringify({ map }),
  });

  if (!respons.ok) {
    alert(`Verplaatsen mislukt (HTTP ${respons.status}).`);

    return;
  }

  await laadLijst();
}

function verplaats(berichtId, huidigeMap) {
  const bestaande = mapNamen().filter((m) => m !== 'Archief');
  const suggestie = huidigeMap && huidigeMap !== 'Archief' ? huidigeMap : '';

  const naam = prompt(
    'Naar welke map verplaatsen?' + (bestaande.length ? '\nBestaand: ' + bestaande.join(', ') : ''),
    suggestie,
  );

  if (naam && naam.trim()) {
    schrijfMap(berichtId, naam.trim());
  }
}

function archiveer(berichtId) {
  schrijfMap(berichtId, 'Archief');
}
```

- [ ] **Stap 2: Voeg een acties-blok toe aan het detail**

Voeg een helper toe en roep die aan in `toonDetail`. Eerst de helper (bij de detail-helpers):

```javascript
function detailActies(bericht) {
  const div = document.createElement('div');

  div.className = 'acties';

  const verplaatsKnop = document.createElement('button');

  verplaatsKnop.textContent = 'Verplaats naar map…';
  verplaatsKnop.addEventListener('click', () => verplaats(bericht.berichtId, mapVan(bericht)));

  const archiefKnop = document.createElement('button');

  archiefKnop.textContent = 'Archiveer';
  archiefKnop.addEventListener('click', () => archiveer(bericht.berichtId));

  const verwijderKnop = document.createElement('button');

  verwijderKnop.textContent = 'Verwijder';
  verwijderKnop.addEventListener('click', () => verwijder(bericht.berichtId));

  div.append(verplaatsKnop, archiefKnop, verwijderKnop);

  return div;
}
```

In `toonDetail`, voeg `detailActies(bericht)` toe aan de `detail.append(...)`-regel, ná
`renderBijlagen(bericht)`:

```javascript
  detail.append(detailKop(bericht), detailInhoud(bericht), renderBijlagen(bericht), detailActies(bericht));
```

`verwijder` wordt in taak 4 gedefinieerd; de knop crasht niet zolang je hem pas na taak 4
gebruikt, maar bouw/commit taak 3 en 4 samen als je de verwijder-knop meteen wilt tonen.
Om een niet-gedefinieerde referentie te vermijden, voeg alvast een tijdelijke stub toe
onderaan (wordt in taak 4 vervangen):

```javascript
// Vervangen in taak 4.
async function verwijder(berichtId) {
  console.log('verwijderen volgt in taak 4', berichtId);
}
```

- [ ] **Stap 3: Styling voor de acties**

Voeg toe aan `berichtenbox.css`:

```css
.detail .acties { margin-top: 1rem; display: flex; gap: 0.5rem; flex-wrap: wrap; }
```

- [ ] **Stap 4: `node --check` en build**

Run: `node --check services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js`
Expected: exit 0. Daarna `./mvnw -B clean package -DskipTests -pl services/demo-console` → `BUILD SUCCESS`.

- [ ] **Stap 5: Handmatige verificatie (Docker)**

Open een bericht → "Verplaats naar map…" → typ "Werk" → het bericht verschijnt in map "Werk"
in de zijbalk en verdwijnt uit Postvak IN. "Archiveer" verplaatst naar Archief. Verplaatsen
naar een bestaande map werkt via de suggestie.

- [ ] **Stap 6: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources
git commit -m "feat(demo-console): verplaatsen naar map en archiveren (PATCH map)"
```

---

### Taak 4: Verwijderen

**Files:** `berichtenbox.js`

**Interfaces:** consumeert `api`, `magazijnVan`, `laadLijst` (taak 1). Vervangt de
`verwijder`-stub uit taak 3.

- [ ] **Stap 1: Vervang de `verwijder`-stub**

```javascript
async function verwijder(berichtId) {
  if (!confirm('Dit bericht definitief verwijderen?')) return;

  const magazijnId = magazijnVan(berichtId);

  const respons = await api(`/berichten/${berichtId}?magazijnId=${encodeURIComponent(magazijnId)}`, {
    method: 'DELETE',
  });

  if (!respons.ok) {
    alert(`Verwijderen mislukt (HTTP ${respons.status}).`);

    return;
  }

  await laadLijst();
}
```

- [ ] **Stap 2: `node --check` en build**

Run: `node --check services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js`
Expected: exit 0. Daarna `./mvnw -B clean package -DskipTests -pl services/demo-console` → `BUILD SUCCESS`.

- [ ] **Stap 3: Handmatige verificatie (Docker)**

Open een bericht → "Verwijder" → bevestig → het verdwijnt uit de lijst en blijft weg na
opnieuw Ophalen. Annuleren laat het staan.

- [ ] **Stap 4: Regressie — volledige suite**

Run: `./mvnw -B clean test -pl services/demo-console -am 2>&1 | tail -10`
Expected: `BUILD SUCCESS` (generator-tests groen; geen nieuwe Kotlin, dus detekt onveranderd
bij een latere `verify`).

- [ ] **Stap 5: Commit**

```bash
git add services/demo-console/src/main/resources/META-INF/resources/berichtenbox.js
git commit -m "feat(demo-console): bericht verwijderen (DELETE met bevestiging)"
```

---

## Definition of done

- [ ] Zijbalk toont Postvak IN + mappen + Archief met tellingen; klikken filtert
- [ ] Sorteren (4 opties) en "alleen ongelezen" werken client-side, zonder nieuwe `/berichten`-call
- [ ] Verplaatsen naar (nieuwe of bestaande) map werkt; het bericht verschuift in de zijbalk
- [ ] Archiveren verplaatst naar Archief
- [ ] Verwijderen vraagt bevestiging en verwijdert het bericht blijvend
- [ ] `node --check` groen; `./mvnw clean test -pl services/demo-console` groen

## Risico's

| Risico | Signaal | Mitigatie |
|---|---|---|
| `PATCH`/`DELETE` niet meteen in `GET /berichten` zichtbaar | Bericht blijft in oude map na actie | Zo nodig `_ophalen` opnieuw aanroepen vóór `laadLijst` in de acties |
| Terug naar Postvak IN gewenst | Gebruiker wil un-archiveren naar inbox | Contract kan `map` niet wissen — gedocumenteerde grens; verplaatsen tussen mappen kan wel |
| Lege map blijft in de zijbalk staan | Map zonder berichten zichtbaar | `herteken` valt terug op Postvak IN en `renderMappen` telt opnieuw uit de array |

## Niet in deze fase

Zoeken (`GET /_zoeken`, kleine losse toevoeging), de rode vlag (fase 7),
storingsscenario's (fase 3). Server-side sorteren/filteren blijft issue #571.
