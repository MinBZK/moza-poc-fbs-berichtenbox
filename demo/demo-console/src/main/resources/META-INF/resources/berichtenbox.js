'use strict';

// Adres van de uitvraag-API. De console levert het op /api/demo/omgeving, want de demo draait ook
// op ZAD, waar geen poort 8086 bestaat en het schema https is. Zolang die waarde leeg is (de
// lokale stack) valt de pagina terug op de browser-locatie plus de vaste dev-poort; elk adres
// waarop de demo dan geopend wordt moet in de CORS-allowlist van de uitvraag staan (`DEMO_HOST`).
let BASIS = `http://${window.location.hostname}:8086/api/v1`;

const omgevingGeladen = fetch('/api/demo/omgeving')
  .then(async (respons) => {
    if (respons.ok) return respons.json();

    // Deze pagina komt van de console en staat op een gedeelde omgeving dus achter dezelfde
    // inlogmuur. Zonder dit valt ze stilletjes terug op de dev-poort hieronder en meldt elke knop
    // daarna een onbereikbare uitvraag, terwijl alleen de sessie verlopen is.
    if (await inlogsessieVerlopen(respons.status)) herstelInlogsessie();

    return null;
  })
  .then((omgeving) => {
    if (omgeving && omgeving.uitvraagBasis) BASIS = omgeving.uitvraagBasis;

    // Het hele antwoord teruggeven: laadPersonas leest de keuzelijst uit ditzelfde adres en hoeft
    // hem dan niet nog een keer op te halen.
    return omgeving;
  })
  .catch(() => {
    // Console onbereikbaar: de fallback hierboven blijft staan. Dit mag de pagina niet blokkeren —
    // een unhandled rejection zou elke knop stil laten falen.
    return null;
  });

// `afzenderNaam` is verplicht in de uitvraag-API; de terugval dekt een uitvraag die het contract
// niet nakomt. Dan liever deze tekst dan de kale OIN, die een schermlezer cijfer voor cijfer
// voorleest — en dan struikelt het sorteren niet over undefined.
function afzenderNaam(bericht) {
  return bericht.afzenderNaam || 'Onbekende organisatie';
}

// Laatst geladen lijst — bron voor client-side sorteren/filteren zonder nieuwe server-call.
let alleBerichten = [];
// Actieve map: null = Postvak IN, 'Archief' = archief, anders een mapnaam.
let actieveMap = null;
let sortering = 'datum-nieuw';
let alleenOngelezen = false;
let huidigePagina = 0;

const PAGINA_GROOTTE = 20;

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

function toon(element, zichtbaar) {
  element.hidden = !zichtbaar;
}

// Sessie van de uitvraag, per persona en per tabblad. sessionStorage overleeft een herlaadslag
// maar wordt niet gedeeld tussen tabbladen: elk tabblad is een eigen "inlog", en de uitvraag
// begrenst het aantal gelijktijdige sessies per ontvanger. Het id is een bearer: nooit loggen en
// nooit in een URL.
const sessieSleutel = (ontvanger) => `fbs-sessie:${ontvanger}`;

function leesSessie(ontvanger) {
  try {
    return sessionStorage.getItem(sessieSleutel(ontvanger));
  } catch (fout) {
    return null;
  }
}

function bewaarSessie(ontvanger, sessieId) {
  try {
    sessionStorage.setItem(sessieSleutel(ontvanger), sessieId);
  } catch (fout) {
    // Zonder opslag begint elke herlaadslag een nieuwe sessie; de oude verloopt vanzelf.
  }
}

function vergeetSessie(ontvanger) {
  try {
    sessionStorage.removeItem(sessieSleutel(ontvanger));
  } catch (fout) {
    // Niets om te wissen.
  }
}

// Eén lopende start per ontvanger, zodat de keuzehandler en een gelijktijdige api()-aanroep niet
// elk een sessie openen (het plafond is vijf).
const lopendeStarts = new Map();

// Geeft het sessie-id, of een respons-vormig foutobject. Een bewaard id wordt hergebruikt.
function startSessie(ontvanger) {
  const bewaard = leesSessie(ontvanger);

  if (bewaard) return Promise.resolve({ sessieId: bewaard });

  if (!lopendeStarts.has(ontvanger)) {
    const start = (async () => {
      await omgevingGeladen;

      try {
        let respons;

        try {
          respons = await fetch(BASIS + '/sessies', { method: 'POST', headers: { 'X-Ontvanger': ontvanger } });
        } catch (fout) {
          return { fout: { ok: false, status: 0, netwerkfout: true, melding: 'geen verbinding: ' + fout } };
        }

        if (respons.status === 429) return { fout: { ok: false, status: 429, melding: teVeelSessiesMelding(respons) } };

        if (!respons.ok) return { fout: respons };

        // Een antwoord kwam er wel: een onleesbare body is een fout van de uitvraag, geen verbroken verbinding.
        let sessieId;

        try {
          ({ sessieId } = await respons.json());
        } catch (fout) {
          sessieId = undefined;
        }

        if (typeof sessieId !== 'string' || sessieId === '') {
          return { fout: { ok: false, status: respons.status, melding: `sessie gestart, maar het antwoord is onleesbaar (HTTP ${respons.status})` } };
        }

        bewaarSessie(ontvanger, sessieId);

        return { sessieId };
      } finally {
        lopendeStarts.delete(ontvanger);
      }
    })();

    lopendeStarts.set(ontvanger, start);
  }

  return lopendeStarts.get(ontvanger);
}

// De uitvraag geeft in Retry-After de seconden tot de vroegst verlopende sessie van deze persona.
function teVeelSessiesMelding(respons) {
  const seconden = Number.parseInt(respons.headers.get('Retry-After'), 10);
  const wachten = Number.isFinite(seconden) && seconden > 0
    ? ` of probeer het over ${Math.ceil(seconden / 60)} minuut${seconden > 60 ? 'en' : ''} opnieuw`
    : '';

  return `Te veel open sessies voor deze persona; sluit een ander tabblad${wachten}.`;
}

// Idempotent aan de serverkant (204 ook bij een onbekend id): een mislukte aanroep laat de sessie
// hooguit tot verlopen staan, dus de gebruiker krijgt er geen melding van. Wel in de console, zodat
// wie de demo bedient ziet dat er een sessie tegen het plafond blijft staan. Zonder het sessie-id:
// dat is een geheim.
async function beeindigSessie(ontvanger) {
  const sessieId = leesSessie(ontvanger);

  vergeetSessie(ontvanger);

  if (!sessieId) return;

  await omgevingGeladen;

  try {
    const respons = await fetch(BASIS + '/sessies/huidige', {
      method: 'DELETE',
      headers: { 'X-Ontvanger': ontvanger, 'X-Sessie': sessieId },
    });

    if (!respons.ok) console.warn(`uitloggen van de vorige persona mislukt (HTTP ${respons.status}); de sessie verloopt vanzelf`);
  } catch (fout) {
    console.warn(`uitloggen van de vorige persona mislukt (geen verbinding: ${fout}); de sessie verloopt vanzelf`);
  }
}

// Alleen het eigen foutcontract telt als sessieverlies. Een 401 van een inlogmuur ervoor (HTML) zegt
// niets over de sessie; die vergeten laat de sessie ongebruikt achter tegen het plafond.
async function isSessieOngeldig(respons) {
  if (respons.status !== 401) return false;

  try {
    const body = await respons.clone().json();

    return body.type === 'urn:fbs:fout:sessie-ongeldig';
  } catch (fout) {
    return false;
  }
}

// Fetch-helper: zet altijd de X-Ontvanger- en X-Sessie-header en de basis-URL. Een connection error
// (uitvraag weg, connection halverwege doorgeknipt) komt terug als respons-vormig object in plaats van
// een verworpen promise, zodat elke aanroeper hem via zijn bestaande `respons.ok`-toets ziet.
// Zonder dat blijft de fout als unhandled rejection liggen en doet de knop zichtbaar niets —
// juist de storing die de demo moet tonen, blijft dan onzichtbaar.
// Een 401 betekent dat de uitvraag de sessie niet (meer) kent: het bewaarde id wordt weggegooid en
// de aanroeper beslist of hij opnieuw ophaalt.
// `ontvanger` staat expliciet in de aanroep van een lopende actie: wisselt de persona halverwege,
// dan blijft die actie bij de oorspronkelijke ontvanger en sessie in plaats van die van de nieuwe.
async function api(pad, opties = {}, ontvanger = huidigeOntvanger()) {
  await omgevingGeladen;
  const sessie = await startSessie(ontvanger);

  if (sessie.fout) return sessie.fout;

  const headers = Object.assign({ 'X-Ontvanger': ontvanger, 'X-Sessie': sessie.sessieId }, opties.headers || {});

  try {
    const respons = await fetch(BASIS + pad, Object.assign({}, opties, { headers }));

    if (await isSessieOngeldig(respons)) {
      vergeetSessie(ontvanger);
      respons.sessieOngeldig = true;
    }

    return respons;
  } catch (fout) {
    return { ok: false, status: 0, netwerkfout: true, melding: 'geen verbinding: ' + fout };
  }
}

function toonVoortgang(regels) {
  const vak = el('voortgang');

  toon(vak, true);
  vak.textContent = regels.join('\n');
}

// _ophalen via fetch (EventSource kan de X-Ontvanger-header niet zetten). De stream is
// text/event-stream; elk frame heeft een data:-regel met één MagazijnEvent als JSON.
async function ophalen(opnieuwNaVerlopen = false) {
  const regels = ['Ophalen gestart…'];
  const afgekapteMagazijnen = new Set();
  // Wisselt de persona tijdens de ronde, dan hoort niets van deze ronde meer op het scherm en mag
  // een 401 (de sessie is bij het wisselen beëindigd) geen ronde voor de nieuwe persona starten.
  const ontvanger = huidigeOntvanger();
  const verouderd = () => huidigeOntvanger() !== ontvanger;

  toonVoortgang(regels);

  const respons = await api('/berichten/_ophalen', {}, ontvanger);

  if (verouderd()) return;

  // Eén nieuwe poging: api() startte bij de aanroep een verse sessie, dus een tweede 401 is geen
  // verlopen sessie maar iets anders, en een lus zou dat verbergen.
  if (respons.sessieOngeldig && !opnieuwNaVerlopen) return ophalen(true);

  if (!respons.ok) {
    toonVoortgang([`Ophalen mislukt (${await foutTekst(respons)})`]);

    return;
  }

  const lezer = respons.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let klaar = false;

  // Wordt de connection halverwege de stream doorgeknipt, dan verwerpt read() of struikelt
  // JSON.parse over een half frame. Zonder deze catch bevriest het voortgangspaneel op de
  // laatste regel en lijkt het ophalen nog te lopen — precies de storing verzwegen die de
  // demo laat zien. De afbreekregel sluit de voortgang expliciet af.
  try {
    while (!klaar) {
      const { done, value } = await lezer.read();

      if (verouderd()) {
        await lezer.cancel();

        return;
      }

      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      let scheiding;

      while ((scheiding = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, scheiding);

        buffer = buffer.slice(scheiding + 2);

        const dataRegel = frame.split('\n').find((r) => r.startsWith('data:'));

        if (dataRegel) {
          const gebeurtenis = JSON.parse(dataRegel.slice(5).trim());

          klaar = verwerkOphaalEvent(gebeurtenis, regels, afgekapteMagazijnen) || klaar;
        }
      }
    }
  } catch (fout) {
    regels.push('Stream afgebroken: ' + fout);
    toonVoortgang(regels);

    return;
  }

  if (verouderd()) return;

  await laadLijst(true);
}

// Zonder deze toevoeging lijkt een afgekapte lijst een volledige lijst. Het totaal komt alleen mee
// als het magazijn het noemde.
function afkapMelding(gebeurtenis) {
  if (!gebeurtenis.afgekapt) return '';

  return typeof gebeurtenis.totaalBeschikbaar === 'number'
    ? `, van ${gebeurtenis.totaalBeschikbaar} — niet alles opgehaald`
    : ', er zijn er meer — niet alles opgehaald';
}

// De slotregel blijft staan als de regels erboven wegscrollen; hij mag een afgekapte oogst dus niet
// als een volledige ophaalronde melden.
function slotregelAfkap(afgekapteMagazijnen) {
  if (afgekapteMagazijnen.size === 0) return '';

  return ` Niet alles opgehaald bij: ${[...afgekapteMagazijnen].join(', ')}.`;
}

// Organisaties die niet bevraagd zijn omdat de uitvraag te veel werk tegelijk had. Ze horen niet
// bij "mislukt": er is niets stuk en opnieuw ophalen helpt hier wél. Zonder deze eigen zin leest de
// ondernemer een storing waar er geen is.
function slotregelNietOpgehaald(aantal) {
  if (!aantal) return '';

  return ` ${aantal} organisatie${aantal === 1 ? '' : 's'} nog niet opgehaald — probeer het opnieuw.`;
}

// Wat een uitkomst betekent voor de ondernemer. Een status die we niet kennen is per contract
// "niet geleverd, opnieuw proberen kan helpen" — nooit stilzwijgend als geslaagd behandelen, want
// dan verdwijnt een latere toevoeging ongemerkt uit beeld.
function uitkomstRegel(gebeurtenis) {
  const melding = gebeurtenis.foutmelding || '';

  switch (gebeurtenis.status) {
    case 'OK':
      return `${gebeurtenis.aantalBerichten} berichten${afkapMelding(gebeurtenis)}`;

    case 'NIET_OPGEHAALD':
      return `nog niet opgehaald — ${melding || 'probeer het opnieuw'}`;

    case 'FOUT':
    case 'TIMEOUT':
      return `mislukt — ${melding}`;

    default:
      return `niet geleverd — ${melding || 'onbekende uitkomst; probeer het opnieuw'}`;
  }
}

// Werkt de voortgangsregels bij; geeft true terug bij een terminaal event. `afgekapteMagazijnen`
// verzamelt over de stream heen welke organisaties niet alles leverden, voor de slotregel.
function verwerkOphaalEvent(gebeurtenis, regels, afgekapteMagazijnen) {
  switch (gebeurtenis.event) {
    case 'magazijn-bevraging-gestart':
      regels.push(`${gebeurtenis.naam || gebeurtenis.magazijnId}: bevragen…`);
      break;

    case 'magazijn-bevraging-voltooid':
      if (gebeurtenis.status === 'OK' && gebeurtenis.afgekapt) {
        afgekapteMagazijnen.add(gebeurtenis.naam || gebeurtenis.magazijnId);
      }

      regels.push(`${gebeurtenis.naam || gebeurtenis.magazijnId}: ${uitkomstRegel(gebeurtenis)}`);
      break;

    case 'ophalen-gereed':
      regels.push(
        `Klaar: ${gebeurtenis.totaalBerichten} berichten uit ${gebeurtenis.totaalMagazijnen} magazijnen (${gebeurtenis.mislukt || 0} mislukt).` +
          slotregelNietOpgehaald(gebeurtenis.nietOpgehaald) +
          slotregelAfkap(afgekapteMagazijnen),
      );

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

// HAL-hrefs zijn server-absoluut (/api/v1/berichten?pagina=…); api() zet BASIS (…/api/v1) er weer
// vóór, dus we strippen die prefix.
const naarApiPad = (href) => href.replace(/^.*\/api\/v1/, '');

// Volgende-pagina-pad uit de HAL-links (_links.next); null als dit de laatste pagina is.
function volgendePad(lijst) {
  const href = lijst._links && lijst._links.next && lijst._links.next.href;

  return href ? naarApiPad(href) : null;
}

// Events van _volgen die binnenkomen terwijl laadLijst() loopt. Het antwoord van die lezing kan van
// vóór het event zijn en het dan ongedaan maken: een verwijderd bericht stond weer in de lijst. Elke
// lopende lezing bewaart ze daarom en past ze na het vervangen van de lijst nog een keer toe; dat
// mag, want ze zijn idempotent.
const lopendeLezingen = new Set();

async function laadLijst(opnieuwNaVerlopen = false) {
  const tijdensLezing = [];

  lopendeLezingen.add(tijdensLezing);

  try {
    await laadLijstMetEvents(opnieuwNaVerlopen, tijdensLezing);
  } finally {
    lopendeLezingen.delete(tijdensLezing);
  }
}

async function laadLijstMetEvents(opnieuwNaVerlopen, tijdensLezing) {
  const verzameld = [];
  let pad = '/berichten';
  let paginas = 0;
  // Zie ophalen(): een wissel van persona maakt dit resultaat waardeloos, en pagina's van twee
  // ontvangers mogen niet in één lijst belanden.
  const ontvanger = huidigeOntvanger();
  let afgebroken = null;

  while (pad && paginas < 100) {
    const respons = await api(pad, {}, ontvanger);

    if (huidigeOntvanger() !== ontvanger) return;

    if (respons.sessieOngeldig && verzameld.length === 0 && !opnieuwNaVerlopen) {
      await ophalen(true);

      return;
    }

    if (respons.status === 409) {
      alleBerichten = [];
      toonVolledigheid(null);
      toonLeeg('Nog niet opgehaald — klik op Ophalen.');

      return;
    }

    if (!respons.ok) {
      if (verzameld.length === 0) {
        toonVolledigheid(null);
        toonLeeg(`Lijst laden mislukt (${await foutTekst(respons)}).`, true);

        return;
      }

      // Een vervolgpagina strandde (sessie verlopen, storing): toon wat er is, maar zeg dat het
      // niet alles is. Anders lijkt een halve lijst een volledige.
      afgebroken = await foutTekst(respons);

      break;
    }

    const lijst = await respons.json();

    if (huidigeOntvanger() !== ontvanger) return;

    // Volledigheid geldt voor de hele lijst en staat op elke pagina; de eerste volstaat.
    if (paginas === 0) toonVolledigheid(lijst);

    verzameld.push(...(lijst.berichten || []));
    pad = volgendePad(lijst);
    paginas += 1;
  }

  alleBerichten = verzameld;
  tijdensLezing.forEach(pasVolgEventToe);
  herteken();

  if (afgebroken) meldOnvolledig(afgebroken);

  volg(ontvanger);
}

// _volgen houdt de lijst bij terwijl het tabblad openstaat: nieuwe berichten, en wat een ander
// tabblad van dezelfde persona markeert, verplaatst of verwijdert. Via fetch, net als _ophalen.
// Eén stream per tabblad, voor de gekozen persona.
let volgStream = null;

const VOLG_HERVERBIND_MS = 5000;
const VOLG_HERVERBIND_MAX_MS = 60000;

const SESSIE_VERLOPEN_MELDING = 'Sessie verlopen — klik op Vernieuw of Ophalen om verder te gaan.';

function stopVolgen() {
  if (volgStream) volgStream.afbreker.abort();

  volgStream = null;
}

function volg(ontvanger) {
  if (volgStream && volgStream.ontvanger === ontvanger) return;

  stopVolgen();
  volgStream = { ontvanger, afbreker: new AbortController(), eerste: true, mislukt: 0 };
  verbindVolgen(volgStream);
}

// Stopt het volgen met een melding, voor wat opnieuw verbinden niet oplost.
function stopVolgenMet(melding) {
  volgStream = null;
  toonVoortgang([melding]);
}

async function verbindVolgen(stream) {
  const actief = () => volgStream === stream;

  // Zonder bewaarde sessie zou api() er een starten, en een herverbinding is geen activiteit van de
  // gebruiker.
  if (!leesSessie(stream.ontvanger)) {
    volgStream = null;

    return;
  }

  const respons = await api('/berichten/_volgen', { signal: stream.afbreker.signal, headers: { Accept: 'text/event-stream' } }, stream.ontvanger);

  if (!actief()) return;

  // 401 (sessie weg) of 409 (niets opgehaald) lost opnieuw verbinden niet op. De volgende klik van
  // de gebruiker start een sessie of een ophaalronde, en daarmee weer het volgen.
  if (respons.status === 401) {
    stopVolgenMet(SESSIE_VERLOPEN_MELDING);

    return;
  }

  if (respons.status === 409) {
    stopVolgenMet('Live bijwerken gestopt: er is nog niets opgehaald — klik op Ophalen.');

    return;
  }

  if (respons.ok) {
    stream.mislukt = 0;

    if ((await leesVolgStream(respons, stream)) === 'sessie-verlopen') {
      if (actief()) stopVolgenMet(SESSIE_VERLOPEN_MELDING);

      return;
    }
  } else {
    stream.mislukt += 1;
  }

  // Elke andere afloop (maximale duur, herstart van de uitvraag, netwerkfout, 5xx, 429): opnieuw.
  // Alleen de eerste verbinding leest bij volgen-gestart de lijst; zie verwerkVolgEvent().
  stream.eerste = false;
  setTimeout(() => {
    if (actief()) verbindVolgen(stream);
  }, herverbindWachttijd(respons, stream.mislukt));
}

// Na een normaal einde de vaste wachttijd; na elke mislukte poging op rij het dubbele, tot een
// minuut, zodat een uitvraag die even weg is niet overspoeld wordt. Retry-After gaat voor.
function herverbindWachttijd(respons, mislukt) {
  const seconden = respons.headers ? Number.parseInt(respons.headers.get('Retry-After'), 10) : NaN;

  if (Number.isFinite(seconden) && seconden > 0) return seconden * 1000;

  return Math.min(VOLG_HERVERBIND_MS * 2 ** Math.max(mislukt - 1, 0), VOLG_HERVERBIND_MAX_MS);
}

// Geeft het laatste event terug, zodat de aanroeper weet of de sessie verliep.
async function leesVolgStream(respons, stream) {
  const lezer = respons.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let laatste = null;

  try {
    while (volgStream === stream) {
      const { done, value } = await lezer.read();

      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      let scheiding;

      while ((scheiding = buffer.indexOf('\n\n')) >= 0) {
        const dataRegel = buffer.slice(0, scheiding).split('\n').find((r) => r.startsWith('data:'));

        buffer = buffer.slice(scheiding + 2);

        if (dataRegel && volgStream === stream) {
          const gebeurtenis = JSON.parse(dataRegel.slice(5).trim());

          laatste = gebeurtenis.event;
          verwerkVolgEvent(gebeurtenis, stream);
        }
      }
    }
  } catch (fout) {
    // Afgebroken (persona gewisseld, connection weg): de aanroeper beslist of hij opnieuw verbindt.
  }

  return laatste;
}

const sleutelVan = (bericht) => `${bericht.magazijnId} ${bericht.berichtId}`;

function verwerkVolgEvent(gebeurtenis, stream) {
  switch (gebeurtenis.event) {
    case 'volgen-gestart':
      // Wat tussen het laden van de lijst en dit moment binnenkwam, staat alleen in de lijst. Na
      // een herverbinding niet opnieuw lezen: dat verlengt de sessie, en herverbinden is geen
      // activiteit van de gebruiker.
      if (stream.eerste) {
        stream.eerste = false;
        laadLijst();
      }

      return;

    case 'bericht-bijgekomen':
    case 'bericht-gewijzigd':
    case 'bericht-verwijderd':
      // Ook als het bericht nog niet in de lijst staat: de lijst die nu geladen wordt, kan het
      // met de oude waarden brengen.
      lopendeLezingen.forEach((tijdensLezing) => tijdensLezing.push(gebeurtenis));
      pasVolgEventToe(gebeurtenis);
      herteken(true);

      return;

    case 'sessie-verlopen':
      // leesVolgStream() geeft het door; de aanroeper stopt het volgen en meldt het.
      return;

    default:
      // Hartslag, en soorten die we niet kennen.
      return;
  }
}

// Het eigen tabblad krijgt zijn eigen wijzigingen ook terug, en een event kan na het laden van de
// lijst nog een keer langskomen; elke tak geeft bij een herhaling hetzelfde beeld. Een bericht is
// het paar van magazijn en berichtnummer. Bij bericht-bijgekomen staat dat paar onder `bericht`,
// bij de andere twee op het hoogste niveau.
function pasVolgEventToe(gebeurtenis) {
  const sleutel = sleutelVan(gebeurtenis.bericht || gebeurtenis);

  if (gebeurtenis.event === 'bericht-bijgekomen') {
    if (!alleBerichten.some((bericht) => sleutelVan(bericht) === sleutel)) alleBerichten.push(gebeurtenis.bericht);
  } else if (gebeurtenis.event === 'bericht-gewijzigd') {
    const bericht = alleBerichten.find((kandidaat) => sleutelVan(kandidaat) === sleutel);

    if (!bericht) return;

    // De waarden van nu, geen verschil: een ontbrekende map is Postvak IN.
    if (gebeurtenis.status) {
      bericht.status = gebeurtenis.status;
    } else {
      delete bericht.status;
    }

    if (gebeurtenis.map) {
      bericht.map = gebeurtenis.map;
    } else {
      delete bericht.map;
    }
  } else if (gebeurtenis.event === 'bericht-verwijderd') {
    alleBerichten = alleBerichten.filter((bericht) => sleutelVan(bericht) !== sleutel);

    if (el('detail').dataset.sleutel === sleutel) toon(el('detail'), false);
  }
}

// Naast wat de volledigheidsregel al over de organisaties zegt.
function meldOnvolledig(oorzaak) {
  const vak = el('volledigheid');

  vak.textContent = [vak.textContent, `Lijst onvolledig geladen (${oorzaak}); klik op Vernieuw om het opnieuw te proberen.`]
    .filter((deel) => deel)
    .join(' ');
  toon(vak, true);
}

// Uit de lijst zelf, niet uit de voortgangsregels: die beschrijven alleen de ronde die in dit
// tabblad liep, en zijn er niet na het herladen van de pagina of in een ander tabblad.
function toonVolledigheid(lijst) {
  const vak = el('volledigheid');
  const delen = [];

  if (lijst) {
    const afgekapt = (lijst.afgekapt || []).map((organisatie) => {
      const van = typeof organisatie.totaalBeschikbaar === 'number' ? ` van ${organisatie.totaalBeschikbaar}` : '';

      return `${organisatie.naam || organisatie.magazijnId} (${organisatie.aantalOpgehaald}${van})`;
    });
    const nietGeleverd = (lijst.nietGeleverd || []).map((organisatie) => organisatie.naam || organisatie.magazijnId);
    const zonderNaam = (lijst.aantalNietGeleverd || 0) - nietGeleverd.length;

    if (afgekapt.length > 0) delen.push(`Niet alles opgehaald bij: ${afgekapt.join(', ')}.`);

    if (nietGeleverd.length > 0) delen.push(`Niet geleverd door: ${nietGeleverd.join(', ')}.`);

    if (zonderNaam > 0) delen.push(`${zonderNaam} organisatie${zonderNaam === 1 ? '' : 's'} leverde${zonderNaam === 1 ? '' : 'n'} niet.`);
  }

  vak.textContent = delen.join(' ');
  toon(vak, delen.length > 0);
}

// Her-rendert zijbalk + lijst uit de in-memory array (na laden, sorteren, filteren of actie). Een
// event van _volgen laat een geopend bericht staan (`behoudDetail`): het komt ook na een eigen
// actie, en zou het bericht dan direct weer sluiten.
function herteken(behoudDetail = false) {
  if (actieveMap !== null && actieveMap !== 'Archief' && !mapNamen().includes(actieveMap)) {
    actieveMap = null;
  }

  renderMappen();

  const zichtbaar = zichtbareBerichten();
  const maxPagina = Math.max(0, Math.ceil(zichtbaar.length / PAGINA_GROOTTE) - 1);

  if (huidigePagina > maxPagina) huidigePagina = maxPagina;

  const start = huidigePagina * PAGINA_GROOTTE;

  tekenLijst(zichtbaar.slice(start, start + PAGINA_GROOTTE), behoudDetail);
  renderPaginering(zichtbaar.length, maxPagina);
}

function renderPaginering(totaal, maxPagina) {
  // Balk altijd tonen bij een niet-lege map, zodat de telling van de huidige map meebeweegt
  // bij wisselen/filteren. Vorige/volgende alleen bij meerdere pagina's.
  toon(el('paginering'), totaal > 0);

  const meerdere = totaal > PAGINA_GROOTTE;

  el('pagina-info').textContent = meerdere
    ? `Pagina ${huidigePagina + 1} van ${maxPagina + 1} · ${totaal} berichten`
    : `${totaal} bericht${totaal === 1 ? '' : 'en'}`;

  el('vorige-pagina').hidden = !meerdere;
  el('volgende-pagina').hidden = !meerdere;
  el('vorige-pagina').disabled = huidigePagina === 0;
  el('volgende-pagina').disabled = huidigePagina >= maxPagina;
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

function tekenLijst(berichten, behoudDetail = false) {
  const ul = el('lijst');

  if (!behoudDetail) toon(el('detail'), false);

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
  huidigePagina = 0;
  herteken();
}

// De hele regel is een <button> in de <li>: een li met click-listener is niet focusbaar en
// niet met het toetsenbord te bedienen. Een knop levert focus, Enter/Spatie en een rol op.
function lijstItem(bericht) {
  const li = document.createElement('li');
  const knop = document.createElement('button');

  knop.type = 'button';

  if (bericht.status !== 'gelezen') knop.classList.add('ongelezen');

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

  knop.append(titel, meta);
  knop.addEventListener('click', () => openBericht(bericht));
  li.appendChild(knop);

  return li;
}

function toonLeeg(tekst, fout) {
  const p = el('lijst-leeg');

  el('lijst').innerHTML = '';
  toon(el('paginering'), false);
  toon(el('detail'), false);
  toon(p, true);
  p.textContent = tekst;
  p.classList.toggle('fout', Boolean(fout));
}

// Eén foutregel voor beide soorten mislukking: geen connection, of een HTTP-status met
// problem+json-detail. Een connection error heeft geen body, dus die leest leesProblem niet.
async function foutTekst(respons) {
  if (respons.melding) return respons.melding;

  return `HTTP ${respons.status}: ${await leesProblem(respons)}`;
}

async function leesProblem(respons) {
  try {
    const body = await respons.json();

    return body.detail || body.title || respons.statusText;
  } catch (fout) {
    return respons.statusText;
  }
}

// Uit /api/demo/omgeving, same-origin — anders dan de rest van deze pagina, die met de uitvraag
// praat. Niet van /api/demo/personas: dat adres hoort bij de personadienst, en deze module
// beantwoordt het bewust niet; de lijst komt uit dezelfde PersonaService die die dienst gebruikt. Mislukt het ophalen, dan blijven
// Ophalen en Vernieuw uit: met een lege keuzelijst zou de volgende klik een lege X-Ontvanger
// sturen en een 400 opleveren die naar de verkeerde component wijst.
async function laadPersonas() {
  try {
    // Wacht op de omgeving die bovenaan al opgehaald wordt: één adres, één ronde. Twee fetches
    // zouden ook twee antwoorden kunnen opleveren die niet gelijk zijn.
    const omgeving = await omgevingGeladen;

    if (omgeving === null) throw new Error('de demo-console is niet bereikbaar');

    const personas = omgeving.personas;

    if (!Array.isArray(personas)) throw new Error('onverwacht antwoord: geen lijst');

    el('persona').replaceChildren(...personas.map((persona) => {
      if (!persona.ontvanger || !persona.label) throw new Error(`onvolledige persona: ${JSON.stringify(persona)}`);

      const optie = document.createElement('option');

      optie.value = persona.ontvanger;
      optie.textContent = persona.bron === 'dataset' ? `${persona.label} (gegenereerde dataset)` : persona.label;

      return optie;
    }));

    if (personas.length === 0) {
      // De personadienst weigert te starten met een lege lijst, dus dit antwoord komt niet van
      // hem: waarschijnlijk staat er iets anders op dit adres.
      toonLeeg('Onverwacht antwoord: geen enkele persona in /api/demo/omgeving.', true);

      return;
    }

    el('ophalen').disabled = false;
    el('vernieuw').disabled = false;

    toonLeeg('Kies een persona en klik op Ophalen.');
  } catch (oorzaak) {
    console.error('personalijst ophalen mislukt', oorzaak);
    toonLeeg(`Personalijst niet op te halen: ${oorzaak.message}`, true);
  }
}

// Persona waarvoor dit tabblad een sessie heeft; nodig om bij wisselen de vorige uit te loggen.
let gekozenOntvanger = null;

// Sessie starten bij het kiezen van een persona (ook de eerste keuze), zodat Ophalen en Vernieuw
// er direct een hebben. Een mislukte start (bijvoorbeeld het plafond van vijf) wordt hier getoond
// in plaats van bij de eerstvolgende klik.
async function sessieBijKeuze() {
  const sessie = await startSessie(huidigeOntvanger());

  if (sessie.fout) toonLeeg(await foutTekst(sessie.fout), true);
}

laadPersonas().then(() => {
  if (el('persona').value) {
    gekozenOntvanger = huidigeOntvanger();

    return sessieBijKeuze();
  }
});

el('ophalen').addEventListener('click', () => ophalen());
// Alleen de lijst (cache) verversen, zonder _ophalen — toont live in de cache opgevoerde berichten.
el('vernieuw').addEventListener('click', () => laadLijst());
// Alles wat van de vórige persona is afgeleid weggooien. Zonder dit blijven de mapknoppen met
// hun tellingen staan en rendert de eerstvolgende actie (map kiezen, sorteren, filteren) de
// lijst van die persona onder de kop van de nieuwe — in een Berichtenbox-demo precies het
// verkeerde plaatje. De magazijnnamen blijven: die horen bij de OIN, niet bij de persona.
function wisPersonaState() {
  // De vorige persona uitloggen vóór de nieuwe sessie start: anders blijft die sessie met zijn
  // berichten in Redis staan tot hij verloopt.
  const vorige = gekozenOntvanger;

  gekozenOntvanger = huidigeOntvanger();

  if (vorige && vorige !== gekozenOntvanger) beeindigSessie(vorige);

  stopVolgen();
  alleBerichten = [];
  actieveMap = null;
  huidigePagina = 0;
  renderMappen();
}

el('persona').addEventListener('change', async () => {
  wisPersonaState();
  toonVolledigheid(null);
  toon(el('voortgang'), false);
  toonLeeg('Persona gewijzigd — klik op Ophalen.');
  await sessieBijKeuze();
});

el('sorteer').addEventListener('change', (gebeurtenis) => {
  sortering = gebeurtenis.target.value;
  huidigePagina = 0;
  herteken();
});

el('alleen-ongelezen').addEventListener('change', (gebeurtenis) => {
  alleenOngelezen = gebeurtenis.target.checked;
  huidigePagina = 0;
  herteken();
});

el('vorige-pagina').addEventListener('click', () => {
  if (huidigePagina > 0) {
    huidigePagina -= 1;
    herteken();
  }
});

el('volgende-pagina').addEventListener('click', () => {
  huidigePagina += 1;
  herteken();
});

// De keten haalt control- en format-tekens uit de bestandsnaam voordat die in
// `Content-Disposition` gaat. Deze download zet de naam zelf (zie hieronder), dus doen we
// hetzelfde met de naam uit de berichtdetails: zonder dit toont `salaris<U+202E>fdp.exe` in
// de downloadlijst als `salarisexe.pdf`.
const zonderOnzichtbareTekens = (naam) => naam.replace(/[\p{Cc}\p{Cf}]/gu, '');

// Download via fetch (geen <a href>: dat stuurt de X-Ontvanger-header niet mee). De
// respons is binair; we maken er een blob-URL van en triggeren de download programmatisch.
// Het adres komt uit `_links.self` van de bijlage: de uitvraag belooft die verwijzing, niet de
// vorm van het pad.
async function downloadBijlage(bijlage) {
  const href = bijlage._links && bijlage._links.self && bijlage._links.self.href;

  if (!href) {
    alert('Geen ophaaladres bekend voor deze bijlage — open het bericht opnieuw.');

    return;
  }

  const respons = await api(naarApiPad(href));

  if (!respons.ok) {
    alert(`Bijlage downloaden mislukt (${await foutTekst(respons)}).`);

    return;
  }

  const blob = await respons.blob();
  const url = URL.createObjectURL(blob);
  const anker = document.createElement('a');

  anker.href = url;
  anker.download = zonderOnzichtbareTekens(bijlage.naam);
  document.body.appendChild(anker);
  anker.click();
  anker.remove();
  URL.revokeObjectURL(url);
}

// Een bericht is het paar van magazijn en berichtnummer: twee organisaties kunnen hetzelfde nummer
// uitdelen, dus elk verzoek over één bericht noemt beide. Lijst en detail dragen allebei het paar.
// Ontbreekt de magazijnId, dan zou encodeURIComponent(undefined) de tekst "undefined" doorsturen en
// antwoordt de server met een 400 die naar de server wijst, terwijl de oorzaak hier ligt. Geeft null
// terug na een melding; de aanroeper stopt dan.
function berichtPad(bericht) {
  if (!bericht.magazijnId) {
    alert('Geen magazijnId bekend voor dit bericht — haal de lijst opnieuw op.');

    return null;
  }

  return `/berichten/${encodeURIComponent(bericht.berichtId)}?magazijnId=${encodeURIComponent(bericht.magazijnId)}`;
}

async function toonDetail(bericht) {
  const pad = berichtPad(bericht);

  if (!pad) return;

  const respons = await api(pad);

  if (!respons.ok) {
    alert(`Bericht laden mislukt (${await foutTekst(respons)}).`);

    return;
  }

  const geladen = await respons.json();
  const detail = el('detail');

  detail.innerHTML = '';
  detail.dataset.sleutel = sleutelVan(geladen);
  detail.append(detailKop(geladen), detailInhoud(geladen), renderBijlagen(geladen), detailActies(geladen));
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
  knop.addEventListener('click', () => markeer(bericht, isGelezen ? 'ongelezen' : 'gelezen'));

  const frag = document.createDocumentFragment();

  frag.append(h2, afz, knop);

  return frag;
}

async function markeer(bericht, status) {
  const pad = berichtPad(bericht);

  if (!pad) return;

  const respons = await api(pad, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json' },
    body: JSON.stringify({ status }),
  });

  if (!respons.ok) {
    alert(`Markeren mislukt (${await foutTekst(respons)}).`);

    return;
  }

  // Eerst de lijst verversen (verbergt het detail), dan het detail opnieuw tonen — zo blijft
  // het geopende bericht zichtbaar met de bijgewerkte status.
  await laadLijst();
  await toonDetail(bericht);
}

// Een bericht openen markeert het als gelezen (alleen hier, niet bij elke her-render van het
// detail, zodat de "markeer ongelezen"-knop blijft werken).
function openBericht(bericht) {
  if (bericht.status !== 'gelezen') {
    markeer(bericht, 'gelezen');
  } else {
    toonDetail(bericht);
  }
}

// PATCH {map}. Merge-patch kan `map` niet wissen (null = niet wijzigen), dus verplaatsen
// gaat alleen náár een map/Archief, niet terug naar Postvak IN.
async function schrijfMap(bericht, map) {
  const pad = berichtPad(bericht);

  if (!pad) return;

  const respons = await api(pad, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json' },
    body: JSON.stringify({ map }),
  });

  if (!respons.ok) {
    alert(`Verplaatsen mislukt (${await foutTekst(respons)}).`);

    return;
  }

  await laadLijst();
}

function verplaats(bericht, huidigeMap) {
  const bestaande = mapNamen().filter((m) => m !== 'Archief');
  const suggestie = huidigeMap && huidigeMap !== 'Archief' ? huidigeMap : '';

  const naam = prompt(
    'Naar welke map verplaatsen?' + (bestaande.length ? '\nBestaand: ' + bestaande.join(', ') : ''),
    suggestie,
  );

  if (naam && naam.trim()) {
    schrijfMap(bericht, naam.trim());
  }
}

function archiveer(bericht) {
  schrijfMap(bericht, 'Archief');
}

async function verwijder(bericht) {
  if (!confirm('Dit bericht definitief verwijderen?')) return;

  const pad = berichtPad(bericht);

  if (!pad) return;

  const respons = await api(pad, {
    method: 'DELETE',
  });

  if (!respons.ok) {
    alert(`Verwijderen mislukt (${await foutTekst(respons)}).`);

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

// Bijlagen komen uit het detail-endpoint met alleen bijlageId + naam; de inhoud volgt pas bij
// het downloaden zelf.
function renderBijlagen(bericht) {
  const div = document.createElement('div');

  div.className = 'bijlagen';

  const bijlagen = bericht.bijlagen || [];

  if (bijlagen.length === 0) return div;

  const kop = document.createElement('strong');

  kop.textContent = 'Bijlagen: ';
  div.appendChild(kop);

  // Knop, geen <a href="#">: dit is een actie, geen navigatie — zo klopt de rol voor
  // schermlezers en hoeft er geen default-navigatie onderdrukt te worden.
  bijlagen.forEach((bijlage) => {
    const knop = document.createElement('button');

    knop.type = 'button';
    knop.textContent = bijlage.naam;
    knop.addEventListener('click', () => downloadBijlage(bijlage));
    div.appendChild(knop);
  });

  return div;
}

function detailActies(bericht) {
  const div = document.createElement('div');

  div.className = 'acties';

  const verplaatsKnop = document.createElement('button');

  verplaatsKnop.textContent = 'Verplaats naar map…';
  verplaatsKnop.addEventListener('click', () => verplaats(bericht, mapVan(bericht)));

  const archiefKnop = document.createElement('button');

  archiefKnop.textContent = 'Archiveer';
  archiefKnop.addEventListener('click', () => archiveer(bericht));

  const verwijderKnop = document.createElement('button');

  verwijderKnop.textContent = 'Verwijder';
  verwijderKnop.addEventListener('click', () => verwijder(bericht));

  div.append(verplaatsKnop, archiefKnop, verwijderKnop);

  return div;
}
