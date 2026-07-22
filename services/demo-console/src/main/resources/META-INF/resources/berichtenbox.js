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

  await toonDetail(berichtId);
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
