// Rechtstreeks ophalen: welke magazijnen er zijn (stelseldocument), per magazijn een sessie en de
// berichtenlijst, en alles samen in één lijst. Zonder DOM, zodat de tests onder Node dezelfde code
// draaien. Een magazijn dat niet of te laat antwoordt, houdt de rest niet op.
import { decodeJwt } from '../vendor/jose-6.2.13/index.js';
import { haalVersleuteld, maakMagazijnsessie } from './activeren.js';

/** Hoe lang de app op één magazijn wacht, vanaf het moment dat het aan de beurt is. */
export const WACHTTIJD_MS = 10_000;

/**
 * Hoeveel magazijnen tegelijk. Een browser opent per origin maar een handvol verbindingen; met alle
 * magazijnen tegelijk zou een verzoek in de wachtrij al aan zijn wachttijd beginnen.
 */
export const GELIJKTIJDIG = 8;

/** Meer magazijnen dan dit is geen stelsel maar een document dat het tabblad wil laten vastlopen. */
export const MAX_MAGAZIJNEN = 500;

const OIN = /^[0-9]{20}$/;

export class StelseldocumentFout extends Error {
  name = 'StelseldocumentFout';
}

/**
 * De magazijnen uit het stelseldocument (een JWS). Een organisatie met een onbruikbare OIN, naam of
 * adres valt weg, zodat één fout in het document de rest niet blokkeert.
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

  if (!Array.isArray(claims.organizations)) throw new StelseldocumentFout('het stelseldocument noemt geen organisaties');

  const bruikbaar = claims.organizations.filter((org) => {
    const goed = OIN.test(org?.oin) && typeof org?.name === 'string' && bruikbaarAdres(org?.magazijn_url);

    // Een OIN is publiek; die mag in de console.
    if (!goed) console.warn(`Organisatie ${org?.oin ?? '(zonder OIN)'} uit het stelseldocument overgeslagen`);

    return goed;
  });

  if (bruikbaar.length > MAX_MAGAZIJNEN) throw new StelseldocumentFout(`het stelseldocument noemt meer dan ${MAX_MAGAZIJNEN} magazijnen`);

  return bruikbaar.map((org) => ({ oin: org.oin, naam: org.name, url: org.magazijn_url.replace(/\/+$/, '') }));
}

/**
 * Haalt berichten op bij één magazijn: challenge, complete, en de lijst binnen de sessie.
 * TODO(#1216): alleen de eerste pagina; de volgende pagina's en één bericht openen volgen.
 */
export async function haalOpBij(magazijn, { sleutelpaar, credential, fetch = globalThis.fetch, wachttijdMs = WACHTTIJD_MS }) {
  const signaal = AbortSignal.timeout(wachttijdMs);
  const metSignaal = (adres, opties = {}) => fetch(adres, { ...opties, signal: signaal });
  const basis = `${magazijn.url}/api/v1/`;
  const sessie = await maakMagazijnsessie({ basis, oin: magazijn.oin, sleutelpaar, credential, fetch: metSignaal });
  const lijst = await haalVersleuteld({ adres: new URL('rechtstreeks/berichten', basis), ...sessie, fetch: metSignaal });

  if (!Array.isArray(lijst.berichten)) throw new Error('onverwachte berichtenlijst');

  return lijst.berichten.map((bericht) => ({ ...bericht, magazijn: magazijn.naam }));
}

/**
 * Bij alle magazijnen tegelijk. Geeft de berichten samen, nieuwste eerst, en de magazijnen die
 * niet antwoordden, met de reden. [haal] is er voor de tests.
 */
export async function haalAllesOp(magazijnen, opties, haal = haalOpBij) {
  const uitkomsten = new Array(magazijnen.length);
  let volgende = 0;

  // Een vast aantal werkers dat de lijst afloopt: zo telt de wachttijd van een magazijn pas vanaf
  // het moment dat het aan de beurt is.
  const werker = async () => {
    while (volgende < magazijnen.length) {
      const i = volgende++;

      try {
        uitkomsten[i] = { status: 'fulfilled', value: await haal(magazijnen[i], opties) };
      } catch (fout) {
        uitkomsten[i] = { status: 'rejected', reason: fout };
      }
    }
  };

  await Promise.all(Array.from({ length: Math.min(GELIJKTIJDIG, magazijnen.length) }, werker));

  const berichten = [];
  const mislukt = [];

  uitkomsten.forEach((uitkomst, i) => {
    if (uitkomst.status === 'fulfilled') {
      berichten.push(...uitkomst.value);
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

/** Een korte, Nederlandse reden voor de gebruiker; de oorzaak zelf hoort in de console. */
export function reden(fout) {
  if (fout?.name === 'TimeoutError' || fout?.cause?.name === 'TimeoutError') return 'antwoordde niet op tijd';

  if (fout?.name === 'VerbindingFout') return 'niet bereikbaar';

  if (fout?.status === 401) return 'weigerde het bewijs';

  if (fout?.status === 503) return 'tijdelijk niet beschikbaar';

  if (fout?.status) return `gaf een fout (${fout.status})`;

  return 'gaf een onbruikbaar antwoord';
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
