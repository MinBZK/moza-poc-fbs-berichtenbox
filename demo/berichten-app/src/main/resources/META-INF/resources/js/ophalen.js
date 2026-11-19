// Rechtstreeks ophalen: welke magazijnen er zijn (stelseldocument), per magazijn een sessie en de
// berichtenlijst, en alles samen in één lijst. Zonder DOM, zodat de tests onder Node dezelfde code
// draaien. Een magazijn dat niet of te laat antwoordt, houdt de rest niet op.
import { decodeJwt, errors } from '../vendor/jose/index.js';
import { ActiverenFout, haalVersleuteld, maakMagazijnsessie } from './activeren.js';

/**
 * De timeout op één verzoek aan een magazijn of het stelselregister. Een magazijn krijgt die per
 * verzoek, en voor zijn drie verzoeken samen [TOTAAL_FACTOR] keer zoveel: een traag magazijn is dan
 * niet meteen onbereikbaar. De tijd loopt pas vanaf de beurt van dat magazijn.
 */
export const TIMEOUT_MS = 10_000;

export const TOTAAL_FACTOR = 2;

/**
 * Hoeveel magazijnen tegelijk. Onder HTTP/1.1 opent een browser zo'n zes connections per origin, en
 * de gesimuleerde magazijnen delen één origin; een verzoek dat daarboven in de wachtrij staat, zou
 * zijn timeout al verbruiken voor het vertrekt.
 */
export const GELIJKTIJDIG = 6;

/** Meer organisaties dan dit is geen stelsel maar een document dat het tabblad wil laten vastlopen. */
export const MAX_MAGAZIJNEN = 500;

/** De app vraagt één pagina van deze grootte op (`pageSize` heeft maximum 100). */
export const MAX_BERICHTEN_PER_MAGAZIJN = 100;

const OIN = /^[0-9]{20}$/;

// Een adres of naam uit het document gaat de pagina in; zonder grens kan één organisatie het tabblad
// laten vastlopen.
const MAX_TEKST = 2048;

export class StelseldocumentFout extends Error {
  name = 'StelseldocumentFout';
}

/**
 * De magazijnen uit het stelseldocument (een JWS), en hoeveel organisaties er onbruikbaar waren. Een
 * organisatie met een onbruikbare OIN, naam of adres valt weg, zodat één fout in het document de
 * rest niet blokkeert. Blijft er van een niet-lege lijst niets over, dan is het document zelf
 * onbruikbaar, en ook als het één OIN twee keer noemt: het stelselregister kan dat niet uitgeven,
 * en welk van de twee adressen klopt, is dan niet te zeggen.
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
  const weg = [];

  organizations.forEach((org) => {
    const oin = typeof org?.oin === 'string' && OIN.test(org.oin) ? org.oin : null;

    if (oin && gezien.has(oin)) throw new StelseldocumentFout(`het stelseldocument noemt OIN ${oin} twee keer`);

    if (oin) gezien.add(oin);

    if (oin === null || !bruikbareNaam(org.name) || !bruikbaarAdres(org.magazijn_url)) {
      // Een OIN is publiek; wie het document onderzoekt, moet zien welke organisatie wegviel.
      weg.push(oin ?? '(geen geldige OIN)');

      return;
    }

    magazijnen.push({ oin, naam: org.name, url: zonderSlashAanHetEind(org.magazijn_url) });
  });

  // Eén regel, niet één per organisatie: een groot document zou de console anders laten vollopen.
  if (weg.length > 0) console.warn(`${weg.length} organisatie(s) uit het stelseldocument overgeslagen:`, weg);

  const overgeslagen = weg.length;

  if (organizations.length > 0 && magazijnen.length === 0) throw new StelseldocumentFout('het stelseldocument noemt geen bruikbare organisatie');

  return { magazijnen, overgeslagen };
}

/**
 * Haalt berichten op bij één magazijn: challenge, sessie, en de lijst binnen de sessie. Geeft de
 * berichten en hoeveel het magazijn er in totaal heeft.
 * TODO(#1216): alleen de eerste pagina; de volgende pagina's en één bericht openen volgen.
 */
export async function haalOpBij(magazijn, { sleutelpaar, credential, fetch = globalThis.fetch, timeoutMs = TIMEOUT_MS }) {
  const samen = AbortSignal.timeout(timeoutMs * TOTAAL_FACTOR);
  const metSignaal = (adres, opties = {}) => fetch(adres, { ...opties, signal: AbortSignal.any([AbortSignal.timeout(timeoutMs), samen]) });
  const basis = `${magazijn.url}/api/v1/`;
  const sessie = await maakMagazijnsessie({ basis, oin: magazijn.oin, sleutelpaar, credential, fetch: metSignaal });
  const adres = new URL('rechtstreeks/berichten', basis);

  adres.searchParams.set('pageSize', String(MAX_BERICHTEN_PER_MAGAZIJN));

  const lijst = await haalVersleuteld({ adres, ...sessie, fetch: metSignaal });

  if (!Array.isArray(lijst?.berichten) || lijst.berichten.length > MAX_BERICHTEN_PER_MAGAZIJN) {
    throw new ActiverenFout('onverwachte berichtenlijst', undefined, 'berichten');
  }

  const berichten = lijst.berichten.map((bericht) => ({ ...bericht, magazijn: magazijn.naam }));
  const totaal = Number.isInteger(lijst.totalElements) ? lijst.totalElements : berichten.length;

  return { berichten, totaal };
}

/**
 * Bij alle magazijnen, hooguit [GELIJKTIJDIG] tegelijk. Geeft de berichten samen, nieuwste eerst,
 * de magazijnen waarbij het ophalen mislukte, met de reden, en de magazijnen die meer berichten
 * hebben dan de app ophaalde. [haal] is er voor de tests.
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
  const afgekapt = [];

  uitkomsten.forEach((uitkomst, i) => {
    if (uitkomst.status === 'fulfilled') {
      const { berichten: van, totaal } = uitkomst.value;

      // Een lus en geen spread: een spread kent een grens op het aantal argumenten.
      for (const bericht of van) berichten.push(bericht);

      if (totaal > van.length) afgekapt.push({ ...magazijnen[i], getoond: van.length, totaal });
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

  return { berichten, mislukt, afgekapt };
}

/** Reden voor een fout die niet aan het magazijn ligt, maar aan de app zelf. */
export const FOUT_IN_DE_APP = 'kon niet worden opgehaald door een fout in de app';

/** Een korte, Nederlandse reden voor de gebruiker; de oorzaak zelf hoort in de console. */
export function reden(fout) {
  // De timeout komt altijd verpakt: verbind en tekstVan maken er een VerbindingFout van.
  if (fout?.cause?.name === 'TimeoutError') return 'antwoordde niet op tijd';

  if (fout?.name === 'VerbindingFout') return 'niet bereikbaar';

  // Bij het openen van de sessie is een 401 een afgewezen presentatie, bij de lijst een sessie die
  // het magazijn niet (meer) kent. Op de challenge vraagt het contract geen bewijs.
  if (fout?.status === 401 && fout.stap === 'sessies') return 'weigerde het bewijs';

  if (fout?.status === 401 && fout.stap === 'berichten') return 'nam de sessie niet aan';

  if (fout?.status === 503) return 'tijdelijk niet beschikbaar';

  if (fout?.status) return `gaf een fout (${fout.status})`;

  if (fout instanceof ActiverenFout || fout instanceof errors.JOSEError) return 'gaf een onbruikbaar antwoord';

  return FOUT_IN_DE_APP;
}

function bruikbareNaam(naam) {
  return typeof naam === 'string' && naam.trim() !== '' && naam.length <= MAX_TEKST;
}

function bruikbaarAdres(adres) {
  if (typeof adres !== 'string' || adres.length > MAX_TEKST) return false;

  try {
    const url = new URL(adres);

    // Een query of fragment zou de paden van de tweede ingang verkeerd laten aanvullen, en een fetch
    // naar een adres met gebruikersnaam weigert de browser.
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.search && !url.hash && !url.username && !url.password;
  } catch {
    return false;
  }
}

/**
 * Lineair. `/\/+$/` probeert de reeks slashes vanaf elke slash opnieuw als er nog een teken achter
 * staat, en is daardoor kwadratisch.
 */
function zonderSlashAanHetEind(adres) {
  let eind = adres.length;

  while (eind > 0 && adres[eind - 1] === '/') eind -= 1;

  return adres.slice(0, eind);
}
