// Het verloop van de app zonder DOM: activeren, afronden na de App Manager, vernieuwen en wissen,
// en de tekst die de gebruiker bij een fout ziet. Kluis, sessionStorage en fetch geeft de aanroeper
// mee, zodat dezelfde code in de browser en in de tests onder Node draait.
import { errors } from '../vendor/jose/index.js';
import {
  ActiverenFout, VerbindingFout, haalCredential, leesCredential, leesRegistratie, maakSessie, registratieAdres,
} from './activeren.js';
import { ACTIVATIE, KluisFout, REGISTRATIE } from './sleutelkluis.js';

// Het session_token van het activeren overleeft zo de navigatie naar de App Manager, maar niet het
// sluiten van het tabblad. In de kluis staat het samen met de AES-sleutel (niet te exporteren),
// zodat een tabblad bij terugkomst ziet of een ander tabblad intussen een eigen activering begon.
export const ACTIVATIE_SESSIE = 'activatie-sessie';

export const OPNIEUW_ACTIVEREN = 'Kies Activeren om opnieuw te beginnen.';
export const OPSLAG_GEWEIGERD = 'Deze browser staat de app geen opslag toe. Gebruik een gewoon venster (geen privévenster) of sta sitegegevens toe, en herlaad de pagina.';

/** Een fout met een tekst die de gebruiker zo te zien krijgt. */
export class Melding extends Error {
  name = 'Melding';
}

/**
 * Het fragment waarmee de App Manager terugstuurt, meteen uit adresbalk en geschiedenis. Geeft
 * `null` als er geen `registration_token` in staat.
 */
export function neemFragment(locatie, geschiedenis) {
  if (!new URLSearchParams(locatie.hash.slice(1)).has('registration_token')) return null;

  const fragment = locatie.hash;

  geschiedenis.replaceState(null, '', locatie.pathname + locatie.search);

  return fragment;
}

/** Begint het activeren; geeft het adres van de App Manager waar de gebruiker heen moet. */
export async function activeer({ appManager, kluis, sessie, fetch = globalThis.fetch }) {
  const sleutelpaar = await kluis.sleutelpaar();
  const { sessieToken, aesSleutel } = await maakSessie({ basis: appManager.url, oin: appManager.oin, sleutelpaar, fetch });

  sessie.setItem(ACTIVATIE_SESSIE, sessieToken);
  await kluis.bewaar(ACTIVATIE, { sessieToken, aesSleutel });

  return registratieAdres(appManager.url, sessieToken);
}

/** Terug van de App Manager: het token uit het fragment halen en er een bewijs mee ophalen. */
export async function rondActiverenAf({ appManager, fragment, kluis, sessie, fetch = globalThis.fetch }) {
  const sessieToken = sessie.getItem(ACTIVATIE_SESSIE);

  sessie.removeItem(ACTIVATIE_SESSIE);

  // Het fragment is al uit de geschiedenis: lukt de opslag hier niet, dan is het token weg en helpt
  // herladen niet.
  const activatie = await kluisOfOpnieuw(() => kluis.haal(ACTIVATIE), 'De app kon het activeren niet afronden.');

  // Hoort de activering in de kluis bij een ander tabblad, dan blijft hij voor dat tabblad staan.
  if (!sessieToken || activatie?.sessieToken !== sessieToken) throw new Melding(ANDER_BEGONNEN);

  const { aesSleutel } = activatie;
  const registratieToken = await leesRegistratie(fragment, aesSleutel, appManager.url);

  // De activering in de kluis verdwijnt pas als het token er staat, en alleen als hij nog van dit
  // tabblad is.
  const bewaard = await kluisOfOpnieuw(() => kluis.vervangRegistratie(registratieToken, sessieToken), 'De app kon de koppeling niet bewaren.');

  if (!bewaard) throw new Melding(ANDER_BEGONNEN);

  let credential;

  try {
    credential = await haalCredential({ basis: appManager.url, sessieToken, aesSleutel, registratieToken, fetch });
    leesCredential(credential);
  } catch (fout) {
    // Alleen wat een volgende poging kan verhelpen; bij een 400 of een onleesbaar antwoord helpt
    // vernieuwen niet, en dan geldt de gewone melding.
    if (fout instanceof VerbindingFout || (fout instanceof ActiverenFout && (fout.status === 401 || fout.status >= 500))) {
      throw new Melding('De koppeling is gelukt, maar het bewijs is nog niet opgehaald. Kies Bewijs vernieuwen om het af te ronden.', { cause: fout });
    }

    throw fout;
  }

  if (!await kluis.bewaarCredential(registratieToken, credential)) throw new Melding(ANDER_TABBLAD);
}

/** Een vers bewijs zonder opnieuw te kiezen: een nieuwe sessie, hetzelfde registration_token. */
export async function vernieuw({ appManager, kluis, fetch = globalThis.fetch }) {
  const registratieToken = await kluis.haal(REGISTRATIE);

  if (!registratieToken) throw new Melding(`Deze browser is nog niet geactiveerd. ${OPNIEUW_ACTIVEREN}`);

  const sleutelpaar = await kluis.sleutelpaar();
  const { sessieToken, aesSleutel } = await maakSessie({ basis: appManager.url, oin: appManager.oin, sleutelpaar, fetch });
  let credential;

  try {
    credential = await haalCredential({ basis: appManager.url, sessieToken, aesSleutel, registratieToken, fetch });
    leesCredential(credential);
  } catch (fout) {
    // Een registratie die de App Manager niet meer kent (verlopen, na een herstart, of van een
    // andere sleutel), blijft anders staan en geeft bij elke klik dezelfde fout. De App Manager
    // antwoordt op elke afwijzing hetzelfde, dus een fout in het verzoek zelf ziet er zo ook uit.
    if (fout instanceof ActiverenFout && fout.status === 401) {
      if (!await kluis.vergeetRegistratie(registratieToken)) throw new Melding(ANDER_TABBLAD, { cause: fout });

      throw new Melding(`Uw activering is niet meer geldig. ${OPNIEUW_ACTIVEREN}`, { cause: fout });
    }

    throw fout;
  }

  if (!await kluis.bewaarCredential(registratieToken, credential)) throw new Melding(ANDER_TABBLAD);
}

/** Een KluisFout wordt een melding die zegt dat opnieuw activeren nodig is. */
async function kluisOfOpnieuw(actie, wat) {
  try {
    return await actie();
  } catch (fout) {
    if (fout instanceof KluisFout) throw new Melding(`${wat} ${OPSLAG_GEWEIGERD} ${OPNIEUW_ACTIVEREN}`, { cause: fout });

    throw fout;
  }
}

export async function wis({ kluis, sessie }) {
  await kluis.wis();
  sessie.removeItem(ACTIVATIE_SESSIE);
}

/** Wat de pagina over de activering zegt. */
export function statustekst(registratie, credential, nu = new Date()) {
  if (!registratie) return 'Deze browser is nog niet geactiveerd.';

  // Zonder vervolgstap: die staat in de melding erboven, en na een fout helpt vernieuwen niet altijd.
  if (!credential) return 'Gekoppeld, maar er is nog geen bewijs.';

  const { ontvanger, verlooptOp } = leesCredential(credential);

  if (verlooptOp <= nu) return `Geactiveerd voor ${ontvanger}. Het bewijs is verlopen; kies Bewijs vernieuwen.`;

  return `Geactiveerd voor ${ontvanger}. Het bewijs is geldig tot ${verlooptOp.toLocaleString('nl-NL')}.`;
}

/** De tekst voor de gebruiker; de oorspronkelijke fout gaat naar de console. */
export function melding(fout) {
  if (fout instanceof Melding) return fout.message;

  if (fout instanceof VerbindingFout) return 'Geen verbinding met de App Manager. Controleer uw verbinding en probeer het opnieuw.';

  if (fout instanceof KluisFout) return OPSLAG_GEWEIGERD;

  if (fout instanceof ActiverenFout && fout.stap === 'instelling') return 'De app en de App Manager zijn verschillend ingesteld. Meld dit aan de beheerder.';

  if (fout instanceof ActiverenFout && fout.status === 401) return 'De App Manager wees het verzoek af. Probeer het opnieuw.';

  // Een 400 wijst op een fout in de app zelf of een App Manager die een andere versie spreekt;
  // opnieuw proberen helpt dan niet.
  if (fout instanceof ActiverenFout && fout.status === 400) return 'De App Manager begrijpt het verzoek van de app niet. Meld dit aan de beheerder.';

  if (fout instanceof ActiverenFout && fout.status === 503) return 'De App Manager is even te druk. Probeer het over een minuut opnieuw.';

  if (fout instanceof ActiverenFout && fout.status) return 'De App Manager gaf een fout. Probeer het later opnieuw.';

  if (fout instanceof ActiverenFout || fout instanceof errors.JOSEError || fout instanceof SyntaxError) {
    return `Het antwoord van de App Manager is niet te lezen. ${OPNIEUW_ACTIVEREN}`;
  }

  return 'Er ging iets mis in de app. Herlaad de pagina en probeer het opnieuw.';
}

const ANDER_BEGONNEN = `Het activeren is in een ander tabblad begonnen, gewist of verlopen. ${OPNIEUW_ACTIVEREN}`;
const ANDER_TABBLAD = 'In een ander tabblad is intussen opnieuw geactiveerd of gewist; dit bewijs is niet bewaard. Herlaad de pagina.';
