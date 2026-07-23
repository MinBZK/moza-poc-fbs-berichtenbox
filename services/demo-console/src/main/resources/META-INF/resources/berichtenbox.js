'use strict';

const BASIS = 'http://localhost:8086/api/v1';

// magazijnId per bericht onthouden — PATCH/DELETE (taak 4/5) vereisen ?magazijnId=.
const magazijnPerBericht = new Map();

// magazijnId (== afzender-OIN) → organisatienaam, gevuld uit de ophaal-events, zodat de UI
// "RVO"/"Belastingdienst" toont i.p.v. de kale OIN.
const magazijnNamen = new Map();

function afzenderNaam(bericht) {
  return magazijnNamen.get(bericht.magazijnId) || bericht.afzender || bericht.magazijnId;
}

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
  if (gebeurtenis.magazijnId && gebeurtenis.naam) {
    magazijnNamen.set(gebeurtenis.magazijnId, gebeurtenis.naam);
  }

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

// Volgende-pagina-pad uit de HAL-links (_links.next); null als dit de laatste pagina is.
// De href is server-absoluut (/api/v1/berichten?pagina=…); api() zet BASIS (…/api/v1) er weer
// vóór, dus we strippen die prefix.
function volgendePad(lijst) {
  const href = lijst._links && lijst._links.next && lijst._links.next.href;

  return href ? href.replace(/^.*\/api\/v1/, '') : null;
}

async function laadLijst() {
  const verzameld = [];
  let pad = '/berichten';
  let paginas = 0;

  while (pad && paginas < 100) {
    const respons = await api(pad);

    if (respons.status === 409) {
      alleBerichten = [];
      toonLeeg('Nog niet opgehaald — klik op Ophalen.');

      return;
    }

    if (!respons.ok) {
      if (verzameld.length === 0) {
        toonLeeg(`Lijst laden mislukt (HTTP ${respons.status}).`, true);

        return;
      }

      break;
    }

    const lijst = await respons.json();

    verzameld.push(...(lijst.berichten || []));
    pad = volgendePad(lijst);
    paginas += 1;
  }

  alleBerichten = verzameld;
  magazijnPerBericht.clear();
  alleBerichten.forEach((bericht) => magazijnPerBericht.set(bericht.berichtId, bericht.magazijnId));
  herteken();
}

// Her-rendert zijbalk + lijst uit de in-memory array (na laden, sorteren, filteren of actie).
function herteken() {
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

function lijstItem(bericht) {
  const li = document.createElement('li');

  if (bericht.status !== 'gelezen') li.classList.add('ongelezen');

  const titel = document.createElement('span');

  const afzender = document.createElement('span');

  afzender.className = 'afzender';
  afzender.textContent = afzenderNaam(bericht);

  const onderwerp = document.createElement('span');

  onderwerp.textContent = bericht.onderwerp;
  titel.append(afzender, onderwerp);

  const meta = document.createElement('span');

  meta.className = 'meta';
  meta.textContent =
    new Date(bericht.publicatietijdstip).toLocaleDateString('nl-NL') +
    (bericht.aantalBijlagen > 0 ? ` 📎${bericht.aantalBijlagen}` : '');

  li.append(titel, meta);
  li.addEventListener('click', () => openBericht(bericht));

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

el('sorteer').addEventListener('change', (gebeurtenis) => {
  sortering = gebeurtenis.target.value;
  herteken();
});

el('alleen-ongelezen').addEventListener('change', (gebeurtenis) => {
  alleenOngelezen = gebeurtenis.target.checked;
  herteken();
});

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

async function toonDetail(berichtId) {
  const respons = await api('/berichten/' + berichtId);

  if (!respons.ok) {
    alert(`Bericht laden mislukt (HTTP ${respons.status}).`);

    return;
  }

  const bericht = await respons.json();
  const detail = el('detail');

  detail.innerHTML = '';
  detail.append(detailKop(bericht), detailInhoud(bericht), renderBijlagen(bericht), detailActies(bericht));
  toon(detail, true);
}

function detailKop(bericht) {
  const h2 = document.createElement('h2');

  h2.textContent = bericht.onderwerp;

  const afz = document.createElement('p');

  afz.className = 'meta';
  afz.textContent = 'Van: ' + afzenderNaam(bericht) + ' — ' + new Date(bericht.publicatietijdstip).toLocaleString('nl-NL');

  const knop = document.createElement('button');

  const isGelezen = bericht.status === 'gelezen';

  knop.textContent = isGelezen ? 'Markeer ongelezen' : 'Markeer gelezen';
  knop.addEventListener('click', () => markeer(bericht.berichtId, isGelezen ? 'ongelezen' : 'gelezen'));

  const frag = document.createDocumentFragment();

  frag.append(h2, afz, knop);

  return frag;
}

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

  // Eerst de lijst verversen (verbergt het detail), dan het detail opnieuw tonen — zo blijft
  // het geopende bericht zichtbaar met de bijgewerkte status.
  await laadLijst();
  await toonDetail(berichtId);
}

// Een bericht openen markeert het als gelezen (alleen hier, niet bij elke her-render van het
// detail, zodat de "markeer ongelezen"-knop blijft werken).
function openBericht(bericht) {
  if (bericht.status !== 'gelezen') {
    markeer(bericht.berichtId, 'gelezen');
  } else {
    toonDetail(bericht.berichtId);
  }
}

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
      downloadBijlage(bericht.berichtId, bijlage.bijlageId, bijlage.naam);
    });
    div.appendChild(knop);
  });

  return div;
}

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
