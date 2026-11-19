// Rechtstreeks ophalen: welke magazijnen er zijn (stelseldocument), per magazijn een sessie en de
// berichtenlijst, en alles samen in één lijst. Zonder DOM, zodat de tests onder Node dezelfde code
// draaien. Een magazijn dat niet of te laat antwoordt, houdt de rest niet op.
import { decodeJwt, errors } from '../vendor/jose/index.js';
import { ActiverenFout, haalVersleuteld, maakMagazijnsessie } from './activeren.js';

/**
 * De timeout op één dienst (een magazijn of het stelselregister), vanaf het moment dat die aan de
 * beurt is.
 */
export const TIMEOUT_MS = 10_000;

/**
 * Hoeveel magazijnen tegelijk. Onder HTTP/1.1 opent een browser zo'n zes connections per origin, en
 * de gesimuleerde magazijnen delen er één; een verzoek dat daarboven in de wachtrij staat, zou zijn
 * timeout al verbruiken voor het vertrekt.
 */
export const GELIJKTIJDIG = 6;

/** Meer organisaties dan dit is geen stelsel maar een document dat het tabblad wil laten vastlopen. */
export const MAX_MAGAZIJNEN = 500;

/** Eén pagina van de lijst telt ten hoogste zoveel berichten (`pageSize` heeft maximum 100). */
export const MAX_BERICHTEN_PER_MAGAZIJN = 100;

const OIN = /^[0-9]{20}$/;

export class StelseldocumentFout extends Error {
  name = 'StelseldocumentFout';
}

/**
 * De magazijnen uit het stelseldocument (een JWS), en hoeveel organisaties er onbruikbaar waren. Een
 * organisatie met een onbruikbare OIN, naam of adres, of een OIN die al eerder in het document
 * stond, valt weg, zodat één fout in het document de rest niet blokkeert. Blijft er van een
 * niet-lege lijst niets over, dan is het document zelf onbruikbaar.
 *
 * TODO(#1216): de handtekening en de geldigheid controleren volgens het toepassingsprofiel, met de
 * vastgelegde root, en de challenge van elk magazijn tegen zijn sleutel uit dat document. Tot dan
 * rust de herkomst op TLS, en kan een vervalst document een magazijn onder de OIN van een ander
 * laten optreden en de presentatie aan dat andere magazijn laten doorspelen; de CSP beperkt alleen
 * de origins, niet welke OIN bij welke origin hoort. Alleen beide controles samen sluiten dat af.
 */
export function leesStelseldocument(jws) {
  let claims;

  try {
    claims = decodeJwt(jws);
  } catch (fout) {
    throw new StelseldocumentFout('het stelseldocument is niet te lezen', { cause: fout });
  }

  const { organizations } = claims;

  if (!Array.isArray(organizations)) throw new StelseldocumentFout('het stelseldocument noemt geen organisaties');

  // Vóór het filter: anders kost een document met miljoenen onbruikbare regels eerst al dat werk.
  if (organizations.length > MAX_MAGAZIJNEN) throw new StelseldocumentFout(`het stelseldocument noemt meer dan ${MAX_MAGAZIJNEN} organisaties`);

  const gezien = new Set();
  const magazijnen = [];

  organizations.forEach((org) => {
    const goed = typeof org?.oin === 'string' && OIN.test(org.oin) && !gezien.has(org.oin)
      && typeof org.name === 'string' && typeof org.magazijn_url === 'string' && bruikbaarAdres(org.magazijn_url);

    if (!goed) return;

    gezien.add(org.oin);
    magazijnen.push({ oin: org.oin, naam: org.name, url: org.magazijn_url.replace(/\/+$/, '') });
  });

  const overgeslagen = organizations.length - magazijnen.length;

  // Eén regel, niet één per organisatie: een groot document zou de console anders laten vollopen.
  if (overgeslagen > 0) console.warn(`${overgeslagen} organisatie(s) uit het stelseldocument overgeslagen`);

  if (organizations.length > 0 && magazijnen.length === 0) throw new StelseldocumentFout('het stelseldocument noemt geen bruikbare organisatie');

  return { magazijnen, overgeslagen };
}

/**
 * Haalt berichten op bij één magazijn: challenge, sessie, en de lijst binnen de sessie.
 * TODO(#1216): alleen de eerste pagina; de volgende pagina's en één bericht openen volgen.
 */
export async function haalOpBij(magazijn, { sleutelpaar, credential, fetch = globalThis.fetch, timeoutMs = TIMEOUT_MS }) {
  const signaal = AbortSignal.timeout(timeoutMs);
  const metSignaal = (adres, opties = {}) => fetch(adres, { ...opties, signal: signaal });
  const basis = `${magazijn.url}/api/v1/`;
  const sessie = await maakMagazijnsessie({ basis, oin: magazijn.oin, sleutelpaar, credential, fetch: metSignaal });
  const lijst = await haalVersleuteld({ adres: new URL('rechtstreeks/berichten', basis), ...sessie, fetch: metSignaal });

  if (!Array.isArray(lijst?.berichten) || lijst.berichten.length > MAX_BERICHTEN_PER_MAGAZIJN) {
    throw new ActiverenFout('onverwachte berichtenlijst', undefined, 'berichten');
  }

  return lijst.berichten.map((bericht) => ({ ...bericht, magazijn: magazijn.naam }));
}

/**
 * Bij alle magazijnen, hooguit [GELIJKTIJDIG] tegelijk. Geeft de berichten samen, nieuwste eerst,
 * en de magazijnen die niet antwoordden, met de reden. [haal] is er voor de tests.
 */
export async function haalAllesOp(magazijnen, opties, haal = haalOpBij) {
  const uitkomsten = new Array(magazijnen.length);
  let volgende = 0;

  // Een vast aantal workers dat de lijst afloopt; zie [TIMEOUT_MS].
  const worker = async () => {
    while (volgende < magazijnen.length) {
      const i = volgende++;

      try {
        uitkomsten[i] = { status: 'fulfilled', value: await haal(magazijnen[i], opties) };
      } catch (fout) {
        uitkomsten[i] = { status: 'rejected', reason: fout };
      }
    }
  };

  await Promise.all(Array.from({ length: Math.min(GELIJKTIJDIG, magazijnen.length) }, worker));

  const berichten = [];
  const mislukt = [];

  uitkomsten.forEach((uitkomst, i) => {
    if (uitkomst.status === 'fulfilled') {
      // Een lus en geen spread: een spread kent een grens op het aantal argumenten.
      for (const bericht of uitkomst.value) berichten.push(bericht);
    } else {
      mislukt.push({ ...magazijnen[i], reden: reden(uitkomst.reason), oorzaak: uitkomst.reason });
    }
  });

  // Op het moment zelf, niet op de tekst: magazijnen schrijven een tijdstip met of zonder fractie,
  // of met een offset. Een bericht zonder bruikbaar tijdstip komt achteraan.
  const moment = (bericht) => {
    const waarde = Date.parse(bericht.publicatietijdstip);

    return Number.isNaN(waarde) ? -Infinity : waarde;
  };

  berichten.sort((a, b) => moment(b) - moment(a));

  return { berichten, mislukt };
}

/** Reden voor een fout die niet aan het magazijn ligt, maar aan de app zelf. */
export const FOUT_IN_DE_APP = 'kon niet worden opgehaald door een fout in de app';

/** Een korte, Nederlandse reden voor de gebruiker; de oorzaak zelf hoort in de console. */
export function reden(fout) {
  if (fout?.name === 'TimeoutError' || fout?.cause?.name === 'TimeoutError') return 'antwoordde niet op tijd';

  if (fout?.name === 'VerbindingFout') return 'niet bereikbaar';

  // Bij het openen van de sessie is een 401 een afgewezen presentatie; daarna een sessie die het
  // magazijn niet (meer) kent.
  if (fout?.status === 401) return fout.stap === 'sessies' ? 'weigerde het bewijs' : 'nam de sessie niet aan';

  if (fout?.status === 503) return 'tijdelijk niet beschikbaar';

  if (fout?.status) return `gaf een fout (${fout.status})`;

  if (fout instanceof ActiverenFout || fout instanceof errors.JOSEError || fout instanceof SyntaxError) return 'gaf een onbruikbaar antwoord';

  return FOUT_IN_DE_APP;
}

function bruikbaarAdres(adres) {
  try {
    const url = new URL(adres);

    // Een query of fragment zou de paden van de tweede ingang verkeerd laten aanvullen.
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.search && !url.hash;
  } catch {
    return false;
  }
}
