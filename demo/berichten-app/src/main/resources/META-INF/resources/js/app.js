// De pagina: activeren, het bewijs vernieuwen en de activering wissen. Tekst uit tokens en
// credentials gaat alleen via textContent de pagina in.
import * as kluis from './sleutelkluis.js';
import {
  ActiverenFout, haalCredential, leesCredential, leesRegistratie, maakSessie, registratieAdres,
} from './activeren.js';

// Het session_token van het activeren overleeft zo de navigatie naar de App Manager, maar niet het
// sluiten van het tabblad. In de kluis staat het samen met de AES-sleutel (niet te exporteren),
// zodat een tabblad bij terugkomst ziet of een ander tabblad intussen een eigen activering begon.
const ACTIVATIE_SESSIE = 'activatie-sessie';
const ACTIVATIE = 'activatie';
const KNOPPEN = ['activeren', 'vernieuwen', 'wissen'];

const OPNIEUW_ACTIVEREN = 'Kies Activeren om opnieuw te beginnen.';

/** Een fout met een tekst die de gebruiker zo te zien krijgt. */
class Melding extends Error {}

const element = (id) => document.getElementById(id);

let vertrokken = false;
let ingesteld = false;

async function laadConfig() {
  const antwoord = await fetch('config.json');

  if (!antwoord.ok) throw new Melding('De app kan haar instellingen niet laden. Probeer het later opnieuw.');

  const { appManager } = await antwoord.json();

  if (typeof appManager?.url !== 'string' || typeof appManager?.oin !== 'string') {
    throw new Melding('De instellingen van de app zijn onvolledig.');
  }

  return appManager;
}

function opslagBeschikbaar() {
  try {
    sessionStorage.setItem('opslagtest', '1');
    sessionStorage.removeItem('opslagtest');

    return typeof indexedDB !== 'undefined' && indexedDB !== null;
  } catch {
    return false;
  }
}

/** Terug van de App Manager: het token uit het fragment halen en er een bewijs mee ophalen. */
async function rondActiverenAf(appManager) {
  if (!new URLSearchParams(location.hash.slice(1)).has('registration_token')) return;

  const fragment = location.hash;

  // Het token hoort niet in de geschiedenis van de browser te blijven staan.
  history.replaceState(null, '', location.pathname + location.search);

  const sessieToken = sessionStorage.getItem(ACTIVATIE_SESSIE);
  const activatie = await kluis.haal(ACTIVATIE);

  sessionStorage.removeItem(ACTIVATIE_SESSIE);

  // Hoort de activering in de kluis bij een ander tabblad, dan blijft hij voor dat tabblad staan.
  if (!sessieToken || activatie?.sessieToken !== sessieToken) {
    throw new Melding(`Het activeren is in een ander tabblad begonnen of verlopen. ${OPNIEUW_ACTIVEREN}`);
  }

  await kluis.verwijder(ACTIVATIE);

  const { aesSleutel } = activatie;
  const registratieToken = await leesRegistratie(fragment, aesSleutel, appManager.url);

  // Eerst het token bewaren, en het bewijs van een eventuele vorige persona weg: mislukt het
  // ophalen hierna, dan haalt "Bewijs vernieuwen" het alsnog.
  await kluis.bewaar('registratie', registratieToken);
  await kluis.verwijder('credential');

  let credential;

  try {
    credential = await haalCredential({ basis: appManager.url, sessieToken, aesSleutel, registratieToken });
    leesCredential(credential);
  } catch (fout) {
    console.error(fout);

    throw new Melding('De koppeling is gelukt, maar het bewijs is nog niet opgehaald. Kies Bewijs vernieuwen om het af te ronden.');
  }

  await kluis.bewaar('credential', credential);
}

async function activeer(appManager) {
  const sleutelpaar = await kluis.sleutelpaar();
  const { sessieToken, aesSleutel } = await maakSessie({ basis: appManager.url, oin: appManager.oin, sleutelpaar });

  sessionStorage.setItem(ACTIVATIE_SESSIE, sessieToken);
  await kluis.bewaar(ACTIVATIE, { sessieToken, aesSleutel });

  vertrokken = true;
  location.assign(registratieAdres(appManager.url, sessieToken));
}

/** Een vers bewijs zonder opnieuw te kiezen: een nieuwe sessie, hetzelfde registration_token. */
async function vernieuw(appManager) {
  const registratieToken = await kluis.haal('registratie');

  if (!registratieToken) throw new Melding(`Deze browser is nog niet geactiveerd. ${OPNIEUW_ACTIVEREN}`);

  const sleutelpaar = await kluis.sleutelpaar();
  const { sessieToken, aesSleutel } = await maakSessie({ basis: appManager.url, oin: appManager.oin, sleutelpaar });

  try {
    const credential = await haalCredential({ basis: appManager.url, sessieToken, aesSleutel, registratieToken });

    leesCredential(credential);
    await kluis.bewaar('credential', credential);
  } catch (fout) {
    // Een registratie die de App Manager niet meer kent (verlopen, of na een herstart), blijft
    // anders staan en geeft bij elke klik dezelfde fout.
    if (fout instanceof ActiverenFout && fout.status === 401) {
      await kluis.verwijder('registratie');
      await kluis.verwijder('credential');

      throw new Melding(`Uw activering is niet meer geldig. ${OPNIEUW_ACTIVEREN}`);
    }

    throw fout;
  }
}

async function wis() {
  if (!confirm('De sleutel en het bewijs van deze browser worden verwijderd. Daarna moet u opnieuw activeren.')) return;

  await kluis.wis();
  sessionStorage.removeItem(ACTIVATIE_SESSIE);
}

async function toon() {
  try {
    const registratie = await kluis.haal('registratie');
    const credential = await kluis.haal('credential');

    zetKnoppen({ activeren: true, vernieuwen: Boolean(registratie), wissen: true });
    element('status').textContent = statustekst(registratie, credential);
  } catch (fout) {
    console.error(fout);
    zetKnoppen({ activeren: true, wissen: true });
    element('status').textContent = 'De opgeslagen activering is niet te lezen. Kies Activering wissen en activeer opnieuw.';
  }
}

function statustekst(registratie, credential) {
  if (!registratie) return 'Deze browser is nog niet geactiveerd.';

  if (!credential) return 'Gekoppeld, maar er is nog geen bewijs. Kies Bewijs vernieuwen.';

  const { ontvanger, verlooptOp } = leesCredential(credential);

  if (verlooptOp <= new Date()) return `Geactiveerd voor ${ontvanger}. Het bewijs is verlopen; kies Bewijs vernieuwen.`;

  return `Geactiveerd voor ${ontvanger}. Het bewijs is geldig tot ${verlooptOp.toLocaleString('nl-NL')}.`;
}

/** Zonder geladen instellingen hangt er niets aan de knoppen; dan blijven ze uit. */
function zetKnoppen(aan) {
  KNOPPEN.forEach((id) => {
    element(id).disabled = !ingesteld || !aan[id];
  });
}

/** De tekst voor de gebruiker; de oorspronkelijke fout gaat naar de console. */
function melding(fout) {
  if (fout instanceof Melding) return fout.message;

  if (fout instanceof ActiverenFout && fout.status === 401) return `De App Manager herkent deze activering niet. ${OPNIEUW_ACTIVEREN}`;

  if (fout instanceof ActiverenFout && fout.status === 503) return 'De App Manager is even te druk. Probeer het over een minuut opnieuw.';

  if (fout instanceof ActiverenFout && fout.status) return 'De App Manager gaf een fout. Probeer het later opnieuw.';

  if (fout instanceof TypeError) return 'Geen verbinding met de App Manager. Controleer uw verbinding en probeer het opnieuw.';

  return `Het antwoord van de App Manager is niet te lezen. ${OPNIEUW_ACTIVEREN}`;
}

function meld(fout) {
  console.error(fout);
  element('fout').textContent = melding(fout);
}

/** Eén actie tegelijk: een dubbele klik start geen tweede activering. */
async function bezig(tekst, actie) {
  zetKnoppen({});
  element('fout').textContent = '';
  element('status').textContent = tekst;

  try {
    await actie();
  } catch (fout) {
    meld(fout);
  } finally {
    if (!vertrokken) await toon();
  }
}

function start() {
  if (!opslagBeschikbaar()) {
    element('status').textContent = 'Deze browser staat geen opslag toe voor deze pagina; de app werkt zo niet.';

    return;
  }

  bezig('Laden…', async () => {
    const appManager = await laadConfig();

    element('activeren').addEventListener('click', () => bezig('Bezig met activeren…', () => activeer(appManager)));
    element('vernieuwen').addEventListener('click', () => bezig('Bezig met vernieuwen…', () => vernieuw(appManager)));
    element('wissen').addEventListener('click', () => bezig('Bezig met wissen…', wis));
    ingesteld = true;

    await rondActiverenAf(appManager);
  });
}

start();
