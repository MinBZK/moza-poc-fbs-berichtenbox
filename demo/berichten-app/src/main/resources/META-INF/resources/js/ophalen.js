// Rechtstreeks ophalen: welke magazijnen er zijn (stelseldocument), per magazijn een sessie en de
// berichtenlijst, en alles samen in één lijst. Zonder DOM, zodat de tests onder Node dezelfde code
// draaien. Een magazijn dat niet of te laat antwoordt, houdt de rest niet op.
import { decodeJwt } from '../vendor/jose-6.2.13/index.js';
import { haalVersleuteld, maakMagazijnsessie } from './activeren.js';

/** Hoe lang de app op één magazijn wacht; daarna telt het als niet bereikbaar. */
export const WACHTTIJD_MS = 10_000;

const OIN = /^[0-9]{20}$/;

export class StelseldocumentFout extends Error {
  name = 'StelseldocumentFout';
}

/**
 * De magazijnen uit het stelseldocument (een JWS). Een organisatie met een onbruikbaar adres valt
 * weg, zodat één fout in het document de rest niet blokkeert.
 *
 * TODO(#1216): de handtekening en de geldigheid controleren volgens het toepassingsprofiel, met de
 * vastgelegde root; tot dan rust de herkomst van het document op TLS.
 */
export function leesStelseldocument(jws) {
  let claims;

  try {
    claims = decodeJwt(jws);
  } catch (fout) {
    throw new StelseldocumentFout('het stelseldocument is niet te lezen', { cause: fout });
  }

  if (!Array.isArray(claims.organizations)) throw new StelseldocumentFout('het stelseldocument noemt geen organisaties');

  return claims.organizations
    .filter((org) => OIN.test(org?.oin) && typeof org?.name === 'string' && bruikbaarAdres(org?.magazijn_url))
    .map((org) => ({ oin: org.oin, naam: org.name, url: org.magazijn_url.replace(/\/+$/, '') }));
}

/** Haalt de eerste pagina berichten op bij één magazijn: challenge, complete, en de lijst binnen de sessie. */
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
  const uitkomsten = await Promise.allSettled(magazijnen.map((magazijn) => haal(magazijn, opties)));
  const berichten = [];
  const mislukt = [];

  uitkomsten.forEach((uitkomst, i) => {
    if (uitkomst.status === 'fulfilled') {
      berichten.push(...uitkomst.value);
    } else {
      mislukt.push({ ...magazijnen[i], reden: reden(uitkomst.reason), oorzaak: uitkomst.reason });
    }
  });

  berichten.sort((a, b) => String(b.publicatietijdstip).localeCompare(String(a.publicatietijdstip)));

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

    return url.protocol === 'https:' || url.protocol === 'http:';
  } catch {
    return false;
  }
}
