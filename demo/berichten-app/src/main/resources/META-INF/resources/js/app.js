// De pagina: knoppen en teksten rond het verloop in verloop.js. Tekst uit tokens en credentials gaat
// alleen via textContent de pagina in.
import * as kluis from './sleutelkluis.js';
import {
  Melding, OPSLAG_GEWEIGERD, activeer, melding, neemFragment, rondActiverenAf, statustekst, vernieuw, wis,
} from './verloop.js';

const KNOPPEN = ['activeren', 'vernieuwen', 'wissen'];

const element = (id) => document.getElementById(id);

// Na het vertrek naar de App Manager blijven de knoppen uit, zodat een tweede klik geen tweede
// activering start terwijl de navigatie loopt. Breekt de gebruiker de navigatie af (Esc, Stop), dan
// komen ze na NAVIGATIE_MS terug.
const NAVIGATIE_MS = 15_000;

let vertrokken = false;
let terugTimer;
let ingesteld = false;

async function laadConfig() {
  try {
    const antwoord = await fetch('config.json');

    if (!antwoord.ok) console.error(`config.json gaf HTTP ${antwoord.status}`);

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

async function toon() {
  if (!ingesteld) {
    zetKnoppen({});
    element('status').textContent = '';

    return;
  }

  try {
    const registratie = await kluis.haal(kluis.REGISTRATIE);
    const credential = await kluis.haal(kluis.CREDENTIAL);

    zetKnoppen({ activeren: true, vernieuwen: Boolean(registratie), wissen: true });
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

/** Zonder geladen instellingen hangt er niets aan de knoppen; dan blijven ze uit. */
function zetKnoppen(aan) {
  KNOPPEN.forEach((id) => {
    element(id).disabled = !ingesteld || !aan[id];
  });
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
  // Het teken voor vangnet.js dat deze module geladen is.
  document.documentElement.dataset.gestart = 'ja';

  // Vóór alles: ook als de browser ongeschikt is of de instellingen niet laden, hoort het token niet
  // in de geschiedenis.
  const fragment = neemFragment(location, history);

  if (!browserGeschikt()) {
    element('status').textContent = 'Deze browser biedt deze pagina geen veilige opslag of cryptografie; de app werkt zo niet.';

    return;
  }

  // Terug met de knop Vorige komt de pagina uit de cache, nog in de toestand van het vertrek.
  addEventListener('pageshow', (gebeurtenis) => {
    if (!gebeurtenis.persisted) return;

    vertrokken = false;
    toon();
  });
  addEventListener('pagehide', () => clearTimeout(terugTimer));

  bezig(null, 'Laden…', async () => {
    let appManager;

    try {
      appManager = await laadConfig();
    } catch (fout) {
      // Het fragment is al weg; na herladen moet de gebruiker de persona dus opnieuw kiezen.
      if (fragment) throw new Melding(`${fout.message} Daarna moet u opnieuw activeren.`);

      throw fout;
    }

    const omgeving = { appManager, kluis, sessie: sessionStorage };

    koppel('activeren', 'Bezig met activeren…', async () => {
      const adres = await activeer(omgeving);

      location.assign(adres);
      vertrokken = true;
      terugTimer = setTimeout(() => {
        vertrokken = false;
        toon();
      }, NAVIGATIE_MS);
    });
    koppel('vernieuwen', 'Bezig met vernieuwen…', () => vernieuw(omgeving));
    element('wissen').addEventListener('click', () => {
      if (confirm('De sleutel en het bewijs van deze browser worden verwijderd. Daarna moet u opnieuw activeren.')) {
        bezig(element('wissen'), 'Bezig met wissen…', () => wis(omgeving));
      }
    });
    ingesteld = true;

    if (fragment) await rondActiverenAf({ ...omgeving, fragment });
  });
}

start();
