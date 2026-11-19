// De pagina: knoppen en teksten rond het verloop in verloop.js, en de berichtenlijst uit ophalen.js.
// Tekst uit tokens, credentials en magazijnen gaat alleen via textContent de pagina in.
import { leesCredential } from './activeren.js';
import { WACHTTIJD_MS, haalAllesOp, leesStelseldocument } from './ophalen.js';
import * as kluis from './sleutelkluis.js';
import {
  Melding, OPSLAG_GEWEIGERD, activeer, melding, neemFragment, rondActiverenAf, statustekst, vernieuw, wis,
} from './verloop.js';

const KNOPPEN = ['activeren', 'vernieuwen', 'wissen', 'ophalen'];

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

async function toon() {
  if (!ingesteld) {
    zetKnoppen({});
    element('status').textContent = '';

    return;
  }

  try {
    const registratie = await kluis.haal(kluis.REGISTRATIE);
    const credential = await kluis.haal(kluis.CREDENTIAL);

    const geldig = Boolean(credential) && leesCredential(credential).verlooptOp > new Date();

    // Een lijst die bij een eerdere ondernemer of een vervallen bewijs hoort, blijft niet staan.
    if (!geldig) leegBerichten();

    zetKnoppen({ activeren: true, vernieuwen: Boolean(registratie), wissen: true, ophalen: geldig });
    element('status').textContent = statustekst(registratie, credential);
  } catch (fout) {
    console.error(fout);
    leegBerichten();

    if (fout instanceof kluis.KluisFout) {
      zetKnoppen({});
      element('status').textContent = OPSLAG_GEWEIGERD;

      return;
    }

    zetKnoppen({ activeren: true, wissen: true });
    element('status').textContent = 'De opgeslagen activering is niet te lezen. Kies Activering wissen en activeer opnieuw.';
  }
}

/** Bij elk magazijn uit het stelseldocument tegelijk, met het bewijs uit de kluis. */
async function haalBerichtenOp(stelselregister) {
  leegBerichten();

  const credential = await kluis.haal(kluis.CREDENTIAL);

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
    let config;

    try {
      config = await laadConfig();
    } catch (fout) {
      // Het fragment is al weg; na herladen moet de gebruiker de persona dus opnieuw kiezen.
      if (fragment) throw new Melding(`${fout.message} Daarna moet u opnieuw activeren.`);

      throw fout;
    }

    const { appManager, stelselregister } = config;
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
    koppel('ophalen', 'Bezig met ophalen…', () => haalBerichtenOp(stelselregister));
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
