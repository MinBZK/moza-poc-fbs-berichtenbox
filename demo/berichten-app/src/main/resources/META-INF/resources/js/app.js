// De pagina: activeren, het bewijs vernieuwen, de activering wissen en berichten ophalen. Tekst uit
// tokens, credentials en magazijnen gaat alleen via textContent de pagina in.
import { errors } from '../vendor/jose-6.2.13/index.js';
import * as kluis from './sleutelkluis.js';
import {
  ActiverenFout, VerbindingFout, haalCredential, leesCredential, leesRegistratie, maakSessie, registratieAdres,
} from './activeren.js';
import { StelseldocumentFout, WACHTTIJD_MS, haalAllesOp, leesStelseldocument } from './ophalen.js';

// Het session_token van het activeren overleeft zo de navigatie naar de App Manager, maar niet het
// sluiten van het tabblad. In de kluis staat het samen met de AES-sleutel (niet te exporteren),
// zodat een tabblad bij terugkomst ziet of een ander tabblad intussen een eigen activering begon.
const ACTIVATIE_SESSIE = 'activatie-sessie';
const ACTIVATIE = 'activatie';
const KNOPPEN = ['activeren', 'vernieuwen', 'wissen', 'ophalen'];

const OPNIEUW_ACTIVEREN = 'Kies Activeren om opnieuw te beginnen.';
const OPSLAG_GEWEIGERD = 'Deze browser staat de app geen opslag toe. Gebruik een gewoon venster (geen privévenster) of sta sitegegevens toe, en herlaad de pagina.';

/** Een fout met een tekst die de gebruiker zo te zien krijgt. */
class Melding extends Error {}

const element = (id) => document.getElementById(id);

let vertrokken = false;
let ingesteld = false;

async function laadConfig() {
  try {
    const antwoord = await fetch('config.json');

    if (!antwoord.ok) console.error(`config.json gaf HTTP ${antwoord.status}`);

    const config = antwoord.ok ? await antwoord.json() : {};
    const { appManager, stelselregister } = config;

    if (typeof appManager?.url === 'string' && typeof appManager?.oin === 'string' && typeof stelselregister?.url === 'string') return config;
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
      leegBerichten();

      throw new Melding(`Uw activering is niet meer geldig. ${OPNIEUW_ACTIVEREN}`);
    }

    throw fout;
  }
}

/** Bij elk magazijn uit het stelseldocument tegelijk, met het bewijs uit de kluis. */
async function haalBerichtenOp(stelselregister) {
  leegBerichten();

  const credential = await kluis.haal('credential');

  if (!credential || leesCredential(credential).verlooptOp <= new Date()) {
    throw new Melding('Er is geen geldig bewijs. Kies Bewijs vernieuwen.');
  }

  let antwoord;

  try {
    antwoord = await fetch(new URL('api/v1/stelseldocument', stelselregister.url), {
      headers: { Accept: 'application/jose' },
      signal: AbortSignal.timeout(WACHTTIJD_MS),
    });
  } catch (fout) {
    throw new Melding('Het stelselregister is niet te bereiken. Probeer het later opnieuw.', { cause: fout });
  }

  if (!antwoord.ok) throw new Melding(`Het stelselregister gaf geen lijst van magazijnen (HTTP ${antwoord.status}). Probeer het later opnieuw.`);

  const magazijnen = leesStelseldocument(await antwoord.text());
  const uitkomst = await haalAllesOp(magazijnen, { sleutelpaar: await kluis.sleutelpaar(), credential });

  uitkomst.mislukt.forEach(({ naam, oin, oorzaak }) => console.warn(`Magazijn ${naam} (${oin}) niet opgehaald`, oorzaak));
  toonBerichten(uitkomst, magazijnen.length);
}

function toonBerichten({ berichten, mislukt }, aantal) {
  element('berichten').replaceChildren(
    ...berichten.map((bericht) => regel(`${datum(bericht.publicatietijdstip)} · ${bericht.magazijn} · ${bericht.onderwerp}`)),
  );

  element('mislukt').replaceChildren(...mislukt.map((magazijn) => regel(`${magazijn.naam}: ${magazijn.reden}`)));
  element('niet-bereikt').hidden = mislukt.length === 0;
  element('overzicht').textContent = overzicht(berichten.length, mislukt, aantal);
}

function overzicht(aantalBerichten, mislukt, aantal) {
  if (aantal === 0) return 'Het stelseldocument noemt geen magazijnen.';

  const tekst = `${aantalBerichten} bericht(en) uit ${aantal - mislukt.length} van de ${aantal} magazijnen.`;

  // Weigert elk magazijn het bewijs, dan ligt het aan het bewijs en niet aan de magazijnen.
  if (mislukt.length === aantal && mislukt.every((magazijn) => magazijn.reden === 'weigerde het bewijs')) {
    return `${tekst} Geen enkel magazijn nam het bewijs aan; kies Bewijs vernieuwen en probeer het opnieuw.`;
  }

  return tekst;
}

/** Een lijst die bij een eerdere ondernemer of een eerder bewijs hoort, blijft niet staan. */
function leegBerichten() {
  element('berichten').replaceChildren();
  element('mislukt').replaceChildren();
  element('niet-bereikt').hidden = true;
  element('overzicht').textContent = '';
}

function regel(tekst) {
  const item = document.createElement('li');

  item.textContent = tekst;

  return item;
}

function datum(tijdstip) {
  const moment = new Date(tijdstip);

  return Number.isNaN(moment.getTime()) ? '(geen datum)' : moment.toLocaleString('nl-NL');
}

async function wis() {
  await kluis.wis();
  sessionStorage.removeItem(ACTIVATIE_SESSIE);
  leegBerichten();
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

    const geldig = credential && leesCredential(credential).verlooptOp > new Date();

    zetKnoppen({ activeren: true, vernieuwen: Boolean(registratie), wissen: true, ophalen: Boolean(geldig) });
    element('status').textContent = statustekst(registratie, credential);
  } catch (fout) {
    console.error(fout);

    if (fout instanceof kluis.KluisFout) {
      zetKnoppen({});
      element('status').textContent = OPSLAG_GEWEIGERD;

      return;
    }

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

  if (fout instanceof kluis.KluisFout) return OPSLAG_GEWEIGERD;

  if (fout instanceof StelseldocumentFout) return 'De lijst van magazijnen is niet te lezen. Probeer het later opnieuw.';

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

      // De knop met focus ging tijdens de actie uit; zet de focus terug voor wie met het toetsenbord
      // werkt. Bleef die knop uit (vernieuwen zonder registratie), dan naar Activeren.
      const terug = knop?.disabled ? element('activeren') : knop;

      if (terug && !terug.disabled) terug.focus();
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
    let config;

    try {
      config = await laadConfig();
    } catch (fout) {
      // Het fragment is al weg; na herladen moet de gebruiker de persona dus opnieuw kiezen.
      if (fragment) throw new Melding(`${fout.message} Daarna moet u opnieuw activeren.`);

      throw fout;
    }

    const { appManager, stelselregister } = config;

    koppel('activeren', 'Bezig met activeren…', () => activeer(appManager));
    koppel('ophalen', 'Bezig met ophalen…', () => haalBerichtenOp(stelselregister));
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
