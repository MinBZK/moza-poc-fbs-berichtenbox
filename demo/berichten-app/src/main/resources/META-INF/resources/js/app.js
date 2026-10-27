// De pagina: activeren, het bewijs vernieuwen en de activering wissen. Tekst uit tokens en
// credentials gaat alleen via textContent de pagina in.
import { errors } from '../vendor/jose-6.2.13/index.js';
import * as kluis from './sleutelkluis.js';
import {
  ActiverenFout, VerbindingFout, haalCredential, leesCredential, leesRegistratie, maakSessie, registratieAdres,
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
  try {
    const antwoord = await fetch('config.json');
    const { appManager } = antwoord.ok ? await antwoord.json() : {};

    if (typeof appManager?.url === 'string' && typeof appManager?.oin === 'string') return appManager;
  } catch (fout) {
    console.error(fout);
  }

  throw new Melding('De app kan haar instellingen niet laden. Herlaad de pagina of probeer het later opnieuw.');
}

/** WebCrypto bestaat alleen in een secure context; sessionStorage en IndexedDB kunnen uit staan. */
function browserGeschikt() {
  if (!globalThis.isSecureContext || !globalThis.crypto?.subtle) return false;

  try {
    sessionStorage.setItem('opslagtest', '1');
    sessionStorage.removeItem('opslagtest');

    return typeof indexedDB !== 'undefined' && indexedDB !== null;
  } catch {
    return false;
  }
}

/** Het fragment waarmee de App Manager terugstuurt, meteen uit adresbalk en geschiedenis. */
function neemFragment() {
  if (!new URLSearchParams(location.hash.slice(1)).has('registration_token')) return null;

  const fragment = location.hash;

  history.replaceState(null, '', location.pathname + location.search);

  return fragment;
}

/** Terug van de App Manager: het token uit het fragment halen en er een bewijs mee ophalen. */
async function rondActiverenAf(appManager, fragment) {
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

  // Eerst het token bewaren: mislukt het ophalen hierna, dan haalt "Bewijs vernieuwen" het alsnog.
  await kluis.vervangRegistratie(registratieToken);

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

  location.assign(registratieAdres(appManager.url, sessieToken));
  vertrokken = true;
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
  await kluis.wis();
  sessionStorage.removeItem(ACTIVATIE_SESSIE);
}

async function toon() {
  if (!ingesteld) {
    zetKnoppen({});
    element('status').textContent = '';

    return;
  }

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

  if (fout instanceof VerbindingFout) return 'Geen verbinding met de App Manager. Controleer uw verbinding en probeer het opnieuw.';

  if (fout instanceof kluis.KluisFout) return 'De opslag van deze browser werkt niet mee. Probeer het opnieuw, of kies Activering wissen.';

  if (fout instanceof ActiverenFout && fout.status === 401 && fout.stap === 'credential') {
    return `De App Manager herkent deze activering niet. ${OPNIEUW_ACTIVEREN}`;
  }

  if (fout instanceof ActiverenFout && fout.status === 401) return 'De App Manager wees het verzoek af. Probeer het opnieuw.';

  if (fout instanceof ActiverenFout && fout.status === 503) return 'De App Manager is even te druk. Probeer het over een minuut opnieuw.';

  if (fout instanceof ActiverenFout && fout.status) return 'De App Manager gaf een fout. Probeer het later opnieuw.';

  if (fout instanceof ActiverenFout || fout instanceof errors.JOSEError || fout instanceof SyntaxError) {
    return `Het antwoord van de App Manager is niet te lezen. ${OPNIEUW_ACTIVEREN}`;
  }

  return 'Er ging iets mis in de app. Herlaad de pagina en probeer het opnieuw.';
}

function meld(fout) {
  console.error(fout);
  element('fout').textContent = melding(fout);
}

/** Eén actie tegelijk: een dubbele klik start geen tweede activering. */
async function bezig(knop, tekst, actie) {
  zetKnoppen({});
  element('fout').textContent = '';
  element('status').textContent = tekst;

  try {
    await actie();
  } catch (fout) {
    meld(fout);
  } finally {
    if (!vertrokken) {
      await toon();

      // De knop met focus ging tijdens de actie uit; zet de focus terug voor wie met het toetsenbord werkt.
      if (knop && !knop.disabled) knop.focus();
    }
  }
}

function koppel(id, tekst, actie) {
  const knop = element(id);

  knop.addEventListener('click', () => bezig(knop, tekst, actie));
}

function start() {
  if (!browserGeschikt()) {
    element('status').textContent = 'Deze browser biedt deze pagina geen veilige opslag of cryptografie; de app werkt zo niet.';

    return;
  }

  // Vóór alles: ook als de instellingen niet laden, hoort het token niet in de geschiedenis.
  const fragment = neemFragment();

  // Terug met de knop Vorige komt de pagina uit de cache, nog in de toestand van het vertrek.
  addEventListener('pageshow', (gebeurtenis) => {
    if (!gebeurtenis.persisted) return;

    vertrokken = false;
    toon();
  });

  bezig(null, 'Laden…', async () => {
    const appManager = await laadConfig();

    koppel('activeren', 'Bezig met activeren…', () => activeer(appManager));
    koppel('vernieuwen', 'Bezig met vernieuwen…', () => vernieuw(appManager));
    element('wissen').addEventListener('click', () => {
      if (confirm('De sleutel en het bewijs van deze browser worden verwijderd. Daarna moet u opnieuw activeren.')) {
        bezig(element('wissen'), 'Bezig met wissen…', wis);
      }
    });
    ingesteld = true;

    if (fragment) await rondActiverenAf(appManager, fragment);
  });
}

start();
